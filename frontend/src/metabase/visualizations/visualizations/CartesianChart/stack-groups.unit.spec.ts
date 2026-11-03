import type { BarSeriesOption, LineSeriesOption } from "echarts/charts";
import type { CallbackDataParams } from "echarts/types/dist/shared";

import { registerVisualizations } from "metabase/visualizations/register";
import {
  DEFAULT_VISUALIZATION_THEME,
  type RenderingContext,
  getCartesianChartModel,
  getCartesianChartOption,
  getChartLayout,
  getComputedSettingsForSeries,
  getVisualizationTransformed,
} from "metabase/viz-core";
import type { SeriesSettings, VisualizationSettings } from "metabase-types/api";
import {
  createMockCard,
  createMockColumn,
  createMockDatasetData,
  createMockSingleSeries,
} from "metabase-types/api/mocks";

import { getTooltipModel } from "./events";

registerVisualizations();

const DATE = createMockColumn({
  name: "日期",
  display_name: "日期",
  base_type: "type/Date",
  effective_type: "type/Date",
  semantic_type: null,
});
const SOURCE = createMockColumn({
  name: "制作人 · 来源",
  display_name: "制作人 · 来源",
  base_type: "type/Text",
  effective_type: "type/Text",
  semantic_type: "type/Category",
});
const ORDERS = createMockColumn({
  name: "有效订单",
  display_name: "有效订单",
  base_type: "type/Integer",
  effective_type: "type/Integer",
  semantic_type: null,
});

const A_PAID = "陈敏菱·投流";
const A_ORGANIC = "陈敏菱·自然流";
const B_PAID = "苍天·投流";
const B_ORGANIC = "苍天·自然流";
const TOTAL = "每日订单合计";

// Day 1: 陈敏菱 8, 苍天 3. Day 2: 陈敏菱 2, 苍天 10. All bars together: 11 and 12.
const BAR_ROWS = [
  ["2026-11-01", A_PAID, 5],
  ["2026-11-01", A_ORGANIC, 3],
  ["2026-11-01", B_PAID, 2],
  ["2026-11-01", B_ORGANIC, 1],
  ["2026-11-02", A_PAID, 1],
  ["2026-11-02", A_ORGANIC, 1],
  ["2026-11-02", B_PAID, 6],
  ["2026-11-02", B_ORGANIC, 4],
];
const TOTAL_ROWS = [
  ["2026-11-01", TOTAL, 11],
  ["2026-11-02", TOTAL, 12],
];

const GROUPED_SERIES_SETTINGS: Record<string, SeriesSettings> = {
  [A_PAID]: { stack_group: "陈敏菱", show_series_values: false },
  [A_ORGANIC]: { stack_group: "陈敏菱", show_series_values: false },
  [B_PAID]: { stack_group: "苍天", show_series_values: false },
  [B_ORGANIC]: { stack_group: "苍天", show_series_values: false },
  [TOTAL]: { display: "line", show_series_values: true },
};

const UNGROUPED_SERIES_SETTINGS: Record<string, SeriesSettings> = {
  [TOTAL]: { display: "line", show_series_values: true },
};

const renderingContext: RenderingContext = {
  getColor: () => "#509EE3",
  measureText: () => 10,
  measureTextHeight: () => 10,
  fontFamily: "Lato",
  theme: DEFAULT_VISUALIZATION_THEME,
};

const CHART_WIDTH = 800;
const CHART_HEIGHT = 400;

function setup({
  rows = [...BAR_ROWS, ...TOTAL_ROWS],
  settings: settingsOverrides = {},
}: {
  rows?: (string | number)[][];
  settings?: VisualizationSettings;
} = {}) {
  const rawSeries = [
    createMockSingleSeries(
      createMockCard({
        display: "bar",
        visualization_settings: {
          "graph.dimensions": ["日期", "制作人 · 来源"],
          "graph.metrics": ["有效订单"],
          "stackable.stack_type": "stacked",
          "graph.show_values": true,
          "graph.show_stack_values": "series",
          "graph.label_value_frequency": "all",
          series_settings: GROUPED_SERIES_SETTINGS,
          ...settingsOverrides,
        },
      }),
      { data: createMockDatasetData({ cols: [DATE, SOURCE, ORDERS], rows }) },
    ),
  ];
  // As in Visualization: settings come from the transformed series.
  const { series: transformedSeries } = getVisualizationTransformed(rawSeries);
  const settings = getComputedSettingsForSeries(transformedSeries);
  const chartModel = getCartesianChartModel(
    rawSeries,
    settings,
    [],
    renderingContext,
  );
  const chartLayout = getChartLayout(
    chartModel,
    settings,
    false,
    CHART_WIDTH,
    CHART_HEIGHT,
    renderingContext,
  );
  const option = getCartesianChartOption(
    chartModel,
    chartLayout,
    false,
    null,
    [],
    settings,
    CHART_WIDTH,
    false,
    renderingContext,
  );
  // Cartesian charts only build bar and line series
  const series = option.series as (BarSeriesOption | LineSeriesOption)[];

  const getDataKey = (name: string) => {
    const seriesModel = chartModel.seriesModels.find((s) => s.name === name);
    if (!seriesModel) {
      throw new Error(`No series named ${name}`);
    }
    return seriesModel.dataKey;
  };
  const getSeries = (name: string) => {
    const dataKey = getDataKey(name);
    const seriesOption = series.find((s) => s.id === dataKey);
    if (!seriesOption) {
      throw new Error(`No ECharts series for ${name}`);
    }
    return seriesOption;
  };

  return { settings, chartModel, series, getDataKey, getSeries };
}

