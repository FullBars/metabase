import { useState } from "react";
import { match } from "ts-pattern";
import { c, t } from "ttag";

import { useConfirmation } from "metabase/common/hooks/use-confirmation";
import type { InvalidateNowButtonProps } from "metabase/plugins";
import { Button, Icon } from "metabase/ui";

import { useInvalidateTarget } from "../hooks/useInvalidateTarget";

type Status = "idle" | "pending" | "success" | "error";

/** Clears the cached results of a database, dashboard or question */
export const InvalidateNowButton = ({
  targetId,
  targetModel,
  targetName,
}: InvalidateNowButtonProps) => {
  const [status, setStatus] = useState<Status>("idle");
  const invalidateTarget = useInvalidateTarget(targetId, targetModel);
  const { show: askConfirmation, modalContent: confirmationModal } =
    useConfirmation();

  const invalidate = async () => {
    setStatus("pending");
    try {
      await invalidateTarget();
      setStatus("success");
    } catch {
      setStatus("error");
    }
  };

  const handleClick = () =>
    askConfirmation({
      title: targetName
        ? t`Clear all cached results for ${targetName}?`
        : t`Clear all cached results for this object?`,
      confirmButtonText: t`Clear cache`,
      onConfirm: invalidate,
    });

  const idleLabel = match(targetModel)
    .with("database", () => t`Clear cache for this database`)
    .with("dashboard", () => t`Clear cache for this dashboard`)
    .with("question", () => t`Clear cache for this question`)
    .with("metric", () => t`Clear cache for this metric`)
    .exhaustive();

  return (
    <>
      <Button
        onClick={handleClick}
        disabled={status === "pending"}
        leftSection={
          status === "success" ? (
            <Icon name="check" />
          ) : status === "error" ? (
            <Icon name="warning" />
          ) : undefined
        }
      >
        {match(status)
          .with("idle", () => idleLabel)
          .with("pending", () =>
            c("Shown when a cache is being cleared").t`Clearing cache… `.trim(),
          )
          .with("success", () => t`Cache cleared`)
          .with("error", () => t`Error`)
          .exhaustive()}
      </Button>
      {confirmationModal}
    </>
  );
};
