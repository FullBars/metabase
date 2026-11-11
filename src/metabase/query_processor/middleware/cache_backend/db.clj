(ns metabase.query-processor.middleware.cache-backend.db
  (:refer-clojure :exclude [mapv some])
  (:require
   [java-time.api :as t]
   [metabase.driver :as driver]
   [metabase.premium-features.core :refer [defenterprise]]
   [metabase.query-processor.db :as query-processor.db]
   [metabase.query-processor.middleware.cache-backend.interface :as i]
   [metabase.query-processor.timezone :as qp.timezone]
   [metabase.util.date-2 :as u.date]
   [metabase.util.encryption :as encryption]
   [metabase.util.log :as log]
   [metabase.util.performance :refer [mapv some]])
  (:import
   (java.io ByteArrayInputStream)
   (java.time Instant)
   (java.util Date TimeZone)
   (org.quartz CronExpression)))

(set! *warn-on-reflection* true)

(defn- ms-ago [n]
  (u.date/add (t/offset-date-time) :millisecond (- n)))

(defn- seconds-ago [n]
  (ms-ago (long (* 1000 n))))

(defmulti results-as-bytes
  "Handles converting the results db column into a byte array"
  {:arglists '([cached])}
  (comp type :results))

(defmethod results-as-bytes java.sql.Blob
  [{:keys [^java.sql.Blob results]}]
  (.getBytes results 1 (int (.length results))))

;; MySQL/Mariadb/Postgresql return a byte array
(defmethod results-as-bytes :default
  [{:keys [results]}]
  results)

(defn select-latest-cache-entry
  "The most recent cache entry for `query-hash` *regardless of TTL* (`query_hash` is the PK, so there's at most one):
  `{:results <raw bytes>, :updated-at <timestamp>}`, or nil if there's no entry. The caller compares `:updated-at`
  against the strategy's freshness boundary to decide whether to serve the entry, serve it stale while refreshing, or
  recompute (see [[strategy->invalidated-at]]).

  Reads the `results` blob *inside* the query reduction (via `select-one-fn`): an H2 `JdbcBlob` is only valid while
  its result set is open, so materializing the row first and reading the blob afterwards throws \"object is already
  closed\"."
  [query-hash]
  (query-processor.db/cache-entry (fn [row]
                                    {:results    (results-as-bytes row)
                                     :updated-at (:updated_at row)})
                                  query-hash))

(defn- schedule-cron-expression
  "`schedule` (a Quartz cron string) read in the report timezone, or the system timezone when there is none -- the
  timezone people mean when they pick \"daily at 11:00\", as for model persistence schedules."
  ^CronExpression [^String schedule]
  (doto (CronExpression. schedule)
    (.setTimeZone (TimeZone/getTimeZone ^String (or (driver/report-timezone)
                                                    (qp.timezone/system-timezone-id)
                                                    "UTC")))))

(def ^:private schedule-lookback-ms
  "How far back to look for a schedule's last firing, shortest first, so a frequent schedule is found in a short window
  and stepping through its firings stays cheap."
  (mapv #(* 1000 %) [60 (* 60 60) (* 24 60 60) (* 7 24 60 60) (* 32 24 60 60) (* 367 24 60 60) (* 5 367 24 60 60)]))

(defn schedule-previous-fire-time
  "The latest instant at or before `instant` at which the Quartz cron `schedule` fires (see
  [[schedule-cron-expression]] for its timezone), or nil if it hasn't fired in the last five years."
  [schedule instant]
  (let [cron   (schedule-cron-expression schedule)
        now    (Date/from (t/instant instant))
        now-ms (.getTime now)
        after  (fn ^Date [^Date d] (.getNextValidTimeAfter cron d))]
    (some (fn [window-ms]
            (when-let [^Date first-fire (after (Date. (- now-ms (long window-ms))))]
              (when-not (.after first-fire now)
                (loop [^Date fire first-fire]
                  (let [^Date next-fire (after fire)]
                    (if (and next-fire (not (.after next-fire now)))
                      (recur next-fire)
                      (.toInstant fire)))))))
          schedule-lookback-ms)))

(defn schedule-next-fire-time
  "The first instant strictly after `instant` at which the Quartz cron `schedule` fires (see
  [[schedule-cron-expression]] for its timezone), or nil if it never fires again."
  [schedule instant]
  (some-> (.getNextValidTimeAfter (schedule-cron-expression schedule) (Date/from (t/instant instant)))
          .toInstant))

(defn invalidated-at-ttl
  "Freshness boundary for a `:ttl` strategy: cache entries with `updated_at` older than this are stale. Returns nil when
  the strategy is missing `:avg-execution-ms` (so a TTL can't be computed and nothing should be served from cache)."
  [strategy]
  (when-let [avg-execution-ms (:avg-execution-ms strategy)]
    ;; `:multiplier` can be fractional, so round to whole milliseconds (`ms-ago`/`u.date/add` want an integer)
    (let [boundary (ms-ago (long (* (:multiplier strategy) avg-execution-ms)))]
      (if-let [invalidated-at (:invalidated-at strategy)]
        (t/max boundary invalidated-at)
        boundary))))

(defn- latest-of
  "The later of `boundary` and `invalidated-at` (a manual invalidation, nil when there was none), as an Instant: the
  two can be different kinds of temporal (an Instant computed here, an OffsetDateTime read from the app DB)."
  [boundary invalidated-at]
  (cond-> (t/instant boundary)
    invalidated-at (t/max (t/instant invalidated-at))))

(defenterprise strategy->invalidated-at
  "Freshness boundary timestamp for `strategy`: cache entries with `updated_at` strictly older than this are stale and
  must be refreshed. For `:duration` it's that long ago, for `:schedule` the last time the schedule ran (the epoch when
  it hasn't run yet), moved forward to the strategy's `:invalidated-at` when that is later. Returns nil when nothing
  can be served from cache for this strategy (`:nocache`, or an unknown type)."
  metabase-enterprise.cache.strategies
  [{:keys [invalidated-at] :as strategy}]
  (case (:type strategy)
    :ttl      (invalidated-at-ttl strategy)
    :duration (latest-of (t/minus (t/instant) (t/duration (long (:duration strategy)) (keyword (:unit strategy))))
                         invalidated-at)
    :schedule (latest-of (or (schedule-previous-fire-time (:schedule strategy) (t/instant))
                             Instant/EPOCH)
                         invalidated-at)
    nil))

(def ^:private lease-free-sentinel
  "Substituted (via COALESCE) for a NULL `refresh_started_at` so that 'no refresh in progress' counts as a free lease.
  Any timestamp older than every realistic lease cutoff works."
  (t/offset-date-time "1970-01-01T00:00Z"))

(defn try-acquire-refresh-lease!
  "Atomically claim, across processes, the right to recompute the expired entry for `query-hash`, via a conditional
  UPDATE on `refresh_started_at`. Returns true iff this process won the lease (and so should recompute); false means
  another process is already refreshing it (and we should serve stale instead). A lease older than `lease-ms` is
  considered abandoned (e.g. the claimer crashed) and can be taken over.

  Updates the raw table, not the `:model/QueryCache` model: the model's `:hook/updated-at-timestamped?` before-update
  hook makes toucan2 run a non-atomic SELECT-then-UPDATE-by-PK, which would move the conditional lease check into the
  SELECT and let two processes both win. The raw table keeps this a single atomic conditional UPDATE, which also means
  the hook does not run -- so `updated_at` is left alone. That is intentional: `updated_at` means \"when the results
  blob was last written\", read that way by [[cache-fresh?]], [[purge-old-cache-entries!]], and the EE refresh
  scheduler; bumping it here would let a crashed refresh silently extend the row's freshness (#76856)."
  [query-hash lease-ms]
  (pos? (query-processor.db/claim-cache-refresh-lease! query-hash lease-free-sentinel (ms-ago lease-ms) (t/offset-date-time))))

(defn delete-entry!
  "Delete the cache entry for `query-hash`, if one exists. Deleting the row also releases any held refresh lease, so
  after a refresh fails to save new results the outdated blob is neither served stale to lease losers nor treated as
  fresh for the rest of its window. Never throws: this runs during query result reduction, and a failed cleanup
  shouldn't fail a query that already ran successfully."
  [^bytes query-hash]
  (try
    (query-processor.db/delete-cache-entry! query-hash)
    (catch Throwable e
      (log/errorf "Error deleting outdated cache entry: %s" (ex-message e))))
  nil)

(defn- purge-old-cache-entries!
  "Delete any cache entries that are older than the global max age `max-cache-entry-age-seconds` (currently 3 months)."
  [max-age-seconds]
  {:pre [(number? max-age-seconds)]}
  (log/trace "Purging old cache entries.")
  (try
    (query-processor.db/delete-cache-entries-updated-before! (seconds-ago max-age-seconds))
    (catch Throwable e
      (log/errorf "Error purging old cache entries: %s" (ex-message e))))
  nil)

(defn- save-results!
  "Save the `results` of query with `query-hash`, updating an existing QueryCache entry if one already exists, otherwise
  creating a new entry."
  [^bytes query-hash ^bytes results]
  (log/debugf "Caching results for query with hash %s." (pr-str (i/short-hex-hash query-hash)))
  (let [final-results (encryption/maybe-encrypt-for-stream results)
        timestamp     (t/offset-date-time)]
    (try
      (query-processor.db/upsert-cache-entry! query-hash timestamp final-results)
      (catch Throwable e
        (log/errorf "Error saving query results to cache: %s" (ex-message e))))
    nil))

(defmethod i/cache-backend :db
  [_]
  (reify i/CacheBackend
    (cached-results [_ query-hash respond]
      (if-let [{:keys [results updated-at]} (select-latest-cache-entry query-hash)]
        (with-open [is (encryption/maybe-decrypt-stream (ByteArrayInputStream. results))]
          (respond is updated-at))
        (respond nil nil)))

    (save-results! [_ query-hash is]
      (save-results! query-hash is)
      nil)

    (purge-old-entries! [_ max-age-seconds]
      (purge-old-cache-entries! max-age-seconds))

    (delete-entry! [_ query-hash]
      (delete-entry! query-hash))

    (try-acquire-refresh-lease! [_ query-hash lease-ms]
      (try-acquire-refresh-lease! query-hash lease-ms))))
