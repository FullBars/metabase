import { t } from "ttag";

import { Link } from "metabase/common/components/Link";
import { Sidebar } from "metabase/common/components/Sidebar";
import { ChannelCard } from "metabase/notifications/pulse/components/ChannelCard";
import { useSelector } from "metabase/redux";
import { getApplicationName } from "metabase/selectors/whitelabel";
import { Box, Stack, Text, Title } from "metabase/ui";
import type { DashboardWebhook } from "metabase-types/api";

interface NewPulseSidebarProps {
  emailConfigured: boolean;
  slackConfigured: boolean;
  /** The Feishu groups a dashboard can go to; undefined for people who don't manage webhooks. */
  dashboardWebhooks?: DashboardWebhook[];
  onNewEmailPulse: () => void;
  onNewSlackPulse: () => void;
  onNewWebhookPulse: () => void;
  onCancel: () => void;
}

export function NewPulseSidebar({
  onCancel,
  emailConfigured,
  slackConfigured,
  dashboardWebhooks,
  onNewEmailPulse,
  onNewSlackPulse,
  onNewWebhookPulse,
}: NewPulseSidebarProps) {
  const applicationName = useSelector(getApplicationName);

  return (
    <Sidebar onCancel={onCancel}>
      <Box mx="xxl" my="xl">
        <Title order={4} mb="sm">{t`Set up a dashboard subscription`}</Title>
        <Text lh="md">{t`Schedule dashboard results to be sent to you and your team. People don't need a ${applicationName} account to subscribe.`}</Text>
      </Box>
      <Stack mx="xxl" gap="sm">
        {emailConfigured ? (
          <ChannelCard
            title={t`Email it`}
            channel="email"
            onClick={onNewEmailPulse}
          />
        ) : (
          <Link to="/admin/settings/email">
            <ChannelCard title={t`Set up email`} channel="email" />
          </Link>
        )}

        {slackConfigured ? (
          <ChannelCard
            title={t`Send it to Slack`}
            channel="slack"
            onClick={onNewSlackPulse}
          />
        ) : (
          <Link to="/admin/settings/slack">
            <ChannelCard title={t`Configure Slack`} channel="slack" />
          </Link>
        )}

        {dashboardWebhooks != null &&
          (dashboardWebhooks.length > 0 ? (
            <ChannelCard
              title={t`Send it to a Feishu group`}
              channel="http"
              onClick={onNewWebhookPulse}
            />
          ) : (
            <Link to="/admin/settings/webhooks">
              <ChannelCard title={t`Set up a Feishu group`} channel="http" />
            </Link>
          ))}
      </Stack>
    </Sidebar>
  );
}
