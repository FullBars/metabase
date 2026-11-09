import type { FormikHelpers } from "formik";
import { useMemo, useState } from "react";
import { c, jt, t } from "ttag";
import * as Yup from "yup";

import { useTestChannelMutation } from "metabase/api/channel";
import { findErrorMessage } from "metabase/api/utils/errors";
import { ExternalLink } from "metabase/common/components/ExternalLink";
import { useActionButtonLabel } from "metabase/common/hooks/use-action-button-label";
import {
  Form,
  FormChipGroup,
  FormProvider,
  FormSubmitButton,
  FormTextInput,
} from "metabase/forms";
import { useSelector } from "metabase/redux";
import { getDocsUrl } from "metabase/selectors/settings";
import {
  Alert,
  Box,
  Button,
  Chip,
  Flex,
  Group,
  Icon,
  ScrollArea,
  Title,
} from "metabase/ui";
import {
  type NotificationAuthMethods,
  type NotificationAuthType,
  isNotificationChannelTestErrorResponse,
} from "metabase-types/api";

import { buildAuthInfo, feishuChatWebhookUrl, getFeishuChatId } from "./utils";

const FEISHU_CHAT_ID_PATTERN = /^oc_[A-Za-z0-9]+$/;

function getValidationSchema() {
  return Yup.object({
    url: Yup.string().when("destination-type", {
      is: "feishu-chat",
      then: Yup.string(),
      otherwise: Yup.string()
        .required(t`Please enter a correctly formatted URL`)
        .test(
          "is-http-url",
          t`Please enter a correctly formatted URL`,
          (value) => value != null && isValidWebhookUrl(value),
        ),
    }),
    "feishu-chat-id": Yup.string().when("destination-type", {
      is: "feishu-chat",
      then: Yup.string().test(
        "is-chat-id",
        t`Enter the group's chat ID, which starts with oc_`,
        (value) => FEISHU_CHAT_ID_PATTERN.test((value ?? "").trim()),
      ),
      otherwise: Yup.string(),
    }),
    name: Yup.string().required(t`Please add a name`),
    description: Yup.string().required(t`Please add a description`),
    "auth-method": Yup.string()
      .required()
      .equals(["none", "header", "query-param", "request-body"]),
    "fe-form-type": Yup.string()
      .required()
      .equals(["none", "basic", "bearer", "api-key"]),
    "auth-info": Yup.object(),
  });
}

/**
 * Approximates the backend validator (metabase.util/url?), which unlike Yup's
 * .url() accepts bare hostnames such as http://webhook-tester:8080/
 */
function isValidWebhookUrl(value: string): boolean {
  if (value !== value.trim() || !value.includes("://")) {
    return false;
  }

  try {
    const { protocol } = new URL(value);
    return protocol === "http:" || protocol === "https:";
  } catch {
    return false;
  }
}

const styles = {
  wrapperProps: {
    fw: 400,
  },
  labelProps: {
    fz: "0.875rem",
    mb: "0.75rem",
  },
};

export type WebhookFormProps = {
  url: string;
  name: string;
  description: string;
  "auth-method": NotificationAuthMethods;
  "auth-info-key"?: string;
  "auth-info-value"?: string;
  "auth-username"?: string;
  "auth-password"?: string;
  "fe-form-type": NotificationAuthType;
};

/**
 * Where the webhook goes: a Feishu group chat, entered as just its chat ID and
 * sent to through the Feishu app, or any other URL.
 */
type DestinationType = "feishu-chat" | "url";

type WebhookFormValues = WebhookFormProps & {
  "destination-type": DestinationType;
  "feishu-chat-id": string;
};

type WebhookFormikHelpers = FormikHelpers<WebhookFormValues>;

const toFormValues = (
  values: WebhookFormProps,
  defaultDestinationType: DestinationType,
): WebhookFormValues => {
  const chatId =
    values["fe-form-type"] === "none" ? getFeishuChatId(values.url) : null;
  return {
    ...values,
    "destination-type": chatId
      ? "feishu-chat"
      : values.url
        ? "url"
        : defaultDestinationType,
    "feishu-chat-id": chatId ?? "",
  };
};

/** What the form saves: a Feishu group becomes its URL, without authentication. */
const fromFormValues = ({
  "destination-type": destinationType,
  "feishu-chat-id": chatId,
  ...values
}: WebhookFormValues): WebhookFormProps =>
  destinationType === "feishu-chat"
    ? {
        name: values.name,
        description: values.description,
        url: feishuChatWebhookUrl(chatId.trim()),
        "auth-method": "none",
        "fe-form-type": "none",
      }
    : values;

