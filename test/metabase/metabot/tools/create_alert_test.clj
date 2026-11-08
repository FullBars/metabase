(ns metabase.metabot.tools.create-alert-test
  (:require
   [clojure.test :refer :all]
   [metabase.channel.settings :as channel.settings]
   [metabase.metabot.tools.create-alert
    :as metabot.tools.create-alert]
   [metabase.metabot.tools.shared :as shared]
   [metabase.test :as mt]
   [toucan2.core :as t2]))

(defn- alert-data [card-id]
  {:card-id        card-id
   :send-condition :has_result
   :slack-channel  "data-team"
   :schedule       {:frequency :daily
                    :hour      9}})

(def ^:private fake-channels
  {:channels [{:display-name "#data-team" :name "data-team" :id "C123"}]})

(deftest create-alert-test
  (mt/with-model-cleanup [:model/Notification]
    (mt/with-temp [:model/Card {card-id :id} {}]
      (let [invoke-tool #(mt/with-current-user (mt/user->id :rasta)
                           (metabot.tools.create-alert/create-alert %))
            base-data   (alert-data card-id)]
        (mt/with-dynamic-fn-redefs [channel.settings/slack-configured? (constantly true)
                                    channel.settings/slack-cached-channels-and-usernames (constantly fake-channels)]
          (testing "Slack alert can be created"
            (is (= {:output "success"}
                   (invoke-tool base-data))))
          (testing "Alert with goal_above send-condition can be created"
            (is (= {:output "success"}
                   (invoke-tool (assoc base-data :send-condition :goal_above)))))
          (testing "Alert with goal_below send-condition can be created"
            (is (= {:output "success"}
                   (invoke-tool (assoc base-data :send-condition :goal_below)))))
          (testing "Alert with send-once can be created"
            (is (= {:output "success"}
                   (invoke-tool (assoc base-data :send-once true))))))))))

(deftest create-alert-validation-test
  (mt/with-temp [:model/Card {card-id :id} {}]
    (let [invoke-tool #(mt/with-current-user (mt/user->id :rasta)
                         (metabot.tools.create-alert/create-alert %))
          base-data   (alert-data card-id)]
      (mt/with-dynamic-fn-redefs [channel.settings/slack-configured? (constantly true)
                                  channel.settings/slack-cached-channels-and-usernames (constantly fake-channels)]
        (testing "Return an error message if the card-id is invalid"
          (is (= {:error "invalid card_id"}
                 (invoke-tool (update base-data :card-id str)))))
        (testing "Return an error message if the card cannot be found"
          (is (= {:error "no saved question with this card_id found"}
                 (invoke-tool (update base-data :card-id + 100)))))
        (testing "Slack alert requires slack-channel"
          (is (= {:error "slack_channel is required"}
                 (invoke-tool (dissoc base-data :slack-channel)))))
        (testing "Invalid send-condition returns an error"
          (is (= {:error "unsupported send_condition: always"}
                 (invoke-tool (assoc base-data :send-condition :always)))))
        (testing "Invalid send-once returns an error"
          (is (= {:error "send_once must be a boolean"}
                 (invoke-tool (assoc base-data :send-once "true")))))))))

(deftest create-alert-channel-not-configured-test
  (mt/with-temp [:model/Card {card-id :id} {}]
    (let [invoke-tool #(mt/with-current-user (mt/user->id :rasta)
                         (metabot.tools.create-alert/create-alert %))
          base-data   (alert-data card-id)]
      (testing "Slack alert fails when Slack is not configured"
        (mt/with-dynamic-fn-redefs [channel.settings/slack-configured? (constantly false)]
          (is (= {:error "slack is not configured. Ask an admin to set up slack notifications in Metabase settings."}
                 (invoke-tool base-data)))))
      (testing "Slack alert fails when channel does not exist"
        (mt/with-dynamic-fn-redefs [channel.settings/slack-configured? (constantly true)
                                    channel.settings/slack-cached-channels-and-usernames (constantly {:channels []})]
          (is (= {:error "no slack channel found with this name"}
                 (invoke-tool (assoc base-data :slack-channel "no-such-channel")))))))))

