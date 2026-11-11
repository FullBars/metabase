(ns metabase.query-processor.task.refresh-cache-test
  ;; the code under test reads cache configs, dashboards and query executions from the app DB, which metadata
  ;; providers don't model
  {:clj-kondo/config '{:linters {:discouraged-var {metabase.test/with-temp           {:level :off}
                                                   toucan2.tools.with-temp/with-temp {:level :off}}}}}
  (:require
   [clojure.test :refer :all]
   [java-time.api :as t]
   [metabase.cache.core :as cache]
   [metabase.query-processor.middleware.process-userland-query :as process-userland-query]
   [metabase.query-processor.task.refresh-cache :as refresh-cache]
   [metabase.task.core :as task]
   [metabase.test :as mt]
   [toucan2.core :as t2])
  (:import
   (metabase.query_processor.task.refresh_cache RefreshCache)))

(set! *warn-on-reflection* true)

(defn- blob->bytes
  "`cache_hash` is a BLOB, which H2 returns as a `Blob` that is only readable while its result set is open."
  [x]
  (if (instance? java.sql.Blob x)
    (let [^java.sql.Blob blob x]
      (.getBytes blob 1 (int (.length blob))))
    x))

(defn- executions!
  "The QueryExecutions of the Card with `card-id` run in `context`, oldest first."
  [card-id context]
  (process-userland-query/flush-execution-metadata!)
  (t2/select-fn-vec #(update % :cache_hash blob->bytes)
                    :model/QueryExecution :card_id card-id :context context {:order-by [[:id :asc]]}))

(defn- refreshes! [card-id]
  (executions! card-id :cache-refresh))

(defn- same-hash? [expected]
  (fn [actual]
    (and (some? actual) (= (seq expected) (seq actual)))))

(defn- served-from-cache-written-since?
  "Whether the API query `result` was served from a cache entry written at or after `instant`."
  [instant result]
  (boolean (some-> (:cached result) t/offset-date-time (t/before? instant) not)))

(defn- refresh-config [model model-id strategy config & {:as more}]
  (merge {:model                 model
          :model_id              model-id
          :strategy              strategy
          :config                config
          :refresh_automatically true}
         more))

(def ^:private one-hour {:duration 1 :unit "hours"})

(deftest question-config-refresh-test
  (mt/with-temp [:model/Card        {card-id :id} {:dataset_query (mt/mbql-query venues {:aggregation [[:count]]})}
                 :model/CacheConfig _             (refresh-config "question" card-id :duration {:duration 1 :unit "minutes"})]
    (let [run-card!       #(mt/user-http-request :rasta :post 202 (format "card/%d/query" card-id))
          _               (run-card!)
          cache-hash      (:cache_hash (first (executions! card-id :question)))
          cached-at       #(t2/select-one-fn :updated_at :model/QueryCache :query_hash cache-hash)
          first-cached-at (cached-at)]
      (testing "the user's query was cached"
        (is (some? first-cached-at)))
      (let [round-start (t/offset-date-time)]
        (refresh-cache/refresh-caches!)
        (testing "a round reruns the card's query, standalone, as a cache refresh on the user's cache key"
          (is (=? [{:dashboard_id nil, :cache_hash (same-hash? cache-hash)}]
                  (refreshes! card-id))))
        (testing "which rewrote the cache entry"
          (is (t/after? (cached-at) first-cached-at)))
        (testing "so the user's next query is served from the newer results"
          (is (served-from-cache-written-since? round-start (run-card!))))))))

(def ^:private dashboard-parameters
  [{:id "name",      :name "Name",      :slug "name",      :type "string/contains", :sectionId "string", :default ["Red"]}
   {:id "price",     :name "Price",     :slug "price",     :type "number/=",        :sectionId "number", :default 2}
   {:id "other",     :name "Other",     :slug "other",     :type "string/=",        :sectionId "string"}
   {:id "elsewhere", :name "Elsewhere", :slug "elsewhere", :type "string/=",        :sectionId "string", :default ["x"]}])

(defn- parameter-mappings [card-id]
  [{:parameter_id "name",  :card_id card-id, :target [:dimension [:field (mt/id :venues :name) nil]]}
   {:parameter_id "price", :card_id card-id, :target [:dimension [:field (mt/id :venues :price) nil]]}
   {:parameter_id "other", :card_id card-id, :target [:dimension [:field (mt/id :venues :name) nil]]}])

