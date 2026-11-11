import Question from "metabase-lib/v1/Question";
import type { CacheableDashboard, CacheableModel } from "metabase-types/api";

import { useCacheConfigs } from "./useCacheConfigs";

export const getCacheableItemId = (item: CacheableDashboard | Question) =>
  item instanceof Question ? item.id() : item.id;

export const getCacheableItemName = (item: CacheableDashboard | Question) =>
  (item instanceof Question ? item.displayName() : item.name) ?? "";

/** Loads the cache strategy saved for a dashboard or question, if it has its own */
export const useSavedStrategy = (
  item: CacheableDashboard | Question,
  model: CacheableModel,
) => {
  const id = getCacheableItemId(item);
  const apiModel = model === "metric" ? "question" : model;
  const { configs, error, isLoading } = useCacheConfigs({
    model: [apiModel],
    id,
  });
  const savedStrategy = configs?.find(
    (config) => config.model === apiModel && config.model_id === id,
  )?.strategy;
  return { id, savedStrategy, error, isLoading };
};
