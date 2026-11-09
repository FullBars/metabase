import type { ChannelApiResponse, ChannelSpec } from "metabase-types/api";
import {
  createMockChannel,
  createMockDashboardSubscription,
} from "metabase-types/api/mocks";

import {
  getEditableSubscriptionChannel,
  getSubscriptionEditUnavailableReason,
} from "./subscription-editing";

const createChannelSpec = (opts: Partial<ChannelSpec>): ChannelSpec => ({
  type: "email",
  name: "Email",
  schedules: ["hourly", "daily", "weekly", "monthly"],
  schedule_type: "hourly",
  allows_recipients: true,
  configured: true,
  ...opts,
});

const CONFIGURED_CHANNELS: ChannelApiResponse = {
  channels: {
    email: { ...createChannelSpec({ type: "email" }), recipients: ["user"] },
    slack: { ...createChannelSpec({ type: "slack" }), fields: [] },
    http: createChannelSpec({ type: "http" }),
  },
};

/** What `form_input` returns to someone who manages webhooks. */
const WEBHOOK_MANAGER_CHANNELS: ChannelApiResponse = {
  channels: {
    ...CONFIGURED_CHANNELS.channels,
    http: {
      ...createChannelSpec({ type: "http" }),
      dashboard_webhooks: [{ id: 7, name: "飞书群 · 运营" }],
    },
  },
};

const UNCONFIGURED_CHANNELS: ChannelApiResponse = {
  channels: {
    email: {
      ...createChannelSpec({ type: "email", configured: false }),
      recipients: ["user"],
    },
    slack: {
      ...createChannelSpec({ type: "slack", configured: false }),
      fields: [],
    },
  },
};

describe("getSubscriptionEditUnavailableReason", () => {
  it.each([CONFIGURED_CHANNELS, UNCONFIGURED_CHANNELS])(
    "allows editing an email or Slack channel the form input lists, set up or not",
    (channelInfo) => {
      expect(
        getSubscriptionEditUnavailableReason(
          { channel_type: "email" },
          channelInfo,
        ),
      ).toBeNull();
      expect(
        getSubscriptionEditUnavailableReason(
          { channel_type: "slack" },
          channelInfo,
        ),
      ).toBeNull();
    },
  );

  it("explains that a channel the form input leaves out can't be edited", () => {
    expect(
      getSubscriptionEditUnavailableReason(
        { channel_type: "email" },
        { channels: {} },
      ),
    ).toBe("You can't edit email subscriptions");
    expect(
      getSubscriptionEditUnavailableReason(
        { channel_type: "slack" },
        { channels: {} },
      ),
    ).toBe("You can't edit Slack subscriptions");
  });

  it("allows editing a webhook when the form input lists the webhooks a dashboard can go to", () => {
    expect(
      getSubscriptionEditUnavailableReason(
        { channel_type: "http" },
        WEBHOOK_MANAGER_CHANNELS,
      ),
    ).toBeNull();
  });

  it("explains that a webhook can't be edited when the form input doesn't list the dashboard's webhooks", () => {
    expect(
      getSubscriptionEditUnavailableReason(
        { channel_type: "http" },
        CONFIGURED_CHANNELS,
      ),
    ).toBe("Webhook subscriptions can't be edited on the dashboard");
  });
});

describe("getEditableSubscriptionChannel", () => {
  it("returns the first channel when the dashboard can edit it", () => {
    const channel = createMockChannel({ channel_type: "slack" });
    expect(
      getEditableSubscriptionChannel(
        createMockDashboardSubscription({ channels: [channel] }),
        CONFIGURED_CHANNELS,
      ),
    ).toBe(channel);
  });

  it("returns null when the sidebar can't edit the first channel", () => {
    expect(
      getEditableSubscriptionChannel(
        createMockDashboardSubscription({
          channels: [createMockChannel({ channel_type: "http" })],
        }),
        CONFIGURED_CHANNELS,
      ),
    ).toBeNull();
  });

  it("returns null for a subscription without a channel", () => {
    expect(
      getEditableSubscriptionChannel(
        createMockDashboardSubscription({ channels: [] }),
        CONFIGURED_CHANNELS,
      ),
    ).toBeNull();
  });
});
