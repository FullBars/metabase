(ns metabase.cache.models.cache-config-test
  (:require
   [clojure.test :refer :all]
   [metabase.cache.core :as cache]
   [metabase.cache.models.cache-config :as cache-config]
   [metabase.test :as mt]))

(defn- effective [card-id database-id dashboard-id]
  (some-> (cache/effective-config {:card-id card-id :database-id database-id :dashboard-id dashboard-id})
          ((juxt :model :model_id))))

(deftest effective-config-precedence-test
  (mt/with-model-cleanup [:model/CacheConfig]
    (mt/with-temp [:model/Database  {db-id :id}    {}
                   :model/Database  {other-db :id} {}
                   :model/Dashboard {dash-id :id}  {}
                   :model/Card      {card-id :id}  {:database_id db-id}
                   :model/Card      {other-card :id} {:database_id db-id}]
      (let [config! (fn [model model-id strategy]
                      (cache-config/store! (mt/user->id :crowberto) {:model model :model_id model-id :strategy strategy}))
            hour    {:type :duration :duration 1 :unit "hours"}]
        (testing "nothing configured"
          (is (nil? (effective card-id db-id dash-id))))
        (config! "root" 0 {:type :ttl :multiplier 10 :min_duration_ms 1000})
        (testing "root is the fallback"
          (is (= ["root" 0] (effective card-id db-id dash-id))))
        (config! "database" db-id hour)
        (testing "the card's database beats root"
          (is (= ["database" db-id] (effective card-id db-id dash-id)))
          (is (= ["root" 0] (effective card-id other-db nil))))
        (config! "dashboard" dash-id {:type :nocache})
        (testing "the dashboard beats the database, but only when the query runs from it"
          (is (= ["dashboard" dash-id] (effective card-id db-id dash-id)))
          (is (= ["database" db-id] (effective card-id db-id nil))))
        (config! "question" card-id hour)
        (testing "the question beats everything"
          (is (= ["question" card-id] (effective card-id db-id dash-id)))
          (testing "and only applies to itself"
            (is (= ["dashboard" dash-id] (effective other-card db-id dash-id)))))))))
