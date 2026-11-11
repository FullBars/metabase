(ns metabase.cache.api-test
  (:require
   [clojure.test :refer :all]
   [metabase.permissions.core :as perms]
   [metabase.query-processor.card :as qp.card]
   [metabase.test :as mt]
   [toucan2.core :as t2]))

(deftest cache-config-test
  (mt/with-model-cleanup [:model/CacheConfig]
    (mt/with-premium-features #{}
      (mt/with-temp [:model/Card {card-id :id} {}]
        (testing "Caching can be configured per question without a premium token"
          (is (=? {:id pos-int?}
                  (mt/user-http-request :crowberto :put 200 "cache/"
                                        {:model    "question"
                                         :model_id card-id
                                         :strategy {:type "nocache"}})))
          (is (=? {:data [{:model "question" :model_id card-id :strategy {:type "nocache"}}]}
                  (mt/user-http-request :crowberto :get 200 "cache/"
                                        :model "question" :id card-id))))
        (testing "...but not for something that doesn't exist"
          (mt/user-http-request :crowberto :put 404 "cache/"
                                {:model    "question"
                                 :model_id Integer/MAX_VALUE
                                 :strategy {:type "nocache"}}))))))

(deftest cache-config-root-settings-test
  (mt/with-model-cleanup [:model/CacheConfig]
    (mt/with-premium-features #{}
      (testing "Can operate on root settings though"
        (is (mt/user-http-request :crowberto :put 200 "cache/"
                                  {:model    "root"
                                   :model_id 0
                                   :strategy {:type "nocache"}}))
        (is (=? {:data [{:model "root" :model_id 0}]}
                (mt/user-http-request :crowberto :get 200 "cache/"
                                      :model "root")))
        (is (nil? (mt/user-http-request :crowberto :delete 204 "cache/"
                                        {:model "root" :model_id 0})))))))

(deftest cache-config-all-strategies-test
  (mt/with-model-cleanup [:model/CacheConfig]
    (mt/with-premium-features #{}
      (testing "Every strategy can be used without a premium token"
        (doseq [strategy [{:type "nocache"}
                          {:type "ttl" :multiplier 10 :min_duration_ms 1000}
                          {:type "duration" :duration 2 :unit "hours" :refresh_automatically false}
                          {:type "schedule" :schedule "0/2 * * * * ?" :refresh_automatically false}]]
          (testing (:type strategy)
            (mt/user-http-request :crowberto :put 200 "cache/" {:model "root" :model_id 0 :strategy strategy})
            (is (= [strategy]
                   (map :strategy (:data (mt/user-http-request :crowberto :get 200 "cache/" :model "root"))))))))
      (testing "An unknown strategy is rejected, naming the valid ones"
        (let [response (mt/user-http-request :crowberto :put 400 "cache/"
                                             {:model    "root"
                                              :model_id 0
                                              :strategy {:type "weekly"}})]
          (is (= {:strategy "cache strategy :type must be one of :nocache, :ttl, :duration, :schedule"}
                 (:errors response)))
          (is (re-find #"invalid cache strategy :type \"weekly\", must be one of :nocache, :ttl, :duration, :schedule"
                       (first (get-in response [:specific-errors :strategy])))))))))

(defn- duration [n unit]
  {:type "duration" :duration n :unit unit :refresh_automatically false})

(deftest duration-round-trip-test
  (mt/with-model-cleanup [:model/CacheConfig]
    (mt/with-premium-features #{}
      (mt/with-temp [:model/Dashboard {dash-id :id} {}
                     :model/Card      {card-id :id} {}]
        (doseq [[model model-id strategy] [["root"      0         (duration 1 "days")]
                                           ["database"  (mt/id)   (assoc (duration 2 "hours") :refresh_automatically true)]
                                           ["dashboard" dash-id   (duration 30 "minutes")]
                                           ["question"  card-id   (assoc (duration 45 "seconds") :refresh_automatically true)]]]
          (testing model
            (is (=? {:id pos-int?}
                    (mt/user-http-request :crowberto :put 200 "cache/"
                                          {:model model :model_id model-id :strategy strategy})))
            (is (= [{:model model :model_id model-id :strategy strategy}]
                   (:data (if (= model "root")
                            (mt/user-http-request :crowberto :get 200 "cache/" :model model)
                            (mt/user-http-request :crowberto :get 200 "cache/" :model model :id model-id)))))))))))

