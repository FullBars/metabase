import type { NotificationChannel } from "metabase-types/api";

import type { WebhookFormProps } from "./WebhookForm";

export const buildAuthInfo = (
  form: WebhookFormProps,
): Record<string, string> => {
  const { "fe-form-type": authType } = form;
  if (authType === "basic") {
    const { "auth-username": username, "auth-password": password } = form;
    return { Authorization: `Basic ${btoa(`${username}:${password}`)}` };
  } else if (authType === "bearer") {
    return { Authorization: `Bearer ${form["auth-info-value"]}` };
  } else if (authType === "api-key") {
    const { "auth-info-key": key, "auth-info-value": value } = form;
    if (key && value) {
      return { [key]: value };
    }
  }

  return {};
};

export const channelToForm = ({ details }: NotificationChannel) => {
  const { "fe-form-type": authType, "auth-info": authInfo } = details;

  if (authType === "bearer") {
    const token = authInfo?.["Authorization"];
    return { "auth-info-value": token?.match(/Bearer (.*)/)?.[1] || "" };
  }
  if (authType === "basic") {
    const info = authInfo?.["Authorization"];
    const encoded = info?.match(/Basic (.*)/)?.[1];

    if (encoded) {
      const decoded = atob(encoded);
      const [_match, username, password] = decoded.match(/(.*):(.*)/) || [];

      if (username && password) {
        return {
          "auth-username": username,
          "auth-password": password,
        };
      }
    }
  }
  if (authType === "api-key" && authInfo) {
    const key = Object.keys(authInfo)[0];

    return { "auth-info-key": key, "auth-info-value": authInfo[key] };
  }
};

const FEISHU_CHAT_URL = new URL(
  "https://open.feishu.cn/open-apis/im/v1/messages",
);

/**
 * The webhook URL that sends to the Feishu group chat `chatId` through the
 * Feishu app in the instance settings (MB_FEISHU_APP_ID / _SECRET).
 */
export const feishuChatWebhookUrl = (chatId: string): string => {
  const url = new URL(FEISHU_CHAT_URL);
  url.searchParams.set("receive_id_type", "chat_id");
  url.searchParams.set("receive_id", chatId);
  return url.toString();
};

/**
 * The chat ID of a [[feishuChatWebhookUrl]], or `null` for any other URL,
 * including the same path on another host, which stays a plain URL.
 */
export const getFeishuChatId = (url: string): string | null => {
  try {
    const parsed = new URL(url);
    const isChatUrl =
      parsed.origin === FEISHU_CHAT_URL.origin &&
      parsed.pathname === FEISHU_CHAT_URL.pathname &&
      (parsed.searchParams.get("receive_id_type") ?? "chat_id") === "chat_id";
    return isChatUrl ? parsed.searchParams.get("receive_id") || null : null;
  } catch {
    return null;
  }
};
