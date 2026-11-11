import { renderWithProviders, screen } from "__support__/ui";
import { reinitialize } from "metabase/plugins";
import { Route } from "metabase/router";

import { initializeCachingPlugin } from "../plugin";

import { PerformanceApp } from "./PerformanceApp";

const setup = () =>
  renderWithProviders(<Route path="*" element={<PerformanceApp />} />, {
    withRouter: true,
    initialRoute: "/admin/performance/databases",
  });

describe("PerformanceApp (OSS caching plugin)", () => {
  beforeEach(() => {
    initializeCachingPlugin();
  });

  afterEach(() => {
    reinitialize();
  });

  it("does not link to the dashboards and questions page", async () => {
    setup();

    expect(
      await screen.findByRole("link", { name: /Database caching/ }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: /Model persistence/ }),
    ).toBeInTheDocument();
    expect(screen.getAllByRole("link")).toHaveLength(2);
    expect(
      screen.queryByRole("link", { name: /Dashboards and questions/ }),
    ).not.toBeInTheDocument();
  });
});
