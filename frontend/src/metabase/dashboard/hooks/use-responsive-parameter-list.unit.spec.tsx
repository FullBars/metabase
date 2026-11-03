import { render, screen } from "@testing-library/react";

import { useResponsiveParameterList } from "./use-responsive-parameter-list";

jest.mock("metabase/dashboard/context", () => ({
  useDashboardContext: () => ({ isEditing: false, isEditingParameter: false }),
}));

const CARD_WIDTH = 170;
const FILTERS_WIDTH = 220;

function Card({ isLoaded }: { isLoaded: boolean }) {
  const { shouldCollapseList, containerRef, parameterListRef } =
    useResponsiveParameterList({ reservedWidth: 50 });

  return (
    <div ref={containerRef} data-width={CARD_WIDTH}>
      {/* Scalar cards render their filters only once the data has loaded */}
      {isLoaded && <div ref={parameterListRef} data-width={FILTERS_WIDTH} />}
      {shouldCollapseList ? "collapsed" : "expanded"}
    </div>
  );
}

describe("useResponsiveParameterList", () => {
  beforeEach(() => {
    jest
      .spyOn(HTMLElement.prototype, "getBoundingClientRect")
      .mockImplementation(function (this: HTMLElement) {
        const width = Number(this.dataset.width ?? 0);
        // The hook reads only `width`; jsdom has no layout to measure.
        return { width, height: 0 } as DOMRect;
      });
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it("should collapse filters that do not fit even when they mount after the card", () => {
    const { rerender } = render(<Card isLoaded={false} />);
    expect(screen.getByText("expanded")).toBeInTheDocument();

    rerender(<Card isLoaded />);

    expect(screen.getByText("collapsed")).toBeInTheDocument();
  });
});