(deftest create-alert-tool-errors-test
  (let [create-alert! #(binding [shared/*memory-atom* (atom {:context {:slack_channel_id "C123"}})]
                         (metabot.tools.create-alert/create-alert-tool
                          {:card_id        %
                           :send_condition "has_result"
                           :schedule       {:frequency "daily" :hour 9}}))]
    (testing "a call from outside Slack goes back to the agent as output"
      (is (= {:output "This tool can only be used from a Slack channel"}
             (metabot.tools.create-alert/create-alert-tool
              {:card_id 1 :send_condition "has_result" :schedule {:frequency "daily" :hour 9}}))))
    (testing "a card the user can't read goes back to the agent as output"
      (mt/with-dynamic-fn-redefs [channel.settings/slack-configured? (constantly true)]
        (mt/with-non-admin-groups-no-root-collection-perms
          (mt/with-temp [:model/Card {card-id :id} {}]
            (mt/with-current-user (mt/user->id :rasta)
              (is (= "You don't have permissions to do that."
                     (:output (create-alert! card-id)))))))))
    (testing "an unexpected error propagates to the agent loop"
      (mt/with-dynamic-fn-redefs [metabot.tools.create-alert/create-alert (fn [_] (throw (ex-info "boom" {})))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom" (create-alert! 1)))))))

(def ^:private feishu-bot-details
  {:url "https://open.feishu.cn/open-apis/bot/v2/hook/test-token" :auth-method "none"})

(defn- card-alert-handlers
  "The `[channel_type channel_id]` of every handler of the alerts on `card-id`."
  [card-id]
  (let [payload-ids      (t2/select-fn-vec :id :model/NotificationCard :card_id card-id)
        notification-ids (when (seq payload-ids)
                           (t2/select-fn-vec :id :model/Notification
                                             :payload_type :notification/card
                                             :payload_id [:in payload-ids]))]
    (when (seq notification-ids)
      (t2/select-fn-vec (juxt :channel_type :channel_id) :model/NotificationHandler
                        :notification_id [:in notification-ids]))))

(deftest create-webhook-alert-test
  (mt/with-model-cleanup [:model/Notification]
    (mt/with-temp [:model/Card    {card-id :id} {}
                   :model/Channel {chan-id :id} {:name "飞书 · 数据告警" :type :channel/http :active true
                                                 :details feishu-bot-details}]
      (let [invoke-tool #(mt/with-current-user (mt/user->id :rasta)
                           (metabot.tools.create-alert/create-alert %))
            base-data   (-> (alert-data card-id)
                            (dissoc :slack-channel)
                            (assoc :webhook "飞书 · 数据告警"))]
        (mt/with-dynamic-fn-redefs [channel.settings/slack-configured? (constantly false)]
          (testing "an alert to a webhook an admin set up, e.g. a Feishu group bot, needs no Slack"
            (is (= {:output "success"}
                   (invoke-tool base-data)))
            (is (= [[:channel/http chan-id]]
                   (card-alert-handlers card-id))))
          (testing "an unknown webhook is an error that lists the webhooks to pick from"
            (let [{:keys [error]} (invoke-tool (assoc base-data :webhook "no-such-hook"))]
              (is (re-find #"no-such-hook" error))
              (is (re-find #"飞书 · 数据告警" error)))))))))

(deftest create-alert-tool-webhook-outside-slack-test
  (testing "the tool creates a webhook alert outside Slack, e.g. from the in-app Metabot"
    (mt/with-model-cleanup [:model/Notification]
      (mt/with-temp [:model/Card    {card-id :id} {}
                     :model/Channel {chan-id :id} {:name "飞书 · 数据告警" :type :channel/http :active true
                                                   :details feishu-bot-details}]
        (mt/with-current-user (mt/user->id :rasta)
          (is (= {:output "success"}
                 (metabot.tools.create-alert/create-alert-tool
                  {:card_id        card-id
                   :send_condition "has_result"
                   :schedule       {:frequency "daily" :hour 9}
                   :webhook        "飞书 · 数据告警"}))))
        (is (= [[:channel/http chan-id]]
               (card-alert-handlers card-id)))))))
