import userEvent from "@testing-library/user-event";

import { renderWithProviders, screen, waitFor } from "__support__/ui";

import { DescriptionTooltip } from "./DescriptionTooltip";

const ITEMS = Array.from(
  { length: 20 },
  (_, index) => `第 ${index + 1} 条口径说明：统计区间内已支付订单的金额合计`,
);
const LONG_DESCRIPTION = ITEMS.map((item) => `- ${item}`).join("\n");

function setup() {
  renderWithProviders(
    <DescriptionTooltip description={LONG_DESCRIPTION}>
      <span data-testid="description-icon">i</span>
    </DescriptionTooltip>,
  );
}

describe("DescriptionTooltip", () => {
  it("should show every line of a long markdown description without clamping it", async () => {
    setup();

    await userEvent.hover(screen.getByTestId("description-icon"));

    const tooltip = await screen.findByRole("tooltip");
    expect(screen.getAllByRole("listitem")).toHaveLength(ITEMS.length);
    expect(tooltip).toHaveTextContent(ITEMS[ITEMS.length - 1]);
    expect(
      // eslint-disable-next-line testing-library/no-node-access -- the clamp is only a CSS variable, jsdom has no layout
      tooltip.querySelector('[style*="--markdown-line-clamp"]'),
    ).not.toBeInTheDocument();
  });

  it("should stay open while the pointer moves onto the tooltip so a long description can be scrolled", async () => {
    setup();

    const icon = screen.getByTestId("description-icon");
    await userEvent.hover(icon);
    const tooltip = await screen.findByRole("tooltip");

    await userEvent.unhover(icon);
    await userEvent.hover(tooltip);
    await new Promise((resolve) => setTimeout(resolve, 500));
    expect(screen.getByRole("tooltip")).toBeInTheDocument();

    await userEvent.unhover(tooltip);
    await waitFor(() =>
      expect(screen.queryByRole("tooltip")).not.toBeInTheDocument(),
    );
  });
});