describe("series_settings stack_group", () => {
  it("stacks bars of the same group and puts each group side by side", () => {
    const { getSeries } = setup();

    const stackOf = (name: string) => getSeries(name).stack;
    expect(stackOf(A_PAID)).toBe(stackOf(A_ORGANIC));
    expect(stackOf(B_PAID)).toBe(stackOf(B_ORGANIC));
    expect(stackOf(A_PAID)).not.toBe(stackOf(B_PAID));
  });

  it("lays the groups out left to right in legend order, each stacked top-down in legend order", () => {
    // ECharts places stacks side by side in the order it first meets them in
    // `series`, and stacks a stack's series bottom-up in `series` order.
    const { series, getDataKey } = setup({
      settings: {
        "graph.series_order_dimension": "制作人 · 来源",
        "graph.series_order": [B_PAID, A_PAID, B_ORGANIC, A_ORGANIC, TOTAL].map(
          (key) => ({ key, name: key, color: "#509EE3", enabled: true }),
        ),
      },
    });
    const barSeries = series.filter((s) => s.type === "bar");
    const stackIds = [...new Set(barSeries.map((s) => s.stack))];
    const stackOf = (name: string) =>
      barSeries.find((s) => s.id === getDataKey(name))?.stack;
    const bottomUp = (name: string) =>
      barSeries.filter((s) => s.stack === stackOf(name)).map((s) => s.id);

    expect(stackIds).toEqual([stackOf(B_PAID), stackOf(A_PAID)]);
    expect(bottomUp(B_PAID)).toEqual([B_ORGANIC, B_PAID].map(getDataKey));
    expect(bottomUp(A_PAID)).toEqual([A_ORGANIC, A_PAID].map(getDataKey));
  });

  it("splits the bar width between the groups so they don't overlap", () => {
    const getBarWidth = (s: BarSeriesOption | LineSeriesOption) =>
      "barWidth" in s ? s.barWidth : undefined;
    const grouped = getBarWidth(setup().getSeries(A_PAID));
    const ungrouped = getBarWidth(
      setup({
        settings: { series_settings: UNGROUPED_SERIES_SETTINGS },
      }).getSeries(A_PAID),
    );

    expect(typeof ungrouped).toBe("number");
    expect(grouped).toBeCloseTo(Number(ungrouped) / 2);
  });

  it("keeps the line series out of the stacks with its values shown", () => {
    const { getSeries } = setup();
    const line = getSeries(TOTAL);

    expect(line.type).toBe("line");
    expect(line.stack).toBeUndefined();
    expect(line.label?.show).toBe(true);
  });

  it("sizes the y-axis to the tallest group, not the sum of all bars", () => {
    const { chartModel } = setup({ rows: BAR_ROWS });

    expect(chartModel.leftAxisModel?.extent[1]).toBe(10);
  });

  it("labels each group's bar with the group total, and no label where a group has no data", () => {
    const { chartModel, series, getSeries } = setup({
      // Day 3 has only 陈敏菱
      rows: [...BAR_ROWS, ["2026-11-03", A_PAID, 4], ...TOTAL_ROWS],
      settings: { "graph.show_stack_values": "total" },
    });
    const totalLabels = series.filter((s) =>
      String(s.id).includes("positiveStackTotal"),
    );
    const formatTotal = (
      s: BarSeriesOption | LineSeriesOption,
      dataIndex: number,
    ) => {
      // Total labels always get a formatter function
      const formatter = s.label?.formatter as (
        params: CallbackDataParams,
      ) => string;
      // The formatter reads only `data` and `dataIndex`
      const params = {
        data: chartModel.transformedDataset[dataIndex],
        dataIndex,
      } as unknown as CallbackDataParams;
      return formatter(params);
    };

    expect(
      Object.fromEntries(
        totalLabels.map((s) => [
          s.stack,
          [formatTotal(s, 0), formatTotal(s, 1), formatTotal(s, 2)],
        ]),
      ),
    ).toEqual({
      [String(getSeries(A_PAID).stack)]: ["8", "2", "4"],
      [String(getSeries(B_PAID).stack)]: ["3", "10", ""],
    });
  });

  it("normalizes each group to 100% on its own", () => {
    const { chartModel, getDataKey } = setup({
      rows: BAR_ROWS,
      settings: { "stackable.stack_type": "normalized" },
    });
    const [day1] = chartModel.transformedDataset;

    expect(day1[getDataKey(A_PAID)]).toBeCloseTo(5 / 8);
    expect(day1[getDataKey(B_PAID)]).toBeCloseTo(2 / 3);
  });

  it("shows only the hovered group in the tooltip, with the group total", () => {
    const { chartModel, settings, getDataKey } = setup();

    const tooltip = getTooltipModel(
      chartModel,
      settings,
      0,
      "bar",
      getDataKey(B_PAID),
    );

    expect(tooltip?.rows.map((row) => row.name).sort()).toEqual(
      [B_PAID, B_ORGANIC].sort(),
    );
    expect(tooltip?.footer?.values[0]).toBe("3");
  });

  it("stacks all bars together as before when no series sets a stack_group", () => {
    const { getSeries } = setup({
      settings: { series_settings: UNGROUPED_SERIES_SETTINGS },
    });

    const stacks = [A_PAID, A_ORGANIC, B_PAID, B_ORGANIC].map(
      (name) => getSeries(name).stack,
    );
    expect(new Set(stacks)).toEqual(new Set(["bar"]));
  });
});
