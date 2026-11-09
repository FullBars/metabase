import { connect } from "metabase/redux";
import type { State } from "metabase/redux/store";

import { getHiddenParameterSlugs, getParameters } from "../../../selectors";

import { AddEditEmailSidebar as AddEditEmailSidebarComponent } from "./AddEditEmailSidebar";
import { AddEditSlackSidebar as AddEditSlackSidebarComponent } from "./AddEditSlackSidebar";
import { AddEditWebhookSidebar as AddEditWebhookSidebarComponent } from "./AddEditWebhookSidebar";

const mapStateToProps = (state: State) => {
  return {
    parameters: getParameters(state),
    hiddenParameters: getHiddenParameterSlugs(state),
  };
};

export const AddEditEmailSidebar = connect(mapStateToProps)(
  AddEditEmailSidebarComponent,
);
export const AddEditSlackSidebar = connect(mapStateToProps)(
  AddEditSlackSidebarComponent,
);
export const AddEditWebhookSidebar = connect(mapStateToProps)(
  AddEditWebhookSidebarComponent,
);
