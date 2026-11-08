(ns metabase.channel.impl.http
  (:require
   [clj-http.client :as http]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [java-time.api :as t]
   [metabase.channel.core :as channel]
   [metabase.channel.models.channel :as models.channel]
   [metabase.channel.render.core :as channel.render]
   [metabase.channel.render.table-data :as table-data]
   [metabase.channel.schema :as channel.schema]
   [metabase.channel.settings :as channel.settings]
   [metabase.channel.shared :as channel.shared]
   [metabase.channel.urls :as urls]
   [metabase.formatter.core :as formatter]
   [metabase.parameters.shared :as shared.params]
   [metabase.system.core :as system]
   [metabase.util :as u]
   [metabase.util.http :as u.http]
   [metabase.util.i18n :refer [tru]]
   [metabase.util.json :as json]
   [metabase.util.log :as log]
   [metabase.util.malli :as mu]
   [metabase.util.malli.schema :as ms]))

(def ^:private image-width
  "Maximum width of the rendered PNG of HTML to be sent to HTTP Content that exceeds this width (e.g. a table with
  many columns) is truncated."
  1200)

(def ^:private HTTPChannel
  "What [[channel/send!]] receives: a Channel hydrated from its row, or in a test just the `:type` and `:details` this
  implementation reads."
  [:merge
   [:map {:closed true}
    [:type                         [:= :channel/http]]
    [:id          {:optional true} ms/PositiveInt]
    [:name        {:optional true} :string]
    [:description {:optional true} [:maybe :string]]
    [:active      {:optional true} :boolean]
    [:created_at  {:optional true} :any]
    [:updated_at  {:optional true} :any]]
   [:map {:closed true}
    [:details ::channel.schema/http-details]]])

(defn- check-url!
  [strategy url]
  (when (str/blank? url)
    (throw (ex-info (tru "No URL is configured for this webhook.") {:status-code 400})))
  (let [url (try
              (io/as-url url)
              (catch Exception e
                (throw (ex-info (tru "Invalid webhook URL: {0}" (ex-message e))
                                {:status-code 400
                                 :url         url}
                                e))))]
    (when-not (u.http/host-allowed-for-network-policy? strategy url)
      (throw (ex-info (tru "URLs referring to hosts that supply internal hosting metadata are prohibited.")
                      {:status-code 400})))))

(defn- maybe-parse-json
  [x]
  (if (string? x)
    (try
      (json/decode x)
      (catch Exception _e
        x))
    x))

;; A Feishu (or Lark) custom bot webhook takes messages in its own format, and answers every request with HTTP 200,
;; also one it rejects; only the `code` in its response, 0 on success, says whether it took the message. A webhook whose
;; URL is a custom bot address gets Feishu messages, and a non-zero code fails the send. Other webhooks are unchanged.