// Helper function to attempt to ensure that any error that comes back
// is in the shape that our FormSubmit logic expects. This controls
// highlighting the correct fields, etc. The shape can be hard to
// determine because we forward responses from alert targets
export const handleFieldError = (e: any) => {
  if (!e.data) {
    return;
  } else if (typeof e.data === "string") {
    throw { data: { errors: { url: e.data } } };
  } else if (e.data.message) {
    throw { data: { errors: { url: e.data.message } } };
  } else if (typeof e.data.errors === "object") {
    throw e;
  }
};

const renderAuthSection = (type: string) => {
  switch (type) {
    case "basic":
      return (
        <>
          <FormTextInput
            name="auth-username"
            label={t`Username`}
            placeholder="user@email.com"
            {...styles}
            mb="1.5rem"
          />
          <FormTextInput
            name="auth-password"
            label={t`Password`}
            placeholder="********"
            {...styles}
          />
        </>
      );
    case "bearer":
      return (
        <FormTextInput
          name="auth-info-value"
          label={t`Bearer token`}
          placeholder={t`Secret Token`}
          {...styles}
          mb="1.5rem"
        />
      );
    case "api-key":
      return (
        <Flex direction="column">
          <FormChipGroup
            name="auth-method"
            label={t`Add to`}
            groupProps={{ mb: "1.5rem", mt: "0.5rem" }}
          >
            <Chip value="header" variant="filled">
              {t`Header`}
            </Chip>
            <Chip value="query-param" variant="filled">
              {t`Query param`}
            </Chip>
          </FormChipGroup>
          <Flex gap="0.5rem">
            <FormTextInput
              name="auth-info-key"
              label={t`Key`}
              placeholder={t`X-API-KEY`}
              {...styles}
              mb="1.5rem"
            />
            <FormTextInput
              name="auth-info-value"
              label={t`Value`}
              placeholder={t`API Key Value`}
              {...styles}
            />
          </Flex>
        </Flex>
      );
    default:
      return null;
  }
};

