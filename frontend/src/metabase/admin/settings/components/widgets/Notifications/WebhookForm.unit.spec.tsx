import userEvent from "@testing-library/user-event";
import fetchMock from "fetch-mock";

import { act, renderWithProviders, screen, waitFor } from "__support__/ui";

import { WebhookForm } from "./WebhookForm";

const INITIAL_FORM_VALUES = {
  url: "",
  name: "",
  description: "",
  "fe-form-type": "none" as const,
  "auth-method": "none" as const,
};

const setup = async ({
  onSubmit = jest.fn(),
  onCancel = jest.fn(),
  onDelete = jest.fn(),
  initialValues = INITIAL_FORM_VALUES,
  populateForm = false,
} = {}) => {
  renderWithProviders(
    <WebhookForm
      onSubmit={onSubmit}
      onCancel={onCancel}
      onDelete={onDelete}
      initialValues={initialValues}
    />,
  );

  if (populateForm) {
    await userEvent.type(
      await screen.findByLabelText("Webhook URL"),
      "http://my-awesome-hook.com/",
    );
    await userEvent.type(
      await screen.findByLabelText("Give it a name"),
      "The best hook",
    );
    await userEvent.type(
      await screen.findByLabelText("Description"),
      "Really though, it's the best",
    );
  }

  return {
    onSubmit,
  };
};

