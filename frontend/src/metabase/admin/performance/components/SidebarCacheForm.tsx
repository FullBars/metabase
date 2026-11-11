import { t } from "ttag";

import { DelayedLoadingAndErrorWrapper } from "metabase/common/components/LoadingAndErrorWrapper/DelayedLoadingAndErrorWrapper";
import { SidesheetSubPage } from "metabase/common/components/Sidesheet";
import type { SidebarCacheFormProps } from "metabase/plugins";
import { Stack } from "metabase/ui";

import { useConfirmIfFormIsDirty } from "../hooks/useConfirmIfFormIsDirty";
import { useSaveStrategy } from "../hooks/useSaveStrategy";
import {
  getCacheableItemName,
  useSavedStrategy,
} from "../hooks/useSavedStrategy";

import { StrategyForm } from "./StrategyForm";

/** Sidesheet page that edits the cache policy of a dashboard or question */
export const SidebarCacheForm = ({
  item,
  model,
  isOpen,
  withOverlay,
  overlayProps,
  onClose,
  onBack,
  withBackButton,
  ...stackProps
}: SidebarCacheFormProps) => {
  const { id, savedStrategy, error, isLoading } = useSavedStrategy(item, model);
  const saveStrategy = useSaveStrategy(id, model);
  const {
    askBeforeDiscardingChanges,
    confirmationModal,
    isStrategyFormDirty,
    setIsStrategyFormDirty,
  } = useConfirmIfFormIsDirty();

  const confirmIfDirty = (callback: () => void) => () => {
    if (isStrategyFormDirty) {
      askBeforeDiscardingChanges(callback);
    } else {
      callback();
    }
  };

  return (
    <SidesheetSubPage
      title={t`Caching settings`}
      isOpen={isOpen}
      onClose={confirmIfDirty(onClose)}
      onBack={confirmIfDirty(onBack)}
      withBackButton={withBackButton}
      withOverlay={withOverlay}
      overlayProps={overlayProps}
    >
      <Stack h="100%" {...stackProps}>
        {error || isLoading ? (
          <DelayedLoadingAndErrorWrapper error={error} loading={isLoading} />
        ) : (
          <StrategyForm
            targetId={id}
            targetModel={model}
            targetName={getCacheableItemName(item)}
            setIsDirty={setIsStrategyFormDirty}
            saveStrategy={saveStrategy}
            savedStrategy={savedStrategy}
            shouldAllowInvalidation
            shouldShowName={false}
            layout="sidebar"
            onCancel={confirmIfDirty(onBack)}
          />
        )}
      </Stack>
      {confirmationModal}
    </SidesheetSubPage>
  );
};
