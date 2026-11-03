import { createMockSeriesModel } from "__support__/echarts";
import type { SeriesSettings } from "metabase-types/api";

import type { ComputedVisualizationSettings } from "../../../types";

import { getStackModels } from "./stack";
import type { LegacySeriesSettingsObjectKey } from "./types";

describe("getStackModels", () => {
  it("should exclude hidden series from stack models (#66149)", () => {
    const seriesModels = [
      createMockSeriesModel({ dataKey: "series1", visible: true }),
      createMockSeriesModel({ dataKey: "series2", visible: false }),
      createMockSeriesModel({ dataKey: "series3", visible: true }),
    ];
    const mockSettings: ComputedVisualizationSettings = {
      "stackable.stack_type": "stacked",
      series: () => ({ display: "bar" }),
    };

    const result = getStackModels(seriesModels, mockSettings);

    expect(result).toHaveLength(1);
    expect(result[0].seriesKeys).toEqual(["series1", "series3"]);
  });

  it("should stack series by display and stack_group, ordered by where each group first appears", () => {
    const seriesSettings: Record<string, SeriesSettings> = {
      a: { display: "bar", stack_group: "B" },
      b: { display: "bar" },
      c: { display: "bar", stack_group: "A" },
      d: { display: "bar", stack_group: "B" },
      e: { display: "line", stack_group: "A" },
      f: { display: "area", stack_group: "A" },
    };
    const seriesModels = Object.keys(seriesSettings).map((dataKey) =>
      createMockSeriesModel({ dataKey }),
    );
    const settings: ComputedVisualizationSettings = {
      "stackable.stack_type": "stacked",
      series: (key: LegacySeriesSettingsObjectKey) =>
        seriesSettings[key.card._seriesKey ?? ""],
    };

    const result = getStackModels(seriesModels, settings);

    expect(
      result.map(({ id, display, seriesKeys }) => [id, display, seriesKeys]),
    ).toEqual([
      ["bar_B", "bar", ["a", "d"]],
      ["bar", "bar", ["b"]],
      ["bar_A", "bar", ["c"]],
      ["area_A", "area", ["f"]],
    ]);
  });
});
