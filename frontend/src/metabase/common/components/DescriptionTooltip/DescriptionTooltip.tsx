import type { CSSProperties, ReactElement } from "react";

import { Markdown } from "metabase/common/components/Markdown";
import { Tooltip } from "metabase/ui";

import S from "./DescriptionTooltip.module.css";

// Long enough to move the pointer across the gap between the icon and the
// tooltip, so a description taller than the screen can be scrolled.
const CLOSE_DELAY_MS = 150;

const TOOLTIP_STYLE: CSSProperties = {
  // Half the viewport always fits on one side of the icon.
  maxInlineSize: "min(40rem, calc(50vw - 2rem))",
  pointerEvents: "auto",
};

interface DescriptionTooltipProps {
  description: string;
  children: ReactElement;
  "data-testid"?: string;
}

/**
 * Shows a card description in full. It opens beside the icon, so the
 * tooltip can use the full viewport height and scrolls past that.
 */
export function DescriptionTooltip({
  description,
  children,
  "data-testid": testId,
}: DescriptionTooltipProps) {
  return (
    <Tooltip
      label={
        <div className={S.body}>
          <Markdown dark compact disallowHeading unstyleLinks>
            {description}
          </Markdown>
        </div>
      }
      position="right"
      closeDelay={CLOSE_DELAY_MS}
      style={TOOLTIP_STYLE}
      data-testid={testId}
    >
      {children}
    </Tooltip>
  );
}
