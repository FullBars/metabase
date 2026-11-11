(ns metabase.query-processor.middleware.cache-backend.db-test
  (:require
   [buddy.core.codecs :as codecs]
   [clojure.java.jdbc :as jdbc]
   [clojure.string :as str]
   [clojure.test :refer :all]
   [java-time.api :as t]
   [metabase.app-db.connection :as mdb.connection]
   [metabase.query-processor.middleware.cache-backend.db :as backend.db]
   [metabase.query-processor.middleware.cache-backend.interface :as i]
   [metabase.test :as mt]
   [metabase.test.data :as data]
   [metabase.util.encryption-test :as encryption-test])
  (:import
   (java.util.concurrent CountDownLatch TimeUnit)
   (javax.sql DataSource)))

(set! *warn-on-reflection* true)

(defn- cache-results
  "Get the stored value from the query_cache"
  []
  (let [conn (.getConnection ^DataSource (:data-source mdb.connection/*application-db*))]
    (-> (jdbc/query {:connection conn} "select results from query_cache limit 1")
        first
        :results
        byte-array
        codecs/bytes->str)))

(deftest encryption-test
  (testing "With no encryption, cache results should be stored plain text"
    (encryption-test/with-secret-key nil
      (data/with-empty-h2-app-db!
        (let [cache-backend (i/cache-backend :db)]
          (i/save-results! cache-backend (codecs/to-bytes "cache-key") (codecs/to-bytes "cache-value"))
          (let [cached (cache-results)]
            (is (= "cache-value" cached)))))))
  (testing "With encryption enabled, cache results should be stored encrypted text"
    (encryption-test/with-secret-key "key1"
      (data/with-empty-h2-app-db!
        (let [cache-backend (i/cache-backend :db)]
          (i/save-results! cache-backend (codecs/to-bytes "cache-key") (codecs/to-bytes "cache-value"))
          (let [cached (cache-results)]
            (is (str/starts-with? cached "AES/CBC/PKCS5Padding"))
            (is (not (str/includes? cached "cache-value")))))))))

(deftest save-results-concurrent-race-test
  (testing "Concurrent save-results! calls with the same query hash should not violate the PK constraint (#73770)"
    (data/with-empty-h2-app-db!
      ;; Real query hashes are 32-byte SHA-256 digests; the query_cache PK is BINARY(32). With a
      ;; shorter hash, H2 pads stored values with zeros, so a subsequent select-by-hash would not
      ;; match — masking the retry logic in update-or-insert!.
      (let [query-hash    (byte-array 32 (map byte (concat (.getBytes "race-test-hash") (repeat 0))))
            results-bytes (codecs/to-bytes "result-bytes")
            ;; Force both threads into the INSERT call simultaneously, so they both attempt to
            ;; insert the same primary key. The latch wraps both t2 insert variants so the race
            ;; is deterministically exercised regardless of which one the implementation uses.
            latch                (CountDownLatch. 2)
            ins-pks-var          (requiring-resolve 'toucan2.core/insert-returning-pk!)
            ins-instances-var    (requiring-resolve 'toucan2.core/insert-returning-instances!)
            orig-ins-pks         @ins-pks-var
            orig-ins-instances   @ins-instances-var
            await-race!          (fn [model]
                                   (when (= model :model/QueryCache)
                                     (.countDown latch)
                                     (.await latch 5 TimeUnit/SECONDS)))
            coordinated-ins-pks  (fn [& args]
                                   (await-race! (first args))
                                   (apply orig-ins-pks args))
            coordinated-ins-inst (fn [& args]
                                   (await-race! (first args))
                                   (apply orig-ins-instances args))
            cache-backend        (i/cache-backend :db)]
        (with-redefs-fn {ins-pks-var       coordinated-ins-pks
                         ins-instances-var coordinated-ins-inst}
          (fn []
            (mt/with-log-messages-for-level [messages :error]
              (mt/repeat-concurrently 2 #(i/save-results! cache-backend query-hash results-bytes))
              (testing "both threads reached the coordination point (otherwise the race wasn't exercised)"
                (is (zero? (.getCount latch))))
              (testing "no \"Error saving query results to cache.\" should be logged"
                (is (empty? (filter #(some-> % :message (str/includes? "Error saving query results to cache"))
                                    (messages))))))))))))

(deftest schedule-fire-times-in-report-timezone-test
  (testing "a cron schedule is read in the report timezone, whatever the JVM's timezone"
    (mt/with-temporary-setting-values [report-timezone "Asia/Shanghai"]
      (let [daily-11am "0 0 11 * * ?"
            beijing    #(t/instant (t/offset-date-time % (t/zone-offset "+08:00")))]
        (testing "the last firing at or before a moment"
          (is (= (t/instant "2026-11-12T03:00:00Z")
                 (backend.db/schedule-previous-fire-time daily-11am (beijing "2026-11-12T11:01:00"))))
          (is (= (t/instant "2026-11-12T03:00:00Z")
                 (backend.db/schedule-previous-fire-time daily-11am (beijing "2026-11-13T10:59:00"))))
          (is (= (t/instant "2026-11-11T03:00:00Z")
                 (backend.db/schedule-previous-fire-time daily-11am (beijing "2026-11-12T10:59:00"))))
          (testing "a firing exactly at the moment counts"
            (is (= (t/instant "2026-11-12T03:00:00Z")
                   (backend.db/schedule-previous-fire-time daily-11am (beijing "2026-11-12T11:00:00"))))))
        (testing "the next firing strictly after a moment"
          (is (= (t/instant "2026-11-12T03:00:00Z")
                 (backend.db/schedule-next-fire-time daily-11am (beijing "2026-11-12T10:59:00"))))
          (is (= (t/instant "2026-11-13T03:00:00Z")
                 (backend.db/schedule-next-fire-time daily-11am (beijing "2026-11-12T11:00:00")))))))))

(deftest schedule-fire-times-frequent-and-rare-schedules-test
  (mt/with-temporary-setting-values [report-timezone "UTC"]
    (testing "every second"
      (is (= (t/instant "2026-11-12T03:04:05Z")
             (backend.db/schedule-previous-fire-time "* * * * * ?" (t/instant "2026-11-12T03:04:05.500Z")))))
    (testing "every minute"
      (is (= (t/instant "2026-11-12T03:04:00Z")
             (backend.db/schedule-previous-fire-time "0 * * * * ?" (t/instant "2026-11-12T03:04:59Z")))))
    (testing "monthly, on the first Monday at 09:00"
      (is (= (t/instant "2026-11-02T09:00:00Z")
             (backend.db/schedule-previous-fire-time "0 0 9 ? * 2#1" (t/instant "2026-11-30T00:00:00Z")))))
    (testing "a schedule that has not fired yet"
      (is (nil? (backend.db/schedule-previous-fire-time "0 0 0 1 1 ? 2099" (t/instant "2026-11-12T00:00:00Z")))))))

(deftest schedule-fire-times-system-timezone-test
  (testing "without a report timezone, the system timezone is used"
    (mt/with-temporary-setting-values [report-timezone nil]
      (mt/with-system-timezone-id! "Asia/Tokyo"
        (is (= (t/instant "2026-11-12T02:00:00Z")
               (backend.db/schedule-previous-fire-time "0 0 11 * * ?" (t/instant "2026-11-12T03:00:00Z"))))))))

(defn- boundary
  "The freshness boundary of `strategy`, as an Instant, at the moment `now` (a ZonedDateTime)."
  [strategy now]
  (mt/with-clock now
    (some-> (backend.db/strategy->invalidated-at strategy) t/instant)))

(defn- fresh?
  "Whether an entry written at `written` is still fresh at `now` under `strategy`, as the cache middleware decides it;
  nil when the strategy has no freshness boundary."
  [strategy written now]
  (when-let [b (boundary strategy now)]
    (not (t/before? (t/instant written) b))))

(deftest duration-freshness-boundary-test
  (testing "a duration keeps entries fresh for that long"
    (mt/with-premium-features #{}
      (doseq [[unit expected] [["seconds" "2026-11-12T02:59:30Z"]
                               ["minutes" "2026-11-12T02:30:00Z"]
                               ["hours"   "2026-11-10T21:00:00Z"]
                               ["days"    "2026-10-13T03:00:00Z"]]]
        (testing unit
          (is (= (t/instant expected)
                 (boundary {:type :duration :duration 30 :unit unit} #t "2026-11-12T03:00:00Z[UTC]"))))))))

(deftest schedule-freshness-boundary-test
  (testing "a schedule keeps entries fresh until its next run, read in the report timezone"
    (mt/with-premium-features #{}
      (mt/with-system-timezone-id! "UTC"
        (mt/with-temporary-setting-values [report-timezone "Asia/Shanghai"]
          (let [daily-11am {:type :schedule :schedule "0 0 11 * * ?"}
                beijing    #(t/zoned-date-time (t/local-date-time %) (t/zone-id "Asia/Shanghai"))]
            (testing "an entry written at Beijing 10:59 is stale at Beijing 11:01"
              (is (false? (fresh? daily-11am (beijing "2026-11-12T10:59") (beijing "2026-11-12T11:01")))))
            (testing "an entry written at Beijing 11:01 is still fresh the next day at Beijing 10:59"
              (is (true? (fresh? daily-11am (beijing "2026-11-12T11:01") (beijing "2026-11-13T10:59")))))
            (testing "...and stale from the next run on"
              (is (false? (fresh? daily-11am (beijing "2026-11-12T11:01") (beijing "2026-11-13T11:00")))))))))))

(deftest schedule-that-never-ran-freshness-boundary-test
  (testing "a schedule that hasn't run yet keeps entries fresh unless they are invalidated"
    (mt/with-premium-features #{}
      (let [year-2099 {:type :schedule :schedule "0 0 0 1 1 ? 2099"}
            now       #t "2026-11-12T03:00:00Z[UTC]"]
        (is (true? (fresh? year-2099 #t "2020-01-01T00:00:00Z[UTC]" now)))
        (is (= (t/instant "2026-11-12T02:00:00Z")
               (boundary (assoc year-2099 :invalidated-at (t/offset-date-time "2026-11-12T02:00:00Z")) now)))))))

(deftest manual-invalidation-freshness-boundary-test
  (testing "an invalidation later than the computed boundary moves it forward; an earlier one doesn't"
    (mt/with-premium-features #{}
      (mt/with-temporary-setting-values [report-timezone "UTC"]
        (let [now #t "2026-11-12T03:30:00Z[UTC]"]
          (doseq [[strategy computed] [[{:type :duration :duration 1 :unit "hours"} "2026-11-12T02:30:00Z"]
                                       [{:type :schedule :schedule "0 0 * * * ?"} "2026-11-12T03:00:00Z"]]]
            (testing (name (:type strategy))
              (is (= (t/instant computed)
                     (boundary strategy now)))
              (is (= (t/instant "2026-11-12T03:10:00Z")
                     (boundary (assoc strategy :invalidated-at (t/offset-date-time "2026-11-12T03:10:00Z")) now)))
              (is (= (t/instant computed)
                     (boundary (assoc strategy :invalidated-at (t/offset-date-time "2026-11-12T02:00:00Z")) now))))))))))

(deftest no-freshness-boundary-test
  (testing "nothing is served from cache for nocache or an unknown strategy"
    (mt/with-premium-features #{}
      (is (nil? (backend.db/strategy->invalidated-at {:type :nocache})))
      (is (nil? (backend.db/strategy->invalidated-at {:type :nocache :invalidated-at (t/offset-date-time)})))
      (is (nil? (backend.db/strategy->invalidated-at {:type :weekly}))))))
