import { AdminSettingsLayout } from "metabase/admin/components/AdminLayout/AdminSettingsLayout";
import {
  AdminNavItem,
  AdminNavWrapper,
} from "metabase/admin/components/AdminNav";
import { PLUGIN_CACHING, PerformanceTabId } from "metabase/plugins";
import { Outlet } from "metabase/router";

import { getPerformanceTabName } from "../utils";

export const PerformanceApp = () => {
  const hasDashboardsAndQuestionsTab =
    PLUGIN_CACHING.isGranularCachingEnabled() &&
    PLUGIN_CACHING.getTabMetadata().some(
      ({ tabId }) => tabId === PerformanceTabId.DashboardsAndQuestions,
    );

  return (
    <AdminSettingsLayout
      fullWidth
      sidebar={
        <AdminNavWrapper>
          <AdminNavItem
            label={getPerformanceTabName(PerformanceTabId.Databases)}
            path="/admin/performance/databases"
            icon="database"
          />
          {hasDashboardsAndQuestionsTab && (
            <AdminNavItem
              label={getPerformanceTabName(
                PerformanceTabId.DashboardsAndQuestions,
              )}
              path="/admin/performance/dashboards-and-questions"
              icon="dashboard"
            />
          )}
          <AdminNavItem
            label={getPerformanceTabName(PerformanceTabId.Models)}
            path="/admin/performance/models"
            icon="model"
          />
        </AdminNavWrapper>
      }
    >
      <Outlet />
    </AdminSettingsLayout>
  );
};
