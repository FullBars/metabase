import { type ReactNode, useState } from "react";
import { t } from "ttag";

import { useListDatabasesQuery } from "metabase/api";
import { DelayedLoadingAndErrorWrapper } from "metabase/common/components/LoadingAndErrorWrapper/DelayedLoadingAndErrorWrapper";
import { SettingsPageWrapper } from "metabase/settings-components";
import { Box, Flex, NavLink, Text } from "metabase/ui";
import type { CacheableModel } from "metabase-types/api";

import { defaultRootStrategy, rootId } from "../constants/simple";
import { useCacheConfigs } from "../hooks/useCacheConfigs";
import { useConfirmIfFormIsDirty } from "../hooks/useConfirmIfFormIsDirty";
import { useSaveStrategy } from "../hooks/useSaveStrategy";
import { getShortStrategyLabel } from "../utils";

import { PerformancePageContent } from "./PerformancePageContent";
import S from "./StrategyEditorForDatabases.module.css";
import { StrategyForm } from "./StrategyForm";

/** Edits the default policy and the policy of each database */
export const DatabaseCachingEditor = () => {
  const [targetId, setTargetId] = useState(rootId);

  const {
    configs,
    error: configsError,
    isLoading: areConfigsLoading,
  } = useCacheConfigs({ model: ["root", "database"] });
  const {
    data: databasesResponse,
    error: databasesError,
    isLoading: areDatabasesLoading,
  } = useListDatabasesQuery();

  const {
    askBeforeDiscardingChanges,
    confirmationModal,
    isStrategyFormDirty,
    setIsStrategyFormDirty,
  } = useConfirmIfFormIsDirty();

  const saveStrategy = useSaveStrategy(targetId, "database");

  const error = configsError ?? databasesError;
  if (error || areConfigsLoading || areDatabasesLoading) {
    return (
      <DelayedLoadingAndErrorWrapper
        error={error}
        loading={areConfigsLoading || areDatabasesLoading}
      />
    );
  }

  const databases = databasesResponse?.data ?? [];
  const getSavedStrategy = (model: CacheableModel, id: number) =>
    configs?.find((config) => config.model === model && config.model_id === id)
      ?.strategy;

  const rootLabel = getShortStrategyLabel(
    getSavedStrategy("root", rootId) ?? defaultRootStrategy,
    "root",
  );
  const getDatabaseLabel = (databaseId: number) => {
    const strategy = getSavedStrategy("database", databaseId);
    return strategy && strategy.type !== "inherit"
      ? getShortStrategyLabel(strategy, "database")
      : t`Default: ${rootLabel}`;
  };

  const selectTarget = (id: number) => {
    if (id === targetId) {
      return;
    }
    if (isStrategyFormDirty) {
      askBeforeDiscardingChanges(() => setTargetId(id));
    } else {
      setTargetId(id);
    }
  };

  const isRoot = targetId === rootId;
  const targetDatabase = databases.find(({ id }) => id === targetId);

  return (
    <PerformancePageContent>
      <SettingsPageWrapper
        title={t`Database caching`}
        aria-label={t`Data caching settings`}
        description={t`Speed up queries by caching results with a global policy or database-specific policies.`}
        h="calc(100vh - 9rem)"
      >
        {confirmationModal}
        <Flex gap="xxl" className={S.scrollableLayout}>
          <Box
            component="ul"
            aria-label={t`Databases`}
            w="20rem"
            flex="0 0 auto"
            p="sm"
            bd="2px solid var(--mb-color-border-neutral)"
            bdrs="1rem"
            className={S.formPanel}
          >
            <li>
              <NavLink
                component="button"
                // These select what to edit on this page; they don't navigate
                role="button"
                label={t`Default policy`}
                description={
                  <PolicyLabel active={isRoot}>{rootLabel}</PolicyLabel>
                }
                active={isRoot}
                onClick={() => selectTarget(rootId)}
              />
            </li>
            {databases.map((database) => (
              <li key={database.id}>
                <NavLink
                  component="button"
                  role="button"
                  label={database.name}
                  description={
                    <PolicyLabel active={targetId === database.id}>
                      {getDatabaseLabel(database.id)}
                    </PolicyLabel>
                  }
                  active={targetId === database.id}
                  onClick={() => selectTarget(database.id)}
                />
              </li>
            ))}
          </Box>
          <Box
            w="100%"
            maw="30rem"
            bd="2px solid var(--mb-color-border-neutral)"
            className={S.roundedBox}
          >
            <Box
              component="section"
              bg="background_page-primary"
              h="100%"
              className={S.formPanel}
            >
              {(isRoot || targetDatabase) && (
                <StrategyForm
                  targetId={targetId}
                  targetModel={isRoot ? "root" : "database"}
                  targetName={targetDatabase?.name ?? t`Default policy`}
                  setIsDirty={setIsStrategyFormDirty}
                  saveStrategy={saveStrategy}
                  savedStrategy={
                    isRoot
                      ? getSavedStrategy("root", rootId)
                      : getSavedStrategy("database", targetId)
                  }
                  shouldAllowInvalidation={!isRoot}
                  shouldShowName={!isRoot}
                />
              )}
            </Box>
          </Box>
        </Flex>
      </SettingsPageWrapper>
    </PerformancePageContent>
  );
};

/** The policy under an item's name. NavLink leaves its description uncolored, which the theme renders invisible. */
const PolicyLabel = ({
  active,
  children,
}: {
  active: boolean;
  children: ReactNode;
}) => (
  <Text component="span" fz="sm" c={active ? "inherit" : "text-secondary"}>
    {children}
  </Text>
);
