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
import Question from "metabase-lib/v1/Question";
import { createMockCard } from "metabase-types/api/mocks";

import { shouldShowQuestionSettingsSidebar } from "../QuestionSettingsSidebar";

import { setup } from "./setup";

const card = createMockCard({ id: 5, type: "question" });

const openCachingForm = async () => {
  await userEvent.click(
    await screen.findByRole("button", { name: /When to get new results/ }),
  );
  return await screen.findByTestId("strategy-form-for-question-5");
};

describe("QuestionSettingsSidebar > OSS caching", () => {
  beforeEach(() => {
    setupCachingPlugin();
    fetchMock.post("path:/api/cache/invalidate", {});
  });

  afterEach(() => {
    reinitialize();
  });

  it("shows the caching section with the inherited policy for a question", async () => {
    await setup({ card });

    expect(await screen.findByText("Caching")).toBeInTheDocument();
    expect(
      await screen.findByRole("button", { name: /When to get new results/ }),
    ).toHaveTextContent("Default");
  });

  it("saves a Duration policy that refreshes automatically", async () => {
    await setup({ card });
    await openCachingForm();

    await selectCacheStrategy(/^Duration/i);
    await changeInput(/cache duration/i, 0, 3);
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
      model: "question",
      model_id: 5,
      strategy: {
        type: "duration",
        duration: 3,
        unit: "hours",
        refresh_automatically: true,
      },
    });
  });

  it("hides the auto-refresh switch for Don't cache", async () => {
    await setup({ card });
    await openCachingForm();

    await selectCacheStrategy(/^Schedule/i);
    expect(
      await screen.findByRole("switch", {
        name: /Refresh cache automatically/,
      }),
    ).toBeInTheDocument();

    await selectCacheStrategy(/^Don.t cache/i);
    expect(
      screen.queryByRole("switch", { name: /Refresh cache automatically/ }),
    ).not.toBeInTheDocument();
  });

  it("clears the question's cache after confirmation", async () => {
    await setup({ card });
    await openCachingForm();

    await userEvent.click(
      await screen.findByRole("button", {
        name: "Clear cache for this question",
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
    expect(url.searchParams.get("question")).toBe("5");
    expect(url.searchParams.get("include")).toBe("overrides");
  });

  it.each(["model", "metric"] as const)(
    "does not show the caching policy section for a %s",
    async (type) => {
      await setup({ card: createMockCard({ id: 5, type }) });

      expect(
        screen.queryByText("When to get new results"),
      ).not.toBeInTheDocument();
    },
  );

  it("does not show the caching policy section to users who cannot edit the question", async () => {
    await setup({ card: createMockCard({ id: 5, can_write: false }) });

    expect(screen.getByText("Question settings")).toBeInTheDocument();
    expect(
      screen.queryByText("When to get new results"),
    ).not.toBeInTheDocument();
  });

  describe("shouldShowQuestionSettingsSidebar", () => {
    it("is true for a question the user can edit", () => {
      expect(shouldShowQuestionSettingsSidebar(new Question(card))).toBe(true);
    });

    it("is false for a question the user cannot edit", () => {
      expect(
        shouldShowQuestionSettingsSidebar(
          new Question(createMockCard({ can_write: false })),
        ),
      ).toBe(false);
    });

    it("is false for a metric", () => {
      expect(
        shouldShowQuestionSettingsSidebar(
          new Question(createMockCard({ type: "metric" })),
        ),
      ).toBe(false);
    });
  });
});
