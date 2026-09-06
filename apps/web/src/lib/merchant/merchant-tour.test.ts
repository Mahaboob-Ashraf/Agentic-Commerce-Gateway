import assert from "node:assert/strict";
import test from "node:test";
// @ts-expect-error Node's native type-stripping runner requires the explicit extension.
import { MERCHANT_TOUR_STEPS, clearMerchantTourSeen, markMerchantTourSeen, merchantTourSeenKey, positionTourCard, selectVisibleTourTarget, shouldStartMerchantTour } from "./merchant-tour.ts";

function memoryStorage() {
  const values = new Map<string, string>();
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key),
  };
}

test("a fresh browser sees the tour once and finish or skip persists locally", () => {
  const storage = memoryStorage();
  assert.equal(shouldStartMerchantTour(storage, "actor-a"), true);
  markMerchantTourSeen(storage, "actor-a");
  assert.equal(storage.getItem(merchantTourSeenKey("actor-a")), "true");
  assert.equal(shouldStartMerchantTour(storage, "actor-a"), false);
});

test("tour presentation state is isolated per actor and replay clears only that actor", () => {
  const storage = memoryStorage();
  markMerchantTourSeen(storage, "actor-a");
  assert.equal(shouldStartMerchantTour(storage, "actor-a"), false);
  assert.equal(shouldStartMerchantTour(storage, "actor-b"), true);
  clearMerchantTourSeen(storage, "actor-a");
  assert.equal(shouldStartMerchantTour(storage, "actor-a"), true);
  assert.equal(shouldStartMerchantTour(storage, "actor-b"), true);
});

test("route-changing steps retain deterministic tour order", () => {
  assert.equal(MERCHANT_TOUR_STEPS.length, 7);
  assert.equal(MERCHANT_TOUR_STEPS[2]?.target, "agentization-nav");
  assert.equal(MERCHANT_TOUR_STEPS[2]?.route, "/merchant/overview");
  assert.equal(MERCHANT_TOUR_STEPS[3]?.route, "/merchant/agentization");
  assert.equal(MERCHANT_TOUR_STEPS[6]?.target, "manifest-nav");
});

test("temporarily missing targets fall back safely and hidden duplicates are ignored", () => {
  assert.equal(selectVisibleTourTarget([]), null);
  const target = selectVisibleTourTarget([
    { id: "hidden", rect: { top: 0, right: 0, bottom: 0, left: 0, width: 0, height: 0 } },
    { id: "visible", rect: { top: 10, right: 110, bottom: 60, left: 10, width: 100, height: 50 } },
  ]);
  assert.equal(target?.id, "visible");
  assert.deepEqual(
    positionTourCard(null, "right", { width: 390, height: 844 }, { width: 358, height: 280 }),
    { left: 16, top: 282 },
  );
});

test("tour cards remain inside narrow and desktop viewports", () => {
  const target = { top: 80, right: 380, bottom: 180, left: 20, width: 360, height: 100 };
  for (const viewport of [{ width: 390, height: 844 }, { width: 1440, height: 900 }]) {
    const card = { width: Math.min(358, viewport.width - 32), height: 260 };
    const position = positionTourCard(target, "right", viewport, card);
    assert.ok(position.left >= 16);
    assert.ok(position.top >= 16);
    assert.ok(position.left + card.width <= viewport.width - 16);
    assert.ok(position.top + card.height <= viewport.height - 16);
  }
});