describe("WebhookForm", () => {
  afterEach(() => {
    jest.useRealTimers();
  });

  it.each(["A-bad-url", "webhook-tester:8080", " http://padded.example.com/"])(
    "should error when an invalid url is given (%s)",
    async (url) => {
      await setup();
      await userEvent.type(
        await screen.findByLabelText("Webhook URL"),
        `${url}{tab}`,
      );
      expect(
        await screen.findByText("Please enter a correctly formatted URL"),
      ).toBeInTheDocument();
    },
  );

  it("should accept bare-hostname URLs (metabase#74812)", async () => {
    const { onSubmit } = await setup();
    await userEvent.type(
      await screen.findByLabelText("Webhook URL"),
      "http://webhook-tester:8080/",
    );
    await userEvent.type(screen.getByLabelText("Give it a name"), "Local hook");
    await userEvent.type(
      screen.getByLabelText("Description"),
      "Docker network hook",
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Create destination" }),
    );

    expect(
      screen.queryByText("Please enter a correctly formatted URL"),
    ).not.toBeInTheDocument();
    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({ url: "http://webhook-tester:8080/" }),
      expect.anything(),
    );
  });

  it("should error when no name is provided", async () => {
    await setup();
    await userEvent.type(
      await screen.findByLabelText("Give it a name"),
      "{tab}",
    );
    expect(await screen.findByText("Please add a name")).toBeInTheDocument();
  });

  it("should error when no description is provided", async () => {
    await setup();
    await userEvent.type(await screen.findByLabelText("Description"), "{tab}");
    expect(
      await screen.findByText("Please add a description"),
    ).toBeInTheDocument();
  });

  it("should show a username and password field when basic auth is selected", async () => {
    const { onSubmit } = await setup({ populateForm: true });

    await userEvent.click(await screen.findByRole("radio", { name: "Basic" }));

    await userEvent.type(
      await screen.findByLabelText("Username"),
      "foo@bar.com",
    );
    await userEvent.type(await screen.findByLabelText("Password"), "pass");

    await userEvent.click(
      await screen.findByRole("button", { name: "Create destination" }),
    );

    expect(onSubmit).toHaveBeenCalledWith(
      {
        name: "The best hook",
        description: "Really though, it's the best",
        url: "http://my-awesome-hook.com/",
        "auth-method": "header",
        "fe-form-type": "basic",
        "auth-username": "foo@bar.com",
        "auth-password": "pass",
      },
      expect.anything(),
    );
  });

  it("should show a token field when bearer auth is selected", async () => {
    const { onSubmit } = await setup({ populateForm: true });

    await userEvent.click(await screen.findByRole("radio", { name: "Bearer" }));

    await userEvent.type(
      await screen.findByLabelText("Bearer token"),
      "SecretToken",
    );
    await userEvent.click(
      await screen.findByRole("button", { name: "Create destination" }),
    );

    expect(onSubmit).toHaveBeenCalledWith(
      {
        name: "The best hook",
        description: "Really though, it's the best",
        url: "http://my-awesome-hook.com/",
        "auth-method": "header",
        "fe-form-type": "bearer",
        "auth-info-value": "SecretToken",
      },
      expect.anything(),
    );
  });

  it("should show a allow you to add a key/value pair to header or query param", async () => {
    const { onSubmit } = await setup({ populateForm: true });

    await userEvent.click(
      await screen.findByRole("radio", { name: "API Key" }),
    );
    await userEvent.click(
      await screen.findByRole("radio", { name: "Query param" }),
    );

    await userEvent.type(await screen.findByLabelText("Key"), "Foo");

    await userEvent.type(await screen.findByLabelText("Value"), "Bar");

    await userEvent.click(
      await screen.findByRole("button", { name: "Create destination" }),
    );

    expect(onSubmit).toHaveBeenCalledWith(
      {
        name: "The best hook",
        description: "Really though, it's the best",
        url: "http://my-awesome-hook.com/",
        "auth-method": "query-param",
        "fe-form-type": "api-key",
        "auth-info-key": "Foo",
        "auth-info-value": "Bar",
      },
      expect.anything(),
    );
  });

  it("should allow you to test a connection", async () => {
    jest.useFakeTimers({
      advanceTimers: true,
    });

    fetchMock.post("path:/api/channel/test", async (call) => {
      // Unjustified type cast. FIXME
      const body = JSON.parse(call.options?.body as string);
      return body.details.url?.endsWith("good") ? { ok: true } : 400;
    });

    await setup();

    await userEvent.type(
      screen.getByLabelText("Webhook URL"),
      "http://my-awesome-hook.com/bad",
    );

    await userEvent.click(screen.getByRole("button", { name: "Send a test" }));

    act(() => {
      jest.advanceTimersByTime(1000);
    });

    expect(await screen.findByText("Test failed")).toBeInTheDocument();

    act(() => jest.advanceTimersByTime(4000));

    expect(screen.getByText("Send a test")).toBeInTheDocument();

    await userEvent.clear(screen.getByLabelText("Webhook URL"));
    await userEvent.type(
      screen.getByLabelText("Webhook URL"),
      "http://my-awesome-hook.com/good",
    );

    await userEvent.click(screen.getByRole("button", { name: "Send a test" }));
    act(() => {
      jest.advanceTimersByTime(1000);
    });

    expect(await screen.findByText("Success")).toBeInTheDocument();
    act(() => jest.advanceTimersByTime(4000));
    expect(screen.getByText("Send a test")).toBeInTheDocument();
  });

  describe("Feishu group", () => {
    const FEISHU_CHAT_URL =
      "https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=chat_id&receive_id=oc_d09effb966cdbc944696268cdea5e56a";

    it("takes just the group's chat ID and submits the Feishu app's URL for it", async () => {
      const { onSubmit } = await setup();

      await userEvent.click(
        await screen.findByRole("radio", { name: "Feishu group" }),
      );
      expect(screen.queryByLabelText("Webhook URL")).not.toBeInTheDocument();
      expect(
        screen.queryByText("Authentication method"),
      ).not.toBeInTheDocument();

      await userEvent.type(
        screen.getByLabelText("Feishu group chat ID"),
        "  oc_d09effb966cdbc944696268cdea5e56a ",
      );
      await userEvent.type(screen.getByLabelText("Give it a name"), "运营群");
      await userEvent.type(screen.getByLabelText("Description"), "日报");
      await userEvent.click(
        screen.getByRole("button", { name: "Create destination" }),
      );

      expect(onSubmit).toHaveBeenCalledWith(
        {
          name: "运营群",
          description: "日报",
          url: FEISHU_CHAT_URL,
          "auth-method": "none",
          "fe-form-type": "none",
        },
        expect.anything(),
      );
    });

    it("rejects something that isn't a chat ID", async () => {
      const { onSubmit } = await setup();

      await userEvent.click(
        await screen.findByRole("radio", { name: "Feishu group" }),
      );
      await userEvent.type(
        screen.getByLabelText("Feishu group chat ID"),
        "运营群{tab}",
      );

      expect(
        await screen.findByText(
          "Enter the group's chat ID, which starts with oc_",
        ),
      ).toBeInTheDocument();
      expect(onSubmit).not.toHaveBeenCalled();
    });

    it("opens an existing Feishu group webhook with its chat ID", async () => {
      await setup({
        initialValues: {
          ...INITIAL_FORM_VALUES,
          url: FEISHU_CHAT_URL,
          name: "运营群",
          description: "日报",
        },
      });

      expect(
        await screen.findByRole("radio", { name: "Feishu group" }),
      ).toBeChecked();
      expect(screen.getByLabelText("Feishu group chat ID")).toHaveValue(
        "oc_d09effb966cdbc944696268cdea5e56a",
      );
      expect(screen.queryByLabelText("Webhook URL")).not.toBeInTheDocument();
    });

    it("keeps a Feishu-shaped URL on another host as a plain URL", async () => {
      const otherHost =
        "http://host.docker.internal:9997/open-apis/im/v1/messages?receive_id_type=chat_id&receive_id=oc_fake";
      await setup({
        initialValues: { ...INITIAL_FORM_VALUES, url: otherHost },
      });

      expect(await screen.findByLabelText("Webhook URL")).toHaveValue(
        otherHost,
      );
    });

    it("tests the group with the Feishu app's URL", async () => {
      fetchMock.post("path:/api/channel/test", { ok: true });
      await setup();

      await userEvent.click(
        await screen.findByRole("radio", { name: "Feishu group" }),
      );
      await userEvent.type(
        screen.getByLabelText("Feishu group chat ID"),
        "oc_d09effb966cdbc944696268cdea5e56a",
      );
      await userEvent.click(
        screen.getByRole("button", { name: "Send a test" }),
      );

      await waitFor(() =>
        expect(fetchMock.callHistory.called("path:/api/channel/test")).toBe(
          true,
        ),
      );
      const call = fetchMock.callHistory.lastCall("path:/api/channel/test");
      const body = await call?.request?.json();
      expect(body.details).toEqual({
        url: FEISHU_CHAT_URL,
        "auth-method": "none",
        "auth-info": {},
      });
    });
  });
});
