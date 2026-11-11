(ns metabase.query-processor.task.refresh-cache
  "Refresh cache automatically: keep the cache of questions and dashboards warm.

  Once a minute, rerun the queries of each question and dashboard CacheConfig that has \"Refresh cache automatically\"
  on and a Duration or Schedule policy whose cached results are due to expire, so that people are served from the
  cache instead of waiting for the query. A question's config reruns its card as the question page runs it, and as
  each dashboard that shows the card runs it; a dashboard's reruns each card on the dashboard that the config governs
  there. A dashboard runs a card with its default parameter values. Each also reruns the (at most ten) other parameter
  combinations people ran most in the last cache period. A rerun writes fresh results under the cache key of the
  query it reruns.

  See https://www.metabase.com/docs/latest/configuring-metabase/caching#refresh-cache-automatically."
  (:refer-clojure :exclude [empty? mapv not-empty])
  (:require
   [clojure.string :as str]
   [clojurewerkz.quartzite.jobs :as jobs]
   [clojurewerkz.quartzite.schedule.cron :as cron]
   [clojurewerkz.quartzite.triggers :as triggers]
   [java-time.api :as t]
   [medley.core :as m]
   [metabase.cache.core :as cache]
   [metabase.lib.core :as lib]
   [metabase.parameters.schema :as parameters.schema]
   [metabase.query-processor.card :as qp.card]
   [metabase.query-processor.dashboard :as qp.dashboard]
   [metabase.query-processor.db :as query-processor.db]
   [metabase.query-processor.middleware.cache-backend.db :as backend.db]
   [metabase.query-processor.util :as qp.util]
   [metabase.request.core :as request]
   [metabase.task.core :as task]
   [metabase.util :as u]
   [metabase.util.log :as log]
   [metabase.util.performance :refer [empty? mapv not-empty]])
  (:import
   (java.time Instant OffsetDateTime ZoneOffset)))

(set! *warn-on-reflection* true)

(def ^:private duration-lead-time
  "How long before a Duration cache expires it is refreshed. The job runs once a minute, so waiting for the expiry would
  leave the cache expired for up to a minute. A Schedule cache isn't refreshed early: whatever is cached before the
  schedule fires expires when it does."
  (t/minutes 1))

(def ^:private max-other-combinations
  "How many parameter combinations besides the default ones are refreshed for each card."
  10)

;;; ------------------------------------------------ Running a query -------------------------------------------------

(defn- discard-rows-rff
  "Reduce a refresh's results to their metadata: the cache middleware has stored the rows before they get here, and
  nobody reads them."
  [metadata]
  (fn discard-rows-rf
    ([] {:data metadata})
    ([result] result)
    ([result _row] result)))

(defn- run-for-refresh
  "A `:make-run` for [[qp.card/process-query-for-card]] that runs the query for the cache it writes: synchronously and
  without the rows. Returns the hash its QueryExecution records and its result."
  [qp _export-format]
  (fn [query info]
    {:hash   (qp.util/query-hash query)
     :result (qp (update query :info merge info) discard-rows-rff)}))

(defn- refresh-target!
  "Rerun the query of the Card of `target` with its `:parameters`, as its `:dashboard` runs it on its `:dashcard` when it
  has them, else as the question page runs it, ignoring cached results so its results are cached afresh. Returns the
  hash of the query as its QueryExecution records it."
  [{:keys [card dashboard dashcard parameters]}]
  (let [{:keys [hash result]} (if dashboard
                                (qp.dashboard/process-query-for-dashcard
                                 :dashboard     dashboard
                                 :dashcard      dashcard
                                 :card          card
                                 :export-format :api
                                 :parameters    parameters
                                 :context       :cache-refresh
                                 :ignore-cache  true
                                 :make-run      run-for-refresh)
                                ;; as `POST /api/card/:id/query` runs it
                                (qp.card/process-query-for-card
                                 card :api
                                 ;; stored parameters are JSON, which that endpoint would have decoded
                                 :parameters   (some->> parameters
                                                        (lib/normalize ::parameters.schema/parameters-with-optional-types))
                                 :context      :cache-refresh
                                 :ignore-cache true
                                 :middleware   {:process-viz-settings? false}
                                 :make-run     run-for-refresh))]
    (when (= :failed (:status result))
      (throw (ex-info (str "Query failed: " (:error result)) {:card-id (:id card)})))
    hash))

(defn- try-refresh!
  "[[refresh-target!]], logging rather than throwing when it fails. Returns the hash, or nil when it failed."
  [{:keys [card dashboard], :as target}]
  (try
    (refresh-target! target)
    (catch Throwable e
      (log/warnf e "Failed to refresh the cached results of Card %d%s" (:id card)
                 (if dashboard (format " on Dashboard %d" (:id dashboard)) ""))
      nil)))

