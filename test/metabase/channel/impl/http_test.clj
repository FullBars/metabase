(ns metabase.channel.impl.http-test
  {:clj-kondo/config '{:linters {:deprecated-var {:exclude {metabase.test.data/mbql-query {:namespaces [metabase.channel.impl.http-test]}}}}}}
  (:require
   [clj-http.client :as http]
   [clojure.string :as str]
   [clojure.test :refer :all]
   [clojure.walk :as walk]
   [compojure.core :as compojure]
   [compojure.route :as compojure.route]
   [metabase.channel.core :as channel]
   [metabase.notification.core :as notification]
   [metabase.notification.test-util :as notification.tu]
   [metabase.pulse.send :as pulse.send]
   [metabase.server.handler :as server.handler]
   [metabase.server.middleware.json :as mw.json]
   [metabase.test :as mt]
   [metabase.util.api-error :as api-error]
   [metabase.util.i18n :refer [deferred-tru]]
   [metabase.util.json :as json]
   [metabase.util.malli :as mu]
   [ring.adapter.jetty :as jetty]
   [ring.middleware.params :refer [wrap-params]]
   [toucan2.core :as t2])
  (:import
   (org.eclipse.jetty.server Server)))

(set! *warn-on-reflection* true)

(comment
  server.handler/keepme)

(defn do-with-captured-http-requests
  [f]
  (let [requests (atom [])]
    (binding [http/request (fn [req]
                             (swap! requests conj req)
                             ::noop)]
      (f requests))))

(defmacro ^:private with-captured-http-requests
  [[requests-binding] & body]
  `(do-with-captured-http-requests
    (fn [~requests-binding]
      ~@body)))

(def ^:private default-request
  {:accept       :json
   :content-type :json})

(defn apply-middleware
  [handler middlewares]
  (reduce
   (fn [handler middleware-fn]
     (middleware-fn handler))
   handler
   middlewares))

(defn- wrap-keywordize-json-body
  [handler]
  (fn [req]
    (handler (update req :body #(cond-> % (map? %) walk/keywordize-keys)))))

(def middlewares [wrap-keywordize-json-body
                  mw.json/wrap-json-body
                  mw.json/wrap-streamed-json-response
                  wrap-params])

(defn do-with-server
  [route+handlers f]
  (let [handler        (as-> route+handlers routes+handlers
                         (mapv :route routes+handlers)
                         (conj routes+handlers (compojure.route/not-found {:status-code 404 :body "Not found."}))
                         ;; throwaway Jetty test handler; no OpenAPI spec needed
                         (apply #_{:clj-kondo/ignore [:discouraged-var]} compojure/routes routes+handlers))
        ^Server server (jetty/run-jetty (apply-middleware handler middlewares) {:port 0 :join? false})]
    (try
      (f (str "http://localhost:" (.. server getURI getPort)))
      (finally
        (.stop server)))))

(defn make-route
  "Create a route to be used with [[with-server]].

  (make-route :get \"/test\" (fn [req] {:status 200 :body \"Hello, world!\"}))"
  [method path handler]
  {:path  path
   :route (compojure/make-route method path handler)})

(defmacro with-server
  "Create a temporary server given a list of routes and handlers, and execute the body
  with the server URL binding.

  (with-server [url [(make-route :get (identity {:status 200}))] & handlers]
  (http/get (str url \"/test_http_channel_200\"))"
  [[url-binding handlers] & body]
  `(do-with-server
    ~handlers
    (fn [~url-binding]
      ~@body)))

(def get-favicon
  (make-route :get "/favicon.ico"
              (fn [_]
                {:status 200
                 :body   "Favicon"})))

(def get-200
  (make-route :get "/test_http_channel_200"
              (fn [_]
                {:status 200
                 :body   "Hello, world!"})))

(def post-200
  (make-route :post "/test_http_channel_200"
              (fn [_]
                {:status 200
                 :body   "Hello, world!"})))

(def get-302-redirect-200
  (make-route :get "/test_http_channel_302_redirect_200"
              (fn [_]
                {:status  302
                 :headers {"Location" (:path get-200)}})))

(def get-400
  (make-route :get "/test_http_channel_400"
              (fn [_]
                {:status 400
                 :body   "Bad request"})))

(def post-400
  (make-route :post "/test_http_channel_400"
              (fn [_]
                {:status 400
                 :body   "Bad request"})))

(def get-302-redirect-400
  (make-route :get "/test_http_channel_302_redirect_400"
              (fn [_]
                {:status  302
                 :headers {"Location" (:path get-400)}})))

(def get-500
  (make-route :get "/test_http_channel_500"
              (fn [_]
                {:status 500
                 :body   "Internal server error"})))

(defn can-connect?
  [details]
  (channel/can-connect? :channel/http details))

(defmacro exception-data
  "The client-facing ex-data of the exception `body` throws."
  [& body]
  `(try
     ~@body
     (catch Exception e#
       (api-error/response-data (ex-data e#)))))

(deftest can-connect-no-auth-test
  (mt/with-temp-env-var-value! [mb-http-channel-allowed-networks "allow-all"]
    (with-server [url [get-favicon get-200 get-302-redirect-200 get-400 get-302-redirect-400 get-500]]
      (let [can-connect?* (fn [route]
                            (can-connect? {:url         (str url (:path route))
                                           :auth-method "none"
                                           :method      "get"}))]
        (testing "connect successfully with 200"
          (is (true? (can-connect?* get-200))))
        (testing "connect successfully with 302 redirect to 200"
          (is (true? (can-connect?* get-302-redirect-200))))
        (testing "failed to connect with a 302 that redirects to 400"
          (is (= {:request-status 400
                  :request-body   "Bad request"}
                 (exception-data (can-connect?* get-302-redirect-400)))))
        (testing "failed to conenct to a 400"
          (is (= {:request-status 400
                  :request-body   "Bad request"}
                 (exception-data (can-connect?* get-400)))))
        (is (=? {:request-status 500
                 :request-body   "Internal server error"}
                (exception-data (can-connect?* get-500))))))))

(deftest can-connect-header-auth-test
  (mt/with-temp-env-var-value! [mb-http-channel-allowed-networks "allow-all"]
    (with-server [url [(make-route :get "/user"
                                   (fn [x]
                                     (if (= "SECRET" (get-in x [:headers "x-api-key"]))
                                       {:status 200
                                        :body   "Hello, world!"}
                                       {:status 401
                                        :body   "Unauthorized"})))]]
      (testing "connect successfully with header auth"
        (is (true? (can-connect? {:url         (str url "/user")
                                  :method      "get"
                                  :auth-method "header"
                                  :auth-info   {"x-api-key" "SECRET"}}))))
      (testing "fail to connect with header auth"
        (is (= {:request-status 401
                :request-body   "Unauthorized"}
               (exception-data (can-connect? {:url         (str url "/user")
                                              :method      "get"
                                              :auth-method "header"
                                              :auth-info   {"x-api-key" "WRONG"}}))))))))

(deftest can-connect-query-param-auth-test
  (mt/with-temp-env-var-value! [mb-http-channel-allowed-networks "allow-all"]
    (with-server [url [(make-route :get "/user"
                                   (fn [x]
                                     (if (= ["qnkhuat" "secretpassword"]
                                            [(get-in x [:query-params "username"]) (get-in x [:query-params "password"])])
                                       {:status 200
                                        :body   "Hello, world!"}
                                       {:status 401
                                        :body   "Unauthorized"})))]]
      (testing "connect successfully with query-param auth"
        (is (true? (can-connect? {:url         (str url "/user")
                                  :method      "get"
                                  :auth-method "query-param"
                                  :auth-info   {"username" "qnkhuat"
                                                "password" "secretpassword"}}))))
      (testing "fail to connect with query-param auth"
        (is (= {:request-status 401
                :request-body   "Unauthorized"}
               (exception-data (can-connect? {:url         (str url "/user")
                                              :method      "get"
                                              :auth-method "query-param"
                                              :auth-info   {"username" "qnkhuat"
                                                            "password" "wrongpassword"}}))))))))

(deftest can-connect-request-body-auth-test
  (mt/with-temp-env-var-value! [mb-http-channel-allowed-networks "allow-all"]
    (with-server [url [(make-route :post "/user"
                                   (fn [x]
                                     (if (= "SECRET_TOKEN" (get-in x [:body :token]))
                                       {:status 200
                                        :body   "Hello, world!"}
                                       {:status 401
                                        :body   "Unauthorized"})))]]
      (testing "connect successfully with request-body auth"
        (is (true? (can-connect? {:url         (str url "/user")
                                  :method      "post"
                                  :auth-method "request-body"
                                  :auth-info   {"token" "SECRET_TOKEN"}}))))
      (testing "fail to connect with request-body auth"
        (is (= {:request-status 401
                :request-body   "Unauthorized"}
               (exception-data (can-connect? {:url         (str url "/user")
                                              :method      "post"
                                              :auth-method "request-body"
                                              :auth-info   {"token" "WRONG_TOKEN"}}))))))))

(deftest can-connect?-errors-test
  (testing "throws an appriopriate errors if details are invalid"
    (testing "invalid url"
      (is (= {:errors {:url [(deferred-tru "value must be a valid URL.")]}}
             (exception-data (can-connect? {:url         "not-an-url"
                                            :auth-method "none"})))))
    (testing "testing missing auth-method"
      (is (= {:errors {:auth-method ["missing required key"]}}
             (exception-data (can-connect? {:url "https://www.secret_service.xyz"})))))
    (testing "include undefined key"
      (is (=? {:errors {:xyz ["disallowed key"]}}
              (exception-data (can-connect? {:xyz "hello world"})))))
    (mt/with-temp-env-var-value! [mb-http-channel-allowed-networks "allow-all"]
      (with-server [url [get-400]]
        (is (= {:request-body   "Bad request"
                :request-status 400}
               (exception-data (can-connect? {:url         (str url (:path get-400))
                                              :method      "get"
                                              :auth-method "none"})))))
      (with-server [url [(make-route :get "/test_http_channel_400"
                                     (fn [_]
                                       {:status 400
                                        :body   {:message "too bad"}}))]]
        (testing "attempt to json parse the response body if it's a string"
          (is (= {:request-body   {"message" "too bad"}
                  :request-status 400}
                 (exception-data (can-connect? {:url         (str url (:path get-400))
                                                :method      "get"
                                                :auth-method "none"})))))))))

(deftest send!-test
  (mt/with-temp-env-var-value! [mb-http-channel-allowed-networks "allow-all"]
    (testing "basic send"
      (with-captured-http-requests [requests]
        (channel/send! {:type        :channel/http
                        :details     {:url         "https://www.secret_service.xyz"
                                      :auth-method "none"
                                      :method      "get"}}
                       nil)
        (is (= (merge default-request
                      {:method       :get
                       :url          "https://www.secret_service.xyz"})
               (first @requests)))))
    (testing "default method is post"
      (with-captured-http-requests [requests]
        (channel/send! {:type    :channel/http
                        :details {:url         "https://www.secret_service.xyz"
                                  :auth-method "none"}}
                       nil)
        (is (= (merge default-request
                      {:method       :post
                       :url          "https://www.secret_service.xyz"})
               (first @requests)))))
    (testing "preserves req headers when use auth-method=:header"
      (with-captured-http-requests [requests]
        (channel/send! {:type    :channel/http
                        :details {:url         "https://www.secret_service.xyz"
                                  :auth-method "header"
                                  :auth-info   {"Authorization" "Bearer 123"}
                                  :method      "get"}}
                       {:headers     {:X-Request-Id "123"}})
        (is (= (merge default-request
                      {:method  :get
                       :url          "https://www.secret_service.xyz"
                       :headers      {"Authorization" "Bearer 123"
                                      :X-Request-Id  "123"}})
               (first @requests)))))
    (testing "preserves req query-params when use auth-method=:query-param"
      (with-captured-http-requests [requests]
        (channel/send! {:type    :channel/http
                        :details {:url         "https://www.secret_service.xyz"
                                  :auth-method "query-param"
                                  :auth-info   {"token" "123"}
                                  :method      "get"}}
                       {:query-params {:page 1}})
        (is (= (merge default-request
                      {:method       :get
                       :url          "https://www.secret_service.xyz"
                       :query-params {"token" "123"
                                      :page   1}})
               (first @requests)))))))

(deftest send!-humanized-invalid-url-test
  (mu/disable-enforcement
    (testing "a missing webhook URL throws a human-readable error rather than an NPE (#76802)"
      (doseq [channel [{:type :channel/http}
                       {:type :channel/http :details {}}
                       {:type :channel/http :details {:url "" :auth-method "none"}}]]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"No URL is configured for this webhook"
                              (channel/send! channel nil)))))
    (testing "an unparseable webhook URL throws a human-readable error (#76802)"
      (mt/with-temp-env-var-value! [mb-http-channel-allowed-networks "external-only"]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Invalid webhook URL"
                              (channel/send! {:type    :channel/http
                                              :details {:url "not-a-url" :auth-method "none"}}
                                             nil)))))))

(deftest send!-rejects-any-local-ula-cgnat-test
  (testing "under :external-only, hosts the old valid-host? let through -- any-local (0.0.0.0 / [::]),
           IPv6 ULA, IPv4 CGNAT -- are rejected up front"
    (mt/with-temp-env-var-value! [mb-http-channel-allowed-networks "external-only"]
      (doseq [host ["0.0.0.0" "[::]" "[fc00::1]" "100.64.0.1"]]
        (testing host
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"internal hosting metadata are prohibited"
               (channel/send! {:type    :channel/http
                               :details {:url (str "http://" host ":80/") :auth-method "none"}}
                              {}))))))))

(deftest send!-attaches-dns-resolver-test
  (testing "a connection-time SSRF :dns-resolver is attached for restrictive policies, but not for :allow-all"
    ;; 8.8.8.8 is a public IP literal, so the up-front check needs no DNS lookup
    (doseq [[strategy resolver?] [[:allow-all false]
                                  [:allow-private true]
                                  [:external-only true]]]
      (with-captured-http-requests [requests]
        (mt/with-temp-env-var-value! [mb-http-channel-allowed-networks (name strategy)]
          (channel/send! {:type :channel/http
                          :details {:url         "https://8.8.8.8"
                                    :auth-method "none"
                                    :method      "get"}}
                         {:url          "http://127.0.0.1/"
                          :dns-resolver ::caller-supplied}))
        ;; unrelated background http/request calls can land in the atom too (Clojure conveys the
        ;; `binding` into async tasks), so pick out our request by URL rather than assuming it is first
        (let [req (first (filter #(= "https://8.8.8.8" (:url %)) @requests))]
          (is (some? req) "the rendered request cannot override the configured webhook URL")
          (is (= resolver? (some? (:dns-resolver req)))
              (str strategy " controls whether the policy DNS resolver is present"))
          (is (not= ::caller-supplied (:dns-resolver req))
              "the rendered request cannot override the policy DNS resolver"))))))

(deftest alert-http-channel-e2e-test
  (mt/with-temp-env-var-value! [mb-http-channel-allowed-networks "allow-all"]
    (let [received-message (atom nil)
          receive-route    (make-route :post "/test_http_channel"
                                       (fn [res]
                                         (reset! received-message res)
                                         {:status 200}))]
      (with-server [url [receive-route]]
        (mt/with-temp [:model/Channel      {chn-id :id}  {:type    :channel/http
                                                          :details {:url         (str url (:path receive-route))
                                                                    :auth-method "none"}}]
          (notification.tu/with-card-notification
            [notification {:card     {:dataset_query (mt/mbql-query checkins {:aggregation [:count]})}
                           :handlers [{:channel_type :channel/http
                                       :channel_id chn-id}]}]
            (notification/send-notification! (t2/select-one :model/Notification (:id notification)) :notification/sync? true)
            (is (=? {:body {:type               "alert"
                            :alert_id           (-> notification :payload :id)
                            :alert_creator_id   (mt/malli=? int?)
                            :alert_creator_name (t2/select-one-fn :common_name :model/User (:creator_id notification))
                            :data               {:type          "question"
                                                 :question_id   (-> notification :payload :card_id)
                                                 :question_name (-> notification :payload :card :name)
                                                 :question_url  (mt/malli=? [:fn #(str/ends-with? % (-> notification :payload :card_id str))])
                                                 :visualization (mt/malli=? [:fn #(str/starts-with? % "data:image/png;base64")])
                                                 :raw_data      {:cols ["count"] :rows [[1000]]}}
                            :sent_at            (mt/malli=? :any)}}
                    @received-message))))))))

;; ------------------------------------------------------------------------------------------------;;
;;                                      Feishu (Lark) bot webhooks                                 ;;
;; ------------------------------------------------------------------------------------------------;;

(defn- feishu-bot-route
  "A Feishu custom bot webhook. Like Feishu, it answers HTTP 200 with `response` (a `{code, msg}` map) whether or not
  it accepts the message. Records the JSON body of every message it receives in `received`."
  [received response]
  (make-route :post "/open-apis/bot/v2/hook/test-token"
              (fn [req]
                (swap! received conj (:body req))
                {:status 200
                 :body   response})))

(defn- feishu-channel-details
  [url route]
  {:url (str url (:path route)) :auth-method "none"})

(deftest feishu-send!-checks-response-code-test
  (mt/with-temp-env-var-value! [mb-http-channel-allowed-networks "allow-all"]
    (let [route (feishu-bot-route (atom []) {:code 19002 :msg "params error, msg_type need"})]
      (with-server [url [route]]
        (testing "a Feishu bot answers a message it rejects with HTTP 200 and a non-zero code: that is a failed send"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"params error, msg_type need"
                                (channel/send! {:type    :channel/http
                                                :details (feishu-channel-details url route)}
                                               {:body {:msg_type "text" :content {:text "hi"}}}))))))
    (let [route (feishu-bot-route (atom []) {:code 0 :msg "success"})]
      (with-server [url [route]]
        (testing "code 0 is a successful send"
          (is (=? {:status 200}
                  (channel/send! {:type    :channel/http
                                  :details (feishu-channel-details url route)}
                                 {:body {:msg_type "text" :content {:text "hi"}}}))))))
    (with-server [url [(make-route :post "/hook" (fn [_] {:status 200 :body {:code 19002 :msg "params error"}}))]]
      (testing "other webhooks still only look at the HTTP status"
        (is (=? {:status 200}
                (channel/send! {:type    :channel/http
                                :details {:url (str url "/hook") :auth-method "none"}}
                               {:body {:a 1}})))))))

(deftest feishu-can-connect-test
  (mt/with-temp-env-var-value! [mb-http-channel-allowed-networks "allow-all"]
    (let [received (atom [])
          route    (feishu-bot-route received {:code 0 :msg "success"})]
      (with-server [url [route]]
        (testing "testing a Feishu bot sends it a text message, which Feishu accepts"
          (is (true? (can-connect? (feishu-channel-details url route))))
          (is (=? [{:msg_type "text" :content {:text #".*Metabase.*"}}]
                  @received)))))
    (let [route (feishu-bot-route (atom []) {:code 19021 :msg "sign match fail or timestamp is not within one hour from current time"})]
      (with-server [url [route]]
        (testing "a Feishu bot that rejects the test message fails the connection check with its response"
          (is (= {:request-status 200
                  :request-body   {"code" 19021
                                   "msg"  "sign match fail or timestamp is not within one hour from current time"}}
                 (exception-data (can-connect? (feishu-channel-details url route))))))))))

(defn- card-table
  "The table of a received Feishu card as rows of cell text, the header row first."
  [feishu-message]
  (let [{:keys [columns rows]} (->> feishu-message :card :body :elements (filter #(= "table" (:tag %))) first)]
    (cons (mapv :display_name columns)
          (for [row rows]
            (mapv #(get row (keyword (:name %))) columns)))))

(defn- card-text
  "The markdown text of a received Feishu card."
  [feishu-message]
  (->> feishu-message :card :body :elements (filter #(= "markdown" (:tag %))) (map :content) (str/join "\n")))

(defn- send-card-alert-to-feishu!
  "Sends an alert for a card with `card-props` to a Feishu bot and returns the message the bot received. `extra-routes`
  are served next to the bot, as the Feishu open platform is."
  [card-props & {:keys [extra-routes]}]
  (mt/with-temp-env-var-value! [mb-http-channel-allowed-networks "allow-all"]
    (let [received (atom [])
          route    (feishu-bot-route received {:code 0 :msg "success"})]
      (with-server [url (into [route] extra-routes)]
        (mt/with-temp [:model/Channel {chn-id :id} {:type    :channel/http
                                                    :details (feishu-channel-details url route)}]
          (notification.tu/with-card-notification
            [notification {:card     card-props
                           :handlers [{:channel_type :channel/http
                                       :channel_id   chn-id}]}]
            (notification/send-notification! (t2/select-one :model/Notification (:id notification)) :notification/sync? true)
            (assoc (first @received) ::card-id (-> notification :payload :card_id))))))))

(defn- card-images
  [feishu-message]
  (->> feishu-message :card :body :elements (filter #(= "img" (:tag %)))))

(deftest alert-feishu-e2e-test
  (testing "an alert to a Feishu bot is a Feishu card with the question name, its formatted results and a link"
    (let [message (send-card-alert-to-feishu! {:name          "有消耗无订单"
                                               :dataset_query (mt/mbql-query checkins {:aggregation [:count]})})]
      (is (=? {:msg_type "interactive"
               :card     {:schema "2.0"
                          :header {:title {:content "有消耗无订单"}}}}
              message))
      (testing "cells are formatted the way Metabase shows them, not raw values"
        (is (= [["Count"] ["1,000"]]
               (card-table message))))
      (testing "without a Feishu app set up there is no chart image"
        (is (empty? (card-images message))))
      (is (=? [{:tag       "button"
                :behaviors [{:type        "open_url"
                             :default_url (mt/malli=? [:fn #(str/ends-with? % (str "/question/" (::card-id message)))])}]}]
              (->> message :card :body :elements (filter #(= "button" (:tag %)))))))))

(deftest alert-feishu-size-limit-test
  (testing "a large result is cut down to fit Feishu's 20 KB request limit, and the card says how many rows there are"
    (let [message (send-card-alert-to-feishu!
                   {:dataset_query (mt/native-query {:query "SELECT X AS N, REPEAT('有消耗无订单', 100) AS LONG_TEXT FROM SYSTEM_RANGE(1, 30)"})})
          body    (json/encode (dissoc message ::card-id))
          [_header & rows] (card-table message)]
      (is (<= (count (.getBytes ^String body "UTF-8")) 20000))
      (is (< 0 (count rows) 30))
      (is (= "1" (ffirst rows)) "the first rows are the ones kept")
      (is (str/includes? (card-text message) "30")))))

(defn- feishu-open-platform-routes
  "The Feishu open platform endpoints an alert uses to upload a chart, recording each call in `calls`. The upload
  answers `upload-response`."
  [calls upload-response]
  [(make-route :post "/open-apis/auth/v3/tenant_access_token/internal"
               (fn [req]
                 (swap! calls conj [:token (:body req)])
                 {:status 200
                  :body   {:code 0 :msg "ok" :tenant_access_token "t-test-token" :expire 7200}}))
   (make-route :post "/open-apis/im/v1/images"
               (fn [req]
                 ;; read the upload like Feishu does: answering before the body is in makes Jetty reset the
                 ;; connection mid-send, and the client then sends the upload again
                 (let [body (slurp (:body req))]
                   (swap! calls conj [:upload {:authorization (get-in req [:headers "authorization"])
                                               :content-type  (get-in req [:headers "content-type"])
                                               :image-type?   (str/includes? body "name=\"image_type\"")
                                               :png?          (str/includes? body "PNG")}]))
                 {:status 200
                  :body   upload-response}))])

(defn- send-card-alert-with-feishu-app!
  "Sends an alert for a card with `card-props` to a Feishu bot with a Feishu app set up whose image uploads answer
  `upload-response`. Returns the bot's message and the open platform calls."
  [card-props upload-response]
  (let [calls (atom [])]
    (mt/with-temporary-setting-values [feishu-app-id     "cli_test"
                                       feishu-app-secret "app-secret"]
      (notification.tu/with-javascript-visualization-stub
        {:message (send-card-alert-to-feishu! card-props
                                              :extra-routes (feishu-open-platform-routes calls upload-response))
         :calls   @calls}))))

(def ^:private uploaded {:code 0 :msg "success" :data {:image_key "img_v3_test"}})

(defn- chart-card []
  {:name          "每日订单"
   :display       :line
   :dataset_query (mt/mbql-query checkins {:aggregation [:count] :breakout [!month.date]})})

(deftest alert-feishu-chart-image-test
  (testing "with a Feishu app set up, a chart alert's card starts with the chart, uploaded with the app's tenant token"
    (let [{:keys [message calls]} (send-card-alert-with-feishu-app! (chart-card) uploaded)]
      (is (=? [{:tag "img" :img_key "img_v3_test"}] (card-images message)))
      (is (= "img" (-> message :card :body :elements first :tag)))
      (is (=? [[:token {:app_id "cli_test" :app_secret "app-secret"}]
               [:upload {:authorization "Bearer t-test-token" :content-type #"multipart/form-data.*"
                         :image-type?   true            :png?         true}]]
              calls))
      (testing "and the table of results is still there"
        (is (seq (rest (card-table message)))))))
  (testing "an upload Feishu rejects still sends the alert, without the chart"
    (let [{:keys [message]} (send-card-alert-with-feishu-app!
                             (chart-card)
                             {:code 99991672 :msg "Access denied. One of the following scopes is required: [im:resource]"})]
      (is (=? {:msg_type "interactive"} message))
      (is (empty? (card-images message)))))
  (testing "a table alert gets no image, since the card already shows the table"
    (let [{:keys [message calls]} (send-card-alert-with-feishu-app!
                                   {:display       :table
                                    :dataset_query (mt/mbql-query checkins {:aggregation [:count]})}
                                   uploaded)]
      (is (empty? (card-images message)))
      (is (empty? calls)))))

;;; Feishu group chats: a webhook whose URL is the Feishu app's send message endpoint for a chat. Messages go out as
;;; the app, which can also send files.

(defn- feishu-app-routes
  "The Feishu open platform as the app uses it to send to a group chat: the tenant token, image and file uploads and
  the send message endpoint. Records each call in `calls`."
  [calls]
  (into (feishu-open-platform-routes calls uploaded)
        [(make-route :post "/open-apis/im/v1/files"
                     (fn [req]
                       (let [body (slurp (:body req) :encoding "UTF-8")]
                         (swap! calls conj [:file {:authorization (get-in req [:headers "authorization"])
                                                   :file-type     (second (re-find #"name=\"file_type\"\r\n(?:[^\r]*\r\n)*?\r\n([^\r]*)" body))
                                                   :file-name     (second (re-find #"name=\"file_name\"\r\n(?:[^\r]*\r\n)*?\r\n([^\r]*)" body))
                                                   :pdf?          (str/includes? body "%PDF")}]))
                       {:status 200
                        :body   {:code 0 :msg "success" :data {:file_key "file_v3_test"}}}))
         (make-route :post "/open-apis/im/v1/messages"
                     (fn [req]
                       (swap! calls conj [:message {:authorization   (get-in req [:headers "authorization"])
                                                    :receive_id_type (get-in req [:query-params "receive_id_type"])
                                                    :body            (:body req)}])
                       {:status 200
                        :body   {:code 0 :msg "success" :data {:message_id "om_test"}}}))]))

(defn- group-chat-url
  [server-url]
  (str server-url "/open-apis/im/v1/messages?receive_id_type=chat_id&receive_id=oc_test"))

(defn- sent-messages
  "The messages sent to the group chat, with their JSON `content` decoded."
  [calls]
  (for [[kind {:keys [body]}] calls
        :when (= :message kind)]
    (update body :content json/decode+kw)))

(defmacro ^:private with-feishu-app
  [& body]
  `(mt/with-temporary-setting-values [~'feishu-app-id     "cli_test"
                                      ~'feishu-app-secret "app-secret"]
     ~@body))

(deftest feishu-group-chat-can-connect-test
  (mt/with-temp-env-var-value! [mb-http-channel-allowed-networks "allow-all"]
    (let [calls (atom [])]
      (with-server [url (feishu-app-routes calls)]
        (testing "a Feishu group chat webhook is tested by the app sending the chat a text message"
          (with-feishu-app
            (is (true? (can-connect? {:url (group-chat-url url) :auth-method "none"}))))
          (is (=? [[:token {:app_id "cli_test" :app_secret "app-secret"}]
                   [:message {:authorization   "Bearer t-test-token"
                              :receive_id_type "chat_id"
                              :body            {:receive_id "oc_test" :msg_type "text"}}]]
                  @calls))
          (is (=? [{:content {:text #".*Metabase.*"}}] (sent-messages @calls))))
        (testing "without a Feishu app the error says what to set up"
          (mt/with-temporary-setting-values [feishu-app-id nil feishu-app-secret nil]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"MB_FEISHU_APP_ID"
                                  (can-connect? {:url (group-chat-url url) :auth-method "none"})))))))))

(deftest alert-feishu-group-chat-test
  (testing "an alert to a Feishu group chat is the same card, sent as the app"
    (mt/with-temp-env-var-value! [mb-http-channel-allowed-networks "allow-all"]
      (let [calls (atom [])]
        (with-server [url (feishu-app-routes calls)]
          (with-feishu-app
            (mt/with-temp [:model/Channel {chn-id :id} {:type    :channel/http
                                                        :details {:url (group-chat-url url) :auth-method "none"}}]
              (notification.tu/with-card-notification
                [notification {:card     {:name          "有消耗无订单"
                                          :display       :table
                                          :dataset_query (mt/mbql-query checkins {:aggregation [:count]})}
                               :handlers [{:channel_type :channel/http :channel_id chn-id}]}]
                (notification/send-notification! (t2/select-one :model/Notification (:id notification))
                                                 :notification/sync? true))))
          (is (=? [{:receive_id "oc_test"
                    :msg_type   "interactive"
                    :content    {:schema "2.0" :header {:title {:content "有消耗无订单"}}}}]
                  (sent-messages @calls))))))))

(deftest dashboard-subscription-feishu-group-chat-test
  (testing "a dashboard subscription to a Feishu group chat sends a card linking to the dashboard, then the
            dashboard as a PDF file"
    (mt/with-temp-env-var-value! [mb-http-channel-allowed-networks "allow-all"]
      (let [calls (atom [])]
        (with-server [url (feishu-app-routes calls)]
          (with-feishu-app
            (mt/with-temp [:model/Channel       {chn-id :id}       {:type    :channel/http
                                                                    :details {:url (group-chat-url url) :auth-method "none"}}
                           :model/Card          {card-id :id}      {:name          "Orders per month"
                                                                    :display       :line
                                                                    :dataset_query (mt/mbql-query checkins {:aggregation [:count]
                                                                                                            :breakout    [!month.date]})}
                           :model/Dashboard     {dashboard-id :id} {:name       "Daily KPIs"
                                                                    :parameters [{:id      "period"
                                                                                  :slug    "period"
                                                                                  :name    "Date"
                                                                                  :type    "date/all-options"
                                                                                  :default "thismonth"}]}
                           :model/DashboardCard _                  {:dashboard_id dashboard-id :card_id card-id}
                           :model/Pulse         {pulse-id :id}     {:name "Daily KPIs" :dashboard_id dashboard-id}
                           :model/PulseCard     _                  {:pulse_id pulse-id :card_id card-id :position 0}
                           :model/PulseChannel  _                  {:pulse_id     pulse-id
                                                                    :channel_type :http
                                                                    :channel_id   chn-id}]
              (notification.tu/with-send-notification-sync
                (notification.tu/with-javascript-visualization-stub
                  (pulse.send/send-pulse! (t2/select-one :model/Pulse pulse-id))))
              (let [[card-message file-message :as messages] (sent-messages @calls)]
                (is (= 2 (count messages)))
                (testing "first a card naming the dashboard, with a button that opens it"
                  (is (=? {:receive_id "oc_test"
                           :msg_type   "interactive"
                           :content    {:header {:title {:content "Daily KPIs"}}}}
                          card-message))
                  (is (some #(str/includes? (str (get-in % [:behaviors 0 :default_url])) (str "/dashboard/" dashboard-id))
                            (-> card-message :content :body :elements))))
                (testing "and its filter values, formatted as other subscriptions show them"
                  (is (some #(str/includes? (str (:content %)) "Date: This month")
                            (-> card-message :content :body :elements))))
                (testing "then the dashboard's PDF, uploaded by the app and sent as a file"
                  (is (=? [[:file {:authorization "Bearer t-test-token"
                                   :file-type     "pdf"
                                   :file-name     "Daily KPIs.pdf"
                                   :pdf?          true}]]
                          (filter #(= :file (first %)) @calls)))
                  (is (=? {:receive_id "oc_test" :msg_type "file" :content {:file_key "file_v3_test"}}
                          file-message)))))))))))
