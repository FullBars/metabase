import { useFormikContext } from "formik";
import { t } from "ttag";

import type { PreemptiveCachingSwitchProps } from "metabase/plugins";
import { Switch } from "metabase/ui";
import type {
  CacheStrategy,
  DurationStrategy,
  ScheduleStrategy,
} from "metabase-types/api";

const canRefreshAutomatically = (
  strategy: CacheStrategy,
): strategy is DurationStrategy | ScheduleStrategy =>
  strategy.type === "duration" || strategy.type === "schedule";

/**
 * The "Refresh cache automatically" option of a question's or dashboard's
 * Duration or Schedule policy: the server reruns their queries in the
 * background when the cached results expire.
 */
export function PreemptiveCachingSwitch({
  handleSwitchToggle,
}: PreemptiveCachingSwitchProps) {
  const { values: strategy } = useFormikContext<CacheStrategy>();
  const isOn =
    canRefreshAutomatically(strategy) && strategy.refresh_automatically;

  return (
    <Switch
      label={t`Refresh cache automatically`}
      description={t`Rerun the query in the background when its cached results expire, so people get fresh results without waiting.`}
      checked={isOn}
      onChange={handleSwitchToggle}
    />
  );
}
