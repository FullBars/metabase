(ns metabase.cache.cache-test
  "End to end: caching configured through `/api/cache`, observed by querying Cards through the REST API, directly and
  from a Dashboard."
  (:require
   [clojure.test :refer :all]
   [java-time.api :as t]
   [metabase.test :as mt]))

(set! *warn-on-reflection* true)

(defn- native-query
  "A native query of the test DB that no other test runs, so its cache entry belongs to this test alone."
  []
  (mt/native-query {:query (format "SELECT ID, NAME, '%s' AS TAG FROM VENUES ORDER BY ID LIMIT 2" (random-uuid))}))

(defn- start-of-minute []
  (t/truncate-to (t/zoned-date-time (t/zone-id "UTC")) :minutes))

(defn- at
  "`seconds` after `t0`, for [[mt/with-clock]]."
  [t0 seconds]
  (t/plus t0 (t/seconds seconds)))

(defn- configure! [model model-id strategy]
  (mt/user-http-request :crowberto :put 200 "cache/" {:model model :model_id model-id :strategy strategy}))

(defn- delete-config! [model model-id]
  (mt/user-http-request :crowberto :delete 204 "cache/" {:model model :model_id [model-id]}))

(defn- served-from-cache? [response]
  (is (= "completed" (:status response)))
  (some? (:cached response)))

(defn- query-card
  "Query the Card directly; true when its results came from the cache."
  [card-id]
  (served-from-cache? (mt/user-http-request :crowberto :post 202 (format "card/%d/query" card-id))))

(defn- query-dashcard
  "Query the Card of `dashcard` from its Dashboard; true when its results came from the cache."
  [{dashboard-id :dashboard_id dashcard-id :id card-id :card_id}]
  (served-from-cache? (mt/user-http-request :crowberto :post 202 (format "dashboard/%d/dashcard/%d/card/%d/query"
                                                                         dashboard-id dashcard-id card-id))))

(def ^:private one-hour {:type "duration" :duration 1 :unit "hours"})

(deftest database-duration-test
  (mt/with-premium-features #{}
    (mt/with-model-cleanup [:model/CacheConfig]
      (mt/with-temp [:model/Card {card-id :id} {:database_id (mt/id) :dataset_query (native-query)}]
        (configure! "database" (mt/id) one-hour)
        (let [t0 (start-of-minute)]
          (mt/with-clock (at t0 0)
            (is (false? (query-card card-id)) "the first query runs"))
          (mt/with-clock (at t0 60)
            (is (true? (query-card card-id)) "within the hour it is served from the cache"))
          (mt/with-clock (at t0 (* 61 60))
            (is (false? (query-card card-id)) "after the hour it runs again"))
          (mt/with-clock (at t0 (* 62 60))
            (is (true? (query-card card-id)) "and is cached again")))))))

(deftest question-schedule-test
  (mt/with-premium-features #{}
    (mt/with-model-cleanup [:model/CacheConfig]
      (mt/with-temp [:model/Card {card-id :id} {:database_id (mt/id) :dataset_query (native-query)}]
        (configure! "question" card-id {:type "schedule" :schedule "0 * * * * ?"})
        (let [t0 (start-of-minute)]
          (mt/with-clock (at t0 10)
            (is (false? (query-card card-id)) "the first query runs"))
          (mt/with-clock (at t0 50)
            (is (true? (query-card card-id)) "within the minute it is served from the cache"))
          (mt/with-clock (at t0 65)
            (is (false? (query-card card-id)) "once the schedule fires again it runs again"))
          (mt/with-clock (at t0 70)
            (is (true? (query-card card-id)) "and is cached again")))))))

(deftest dashboard-nocache-test
  (mt/with-premium-features #{}
    (mt/with-model-cleanup [:model/CacheConfig]
      (mt/with-temp [:model/Card          {card-id :id} {:database_id (mt/id) :dataset_query (native-query)}
                     :model/Dashboard     {dash-id :id} {}
                     :model/DashboardCard dashcard      {:dashboard_id dash-id :card_id card-id}]
        (configure! "database" (mt/id) one-hour)
        (configure! "dashboard" dash-id {:type "nocache"})
        (let [t0 (start-of-minute)]
          (testing "from the dashboard the card is never cached"
            (mt/with-clock (at t0 0)
              (is (false? (query-dashcard dashcard))))
            (mt/with-clock (at t0 10)
              (is (false? (query-dashcard dashcard)))))
          (testing "queried directly, the database's duration applies"
            (mt/with-clock (at t0 20)
              (is (false? (query-card card-id))))
            (mt/with-clock (at t0 30)
              (is (true? (query-card card-id)))))
          (testing "from the dashboard it still runs"
            (mt/with-clock (at t0 40)
              (is (false? (query-dashcard dashcard))))))))))

