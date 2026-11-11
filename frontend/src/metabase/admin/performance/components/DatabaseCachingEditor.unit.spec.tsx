import userEvent from "@testing-library/user-event";
import fetchMock from "fetch-mock";

import { screen, waitFor, within } from "__support__/ui";
import { reinitialize } from "metabase/plugins";
import {
  createMockCacheConfig,
  createMockCacheConfigWithDoNotCacheStrategy,
} from "metabase-types/api/mocks";

import { initializeCachingPlugin } from "../plugin";

import {
  changeInput,
  getSaveButton,
  selectCacheStrategy,
  setupStrategyEditorForDatabases,
} from "./test-utils";

const setup = () =>
  setupStrategyEditorForDatabases({
    databaseCount: 2,
    cacheConfigs: [
      createMockCacheConfig({
        model: "root",
        model_id: 0,
        strategy: { type: "ttl", multiplier: 10, min_duration_ms: 1000 },
      }),
      createMockCacheConfigWithDoNotCacheStrategy({ model_id: 1 }),
    ],
  });

const getListItem = async (name: RegExp) =>
  within(await screen.findByRole("list", { name: "Databases" })).findByRole(
    "button",
    { name },
  );

describe("DatabaseCachingEditor", () => {
  beforeEach(() => {
    initializeCachingPlugin();
  });

  afterEach(() => {
    reinitialize();
  });

  it("lists the default policy first, then every database with its policy", async () => {
    setup();

    const list = await screen.findByRole("list", { name: "Databases" });
    await within(list).findByText("Database 2");
    const items = within(list).getAllByRole("button");
    expect(items).toHaveLength(3);
    expect(items[0]).toHaveTextContent("Default policy");
    expect(items[0]).toHaveTextContent("Adaptive");
    expect(items[1]).toHaveTextContent("Database 1");
    expect(items[1]).toHaveTextContent("No caching");
    expect(items[2]).toHaveTextContent("Database 2");
    // Database 2 has no policy of its own, so it inherits the default one
    expect(items[2]).toHaveTextContent("Default: Adaptive");
  });

  it("labels an inheriting database with the implicit default (no caching) when there is no root policy", async () => {
    setupStrategyEditorForDatabases({ databaseCount: 1, cacheConfigs: [] });

    expect(await getListItem(/Database 1/)).toHaveTextContent(
      "Default: No caching",
    );
  });

  it("saves a Duration policy for a database", async () => {
    setup();

    await userEvent.click(await getListItem(/Database 2/));
    expect(
      await screen.findByTestId("strategy-form-for-database-2"),
    ).toBeInTheDocument();

    await selectCacheStrategy(/^Duration/i);
    await changeInput(/cache duration/i, 0, 48);
    await userEvent.click(await getSaveButton());

    await waitFor(() => {
      expect(
        fetchMock.callHistory.lastCall("path:/api/cache", { method: "PUT" }),
      ).toBeTruthy();
    });
    const call = fetchMock.callHistory.lastCall("path:/api/cache", {
      method: "PUT",
    });
    expect(await call?.request?.json()).toEqual({
      model: "database",
      model_id: 2,
      strategy: {
        type: "duration",
        duration: 48,
        unit: "hours",
        refresh_automatically: false,
      },
    });
    expect(await getListItem(/Database 2/)).toHaveTextContent("Duration: 48h");
  });

  it("offers Duration and Schedule for the default policy, without inherit", async () => {
    setup();

    await userEvent.click(await screen.findByTestId("cache-strategy-select"));
    const options = await screen.findAllByRole("option");
    expect(options.map((option) => option.textContent)).toEqual([
      expect.stringMatching(/^Duration/),
      expect.stringMatching(/^Schedule/),
      expect.stringMatching(/^Adaptive/),
      expect.stringMatching(/^Don.t cache results/),
    ]);
  });

  it("shows the clear cache button for a database but not for the default policy", async () => {
    setup();

    expect(
      await screen.findByTestId("strategy-form-for-root-0"),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /Clear cache/ }),
    ).not.toBeInTheDocument();

    await userEvent.click(await getListItem(/Database 1/));
    expect(
      await screen.findByRole("button", {
        name: "Clear cache for this database",
      }),
    ).toBeInTheDocument();
  });

  it("does not show the upsell", async () => {
    setup();

    await screen.findByRole("list", { name: "Databases" });
    expect(screen.queryByText(/Control your caching/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Metabase Pro/)).not.toBeInTheDocument();
  });
});
