import { t } from "ttag";
import * as Yup from "yup";

import { defaultCron } from "metabase/common/components/Schedule/cron";
import {
  PLUGIN_CACHING,
  type StrategyData,
  strategies as defaultStrategies,
  getPositiveIntegerSchema,
} from "metabase/plugins";
import type Question from "metabase-lib/v1/Question";
import { CacheDurationUnit } from "metabase-types/api";

import { DatabaseCachingEditor } from "../components/DatabaseCachingEditor";
import { InvalidateNowButton } from "../components/InvalidateNowButton";
import { PreemptiveCachingSwitch } from "../components/PreemptiveCachingSwitch";
import { SidebarCacheForm } from "../components/SidebarCacheForm";
import { SidebarCacheSection } from "../components/SidebarCacheSection";

/** Rather than a constant, this is a function, so that ttag.t runs after the locale is set */
const getDurationStrategyValidationSchema = () =>
  Yup.object({
    type: Yup.string().equals(["duration"]),
    duration: getPositiveIntegerSchema().required(t`Enter a positive number.`),
    unit: Yup.string()
      .oneOf(Object.values(CacheDurationUnit))
      .default(CacheDurationUnit.Hours),
    refresh_automatically: Yup.boolean().default(false),
  });

/** Rather than a constant, this is a function, so that ttag.t runs after the locale is set */
const getScheduleStrategyValidationSchema = () =>
  Yup.object({
    type: Yup.string().equals(["schedule"]),
    schedule: Yup.string()
      .required(t`A cron expression is required`)
      .default(defaultCron),
    refresh_automatically: Yup.boolean().default(false),
  });

const durationStrategy: StrategyData = {
  label: () => t`Duration`,
  shortLabel: () => t`Duration`,
  description: () => t`Keep the cache for a set duration`,
  validationSchema: getDurationStrategyValidationSchema,
};

const scheduleStrategy: StrategyData = {
  label: () => t`Schedule`,
  shortLabel: () => t`Scheduled`,
  description: () => t`Pick when to regularly invalidate the cache`,
  validationSchema: getScheduleStrategyValidationSchema,
};

/** Fills PLUGIN_CACHING with the OSS granular caching controls */
export function initializeCachingPlugin() {
  PLUGIN_CACHING.isGranularCachingEnabled = () => true;
  PLUGIN_CACHING.canOverrideRootStrategy = true;
  // The order of the keys is the order of the options in the strategy picker
  PLUGIN_CACHING.strategies = {
    inherit: defaultStrategies.inherit,
    duration: durationStrategy,
    schedule: scheduleStrategy,
    ttl: defaultStrategies.ttl,
    nocache: defaultStrategies.nocache,
  };
  PLUGIN_CACHING.DatabaseCachingEditor = DatabaseCachingEditor;
  PLUGIN_CACHING.SidebarCacheSection = SidebarCacheSection;
  PLUGIN_CACHING.SidebarCacheForm = SidebarCacheForm;
  PLUGIN_CACHING.InvalidateNowButton = InvalidateNowButton;
  PLUGIN_CACHING.PreemptiveCachingSwitch = PreemptiveCachingSwitch;
  // Cards don't say whether the user may set their cache policy, so this
  // follows write access. Models and metrics have no cache policy section.
  PLUGIN_CACHING.hasQuestionCacheSection = (question: Question) =>
    question.type() === "question" && question.canWrite();
}