(deftest question-nocache-under-dashboard-duration-test
  (mt/with-premium-features #{}
    (mt/with-model-cleanup [:model/CacheConfig]
      (mt/with-temp [:model/Card          {nocache-card-id :id} {:database_id (mt/id) :dataset_query (native-query)}
                     :model/Card          {other-card-id :id}   {:database_id (mt/id) :dataset_query (native-query)}
                     :model/Dashboard     {dash-id :id}         {}
                     :model/DashboardCard nocache-dashcard      {:dashboard_id dash-id :card_id nocache-card-id}
                     :model/DashboardCard other-dashcard        {:dashboard_id dash-id :card_id other-card-id}]
        (configure! "dashboard" dash-id one-hour)
        (configure! "question" nocache-card-id {:type "nocache"})
        (let [t0 (start-of-minute)]
          (testing "the nocache question always runs"
            (mt/with-clock (at t0 0)
              (is (false? (query-dashcard nocache-dashcard))))
            (mt/with-clock (at t0 10)
              (is (false? (query-dashcard nocache-dashcard)))))
          (testing "while another card on the dashboard is cached"
            (mt/with-clock (at t0 20)
              (is (false? (query-dashcard other-dashcard))))
            (mt/with-clock (at t0 30)
              (is (true? (query-dashcard other-dashcard)))))
          (mt/with-clock (at t0 40)
            (is (false? (query-dashcard nocache-dashcard)))))))))

(deftest deleted-configs-fall-back-test
  (mt/with-premium-features #{}
    (mt/with-model-cleanup [:model/CacheConfig]
      (mt/with-temp [:model/Card          {card-id :id} {:database_id (mt/id) :dataset_query (native-query)}
                     :model/Dashboard     {dash-id :id} {}
                     :model/DashboardCard dashcard      {:dashboard_id dash-id :card_id card-id}]
        (configure! "database" (mt/id) one-hour)
        (configure! "dashboard" dash-id {:type "nocache"})
        (configure! "question" card-id {:type "nocache"})
        (let [t0 (start-of-minute)]
          (mt/with-clock (at t0 0)
            (is (false? (query-dashcard dashcard))))
          (mt/with-clock (at t0 10)
            (is (false? (query-dashcard dashcard))))
          (testing "without the question's config, the dashboard's nocache applies"
            (delete-config! "question" card-id)
            (mt/with-clock (at t0 20)
              (is (false? (query-dashcard dashcard))))
            (mt/with-clock (at t0 30)
              (is (false? (query-dashcard dashcard)))))
          (testing "without the dashboard's config either, the database's duration applies"
            (delete-config! "dashboard" dash-id)
            (mt/with-clock (at t0 40)
              (is (false? (query-dashcard dashcard))))
            (mt/with-clock (at t0 50)
              (is (true? (query-dashcard dashcard))))))))))

(deftest invalidate-test
  (mt/with-premium-features #{}
    (mt/with-model-cleanup [:model/CacheConfig]
      (mt/with-temp [:model/Card          {card-id :id} {:database_id (mt/id) :dataset_query (native-query)}
                     :model/Dashboard     {dash-id :id} {}
                     :model/DashboardCard _             {:dashboard_id dash-id :card_id card-id}]
        (configure! "database" (mt/id) one-hour)
        (let [t0 (start-of-minute)]
          (mt/with-clock (at t0 0)
            (is (false? (query-card card-id))))
          (mt/with-clock (at t0 10)
            (is (true? (query-card card-id))))
          (doseq [[i [param id]] (map-indexed vector [[:question card-id] [:dashboard dash-id] [:database (mt/id)]])
                  :let [t (* 100 (inc i))]]
            (testing (str "invalidating the " (name param) " with its overrides")
              (mt/with-clock (at t0 t)
                (mt/user-http-request :crowberto :post 200 "cache/invalidate" :include "overrides" param id))
              (mt/with-clock (at t0 (+ t 10))
                (is (false? (query-card card-id)) "the next query runs"))
              (mt/with-clock (at t0 (+ t 20))
                (is (true? (query-card card-id)) "the one after is served from the cache")))))))))