(deftest cache-config-permissions-test
  (mt/with-model-cleanup [:model/CacheConfig]
    (mt/with-premium-features #{}
      (mt/with-non-admin-groups-no-root-collection-perms
        (mt/with-temp [:model/Collection {coll-id :id} {}
                       :model/Card       {card-id :id} {:collection_id coll-id}]
          (let [put! (fn [status model model-id]
                       (mt/user-http-request :rasta :put status "cache/"
                                             {:model model :model_id model-id :strategy (duration 1 "hours")}))
                stored? #(t2/exists? :model/CacheConfig :model %1 :model_id %2)]
            (testing "a question needs curate permissions on its collection"
              (put! 403 "question" card-id)
              (is (not (stored? "question" card-id)))
              (perms/grant-collection-readwrite-permissions! (perms/all-users-group) coll-id)
              (put! 200 "question" card-id)
              (is (stored? "question" card-id)))
            (testing "root needs an admin"
              (put! 403 "root" 0)
              (is (not (stored? "root" 0))))
            (testing "a database needs write permissions on it"
              (put! 403 "database" (mt/id))
              (is (not (stored? "database" (mt/id)))))))))))

(deftest get-checks-access-to-each-model-test
  (mt/with-model-cleanup [:model/CacheConfig]
    (mt/with-premium-features #{}
      (mt/with-non-admin-groups-no-root-collection-perms
        (mt/with-temp [:model/Collection  {coll-id :id} {}
                       :model/Card        {card-id :id} {:collection_id coll-id}
                       ;; the policy of a dashboard with the card's id, which rasta has no access to
                       :model/CacheConfig _             {:model "dashboard", :model_id card-id, :strategy :nocache, :config {}}]
          (perms/grant-collection-readwrite-permissions! (perms/all-users-group) coll-id)
          (testing "sanity check: rasta can read the question's policy"
            (mt/user-http-request :rasta :get 200 "cache/" :model "question" :id card-id))
          (testing "listing several models checks access to each, not just the first"
            (mt/user-http-request :rasta :get 403 "cache/" :model ["question" "dashboard"] :id card-id)))))))

(deftest cache-config-validation-test
  (mt/with-model-cleanup [:model/CacheConfig]
    (mt/with-premium-features #{}
      (mt/with-temp [:model/Card {card-id :id} {}]
        (let [put! (fn [status strategy]
                     (mt/user-http-request :crowberto :put status "cache/"
                                           {:model "question" :model_id card-id :strategy strategy}))]
          (doseq [strategy [(duration 0 "hours")
                            (duration -1 "hours")
                            {:type "schedule" :schedule "not a cron"}
                            {:type "schedule" :schedule "0 0 25 * * ?"}
                            {:type "weekly"}]]
            (testing (pr-str strategy)
              (put! 400 strategy)
              (is (not (t2/exists? :model/CacheConfig :model "question" :model_id card-id)))))
          (testing "while a valid one is stored"
            (put! 200 (duration 1 "hours"))
            (is (t2/exists? :model/CacheConfig :model "question" :model_id card-id))))))))

(deftest delete-falls-back-test
  (mt/with-model-cleanup [:model/CacheConfig]
    (mt/with-premium-features #{}
      (mt/with-temp [:model/Card card {:database_id   (mt/id)
                                       :dataset_query (mt/native-query {:query "SELECT 1"})}]
        (let [put! (fn [model model-id strategy]
                     (mt/user-http-request :crowberto :put 200 "cache/"
                                           {:model model :model_id model-id :strategy strategy}))]
          (put! "database" (mt/id) (duration 1 "hours"))
          (put! "question" (:id card) {:type "schedule" :schedule "0 0 * * * ?"})
          (is (=? {:type :schedule} (qp.card/cache-strategy card nil)))
          (mt/user-http-request :crowberto :delete 204 "cache/" {:model "question" :model_id [(:id card)]})
          (is (= [] (:data (mt/user-http-request :crowberto :get 200 "cache/" :model "question" :id (:id card)))))
          (is (=? {:type :duration :duration 1 :unit "hours"} (qp.card/cache-strategy card nil))))))))

(deftest invalidate-test
  (mt/with-premium-features #{}
    (mt/with-temp [:model/Card          {card-id :id} {:database_id (mt/id)}
                   :model/Dashboard     {dash-id :id} {}
                   :model/DashboardCard _             {:dashboard_id dash-id :card_id card-id}]
      (doseq [[param id] [[:question card-id] [:dashboard dash-id] [:database (mt/id)]]]
        (testing (name param)
          (t2/update! :model/Card card-id {:cache_invalidated_at nil})
          (is (=? {:count pos-int? :message string?}
                  (mt/user-http-request :crowberto :post 200 "cache/invalidate" :include "overrides" param id)))
          (is (some? (t2/select-one-fn :cache_invalidated_at :model/Card :id card-id))))))))