(defn- feishu-bot-url?
  "Whether `url` is a Feishu custom bot webhook. Matches the path, so Lark (larksuite.com) and privately deployed
  Feishu match too."
  [url]
  (boolean (some->> url (re-find #"^https?://[^/?#]+/open-apis/bot/v2/hook/"))))

(defn- feishu-url?
  "Whether `url` takes Feishu messages: a group's custom bot, or a group chat that the Feishu app sends to (see
  [[models.channel/feishu-chat]])."
  [url]
  (or (feishu-bot-url? url) (some? (models.channel/feishu-chat url))))

(defn- check-feishu-response!
  [{:keys [status body] :as response}]
  (let [{:strs [code msg]} (let [parsed (maybe-parse-json body)]
                             (when (map? parsed) parsed))]
    (when (and (some? code) (not= 0 code))
      (throw (ex-info (tru "Feishu rejected the message: {0} (code {1})" msg (str code))
                      {:type   ::feishu-rejected
                       :status status
                       :body   body}))))
  response)

(defn- feishu-open-api!
  "POST `request` to the Feishu open platform `path` on the host of the bot's `webhook-url`, so Lark and privately
  deployed Feishu work too, under the webhook's network policy. Returns the decoded response body; a non-zero `code`
  throws."
  [webhook-url path request]
  (let [strategy (channel.settings/http-channel-allowed-networks)
        url      (str (re-find #"^https?://[^/?#]+" webhook-url) path)]
    (check-url! strategy url)
    (-> (http/request (-> (merge {:method :post :accept :json} request {:url url})
                          (u/assoc-dissoc :dns-resolver (u.http/network-policy-dns-resolver strategy))))
        check-feishu-response!
        :body
        json/decode+kw)))

(defn- feishu-tenant-token
  "A tenant access token of the Feishu app in settings, from the Feishu open platform next to `webhook-url`."
  [webhook-url]
  (when-not (and (channel.settings/feishu-app-id) (channel.settings/unobfuscated-feishu-app-secret))
    (throw (ex-info (tru "Set up a Feishu app (MB_FEISHU_APP_ID and MB_FEISHU_APP_SECRET) to send to a Feishu group chat.")
                    {:status-code 400})))
  (:tenant_access_token
   (feishu-open-api! webhook-url "/open-apis/auth/v3/tenant_access_token/internal"
                     {:content-type :json
                      :body         (json/encode {:app_id     (channel.settings/feishu-app-id)
                                                  :app_secret (channel.settings/unobfuscated-feishu-app-secret)})})))

(defn- upload-feishu-file!
  "Upload the PDF `content` as `file-name` with the Feishu app, returning its file key."
  [webhook-url token file-name content]
  (-> (feishu-open-api! webhook-url "/open-apis/im/v1/files"
                        {:headers   {"Authorization" (str "Bearer " token)}
                         :multipart [{:name "file_type" :content "pdf"}
                                     {:name "file_name" :content file-name}
                                     {:part-name "file" :name "dashboard.pdf" :mime-type "application/pdf" :content content}]})
      :data
      :file_key))

(defn- send-feishu-app-message!
  "Send `request` to the Feishu group chat of `url` as the Feishu app. Its `:body` is a Feishu message, an interactive
  card `{:msg_type \"interactive\" :card ...}` or `{:msg_type ... :content ...}`; a `:feishu/file` request,
  `{:file-name ... :content <PDF bytes>}`, uploads the file and sends it as a file message."
  [url {:keys [body] file :feishu/file}]
  (let [{:keys [receive-id receive-id-type]} (models.channel/feishu-chat url)
        token                                (feishu-tenant-token url)
        [msg-type content]                   (cond
                                               file
                                               ["file" {:file_key (upload-feishu-file! url token (:file-name file) (:content file))}]

                                               (= "interactive" (:msg_type body))
                                               ["interactive" (:card body)]

                                               :else
                                               [(:msg_type body) (:content body)])]
    (feishu-open-api! url (str "/open-apis/im/v1/messages?receive_id_type=" receive-id-type)
                      {:headers      {"Authorization" (str "Bearer " token)}
                       :content-type :json
                       :body         (json/encode {:receive_id receive-id
                                                   :msg_type   msg-type
                                                   :content    (json/encode content)})})))

(defn- upload-feishu-image!
  "Upload `png` with the Feishu app in settings, returning its image key."
  [webhook-url png]
  (let [token (feishu-tenant-token webhook-url)]
    (-> (feishu-open-api! webhook-url "/open-apis/im/v1/images"
                          {:headers   {"Authorization" (str "Bearer " token)}
                           :multipart [{:name "image_type" :content "message"}
                                       {:part-name "image" :name "chart.png" :mime-type "image/png" :content png}]})
        :data
        :image_key)))

(mu/defmethod channel/send! :channel/http
  [{{:keys [url method auth-method auth-info]} :details} :- HTTPChannel
   request]
  (let [strategy (channel.settings/http-channel-allowed-networks)
        resolver (u.http/network-policy-dns-resolver strategy)]
    (check-url! strategy url)
    (if (models.channel/feishu-chat url)
      (send-feishu-app-message! url request)
      (let [req (-> (merge
                     {:accept       :json
                      :content-type :json
                      :method       :post}
                     (when method
                       {:method (keyword method)})
                     (cond-> request
                       (= "request-body" auth-method) (update :body merge auth-info)
                       (= "header" auth-method)       (update :headers merge auth-info)
                       (= "query-param" auth-method)  (update :query-params merge auth-info)))
                    (assoc :url url)
                    ;; Remove an incoming resolver under :allow-all; rendered requests must not control
                    ;; DNS resolution.
                    (u/assoc-dissoc :dns-resolver resolver))]
        (cond-> (http/request (cond-> req
                                (or (map? (:body req))
                                    (sequential? (:body req))) (update :body json/encode)))
          (feishu-bot-url? url) check-feishu-response!)))))

(defmethod channel/can-connect? :channel/http
  [_channel-type details]
  (channel.shared/validate-channel-details ::channel.schema/http-details details)
  (try
    (channel/send! {:type :channel/http :details details}
                   (if (feishu-url? (:url details))
                     ;; Feishu rejects anything but one of its messages
                     {:body {:msg_type "text"
                             :content  {:text (tru "Metabase can send to this Feishu bot. This is a test message.")}}}
                     {}))
    true
    (catch Exception e
      (let [data (ex-data e)]
        ;; throw an appropriate error if it's a connection error
        (if (#{::http/unexceptional-status ::feishu-rejected} (:type data))
          (throw (ex-info (tru "Failed to connect to channel")
                          {:request-status (:status data)
                           :request-body   (maybe-parse-json (:body data))
                           :response/keys  #{:request-status :request-body}}))
          (throw e))))))

;; ------------------------------------------------------------------------------------------------;;
;;                                           Alerts                                                ;;
;; ------------------------------------------------------------------------------------------------;;

(defn- qp-result->raw-data
  [qp-result]
  (let [data (:data qp-result)]
    {:cols (map :name (:cols data))
     :rows (:rows data)}))

(def ^:private feishu-max-rows
  "Most rows a Feishu alert card shows."
  20)

(def ^:private feishu-max-cell-length
  "Most characters a Feishu table cell shows. The card cuts long cells off with an ellipsis anyway."
  100)

(def ^:private feishu-max-body-bytes
  "Feishu rejects a request body over 20 KB. Leaves room for the request around the card."
  18000)

(defn- feishu-cell-text
  [s]
  (let [s (str s)]
    (if (> (count s) feishu-max-cell-length)
      (str (u/truncate s (dec feishu-max-cell-length)) "\u2026")
      s)))

(defn- feishu-table
  "The columns of a result as `{:title :numeric?}`, and its rows as cell text formatted as Metabase shows it."
  [card {:keys [cols rows viz-settings]}]
  (let [visible-cols (table-data/visible-columns cols)
        timezone-id  (channel.render/defaulted-timezone card)
        formatters   (mapv #(formatter/create-formatter timezone-id (dissoc % :source-idx :remapped_to_column) viz-settings)
                           visible-cols)]
    {:columns (for [col visible-cols
                    ;; a remapped column is aligned by its target's type
                    :let [value-col (or (:remapped_to_column col) col)]]
                {:title    (feishu-cell-text (or (:display_name col) (:name col)))
                 :numeric? (isa? ((some-fn :effective_type :base_type) value-col) :type/Number)})
     :rows    (for [row rows]
                (mapv (fn [col fmt]
                        (let [value (nth row (:source-idx col) nil)]
                          (feishu-cell-text (when (some? value) (fmt value)))))
                      visible-cols
                      formatters))}))

(def ^:private feishu-imageless-displays
  "Displays whose card shows no image: the card's table already is the visualization."
  #{:table :pivot :object})

(defn- feishu-chart-image-key
  "The image key of the chart of `card-part`, uploaded with the Feishu app in settings. Nil when no app is set up or
  the card is a table, and when the upload fails, which is logged: the alert then goes out without the chart."
  [webhook-url {:keys [card result]}]
  (when (and (channel.settings/feishu-app-id)
             (channel.settings/unobfuscated-feishu-app-secret)
             (not (feishu-imageless-displays (keyword (:display card)))))
    (try
      (upload-feishu-image! webhook-url (channel.render/render-pulse-card-to-png
                                         (channel.render/defaulted-timezone card) card result image-width))
      (catch Exception e
        (log/warn e "Couldn't upload the chart to Feishu; sending the alert without it")
        nil))))

(defn- feishu-alert-card
  "A Feishu interactive card for an alert on `card` showing `shown-rows` of the result's `row-count` rows, under the
  chart uploaded as `image-key` when there is one."
  [card image-key columns shown-rows row-count]
  (let [column-key #(str "c" %)]
    {:msg_type "interactive"
     :card     {:schema "2.0"
                :config {:update_multi true
                         :summary      {:content (tru "Metabase alert: {0}" (:name card))}}
                :header {:title    {:tag "plain_text" :content (:name card)}
                         :subtitle {:tag "plain_text" :content (tru "Metabase alert")}
                         :template "orange"}
                :body   {:elements (concat
                                    (when image-key
                                      [{:tag        "img"
                                        :img_key    image-key
                                        :alt        {:tag "plain_text" :content (:name card)}
                                        :scale_type "fit_horizontal"
                                        :preview    true}])
                                    [{:tag     "markdown"
                                      :content (cond-> (tru "Rows: {0}" row-count)
                                                 (< (count shown-rows) row-count)
                                                 (str " " (tru "Showing the first {0}. Open the question in Metabase for all of them."
                                                               (count shown-rows))))}]
                                    (when (seq shown-rows)
                                      [{:tag          "table"
                                        :page_size    10
                                        :row_height   "low"
                                        :header_style {:bold true :background_style "grey"}
                                        :columns      (map-indexed (fn [i {:keys [title numeric?]}]
                                                                     (cond-> {:name         (column-key i)
                                                                              :display_name title
                                                                              :data_type    "text"
                                                                              :width        "auto"}
                                                                       numeric? (assoc :horizontal_align "right")))
                                                                   columns)
                                        :rows         (for [row shown-rows]
                                                        (zipmap (map column-key (range)) row))}])
                                    [{:tag       "button"
                                      :type      "primary"
                                      :text      {:tag "plain_text" :content (tru "View in Metabase")}
                                      :behaviors [{:type        "open_url"
                                                   :default_url (urls/card-url (:id card))}]}])}}}))

(defn- feishu-alert-message
  "A Feishu card for an alert on `card`, with its chart when a Feishu app is set up, and as many of the first rows of
  its result as fit in a Feishu request."
  [webhook-url card card-part]
  (let [{:keys [columns rows]} (feishu-table card (-> card-part :result :data))
        row-count              (count rows)
        image-key              (feishu-chart-image-key webhook-url card-part)]
    (loop [n (min feishu-max-rows row-count)]
      (let [message (feishu-alert-card card image-key columns (take n rows) row-count)]
        (if (or (zero? n)
                (<= (u/string-byte-count (json/encode message)) feishu-max-body-bytes))
          message
          (recur (dec n)))))))

(mu/defmethod channel/render-notification [:channel/http :notification/card]
  [_channel-type {:keys [payload creator]} handler]
  (let [{:keys [card notification_card card_part]} payload
        card_part                        (channel.shared/maybe-realize-data-rows card_part)
        webhook-url                      (-> handler :channel :details :url)]
    (if (feishu-url? webhook-url)
      [{:body (feishu-alert-message webhook-url card card_part)}]
      [{:body {:type               "alert"
               ;; TODO: can we rename this???
               :alert_id           (:id notification_card)
               :alert_creator_id   (:id creator)
               :alert_creator_name (:common_name creator)
               :data               {:type          "question"
                                    :question_id   (:id card)
                                    :question_name (:name card)
                                    :question_url  (urls/card-url (:id card))
                                    :visualization (let [{:keys [card dashcard result]} card_part]
                                                     (channel.render/render-pulse-card-to-base64
                                                      (channel.render/defaulted-timezone card) card dashcard result image-width))
                                    :raw_data      (qp-result->raw-data (:result card_part))}
               :sent_at            (t/offset-date-time)}}])))

;; ------------------------------------------------------------------------------------------------;;
;;                                    Dashboard Subscriptions                                      ;;
;; ------------------------------------------------------------------------------------------------;;

(defn- feishu-dashboard-card
  "The Feishu card that comes before a dashboard's PDF: the dashboard's name, its filter values, and a button that opens
  it. `pdf?` says whether the PDF follows."
  [dashboard parameters pdf?]
  (let [filters (for [p parameters
                      :let [v (some-> (shared.params/value-string p (system/site-locale)) str str/trim)]
                      :when (not (str/blank? v))]
                  (str (:name p) ": " v))]
    {:msg_type "interactive"
     :card     {:schema "2.0"
                :config {:update_multi true
                         :summary      {:content (tru "Metabase dashboard subscription: {0}" (:name dashboard))}}
                :header {:title    {:tag "plain_text" :content (:name dashboard)}
                         :subtitle {:tag "plain_text" :content (tru "Metabase dashboard subscription")}
                         :template "blue"}
                :body   {:elements [{:tag     "markdown"
                                     :content (cond-> (if pdf?
                                                        (tru "The dashboard is in the PDF below.")
                                                        (tru "Metabase could not make a PDF of the dashboard this time. Open it in Metabase instead."))
                                                (seq filters) (str "\n" (tru "Filters: {0}" (str/join "; " filters))))}
                                    {:tag       "button"
                                     :type      "primary"
                                     :text      {:tag "plain_text" :content (tru "View in Metabase")}
                                     :behaviors [{:type        "open_url"
                                                  :default_url (urls/dashboard-url (:id dashboard) parameters)}]}]}}}))

(mu/defmethod channel/render-notification [:channel/http :notification/dashboard]
  [_channel-type {:keys [payload creator_id]} handler]
  (let [webhook-url                                    (-> handler :channel :details :url)
        {:keys [dashboard dashboard_parts parameters]} payload]
    (when-not (models.channel/feishu-chat webhook-url)
      (throw (ex-info (tru "Only a Feishu group chat webhook can receive a dashboard subscription, which it gets as a PDF.")
                      {:dashboard-id (:id dashboard)})))
    (let [pdf (try
                (channel.render/render-dashboard-to-pdf (:id dashboard) creator_id (vec parameters) :a4 dashboard_parts)
                (catch Throwable e
                  (log/error e "Couldn't render the dashboard subscription PDF; sending the card without it")
                  nil))]
      (cond-> [{:body (feishu-dashboard-card dashboard parameters (some? pdf))}]
        pdf (conj {:feishu/file {:file-name (str (or (some-> (:name dashboard) str/trim not-empty) "dashboard") ".pdf")
                                 :content   pdf}})))))
