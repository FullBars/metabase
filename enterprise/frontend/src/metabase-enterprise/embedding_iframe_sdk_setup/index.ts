import {
  PLUGIN_DATA_APPS,
  PLUGIN_EMBEDDING_IFRAME_SDK_SETUP,
} from "metabase/plugins";
import { hasPremiumFeature } from "metabase-enterprise/settings";

import { EmbedDataAppPicker } from "./components/EmbedDataAppPicker";

/**
 * Initialize embedding iframe SDK setup plugin features that depend on hasPremiumFeature.
 */
export function initializePlugin() {
  // Feature gate the embed setup for gradual rollouts.
  // In the future, we may drop this to enable users to try out new iframe embedding.
  if (hasPremiumFeature("embedding_simple")) {
    PLUGIN_EMBEDDING_IFRAME_SDK_SETUP.isEnabled = () => true;
  }

  if (hasPremiumFeature("data-apps")) {
    PLUGIN_DATA_APPS.EmbedDataAppPicker = EmbedDataAppPicker;
  }
}
