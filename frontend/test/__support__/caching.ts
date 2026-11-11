import { initializeCachingPlugin } from "metabase/admin/performance/plugin";

/**
 * Turns on the OSS granular caching controls in PLUGIN_CACHING.
 *
 * Feature modules may not import metabase/admin, so their tests set the plugin
 * up through here, the way setupEnterpriseOnlyPlugin sets up an enterprise one.
 * Call reinitialize() from metabase/plugins after each test to turn it off.
 */
export const setupCachingPlugin = () => initializeCachingPlugin();

export {
  changeInput,
  getSaveButton,
  selectCacheStrategy,
} from "metabase/admin/performance/components/test-utils";
