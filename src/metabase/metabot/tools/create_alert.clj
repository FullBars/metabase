(ns metabase.metabot.tools.create-alert
  "Agent tool wrapper for creating alerts on saved questions.
  Delivers to a webhook an admin set up in Metabase when the agent names one, such as a Feishu group bot, and
  otherwise to the Slack channel in the agent context."
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [metabase.api.common :as api]
   [metabase.channel.models.channel :as models.channel]
   [metabase.channel.settings :as channel.settings]
   [metabase.metabot.db :as metabot.db]
   [metabase.metabot.scope :as scope]
   [metabase.metabot.tools.shared :as shared]
   [metabase.metabot.tools.util :as metabot.tools.u]
   [metabase.notification.api :as notification.api]
   [metabase.util.cron :as u.cron]
   [metabase.util.malli :as mu]))

(set! *warn-on-reflection* true)

(defn- schedule->cron
  "Convert the tool schedule format to a Quartz cron string.
  The schedule comes in as e.g. {:frequency :daily :hour 9} and needs to be
  converted to a Quartz cron string like \"0 0 9 * * ? *\"."
  [schedule]
  (u.cron/schedule-map->cron-string (metabot.tools.u/schedule->schedule-map schedule)))

(defn- unknown-webhook-error
  [webhook]
  (let [names (map :name (models.channel/active-webhooks))]
    (str "no webhook named \"" webhook "\" found. "
         (if (seq names)
           (str "The webhooks set up in Metabase are: " (str/join ", " (map #(str "\"" % "\"") names)) ".")
           (str "No webhooks are set up. Ask an admin to add one in Admin settings > Webhooks "
                "(a Feishu or Lark group bot's webhook URL works).")))))

(defn- create-alert*
  "Private helper for create-alert (call that instead)."
  [{:keys [card-id slack-channel webhook schedule send-condition send-once]
    :or   {send-once false}}]
  (let [card         (some-> (metabot.db/card card-id) api/read-check)
        webhook-id   (some-> webhook models.channel/find-active-webhook :id)
        channel-name (when-not webhook
                       (some->> slack-channel
                                channel.settings/find-cached-slack-channel-or-username
                                :display-name))]
    (cond
      (nil? card)
      {:error "no saved question with this card_id found"}

      (and webhook (nil? webhook-id))
      {:error (unknown-webhook-error webhook)}

      (and (not webhook) (nil? channel-name))
      {:error "no slack channel found with this name"}

      :else
      (do
        (notification.api/create-notification!
         {:payload_type  :notification/card
          :active        true
          :creator_id    api/*current-user-id*
          :payload       {:card_id        card-id
                          :send_condition send-condition
                          :send_once      send-once}
          :subscriptions [{:type          :notification-subscription/cron
                           :cron_schedule (schedule->cron schedule)}]
          :handlers      [(if webhook-id
                            {:channel_type :channel/http
                             :channel_id   webhook-id
                             :recipients   []}
                            {:channel_type :channel/slack
                             :recipients   [{:type    :notification-recipient/raw-value
                                             :details {:value channel-name}}]})]})
        {:output "success"}))))

(defn create-alert
  "Create an alert (notification when a saved question returns results)."
  [{:keys [card-id send-condition send-once slack-channel webhook]
    :as   args}]
  (cond
    (not (int? card-id))
    {:error "invalid card_id"}

    (and (some? send-once) (not (boolean? send-once)))
    {:error "send_once must be a boolean"}

    (not (#{:has_result :goal_above :goal_below} send-condition))
    {:error (str "unsupported send_condition: " (some-> send-condition name))}

    (and (not webhook) (not (channel.settings/slack-configured?)))
    {:error "slack is not configured. Ask an admin to set up slack notifications in Metabase settings."}

    (and (not webhook) (empty? slack-channel))
    {:error "slack_channel is required"}

    :else
    (try
      (create-alert* args)
      (catch Exception e
        (-> (metabot.tools.u/handle-agent-error e)
            (set/rename-keys {:output :error}))))))

(def ^:private webhook-schema
  [:map {:closed true}
   [:name [:string {:min 1}]]
   [:url {:optional true} [:maybe [:string {:min 1}]]]
   [:feishu_chat_id {:optional true} [:maybe [:string {:min 1}]]]
   [:description {:optional true} [:maybe :string]]])

(mu/defn ^{:tool-name "create_webhook"
           :scope     scope/agent-alert-create}
  create-webhook-tool
  "Add a webhook that alerts can deliver to, such as a Feishu (飞书) or Lark group bot. Use it when the user wants
  alerts in a group whose webhook isn't set up in Metabase yet and gives you the bot's webhook URL
  (https://open.feishu.cn/open-apis/bot/v2/hook/...).

  `name` is how the webhook shows in Metabase and what you then pass as create_alert's `webhook`; it must not be
  taken. `url` is the webhook URL. Instead of `url`, `feishu_chat_id` (oc_...) adds a Feishu group chat the Feishu
  app is in: Metabase sends there as the app, which can also send files, so the chat can receive dashboard
  subscriptions as PDFs (create_dashboard_subscription with `webhook`). `description` is an optional note.

  Metabase first sends the webhook a test message (a Feishu bot posts it in its group) and saves the webhook only
  when that works. Adding a webhook needs the user to be allowed to manage Metabase settings."
  [{webhook-name :name :keys [url feishu_chat_id description]} :- webhook-schema]
  (try
    (when-not (or url feishu_chat_id)
      (throw (ex-info "Pass the webhook's url, or feishu_chat_id for a Feishu group chat the Feishu app is in."
                      {:status-code 400})))
    (let [{:keys [id]} (models.channel/create-webhook! {:name           webhook-name
                                                        :url            url
                                                        :feishu-chat-id feishu_chat_id
                                                        :description    description})]
      {:output (str "Webhook \"" webhook-name "\" (id " id ") created, and Metabase's test message reached it. "
                    "Pass webhook \"" webhook-name "\" to create_alert to deliver alerts there.")})
    (catch clojure.lang.ExceptionInfo e
      (let [{:keys [status-code request-body]} (ex-data e)]
        (if (= 403 status-code)
          (metabot.tools.u/handle-agent-or-api-error e)
          {:output (str "Couldn't add the webhook: " (ex-message e)
                        (when request-body (str " The webhook answered " (pr-str request-body) ".")))})))))

(def schedule-schema
  "Schedule schema shared with create-dashboard-subscription tool."
  [:or
   [:map {:closed true}
    [:frequency [:= "hourly"]]]
   [:map {:closed true}
    [:frequency [:= "daily"]]
    [:hour [:int {:min 0 :max 23}]]]
   [:map {:closed true}
    [:frequency [:= "weekly"]]
    [:day_of_week [:enum "sunday" "monday" "tuesday" "wednesday" "thursday" "friday" "saturday"]]
    [:hour [:int {:min 0 :max 23}]]]
   [:map {:closed true}
    [:frequency [:= "monthly"]]
    [:day_of_month [:enum "first-calendar-day"
                    "first-sunday" "first-monday" "first-tuesday" "first-wednesday"
                    "first-thursday" "first-friday" "first-saturday"
                    "middle-of-month"
                    "last-calendar-day"
                    "last-sunday" "last-monday" "last-tuesday" "last-wednesday"
                    "last-thursday" "last-friday" "last-saturday"]]
    [:hour [:int {:min 0 :max 23}]]]])

(def ^:private alert-schema
  [:map {:closed true}
   [:card_id :int]
   [:send_condition [:enum "has_result" "goal_above" "goal_below"]]
   [:schedule schedule-schema]
   [:send_once {:optional true :default false} :boolean]
   [:webhook {:optional true} [:string {:min 1}]]])

(mu/defn ^{:tool-name "create_alert"
           :scope     scope/agent-alert-create}
  create-alert-tool
  "Create an alert that sends a saved question's results on a recurring schedule, either to a
  webhook an admin set up in Metabase (`webhook`) or to the user's current Slack channel. Alerts
  are for saved questions (cards), not dashboards — use the dashboard subscription tool for
  dashboard delivery.

  When the user asks to be alerted or notified about a saved question's results, you MUST call
  this tool; never claim an alert was created without calling it. If you are missing required
  information, ask the user for it rather than guessing.

  `card_id` is the id of a saved question, from a prior search result or the conversation context.

  `send_condition` decides when the alert fires:
  - `has_result` — the question returns any rows. Default this when the user doesn't say.
  - `goal_above` — the result exceeds the goal line set on the question.
  - `goal_below` — the result drops below the goal line.
  `goal_above` and `goal_below` only work on questions that have a goal line configured.

  `schedule` is a frequency (hourly, daily, weekly, monthly) plus that frequency's time fields.
  `send_once` is optional (default false); when true the alert is deleted after it fires once.

  `webhook` is optional: the name of a webhook an admin set up in Metabase (Admin settings >
  Webhooks), such as a Feishu (飞书) or Lark group bot. Pass it when the user asks to
  send the alert there; the webhook then gets a card with the question's results. Without it the
  alert posts to the current Slack channel, which only works when the user is talking to you in
  Slack. If the name is wrong, the error lists the webhooks there are."
  [{:keys [card_id send_condition schedule send_once webhook]} :- alert-schema]
  (let [slack-channel-id (:slack_channel_id (shared/current-context))]
    (try
      (when-not (or webhook slack-channel-id)
        (throw (ex-info "This tool can only be used from a Slack channel"
                        {:agent-error? true})))
      (let [result (create-alert {:card-id        card_id
                                  :send-condition (keyword send_condition)
                                  :schedule       schedule
                                  :send-once      (boolean send_once)
                                  :slack-channel  slack-channel-id
                                  :webhook        webhook})]
        (if (:error result)
          {:output (:error result)}
          {:output (or (:output result) "Alert created successfully.")}))
      (catch Exception e
        (metabot.tools.u/handle-agent-or-api-error e)))))
