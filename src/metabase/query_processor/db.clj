(ns metabase.query-processor.db
  "Application database queries for the query processor module. Every function here is a direct Toucan 2 call with no
  additional logic, so the rest of the module never talks to `toucan2.core` itself."
  (:require
   [java-time.api :as t]
   [metabase.app-db.core :as app-db]
   [metabase.lib.schema.id :as lib.schema.id]
   [metabase.lib.schema.metadata :as lib.schema.metadata]
   [metabase.queries.schema :as queries.schema]
   [metabase.util.malli :as mu]
   [metabase.warehouse-schema-overlay.core :as warehouse-schema-overlay]
   ^{:clj-kondo/ignore [:discouraged-namespace]}
   [toucan2.core :as t2]))

(def ^:private Temporal
  "A `java.time` instant, date, or date-time."
  [:fn #(instance? java.time.temporal.Temporal %)])

(mu/defn table
  "The Table with `table-id`, or nil."
  [table-id :- [:maybe ::lib.schema.id/table]]
  (t2/select-one :model/Table :id table-id {:from [(warehouse-schema-overlay/table-query)]}))

(mu/defn dashcard-series-exists?
  "Whether the Card with `card-id` is a series of the DashboardCard with `dashcard-id`."
  [card-id     :- [:maybe ::lib.schema.id/card]
   dashcard-id :- [:maybe ::lib.schema.id/dashcard]]
  (t2/exists? :model/DashboardCardSeries :card_id card-id :dashboardcard_id dashcard-id))

(mu/defn cache-entry
  "`row-fn` applied to the results and updated-at of the QueryCache entry for `query-hash`, or nil."
  [row-fn     :- fn?
   query-hash :- bytes?]
  (t2/select-one-fn row-fn [:model/QueryCache :results :updated_at] :query_hash query-hash))

(mu/defn claim-cache-refresh-lease!
  "Set `refresh_started_at` of the QueryCache entry for `query-hash` to `started-at` if its lease (defaulting to
  `lease-free-sentinel`) is older than `lease-cutoff`, returning the number of rows updated."
  [query-hash          :- bytes?
   lease-free-sentinel :- Temporal
   lease-cutoff        :- Temporal
   started-at          :- Temporal]
  (t2/update! (t2/table-name :model/QueryCache)
              {:query_hash                                         query-hash
               [:coalesce :refresh_started_at lease-free-sentinel] [:< lease-cutoff]}
              {:refresh_started_at started-at}))

(mu/defn delete-cache-entry!
  "Delete the QueryCache entry for `query-hash`."
  [query-hash :- bytes?]
  (t2/delete! (t2/table-name :model/QueryCache) :query_hash query-hash))

(mu/defn delete-cache-entries-updated-before!
  "Delete the QueryCache entries last updated at or before `updated-before`."
  [updated-before :- Temporal]
  (t2/delete! (t2/table-name :model/QueryCache) :updated_at [:<= updated-before]))

(mu/defn insert-query-executions!
  "Insert the QueryExecution `rows`. Runs in its own (possibly nested) transaction so that if the app DB rejects a
  row, the failure is rolled back to a savepoint instead of aborting a caller's enclosing transaction."
  [rows :- [:sequential
            ::queries.schema/query-execution.update]]
  (t2/with-transaction [_conn]
    (t2/insert! :model/QueryExecution rows)))

(mu/defn set-card-result-metadata!
  "Set the result metadata of the Card with `card-id` without touching `updated_at`."
  [card-id         :- ::lib.schema.id/card
   result-metadata :- [:maybe ::lib.schema.metadata/card.result-metadata]]
  (t2/update! :model/Card card-id {:result_metadata result-metadata
                                   :updated_at      :updated_at}))

(mu/defn update-cards-last-used-at!
  "Move `last_used_at` of each Card in `card-id->timestamp` forward to its timestamp, without touching `updated_at`."
  [card-id->timestamp :- [:map-of ::lib.schema.id/card Temporal]]
  (t2/query {:update [(t2/table-name :model/Card)]
             :where  [:in :id (keys card-id->timestamp)]
             :set    {:last_used_at (into [:case]
                                          (mapcat (fn [[id timestamp]]
                                                    [[:= :id id] [:greatest [:coalesce :last_used_at (t/offset-date-time 0)] timestamp]])
                                                  card-id->timestamp))
                      :updated_at :updated_at}}))

(mu/defn card-database-ids
  "The `:id` and `:database_id` of the Cards with `card-ids`."
  [card-ids :- [:set ::lib.schema.id/card]]
  (t2/select [:model/Card :id :database_id] :id [:in card-ids]))

(mu/defn upsert-cache-entry!
  "Insert or update the QueryCache entry for `query-hash`, setting `:results` to `results` and `:updated_at` to
  `timestamp`, and clearing `:refresh_started_at`. Returns the entry's primary key, which is the hash bytes."
  [query-hash :- bytes?
   timestamp  :- Temporal
   results    :- bytes?]
  (app-db/update-or-insert! :model/QueryCache {:query_hash query-hash}
                            (constantly {:updated_at         timestamp
                                         :results            results
                                         :refresh_started_at nil})))

(mu/defn unarchived-card
  "The Card with `card-id` unless it's archived, or nil."
  [card-id :- ::lib.schema.id/card]
  (t2/select-one :model/Card :id card-id :archived false))

(mu/defn unarchived-dashboard
  "The Dashboard with `dashboard-id` unless it's archived, or nil."
  [dashboard-id :- ::lib.schema.id/dashboard]
  (t2/select-one :model/Dashboard :id dashboard-id :archived false))

(mu/defn card-dashcards
  "The DashboardCards of the Dashboard with `dashboard-id` that show a Card (not an action, text, heading or link),
  oldest first."
  [dashboard-id :- ::lib.schema.id/dashboard]
  (t2/select :model/DashboardCard :dashboard_id dashboard-id :card_id [:not= nil] :action_id nil
             {:order-by [[:id :asc]]}))

(mu/defn dashcards-series
  "The `:dashboardcard_id` and `:card_id` of the series of the DashboardCards with `dashcard-ids`, in position order."
  [dashcard-ids :- [:sequential ::lib.schema.id/dashcard]]
  (t2/select [:model/DashboardCardSeries :dashboardcard_id :card_id] :dashboardcard_id [:in dashcard-ids]
             {:order-by [[:position :asc]]}))

(mu/defn card-dashboard-ids
  "The ids of the Dashboards that show the Card with `card-id`, on a DashboardCard or as a DashboardCard's series."
  [card-id :- ::lib.schema.id/card]
  (let [series-dashcard-ids (t2/select-fn-set :dashboardcard_id :model/DashboardCardSeries :card_id card-id)]
    (into (set (t2/select-fn-set :dashboard_id :model/DashboardCard :card_id card-id))
          (when (seq series-dashcard-ids)
            (t2/select-fn-set :dashboard_id :model/DashboardCard :id [:in series-dashcard-ids])))))

(mu/defn most-run-query-hashes
  "The `:hash` and number of `:runs` of the queries of the Card with `card-id` that ran without error in `context`
  from the Dashboard with `dashboard-id` (outside any Dashboard when nil) since `since`, most run first, at most
  `limit` of them."
  [card-id      :- ::lib.schema.id/card
   dashboard-id :- [:maybe ::lib.schema.id/dashboard]
   context      :- :keyword
   since        :- Temporal
   limit        :- pos-int?]
  (t2/query {:select   [:hash [[:count :*] :runs]]
             :from     [(t2/table-name :model/QueryExecution)]
             :where    [:and
                        [:= :card_id card-id]
                        [:= :dashboard_id dashboard-id]
                        [:= :context (name context)]
                        [:= :error nil]
                        [:>= :started_at since]]
             :group-by [:hash]
             :order-by [[:runs :desc]]
             :limit    limit}))

(mu/defn stored-query
  "The query the Query model stores for `query-hash`, or nil."
  [query-hash :- bytes?]
  (t2/select-one-fn :query :model/Query :query_hash query-hash))
