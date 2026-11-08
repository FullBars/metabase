(ns ^{:added "0.51.0"} metabase.channel.models.channel
  (:require
   [clojure.string :as str]
   [malli.core :as mc]
   [metabase.analytics-interface.core :as analytics]
   [metabase.analytics.core :as analytics.core]
   [metabase.api.common :as api]
   [metabase.channel.core :as channel]
   [metabase.channel.db :as channel.db]
   [metabase.channel.schema :as channel.schema]
   [metabase.events.core :as events]
   [metabase.lib.schema.common :as lib.schema.common]
   [metabase.models.interface :as mi]
   [metabase.models.serialization :as serdes]
   [metabase.permissions.core :as perms]
   [metabase.util :as u]
   [metabase.util.i18n :refer [tru]]
   [metabase.util.log :as log]
   [metabase.util.malli :as mu]
   [metabase.util.malli.registry :as mr]
   [metabase.util.malli.schema :as ms]
   [methodical.core :as methodical]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

(methodical/defmethod t2/table-name :model/Channel         [_model] :channel)
(methodical/defmethod t2/table-name :model/ChannelTemplate [_model] :channel_template)

(doto :model/Channel
  (derive :metabase/model)
  (derive :hook/timestamped?))

(doto :model/ChannelTemplate
  (derive :metabase/model)
  (derive :hook/timestamped?))

;; ------------------------------------------------------------------------------------------------;;
;;                                           :model/Channel                                        ;;
;; ------------------------------------------------------------------------------------------------;;

(def ^:private RowTimestamp
  "A `created_at`/`updated_at` column: a `java.time` value on the way out, and -- because a notification update echoes
  back the channel and template it was handed -- the ISO string it was encoded as on the way back in."
  [:maybe [:or ms/TemporalInstant ms/TemporalString]])

(defn- stringify-auth-info-keys
  "An HTTP channel's `:auth-info` is keyed by the header or parameter names the user chose, so it is string-keyed, as
  `::channel.schema/http-details` says, rather than keywordized like the rest of the details."
  [details]
  (cond-> details
    (map? (:auth-info details)) (update :auth-info update-keys u/qualified-name)))

(t2/deftransforms :model/Channel
  {:type    (mi/transform-validator mi/transform-keyword (partial mi/assert-namespaced "channel"))
   :details (update (mi/transform-encrypted-json "channel.details") :out #(comp stringify-auth-info-keys %))})

(mr/def ::Channel
  "Channel schema."
  [:merge
   [:map {:closed true}
    [:name                         string?]
    [:type                         :keyword]
    [:active      {:optional true} :boolean]
    [:description {:optional true} [:maybe string?]]
    [:id          {:optional true} ms/PositiveInt]
    [:created_at  {:optional true} RowTimestamp]
    [:updated_at  {:optional true} RowTimestamp]]
   ::channel.schema/channel.details-by-type])

(defmethod mi/can-write? :model/Channel
  [& _]
  (or (mi/superuser?)
      (perms/current-user-has-application-permissions? :setting)))

(defmethod mi/can-read? :model/Channel
  [_channel]
  (or (mi/superuser?)
      (perms/current-user-has-application-permissions? :setting)))

(methodical/defmethod mi/to-json :model/Channel
  "Only include `:details` for callers who can write the channel, matching `remove-details-if-needed` in the channel
  API. Encoding at the model boundary keeps every response that returns a Channel consistent."
  [channel json-generator]
  (next-method (if (mi/can-write? channel)
                 channel
                 (dissoc channel :details))
               json-generator))

(t2/define-before-update :model/Channel
  [instance]
  (let [deactivation? (false? (:active (t2/changes instance)))]
    (when deactivation?
      (channel.db/delete-pulse-channels-for-channel! (:id instance)))
    (cond-> instance
      deactivation?
      ;; Channel.name has an unique constraint and it's a useful property for serialization
      ;; We rename deactivated channels so that new channels can reuse the name
      ;; Limit to 254 characters to avoid hitting character limit
      (assoc :name (u/truncate (format "DEACTIVATED_%d %s" (:id instance) (:name instance)) 254)))))

(defmethod serdes/entity-id "Channel" [_ {:keys [name]}] name)

(defmethod serdes/load-find-local "Channel"
  [path]
  (channel.db/channel-by-name (:id (last path))))

(defmethod serdes/generate-path "Channel" [_ channel]
  [(serdes/infer-self-path "Channel" channel)])

(defmethod serdes/storage-path "Channel" [channel _ctx]
  [{:label "channels"} {:label (:name channel) :key (serdes/entity-id "Channel" channel)}])

(defmethod serdes/make-spec "Channel"
  [_model-name _opts]
  {:copy           [:name :description :type :details :active]
   :defaults {:active true}})

(defn active-webhooks
  "The `:id` and `:name` of every active webhook (`:channel/http`) channel, by name: what an agent picks an alert's
  delivery from, such as a Feishu group bot an admin set up. Never the details, which hold the URL and credentials."
  []
  (channel.db/active-webhook-ids-and-names))

(defn find-active-webhook
  "The active webhook in [[active-webhooks]] named `name-or-id`: its id, as an integer or a numeric string, or its
  name, ignoring case and surrounding whitespace. Nil when there is none."
  [name-or-id]
  (let [webhooks (active-webhooks)
        id       (cond
                   (int? name-or-id)                                             name-or-id
                   (and (string? name-or-id) (re-matches #"\d+" name-or-id)) (parse-long name-or-id))
        norm     #(u/lower-case-en (str/trim %))]
    (or (when id
          (some #(when (= id (:id %)) %) webhooks))
        (when (string? name-or-id)
          (some #(when (= (norm name-or-id) (norm (:name %))) %) webhooks)))))

(defn feishu-chat
  "The receiver of a Feishu group chat webhook, `{:receive-id \"oc_...\" :receive-id-type \"chat_id\"}`, or nil when
  `url` isn't one. Such a webhook is the Feishu app's send message endpoint for a chat,
  https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=chat_id&receive_id=oc_..., and Metabase sends to it
  as the app in the Feishu app settings, which, unlike a group's custom bot, can send files."
  [url]
  (when-let [[_ query] (some->> url (re-find #"^https?://[^/?#]+/open-apis/im/v1/messages\?([^#]*)"))]
    (let [params (into {}
                       (keep #(let [[k v] (str/split % #"=" 2)] (when v [k v])))
                       (str/split query #"&"))]
      (when-let [receive-id (not-empty (get params "receive_id"))]
        {:receive-id      receive-id
         :receive-id-type (get params "receive_id_type" "chat_id")}))))

(defn feishu-chat-webhook-url
  "The webhook URL of the Feishu group chat with id `chat-id`."
  [chat-id]
  (str "https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=chat_id&receive_id=" chat-id))

(defn active-dashboard-webhooks
  "The `:id` and `:name` of every active webhook that can receive a dashboard subscription: a Feishu group chat, which
  gets the dashboard as a PDF. A custom bot can't send files."
  []
  (into []
        (comp (filter #(feishu-chat (get-in % [:details :url])))
              (map #(select-keys % [:id :name])))
        (channel.db/active-webhooks)))

(defn receives-dashboards?
  "Whether the webhook channel with id `channel-id` can receive a dashboard subscription. See
  [[active-dashboard-webhooks]]."
  [channel-id]
  (boolean (some-> (channel.db/channel channel-id) :details :url feishu-chat)))

(defn create-webhook!
  "Create an active webhook channel named `webhook-name` that posts to `url` without authentication, or to the Feishu
  group chat `feishu-chat-id`, as the current user: what the admin Webhooks form does, for agents. Like the REST API it needs the setting permission and a name no
  channel has. It runs the form's connection test first, so a URL that doesn't answer, or a Feishu bot that rejects
  the message, is never saved; a Feishu bot gets a test message in its group. Returns the channel's `:id` and `:name`."
  [{webhook-name :name :keys [url feishu-chat-id description]}]
  (perms/check-has-application-permission :setting)
  (when (channel.db/channel-name-exists? webhook-name)
    (throw (ex-info (tru "A channel named {0} already exists." webhook-name)
                    {:status-code 409})))
  (let [url     (or url (some-> feishu-chat-id feishu-chat-webhook-url))
        details {:url url :auth-method "none" :fe-form-type "none" :method "post"}]
    (channel/can-connect? :channel/http details)
    (let [created (channel.db/insert-channel! (cond-> {:name    webhook-name
                                                       :type    :channel/http
                                                       :details details
                                                       :active  true}
                                                description (assoc :description description)))]
      (events/publish-event! :event/channel-create {:object created :user-id api/*current-user-id*})
      (select-keys created [:id :name]))))

;; ------------------------------------------------------------------------------------------------;;
;;                                       :model/ChannelTemplate                                    ;;
;; ------------------------------------------------------------------------------------------------;;

(t2/deftransforms :model/ChannelTemplate
  {:channel_type  (mi/transform-validator mi/transform-keyword (partial mi/assert-namespaced "channel"))
   :details       mi/transform-json})

(def ^:private channel-template-entries
  "Entries every channel template has, whatever its `:channel_type`."
  [[:id           {:optional true} ms/PositiveInt]
   [:name         {:optional true} ms/NonBlankString]
   [:channel_type                  [:fn #(= "channel" (-> % keyword namespace))]]])

(def ^:private channel-template-row-entries
  "[[channel-template-entries]] plus the timestamps a stored template carries. Kept out of the user-provided schema
  below, which describes a request body rather than a row."
  (into channel-template-entries
        [[:created_at {:optional true} RowTimestamp]
         [:updated_at {:optional true} RowTimestamp]]))

(mr/def ::ChannelTemplate
  "Channel Template schema."
  [:merge
   (into [:map {:closed true}] channel-template-row-entries)
   [:multi {:decode/normalize lib.schema.common/normalize-map-no-kebab-case
            :dispatch         (comp keyword :channel_type)}
    [:channel/email [:map {:closed true} [:details ::channel.schema/channel-template.email-details]]]
    [::mc/default   [:map {:closed true} [:details {:optional true} :nil]]]]])

(mr/def ::ChannelTemplateUserProvided
  "Channel Template schema for API-provided templates. Does not allow handlebars-resource."
  [:merge
   (into [:map {:closed true}] channel-template-entries)
   [:multi {:decode/normalize lib.schema.common/normalize-map-no-kebab-case
            :dispatch         (comp keyword :channel_type)}
    [:channel/email [:map {:closed true} [:details ::channel.schema/channel-template.email-details.user-provided]]]
    [::mc/default   [:map {:closed true}]]]])

(defn- check-valid-channel-template
  [channel-template]
  (mu/validate-throw ::ChannelTemplate channel-template))

(defn- log-template-change!
  "Log template creation or update with relevant details for observability."
  [action {:keys [channel_type details] :as _instance}]
  (let [template-type (keyword (:type details))]
    (log/infof "ChannelTemplate %s: channel_type=%s template_type=%s user_id=%s"
               (name action) channel_type template-type api/*current-user-id*)
    (analytics/inc! (case action
                      :create :metabase-notification/template-create
                      :update :metabase-notification/template-update)
                    {:channel-type channel_type})))

(t2/define-before-insert :model/ChannelTemplate
  [instance]
  (check-valid-channel-template instance)
  instance)

(t2/define-before-update :model/ChannelTemplate
  [instance]
  (check-valid-channel-template instance)
  instance)

(t2/define-after-insert :model/ChannelTemplate
  [instance]
  (log-template-change! :create instance)
  instance)

(t2/define-after-update :model/ChannelTemplate
  [instance]
  (log-template-change! :update instance)
  instance)

;; Currently only email channel has templates, but this is extensible
(def ^:private template-channel-labels [{:channel-type :channel/email}])

(defmethod analytics.core/known-labels :metabase-notification/template-create [_] template-channel-labels)
(defmethod analytics.core/known-labels :metabase-notification/template-update [_] template-channel-labels)

(defmethod mi/can-write? :model/ChannelTemplate
  [& _]
  (or (mi/superuser?)
      (perms/current-user-has-application-permissions? :setting)))
