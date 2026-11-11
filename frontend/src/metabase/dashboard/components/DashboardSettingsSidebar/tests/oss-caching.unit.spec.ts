import userEvent from "@testing-library/user-event";
import fetchMock from "fetch-mock";

import {
  changeInput,
  getSaveButton,
  selectCacheStrategy,
  setupCachingPlugin,
} from "__support__/caching";
import { screen, waitFor, within } from "__support__/ui";
import { reinitialize } from "metabase/plugins";
import {
  createMockCacheConfigWithDurationStrategy,
  createMockDashboard,
} from "metabase-types/api/mocks";

import { setup } from "./setup";

const dashboard = createMockDashboard({ id: 7, can_set_cache_policy: true });

const openCachingForm = async () => {
  await userEvent.click(
    await screen.findByRole("button", { name: /When to get new results/ }),
  );
  return await screen.findByTestId("strategy-form-for-dashboard-7");
};

describe("DashboardSettingsSidebar > OSS caching", () => {
  beforeEach(() => {
    setupCachingPlugin();
    fetchMock.post("path:/api/cache/invalidate", {});
  });

  afterEach(() => {
    reinitialize();
  });

  it("shows the caching card with the inherited policy when the user can set the cache policy", async () => {
    await setup({ dashboard });

    expect(await screen.findByText("Caching")).toBeInTheDocument();
    expect(
      await screen.findByRole("button", { name: /When to get new results/ }),
    ).toHaveTextContent("Default");
  });

  it("shows the dashboard's own policy", async () => {
    await setup({
      dashboard,
      cacheConfigs: [
        createMockCacheConfigWithDurationStrategy({
          model: "dashboard",
          model_id: 7,
        }),
      ],
    });

    expect(
      await screen.findByRole("button", { name: /When to get new results/ }),
    ).toHaveTextContent("Duration: 1h");
  });

  it("does not show the caching card when the user cannot set the cache policy", async () => {
    await setup({
      dashboard: createMockDashboard({ id: 7, can_set_cache_policy: false }),
    });

    expect(screen.getByText("Auto-apply filters")).toBeInTheDocument();
    expect(screen.queryByText("Caching")).not.toBeInTheDocument();
  });

  it("shows the auto-refresh switch for Duration but not for Adaptive", async () => {
    await setup({ dashboard });
    await openCachingForm();
    expect(await screen.findByText("Caching settings")).toBeInTheDocument();

    await selectCacheStrategy(/^Duration/i);
    expect(
      await screen.findByRole("switch", {
        name: /Refresh cache automatically/,
      }),
    ).not.toBeChecked();

    await selectCacheStrategy(/^Adaptive/i);
    expect(
      screen.queryByRole("switch", { name: /Refresh cache automatically/ }),
    ).not.toBeInTheDocument();
  });

  it("saves a Duration policy that refreshes automatically", async () => {
    await setup({ dashboard });
    await openCachingForm();

    await selectCacheStrategy(/^Duration/i);
    await changeInput(/cache duration/i, 0, 2);
    await userEvent.click(
      screen.getByRole("switch", { name: /Refresh cache automatically/ }),
    );
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
      model: "dashboard",
      model_id: 7,
      strategy: {
        type: "duration",
        duration: 2,
        unit: "hours",
        refresh_automatically: true,
      },
    });
  });

  it("clears the dashboard's cache after confirmation", async () => {
    await setup({ dashboard });
    await openCachingForm();

    await userEvent.click(
      await screen.findByRole("button", {
        name: "Clear cache for this dashboard",
      }),
    );
    const modal = await screen.findByTestId("confirm-modal");
    await userEvent.click(
      within(modal).getByRole("button", { name: "Clear cache" }),
    );

    await waitFor(() => {
      expect(
        fetchMock.callHistory.called("path:/api/cache/invalidate", {
          method: "POST",
        }),
      ).toBe(true);
    });
    const url = new URL(
      fetchMock.callHistory.lastCall("path:/api/cache/invalidate")!.url,
    );
    expect(url.searchParams.get("dashboard")).toBe("7");
    expect(url.searchParams.get("include")).toBe("overrides");
    expect(await screen.findByText("Cache cleared")).toBeInTheDocument();
  });

  it("goes back to the settings from the caching form", async () => {
    await setup({ dashboard });
    await openCachingForm();

    await userEvent.click(
      screen.getByRole("button", { name: /Caching settings/ }),
    );
    expect(await screen.findByText("Dashboard settings")).toBeInTheDocument();
  });
});
