import type { ComputedVisualizationSettings } from "../../../types";

import type { SeriesModel, StackDisplay, StackModel } from "./types";

const isStackDisplay = (display: string | undefined): display is StackDisplay =>
  display === "bar" || display === "area";

/**
 * Series stack together when they have the same display and `stack_group`.
 * Series without a `stack_group` share one stack per display, whose id is the
 * display itself, as before groups existed. Stacks are ordered by where their
 * first series appears.
 */
export const getStackModels = (
  seriesModels: SeriesModel[],
  settings: ComputedVisualizationSettings,
): StackModel[] => {
  if (!settings["stackable.stack_type"]) {
    return [];
  }

  const stackSeriesModelsById = new Map<
    string,
    { display: StackDisplay; seriesModels: SeriesModel[] }
  >();

  for (const seriesModel of seriesModels) {
    if (!seriesModel.visible) {
      continue;
    }

    const seriesSettings = settings.series?.(
      seriesModel.legacySeriesSettingsObjectKey,
    );
    const display = seriesSettings?.display;
    if (!isStackDisplay(display)) {
      continue;
    }

    const stackGroup = seriesSettings?.stack_group;
    const id = stackGroup ? `${display}_${stackGroup}` : display;
    const stack = stackSeriesModelsById.get(id) ?? {
      display,
      seriesModels: [],
    };
    stack.seriesModels.push(seriesModel);
    stackSeriesModelsById.set(id, stack);
  }

  return Array.from(
    stackSeriesModelsById,
    ([id, { display, seriesModels: stackSeriesModels }]) => {
      let axis: "left" | "right";
      if (settings["stackable.stack_type"] === "normalized") {
        axis = "left";
      } else {
        axis = stackSeriesModels.every(
          (seriesModel) =>
            settings.series?.(seriesModel.legacySeriesSettingsObjectKey)
              ?.axis === "right",
        )
          ? "right"
          : "left";
      }

      return {
        id,
        axis,
        display,
        seriesKeys: stackSeriesModels.map((seriesModel) => seriesModel.dataKey),
      };
    },
  );
};