export const WebhookForm = ({
  onSubmit,
  onCancel,
  onDelete,
  initialValues,
  defaultDestinationType = "url",
  submitLabel = t`Create destination`,
}: {
  onSubmit: (
    props: WebhookFormProps,
    helpers: WebhookFormikHelpers,
  ) => void | Promise<void>;
  onCancel: () => void;
  onDelete?: () => void;
  initialValues: WebhookFormProps;
  /** For a new webhook, whose URL is still empty. */
  defaultDestinationType?: DestinationType;
  submitLabel?: string;
}) => {
  const { label: testButtonLabel, setLabel: setTestButtonLabel } =
    useActionButtonLabel({ defaultLabel: t`Send a test` });
  const [testChannel, { error }] = useTestChannelMutation();
  const [validationSchema] = useState(getValidationSchema());
  const formValues = useMemo(
    () => toFormValues(initialValues, defaultDestinationType),
    [initialValues, defaultDestinationType],
  );

  // A Feishu group has no URL field, so its errors go to the chat ID.
  const errorField = (values: WebhookFormValues) =>
    values["destination-type"] === "feishu-chat" ? "feishu-chat-id" : "url";

  const handleSubmit = async (
    values: WebhookFormValues,
    helpers: WebhookFormikHelpers,
  ) => {
    try {
      await onSubmit(fromFormValues(values), helpers);
    } catch (e: any) {
      const urlError = e?.data?.errors?.url;
      if (urlError && errorField(values) !== "url") {
        throw { data: { errors: { [errorField(values)]: urlError } } };
      }
      throw e;
    }
  };

  const errorData = useMemo(() => {
    if (isNotificationChannelTestErrorResponse(error)) {
      return error.data.data;
    }
  }, [error]);

  const docsUrl = useSelector((state) =>
    getDocsUrl(state, { page: "questions/sharing/alerts" }),
  );

  const handleTest = async (
    formValues: WebhookFormValues,
    setFieldError: WebhookFormikHelpers["setFieldError"],
  ) => {
    const values = fromFormValues(formValues);
    const field = errorField(formValues);
    await testChannel({
      details: {
        url: values.url,
        "auth-method": values["auth-method"],
        "auth-info": buildAuthInfo(values),
      },
    })
      .unwrap()
      .then(
        () => {
          setFieldError(field, undefined);
          setTestButtonLabel(t`Success`);
        },
        (e) => {
          setTestButtonLabel(t`Test failed`);
          const message = findErrorMessage(e);

          setFieldError(field, message);
        },
      );
  };

  const testButton = (
    values: WebhookFormValues,
    setFieldError: WebhookFormikHelpers["setFieldError"],
  ) => (
    <Button size="lg" onClick={() => handleTest(values, setFieldError)}>
      {testButtonLabel}
    </Button>
  );

  return (
    <FormProvider
      initialValues={formValues}
      onSubmit={handleSubmit}
      validationSchema={validationSchema}
    >
      {({ dirty, values, setFieldError, setFieldValue }) => (
        <Form>
          <Alert size="compact" variant="light" mb="1.5rem">
            {jt`You can send the payload of any Alert to this destination whenever the Alert is triggered. ${(
              <ExternalLink key="link" href={docsUrl}>
                {t`Learn about Alerts`}
              </ExternalLink>
            )}`}
          </Alert>
          <FormChipGroup
            name="destination-type"
            label={t`Send to`}
            groupProps={{ mb: "1.5rem", mt: "0.5rem" }}
          >
            <Chip value="feishu-chat" variant="filled">
              {t`Feishu group`}
            </Chip>
            <Chip value="url" variant="filled">
              {t`Other URL`}
            </Chip>
          </FormChipGroup>
          <Box mb="1.5rem">
            {values["destination-type"] === "feishu-chat" ? (
              <Flex align="end" gap="1rem">
                <FormTextInput
                  name="feishu-chat-id"
                  label={t`Feishu group chat ID`}
                  description={t`Add the Feishu app's bot to the group first. The group can get alerts and dashboard subscriptions (as a PDF).`}
                  placeholder="oc_…"
                  style={{ flexGrow: 1 }}
                  {...styles}
                  maw="21rem"
                />
                {testButton(values, setFieldError)}
              </Flex>
            ) : (
              <Flex align="end" gap="1rem">
                <FormTextInput
                  name="url"
                  label={t`Webhook URL`}
                  placeholder="http://hooks.example.com/hooks/catch/"
                  style={{ flexGrow: 1 }}
                  {...styles}
                  maw="21rem"
                />
                {testButton(values, setFieldError)}
              </Flex>
            )}
            {!!errorData && (
              <ScrollArea.Autosize mah={200} mt="0.75rem">
                <Title order={6} mb="0.75rem" lh="1rem">
                  {c("The response returned by an API Request")
                    .t`Test response`}
                </Title>
                <Box
                  py="0.5rem"
                  px="1.5rem"
                  bg="background_page-secondary"
                  style={{ borderRadius: "0.5rem" }}
                  data-testid="notification-test-response"
                >
                  <pre
                    style={{
                      margin: 0,
                      fontSize: "0.75rem",
                      lineHeight: "1rem",
                      whiteSpace: "pre-wrap",
                    }}
                  >
                    {JSON.stringify(errorData, null, 2)}
                  </pre>
                </Box>
              </ScrollArea.Autosize>
            )}
          </Box>

          <FormTextInput
            name="name"
            label={t`Give it a name`}
            placeholder={t`Something descriptive`}
            {...styles}
            mb="1.5rem"
            maw="14.5rem"
          />
          <FormTextInput
            name="description"
            label={t`Description`}
            placeholder={t`Where is this going and what does it send?`}
            {...styles}
            mb="1.5rem"
            maw="21rem"
          />
          {values["destination-type"] === "url" && (
            <>
              <FormChipGroup
                name="fe-form-type"
                label={t`Authentication method`}
                groupProps={{ mb: "1.5rem", mt: "0.5rem" }}
                onChange={(val) => {
                  if (val === "none") {
                    setFieldValue("auth-method", "none");
                  } else {
                    setFieldValue("auth-method", "header");
                  }
                }}
              >
                <Chip value="none" variant="filled">
                  {t`None`}
                </Chip>
                <Chip value="basic" variant="filled">
                  {t`Basic`}
                </Chip>
                <Chip value="bearer" variant="filled">
                  {t`Bearer`}
                </Chip>
                <Chip value="api-key" variant="filled">
                  {t`API Key`}
                </Chip>
              </FormChipGroup>

              {renderAuthSection(values["fe-form-type"])}
            </>
          )}

          <Flex
            mt="1.5rem"
            justify={onDelete ? "space-between" : "end"}
            align="center"
            gap="0.75rem"
          >
            {onDelete && (
              <Button
                variant="transparent"
                size="compact-md"
                leftSection={<Icon name="trash" />}
                onClick={onDelete}
              >{t`Delete this destination`}</Button>
            )}
            <Group>
              <Button onClick={onCancel}>{t`Cancel`}</Button>
              <FormSubmitButton
                disabled={!dirty}
                label={submitLabel}
                variant="filled"
              />
            </Group>
          </Flex>
        </Form>
      )}
    </FormProvider>
  );
};
