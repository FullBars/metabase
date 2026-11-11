import { t } from "ttag";

import { DelayedLoadingAndErrorWrapper } from "metabase/common/components/LoadingAndErrorWrapper/DelayedLoadingAndErrorWrapper";
import { SidesheetButtonWithChevron } from "metabase/common/components/Sidesheet";
import { useUniqueId } from "metabase/common/hooks/use-unique-id";
import type { SidebarCacheSectionProps } from "metabase/plugins";
import { Flex, Text } from "metabase/ui";

import { useSavedStrategy } from "../hooks/useSavedStrategy";
import { getShortStrategyLabel } from "../utils";

/** Shows the cache policy of a dashboard or question in its settings sidesheet */
export const SidebarCacheSection = ({
  item,
  model,
  setPage,
}: SidebarCacheSectionProps) => {
  const { savedStrategy, error, isLoading } = useSavedStrategy(item, model);
  const titleId = useUniqueId();
  const labelId = useUniqueId();

  if (error || isLoading) {
    return <DelayedLoadingAndErrorWrapper error={error} loading={isLoading} />;
  }

  return (
    <Flex align="center" justify="space-between" gap="md">
      <Text id={titleId}>{t`When to get new results`}</Text>
      <SidesheetButtonWithChevron
        aria-labelledby={`${titleId} ${labelId}`}
        onClick={() => setPage("caching")}
      >
        <span id={labelId}>
          {getShortStrategyLabel(savedStrategy ?? { type: "inherit" }, model)}
        </span>
      </SidesheetButtonWithChevron>
    </Flex>
  );
};
