(ns metabase.metabot.tools.subscriptions
  "Dashboard subscription tool wrapper."
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [metabase.api.common :as api]
   [metabase.channel.models.channel :as models.channel]
   [metabase.channel.settings :as channel.settings]
   [metabase.metabot.db :as metabot.db]
   [metabase.metabot.scope :as scope]
   [metabase.metabot.tools.create-alert :as tools.create-alert]
   [metabase.metabot.tools.util :as metabot.tools.u]
   [metabase.pulse.api :as pulse.api]
   [metabase.util.malli :as mu]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

(defn- make-slack-channel
  "Build a pulse channel for Slack delivery."
  [schedule slack-channel]
  (merge {:channel_type :slack
          :enabled      true
          :details      {:channel slack-channel}}
         (metabot.tools.u/schedule->schedule-map schedule)))

(defn- make-webhook-channel
  "Build a pulse channel for delivery to the webhook with id `channel-id`."
  [schedule channel-id]
  (merge {:channel_type :http
          :enabled      true
          :channel_id   channel-id}
         (metabot.tools.u/schedule->schedule-map schedule)))

(defn- dashboard-webhook
  "`{:id ...}` of the active webhook named `webhook` that can take a dashboard subscription, or `{:error ...}`."
  [webhook]
  (let [found (models.channel/find-active-webhook webhook)
        names (map :name (models.channel/active-dashboard-webhooks))]
    (cond
      (nil? found)
      {:error (str "no webhook named \"" webhook "\" found. "
                   (if (seq names)
                     (str "The webhooks that can take a dashboard subscription are: "
                          (str/join ", " (map #(str "\"" % "\"") names)) ".")
                     "None can take a dashboard subscription yet: add a Feishu group chat with create_webhook's feishu_chat_id."))}

      (not (models.channel/receives-dashboards? (:id found)))
      {:error (str "webhook \"" webhook "\" can't take a dashboard subscription: only a Feishu group chat webhook can, "
                   "as the dashboard goes there as a PDF and a group's custom bot can't send files. Add the group chat "
                   "with create_webhook's feishu_chat_id.")}

      :else
      {:id (:id found)})))

(defn- create-dashboard-subscription*
  "Private helper for create-dashboard-subscription (call that instead)."
  [{:keys [dashboard-id slack-channel webhook schedule]}]
  (let [dashboard (some-> (metabot.db/dashboard dashboard-id)
                          api/read-check
                          (t2/hydrate [:dashcards :card]))
        cards (for [{:keys [id card]} (:dashcards dashboard)
                    :when (-> card :id int?)]
                (-> card
                    api/read-check
                    (select-keys [:id :name :collection_id :description :display :parameter_mappings])
                    (assoc :dashboard_card_id id
                           :dashboard_id      dashboard-id
                           :include_csv       false
                           :include_xls       false)))
        webhook      (some-> webhook dashboard-webhook)
        channel-name (when-not webhook
                       (some->> slack-channel
                                channel.settings/find-cached-slack-channel-or-username
                                ;; match existing code which stores display names like "#some-channel"
                                :display-name))
        pulse-data (-> dashboard
                       (select-keys [:collection_id :collection_position :name :parameters])
                       (assoc :dashboard_id  dashboard-id
                              :creator_id    api/*current-user-id*
                              :skip_if_empty false))]
    (cond
      (nil? dashboard)
      {:error "no dashboard with this dashboard_id found"}

      (:error webhook)
      webhook

      (and (not webhook) (nil? channel-name))
      {:error "no slack channel found with this name"}

      :else
      (do (pulse.api/create-pulse-with-perm-checks!
           cards
           [(if webhook
              (make-webhook-channel schedule (:id webhook))
              (make-slack-channel schedule channel-name))]
           pulse-data)
          {:output "success"}))))

(defn create-dashboard-subscription
  "Create a dashboard subscription and send it to a slack channel, or as a PDF to a Feishu group chat webhook."
  [{:keys [dashboard-id slack-channel webhook] :as args}]
  (cond
    (not (int? dashboard-id))
    {:error "invalid dashboard_id"}

    (and (not webhook) (not (channel.settings/slack-configured?)))
    {:error "slack is not configured. Ask an admin to connect slack in Metabase settings."}

    (and (not webhook) (empty? slack-channel))
    {:error "slack_channel is required"}

    :else
    (try
      (create-dashboard-subscription* args)
      (catch Exception e
        (-> (metabot.tools.u/handle-agent-error e)
            (set/rename-keys {:output :error}))))))

(def ^:private subscription-schema
  [:map {:closed true}
   [:dashboard_id :int]
   [:email {:optional true} [:maybe :string]]
   [:slack_channel {:optional true} [:maybe :string]]
   [:webhook {:optional true} [:maybe :string]]
   [:schedule tools.create-alert/schedule-schema]])

(mu/defn ^{:tool-name "create_dashboard_subscription"
           :scope     scope/agent-dashboard-subscribe}
  create-dashboard-subscription-tool
  "Create a dashboard subscription that sends regular updates to a Slack channel, or the dashboard as a PDF to a
  Feishu (飞书) group chat.

  Use when a user wants to receive or send regular updates on a dashboard's contents.
  Requires a valid `dashboard_id`, a `schedule`, and either a `slack_channel` name or `webhook`: the name of a Feishu
  group chat webhook (create_webhook with feishu_chat_id adds one), which gets the dashboard as a PDF.

  The `email` argument is not implemented and is ignored, so if the user asks to be emailed a
  dashboard, tell them this tool can only deliver to Slack or a Feishu group chat rather than
  accepting an email address. Slack must be connected in Metabase settings."
  [{:keys [dashboard_id email slack_channel webhook schedule]} :- subscription-schema]
  (try
    (-> (create-dashboard-subscription
         {:dashboard-id  dashboard_id
          :email         email
          :slack-channel slack_channel
          :webhook       webhook
          :schedule      schedule})
        (set/rename-keys {:error :output}))
    (catch Exception e
      (metabot.tools.u/handle-agent-or-api-error e))))
