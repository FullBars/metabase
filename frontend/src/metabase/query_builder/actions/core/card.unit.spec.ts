import { waitFor } from "@testing-library/react";
import fetchMock from "fetch-mock";

import { getStore } from "__support__/entities-store";
import { Api, cardApi } from "metabase/api";
import type { Dispatch } from "metabase/redux/store";
import { createMockCard } from "metabase-types/api/mocks";

import { loadCard } from "./card";

const CARD_ID = 1;
const OLD_CARD = createMockCard({ id: CARD_ID, name: "Before the save" });
const SAVED_CARD = createMockCard({ id: CARD_ID, name: "After the save" });

describe("loadCard", () => {
  let activeStore: ReturnType<typeof setup>["store"] | undefined;

  afterEach(() => {
    // Abort the running query before removing its fetch route.
    activeStore?.dispatch(Api.util.resetApiState());
    activeStore = undefined;
    fetchMock.removeRoutes().clearHistory();
  });

  it("returns the saved version when a save's invalidation is held back by running queries", async () => {
    const { store, load } = setup();
    activeStore = store;

    let serverCard = OLD_CARD;
    fetchMock.get(`path:/api/card/${CARD_ID}`, () => serverCard);
    fetchMock.put(`path:/api/card/${CARD_ID}`, () => {
      serverCard = SAVED_CARD;
      return serverCard;
    });
    fetchMock.post(`path:/api/card/${CARD_ID}/query`, new Promise(() => {}));

    expect(await load()).toMatchObject({ name: OLD_CARD.name });

    // Two, because RTK Query's pending-request count drops by one on every cache
    // hit, so a single running query stops holding back the invalidation on load.
    // The dashboardId only gives the second query its own cache entry.
    store.dispatch(
      cardApi.endpoints.getCardQuery.initiate({ cardId: CARD_ID }),
    );
    store.dispatch(
      cardApi.endpoints.getCardQuery.initiate({
        cardId: CARD_ID,
        dashboardId: 1,
      }),
    );
    await store.dispatch(
      cardApi.endpoints.updateCard.initiate({
        id: CARD_ID,
        name: SAVED_CARD.name,
      }),
    );

    await waitFor(() =>
      expect(
        cardApi.endpoints.getCard.select({ id: CARD_ID })(store.getState())
          .data,
      ).toMatchObject({ name: SAVED_CARD.name }),
    );

    expect(await load()).toMatchObject({ name: SAVED_CARD.name });
    expect(
      fetchMock.callHistory.calls(`path:/api/card/${CARD_ID}`, {
        method: "GET",
      }),
    ).toHaveLength(1);
  });
});

function setup() {
  const store = getStore({ [Api.reducerPath]: Api.reducer }, {}, [
    Api.middleware,
  ]);
  // getStore's middleware typing loses the thunk overload, but the API middleware handles thunks.
  const dispatch = store.dispatch as Dispatch;
  const load = () =>
    loadCard({ cardId: CARD_ID }, { dispatch, getState: store.getState });

  return { store, load };
}