(defn- api-dashcard
  "The DashboardCard with `dashcard-id` as the frontend gets it from `GET /api/dashboard/:id`."
  [dashboard-id dashcard-id]
  (->> (mt/user-http-request :rasta :get 200 (format "dashboard/%d" dashboard-id))
       :dashcards
       (some #(when (= (:id %) dashcard-id) %))))

(defn- frontend-parameters
  "The `parameters` the frontend posts to run `card-id` on `dashcard` when the Dashboard shows [[dashboard-parameters]]
  with their default values, written out by hand from `applyParameters` (frontend/src/metabase/common/utils/card.ts):
  one entry per Dashboard parameter mapped to the card, with the value normalized by `normalizeParameterValue` (string
  and number values in an array, no value as null) and the case-insensitive default options of a `contains` filter."
  [dashcard card-id & {:keys [name-value], :or {name-value ["Red"]}}]
  (let [target (fn [parameter-id]
                 (some #(when (and (= (:parameter_id %) parameter-id) (= (:card_id %) card-id))
                          (:target %))
                       (:parameter_mappings dashcard)))]
    [{:type "string/contains", :value name-value, :id "name", :options {:case-sensitive false}, :target (target "name")}
     {:type "number/=", :value [2], :id "price", :target (target "price")}
     {:type "string/=", :value nil, :id "other", :target (target "other")}]))

(defn- run-dashcard! [dashboard-id dashcard-id card-id parameters]
  (mt/user-http-request :rasta :post 202
                        (format "dashboard/%d/dashcard/%d/card/%d/query" dashboard-id dashcard-id card-id)
                        {:parameters parameters}))

(deftest dashboard-config-refresh-test
  (mt/with-temp [:model/Card          {card-id :id}     {:dataset_query (mt/mbql-query venues {:aggregation [[:count]]})}
                 :model/Card          {own-card-id :id} {:dataset_query (mt/mbql-query venues {:aggregation [[:sum $price]]})}
                 :model/Card          {series-id :id}   {:dataset_query (mt/mbql-query venues {:aggregation [[:max $price]]})}
                 :model/Dashboard     {dash-id :id}     {:parameters dashboard-parameters}
                 :model/DashboardCard {dashcard-id :id} {:dashboard_id dash-id, :card_id card-id
                                                         :parameter_mappings (parameter-mappings card-id)}
                 :model/DashboardCardSeries _           {:dashboardcard_id dashcard-id, :card_id series-id, :position 0}
                 :model/DashboardCard _                 {:dashboard_id dash-id, :card_id own-card-id}
                 :model/DashboardCard _                 {:dashboard_id dash-id, :card_id nil
                                                         :visualization_settings {:virtual_card {:display "text"}
                                                                                  :text         "Not a query"}}
                 :model/CacheConfig   _                 (refresh-config "dashboard" dash-id :duration one-hour)
                 :model/CacheConfig   _                 (refresh-config "question" own-card-id :duration one-hour
                                                                        :refresh_automatically false)]
    (refresh-cache/refresh-caches!)
    (let [result   (run-dashcard! dash-id dashcard-id card-id (frontend-parameters (api-dashcard dash-id dashcard-id) card-id))
          user-run (first (executions! card-id :dashboard))]
      (testing "the round refreshes the dashcard's card as the dashboard runs it"
        (is (=? [{:dashboard_id dash-id}]
                (refreshes! card-id))))
      (testing "on the cache key of the query the frontend sends for the default parameter values"
        (is (some? (:cache_hash user-run)))
        (is (=? [{:cache_hash (same-hash? (:cache_hash user-run))}]
                (refreshes! card-id))))
      (testing "so the user's query is served from the cache"
        (is (some? (:cached result))))
      (testing "the cards of a dashcard's series are refreshed too"
        (is (=? [{:dashboard_id dash-id}]
                (refreshes! series-id))))
      (testing "a card whose own question config wins over the dashboard's is not refreshed"
        (is (empty? (refreshes! own-card-id)))))))

(deftest question-config-refreshes-its-card-on-dashboards-test
  (mt/with-temp [:model/Card          {card-id :id}       {:dataset_query (mt/mbql-query venues {:aggregation [[:count]]})}
                 :model/Card          {other-id :id}      {:dataset_query (mt/mbql-query venues {:aggregation [[:sum $price]]})}
                 :model/Dashboard     {dash-id :id}       {:parameters dashboard-parameters}
                 :model/DashboardCard {dashcard-id :id}   {:dashboard_id dash-id, :card_id card-id
                                                           :parameter_mappings (parameter-mappings card-id)}
                 :model/DashboardCard _                   {:dashboard_id dash-id, :card_id other-id}
                 :model/Dashboard     {series-dash :id}   {}
                 :model/DashboardCard {series-dc :id}     {:dashboard_id series-dash, :card_id other-id}
                 :model/DashboardCardSeries _             {:dashboardcard_id series-dc, :card_id card-id, :position 0}
                 :model/Dashboard     {archived-dash :id} {:archived true}
                 :model/DashboardCard _                   {:dashboard_id archived-dash, :card_id card-id}
                 :model/CacheConfig   _                   (refresh-config "question" card-id :duration one-hour)]
    (let [dashcard   (api-dashcard dash-id dashcard-id)
          run-value! #(run-dashcard! dash-id dashcard-id card-id (frontend-parameters dashcard card-id :name-value %))]
      (run-value! ["Red"])
      (run-value! ["Bar"])
      (let [user-hashes (into #{} (map (comp seq :cache_hash)) (executions! card-id :dashboard))
            round-start (t/offset-date-time)]
        (refresh-cache/refresh-caches!)
        (testing "a question's round also reruns its card on each unarchived dashboard that shows it, as a card or a series"
          (is (= #{nil dash-id series-dash}
                 (into #{} (map :dashboard_id) (refreshes! card-id)))))
        (testing "with the dashboard's default values and the other values used there in the last period"
          (is (= 2 (count user-hashes)))
          (is (= user-hashes
                 (into #{}
                       (comp (filter #(= dash-id (:dashboard_id %))) (map (comp seq :cache_hash)))
                       (refreshes! card-id)))))
        (testing "so a user's query on the dashboard is served from the refreshed results"
          (is (served-from-cache-written-since? round-start (run-value! ["Red"]))))
        (testing "the dashboard's other cards are left alone"
          (is (empty? (refreshes! other-id))))))))

(deftest refresh-is-not-a-dashboard-view-test
  (mt/test-helpers-set-global-values!
    (mt/with-temporary-setting-values [synchronous-batch-updates true]
      (mt/with-temp [:model/Card          {card-id :id} {:dataset_query (mt/mbql-query venues {:aggregation [[:count]]})}
                     :model/Dashboard     {dash-id :id} {:last_viewed_at #t "2000-01-01T00:00:00Z"}
                     :model/DashboardCard _             {:dashboard_id dash-id, :card_id card-id}
                     :model/CacheConfig   _             (refresh-config "dashboard" dash-id :duration one-hour)]
        (let [last-viewed-at #(t2/select-one-fn :last_viewed_at :model/Dashboard dash-id)
              before         (last-viewed-at)]
          (refresh-cache/refresh-caches!)
          (testing "sanity check: the round refreshed the dashboard's card"
            (is (= 1 (count (refreshes! card-id)))))
          (testing "a refresh round doesn't make the dashboard look recently viewed"
            (is (= before (last-viewed-at)))))))))

(deftest no-refresh-test
  (mt/with-temp [:model/Card        {off-id :id}      {:dataset_query (mt/mbql-query venues {:aggregation [[:count]]})}
                 :model/Card        {ttl-id :id}      {:dataset_query (mt/mbql-query venues {:aggregation [[:count]]})}
                 :model/Card        {nocache-id :id}  {:dataset_query (mt/mbql-query venues {:aggregation [[:count]]})}
                 :model/Card        {database-id :id} {:dataset_query (mt/mbql-query venues {:aggregation [[:count]]})}
                 :model/CacheConfig _ (refresh-config "question" off-id :duration one-hour :refresh_automatically false)
                 :model/CacheConfig _ (refresh-config "question" ttl-id :ttl {:multiplier 10, :min_duration_ms 0})
                 :model/CacheConfig _ (refresh-config "question" nocache-id :nocache {})
                 :model/CacheConfig _ (refresh-config "database" (mt/id) :duration one-hour)]
    (refresh-cache/refresh-caches!)
    (testing "refresh off, an Adaptive or Don't-cache policy, and a database policy run no cache-refresh query"
      (doseq [card-id [off-id ttl-id nocache-id database-id]]
        (is (empty? (refreshes! card-id)))))))

(deftest failing-target-does-not-stop-the-round-test
  (mt/with-temp [:model/Card        {card-1 :id} {:dataset_query (mt/mbql-query venues {:aggregation [[:count]]})}
                 :model/Card        {card-2 :id} {:dataset_query (mt/mbql-query venues {:aggregation [[:count]]})}
                 :model/CacheConfig _            (refresh-config "question" card-1 :duration one-hour)
                 :model/CacheConfig _            (refresh-config "question" card-2 :duration one-hour)]
    (let [refresh-target! (mt/original-fn #'refresh-cache/refresh-target!)
          calls           (atom 0)
          refresh-count   #(+ (count (refreshes! card-1)) (count (refreshes! card-2)))]
      (mt/with-dynamic-fn-redefs [refresh-cache/refresh-target! (fn [target]
                                                                  (if (= 1 (swap! calls inc))
                                                                    (throw (ex-info "Boom" {}))
                                                                    (refresh-target! target)))]
        (refresh-cache/refresh-caches!))
      (testing "when the first target throws, the round still refreshes the other one"
        (is (= 2 @calls))
        (is (= 1 (refresh-count))))
      (testing "and the next round runs normally"
        (t2/update! :model/CacheConfig {:model "question", :model_id [:in [card-1 card-2]]} {:next_run_at nil})
        (refresh-cache/refresh-caches!)
        (is (= 3 (refresh-count)))))))

(deftest app-db-error-does-not-escape-the-round-test
  (mt/with-dynamic-fn-redefs [cache/cache-configs-due-for-refresh (fn [& _]
                                                                    (throw (java.sql.SQLException. "Connection refused")))]
    (testing "a round whose app DB queries fail logs it rather than throwing"
      (is (nil? (refresh-cache/refresh-caches!))))
    (testing "and so does the job"
      (is (nil? (.execute (RefreshCache.) nil))))))

(deftest next-run-at-test
  (mt/with-temporary-setting-values [report-timezone "UTC"]
    (let [t0 #t "2026-11-11T10:59:30Z[UTC]"]
      (mt/with-temp [:model/Card        {duration-card :id} {:dataset_query (mt/mbql-query venues {:aggregation [[:count]]})}
                     :model/Card        {schedule-card :id} {:dataset_query (mt/mbql-query venues {:aggregation [[:count]]})}
                     :model/CacheConfig {duration-id :id}   (refresh-config "question" duration-card :duration one-hour
                                                                            :next_run_at (t/plus t0 (t/minutes 5)))
                     :model/CacheConfig {schedule-id :id}   (refresh-config "question" schedule-card :schedule
                                                                            {:schedule "0 0 * * * ?"}
                                                                            :next_run_at #t "2026-11-11T11:00:00Z[UTC]")]
        (let [next-run-at #(some-> (t2/select-one-fn :next_run_at :model/CacheConfig %) t/instant)]
          (mt/with-clock t0
            (refresh-cache/refresh-caches!))
          (testing "nothing runs before it is due"
            (is (empty? (refreshes! duration-card)))
            (is (empty? (refreshes! schedule-card))))
          (t2/update! :model/CacheConfig duration-id {:next_run_at (t/plus t0 (t/seconds 30))})
          (mt/with-clock t0
            (refresh-cache/refresh-caches!))
          (testing "a Duration config runs when its cache expires within the next minute"
            (is (= 1 (count (refreshes! duration-card))))
            (testing "and is next due when what it just cached expires"
              (is (= (t/instant (t/plus t0 (t/hours 1)))
                     (next-run-at duration-id)))))
          (testing "a Schedule config waits for its schedule to fire, since what it cached before would expire then"
            (is (empty? (refreshes! schedule-card))))
          (mt/with-clock (t/plus t0 (t/seconds 35))
            (refresh-cache/refresh-caches!))
          (testing "once its schedule has fired, a Schedule config runs"
            (is (= 1 (count (refreshes! schedule-card))))
            (testing "and is next due at the schedule's next firing"
              (is (= (t/instant "2026-11-11T12:00:00Z")
                     (next-run-at schedule-id)))))
          (testing "a Duration config that isn't due again doesn't run again"
            (is (= 1 (count (refreshes! duration-card))))))))))

(deftest edited-config-is-due-at-once-test
  (testing "a policy saved through the API is due at the next round, not when the policy it replaced would have been"
    (mt/with-temp [:model/Card        {card-id :id} {:dataset_query (mt/mbql-query venues {:aggregation [[:count]]})}
                   :model/CacheConfig _             (refresh-config "question" card-id :duration one-hour
                                                                    :next_run_at (t/plus (t/offset-date-time) (t/minutes 50)))]
      (refresh-cache/refresh-caches!)
      (testing "sanity check: not due yet"
        (is (empty? (refreshes! card-id))))
      (mt/user-http-request :crowberto :put 200 "cache/"
                            {:model    "question"
                             :model_id card-id
                             :strategy {:type "duration" :duration 5 :unit "minutes" :refresh_automatically true}})
      (refresh-cache/refresh-caches!)
      (is (= 1 (count (refreshes! card-id)))))))

(deftest refresh-most-used-parameter-values-test
  (mt/with-temp [:model/Card          {card-id :id}     {:dataset_query (mt/mbql-query venues {:aggregation [[:count]]})}
                 :model/Dashboard     {dash-id :id}     {:parameters dashboard-parameters}
                 :model/DashboardCard {dashcard-id :id} {:dashboard_id dash-id, :card_id card-id
                                                         :parameter_mappings (parameter-mappings card-id)}
                 :model/CacheConfig   _                 (refresh-config "dashboard" dash-id :duration one-hour)]
    (let [dashcard   (api-dashcard dash-id dashcard-id)
          run-value! #(run-dashcard! dash-id dashcard-id card-id (frontend-parameters dashcard card-id :name-value %))]
      (run-value! ["Red"])
      (run-value! ["Bar"])
      (run-value! ["Bar"])
      (run-value! ["Cafe"])
      (let [user-hashes (into #{} (map (comp seq :cache_hash)) (executions! card-id :dashboard))
            round-start (t/offset-date-time)]
        (refresh-cache/refresh-caches!)
        (testing "the round refreshes the default values and each other combination used in the last period, once"
          (is (= 3 (count user-hashes)))
          (is (= user-hashes
                 (into #{} (map (comp seq :cache_hash)) (refreshes! card-id))))
          (is (= 3 (count (refreshes! card-id)))))
        (testing "so a user's query with a value used in the period is served from the refreshed results"
          (is (served-from-cache-written-since? round-start (run-value! ["Bar"]))))))))

(deftest refresh-most-used-question-parameter-values-test
  (mt/with-temp [:model/Card        {card-id :id} {:dataset_query (mt/native-query
                                                                   {:query         "SELECT COUNT(*) FROM VENUES WHERE {{name}}"
                                                                    :template-tags {"name" {:id           "name"
                                                                                            :name         "name"
                                                                                            :display-name "Name"
                                                                                            :type         :dimension
                                                                                            :dimension    [:field (mt/id :venues :name) nil]
                                                                                            :widget-type  :string/=}}})}
                 :model/CacheConfig _             (refresh-config "question" card-id :duration one-hour)]
    (let [run-value! #(mt/user-http-request :rasta :post 202 (format "card/%d/query" card-id)
                                            {:parameters [{:type   "string/="
                                                           :value  %
                                                           :id     "name"
                                                           :target [:dimension [:template-tag "name"]]}]})]
      (run-value! ["Red Medicine"])
      (run-value! ["Red Medicine"])
      (let [user-hash   (:cache_hash (first (executions! card-id :question)))
            round-start (t/offset-date-time)]
        (refresh-cache/refresh-caches!)
        (testing "the round refreshes the card without parameters and with the values used in the last period"
          (is (=? [{:cache_hash (complement (same-hash? user-hash))}
                   {:cache_hash (same-hash? user-hash)}]
                  (refreshes! card-id))))
        (testing "so a user's query with those values is served from the refreshed results"
          (is (served-from-cache-written-since? round-start (run-value! ["Red Medicine"]))))))))

(deftest job-is-scheduled-test
  (mt/with-temp-scheduler!
    (task/init! ::refresh-cache/RefreshCache)
    (is (=? {:class                            "metabase.query_processor.task.refresh_cache.RefreshCache"
             :concurrent-execution-disallowed? true
             :triggers                         [{:schedule            "0 * * * * ?"
                                                 :misfire-instruction "DO_NOTHING"}]}
            (task/job-info "metabase.task.refresh-cache.job")))))