;;; ---------------------------------------- What a dashboard's frontend sends ----------------------------------------

(defn- parameter-type-parts
  "The section and operator of a parameter type, e.g. `[\"string\" \"contains\"]` for `string/contains`."
  [parameter-type]
  (str/split (u/qualified-name parameter-type) #"/" 2))

(def ^:private case-insensitive-string-operators
  "The string operators whose options default to a case-insensitive match (`optionsDefaults` in
  frontend/src/metabase-lib/v1/operators/constants.ts)."
  #{"contains" "does-not-contain" "starts-with" "ends-with"})

(defn- frontend-options
  "The options the frontend adds for `parameter`: the defaults of its operator's options
  (`deriveFieldOperatorFromParameter` in frontend/src/metabase-lib/v1/parameters/utils/operators.ts), which only the
  case-insensitive string operators have."
  [{parameter-type :type, :keys [sectionId]}]
  (let [[section operator] (parameter-type-parts parameter-type)]
    (when (and (#{"string" "category" "location"} (or sectionId section))
               (case-insensitive-string-operators operator))
      {:case-sensitive false})))

(defn- frontend-value
  "`value` as the frontend sends it for a parameter of `parameter-type` (`normalizeParameterValue` in
  frontend/src/metabase-lib/v1/parameters/utils/parameter-values.ts): no value as null, a string or number one as a
  list."
  [parameter-type value]
  (cond
    (or (nil? value) (= value "") (and (sequential? value) (empty? value))) nil
    (#{"string" "number"} (first (parameter-type-parts parameter-type)))    (if (sequential? value) (vec value) [value])
    :else                                                                    value))

(defn- default-dashboard-parameters
  "The `parameters` the frontend sends to run the Card with `card-id` on `dashcard` when `dashboard` shows its default
  parameter values: `fetchCardDataAction` in frontend/src/metabase/dashboard/actions/data-fetching.ts builds them with
  `applyParameters` in frontend/src/metabase/common/utils/card.ts, one for each Dashboard parameter the DashboardCard
  maps to the Card, in the Dashboard's order."
  [dashboard dashcard card-id]
  (into []
        (keep (fn [{:keys [id default], parameter-type :type, :as parameter}]
                (when-let [mapping (u/seek #(and (= (:card_id %) card-id) (= (:parameter_id %) id))
                                           (:parameter_mappings dashcard))]
                  (m/assoc-some {:type   parameter-type
                                 :value  (frontend-value parameter-type default)
                                 :id     id
                                 :target (:target mapping)}
                                :options (frontend-options parameter)))))
        (:parameters dashboard)))

;;; ---------------------------------------------------- Targets -----------------------------------------------------

(defn- dashboard-card-targets
  "The queries of the cards `dashboard` runs (on a DashboardCard or as its series) that the CacheConfig with `config-id`
  governs there, with the dashboard's default parameter values."
  [config-id {dashboard-id :id, :as dashboard}]
  (let [dashcards (query-processor.db/card-dashcards dashboard-id)
        series    (group-by :dashboardcard_id (when (seq dashcards)
                                                (query-processor.db/dashcards-series (mapv :id dashcards))))]
    (into []
          (for [dashcard dashcards
                card-id  (cons (:card_id dashcard) (map :card_id (series (:id dashcard))))
                :let     [card (query-processor.db/unarchived-card card-id)]
                :when    (and card
                              (= config-id (:id (cache/effective-config {:card-id      card-id
                                                                         :database-id  (:database_id card)
                                                                         :dashboard-id dashboard-id}))))]
            {:dashboard  dashboard
             :dashcard   dashcard
             :card       card
             :parameters (default-dashboard-parameters dashboard dashcard card-id)}))))

(defn- question-targets
  "The queries a question's `config` refreshes: its card's as the question page runs it, without parameters so the
  card's own defaults apply, and as each dashboard that shows the card runs it, where the question's config governs it
  too and the parameters make the cache key differ."
  [{config-id :id, card-id :model_id}]
  (when-let [card (query-processor.db/unarchived-card card-id)]
    (into [{:card card}]
          (comp (keep query-processor.db/unarchived-dashboard)
                (mapcat #(dashboard-card-targets config-id %)))
          (query-processor.db/card-dashboard-ids card-id))))

(defn- dashboard-targets
  "The queries a dashboard's `config` refreshes: those of each card the dashboard runs that `config` governs there."
  [{config-id :id, dashboard-id :model_id}]
  (some->> (query-processor.db/unarchived-dashboard dashboard-id)
           (dashboard-card-targets config-id)))

(defn- other-parameter-combinations
  "The `:parameters` of the queries of `target`'s card that people ran most since `since` (from its dashboard if it has
  one, else on the question page), other than those whose hashes are in `refreshed`, at most [[max-other-combinations]]."
  [{:keys [card dashboard]} since refreshed]
  (->> (query-processor.db/most-run-query-hashes (:id card) (:id dashboard) (if dashboard :dashboard :question) since
                                                 (+ max-other-combinations (count refreshed)))
       (map :hash)
       (remove #(contains? refreshed (vec %)))
       (take max-other-combinations)
       (keep #(not-empty (:parameters (query-processor.db/stored-query %))))
       ;; realized here, so a failing query fails the caller's try
       vec))

;;; --------------------------------------------------- A config -----------------------------------------------------

(defn- ->offset-date-time ^OffsetDateTime [^Instant instant]
  (OffsetDateTime/ofInstant instant ZoneOffset/UTC))

(defn- duration [{:keys [config]}]
  (t/duration (:duration config) (keyword (:unit config))))

(defn- next-run-at
  "When the results of `config` cached at `now` expire."
  [{:keys [strategy config], :as cache-config} now]
  (case strategy
    :duration (t/plus now (duration cache-config))
    :schedule (or (some-> (backend.db/schedule-next-fire-time (:schedule config) now) ->offset-date-time)
                  ;; a schedule that never fires again never expires them
                  (t/plus now (t/years 100)))))

(defn- period-start
  "When the cache period of `config` that ends at `now` began: one duration ago, or when the schedule fired before it
  last fired. Nil when the schedule hasn't fired."
  [{:keys [strategy config], :as cache-config} now]
  (case strategy
    :duration (t/minus now (duration cache-config))
    :schedule (when-let [fired (backend.db/schedule-previous-fire-time (:schedule config) now)]
                (->offset-date-time
                 (or (backend.db/schedule-previous-fire-time (:schedule config) (t/minus fired (t/millis 1)))
                     fired)))))

(defn- refresh-config!
  "Refresh the cached results `config` governs, then record when they are next due."
  [{:keys [model], :as config}]
  (let [now       (t/offset-date-time)
        since     (period-start config now)
        refreshed (volatile! #{})
        refresh!  (fn [target]
                    (when-let [hash (try-refresh! target)]
                      (vswap! refreshed conj (vec hash))))]
    (doseq [target (case model
                     "question"  (question-targets config)
                     "dashboard" (dashboard-targets config))]
      (refresh! target)
      (when since
        (doseq [parameters (try
                             (other-parameter-combinations target since @refreshed)
                             (catch Throwable e
                               (log/warnf e "Failed to find the parameter values used with Card %d" (:id (:card target)))
                               nil))]
          (refresh! (assoc target :parameters parameters)))))
    (cache/set-next-refresh! (:id config) (next-run-at config now))))

(defn refresh-caches!
  "Run a round of automatic cache refreshes: refresh, one query at a time and as an admin, the cached results of each
  question and dashboard CacheConfig with \"Refresh cache automatically\" on that are due -- Duration ones expiring
  within [[duration-lead-time]], Schedule ones whose schedule has fired, and those not refreshed yet -- and record when
  each is due next. A failure is logged and the round goes on: the round never throws."
  []
  (try
    (let [now (t/offset-date-time)]
      (request/as-admin
        (doseq [config (cache/cache-configs-due-for-refresh (t/plus now duration-lead-time) now)]
          (try
            (refresh-config! config)
            (catch Throwable e
              (log/errorf e "Failed to refresh the cached results of %s %d" (:model config) (:model_id config)))))))
    (catch Throwable e
      (log/error e "Failed to refresh cached results")))
  nil)

;;; ------------------------------------------------------ Job -------------------------------------------------------

(task/defjob RefreshCache
  "Run a round of automatic cache refreshes."
  {:saved-class "metabase.query_processor.task.refresh_cache.RefreshCache"
   :concurrent? false}
  [_job-context]
  (refresh-caches!))

(def ^:private job-key     "metabase.task.refresh-cache.job")
(def ^:private trigger-key "metabase.task.refresh-cache.trigger")

(defmethod task/init! ::RefreshCache [_]
  (let [job     (jobs/build
                 (jobs/of-type RefreshCache)
                 (jobs/with-identity (jobs/key job-key)))
        trigger (triggers/build
                 (triggers/with-identity (triggers/key trigger-key))
                 (triggers/start-now)
                 (triggers/with-schedule
                  (cron/schedule
                   (cron/cron-schedule "0 * * * * ?")
                   (cron/with-misfire-handling-instruction-do-nothing))))]
    (task/schedule-task! job trigger)))
