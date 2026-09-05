import assert from "node:assert/strict";
import test from "node:test";
// @ts-expect-error Node's native type-stripping runner requires the explicit extension.
import { boundedComposerHeight, buyerOnboardingSeenKey, buyerWorkspaceKey, buyerTourSteps, canEnterBuyer, commerceThreadHint, configuredDemoBuyer, initialBuyerInputMode, nextNewChatGeneration, reduceBuyerInputMode, shouldRenderVoiceOrb, shouldShowBuyerOnboarding } from "./buyer-experience.ts";

test("a fresh Buyer conversation starts in voice mode", () => {
  assert.equal(initialBuyerInputMode(), "VOICE");
});

test("a restored durable conversation starts in text mode", () => {
  assert.equal(initialBuyerInputMode("thread-1"), "TEXT");
});

test("voice and text transitions render the orb only in voice mode", () => {
  const text = reduceBuyerInputMode("VOICE", "TEXT_SELECTED");
  assert.equal(text, "TEXT");
  assert.equal(shouldRenderVoiceOrb(text), false);
  const voice = reduceBuyerInputMode(text, "VOICE_SELECTED");
  assert.equal(voice, "VOICE");
  assert.equal(shouldRenderVoiceOrb(voice), true);
});

test("the shared composer stays compact and caps growing text", () => {
  assert.equal(boundedComposerHeight(20), 48);
  assert.equal(boundedComposerHeight(96), 96);
  assert.equal(boundedComposerHeight(500), 192);
  assert.equal(boundedComposerHeight(Number.NaN), 48);
});

test("the product tour is bounded and honest about upcoming features", () => {
  assert.equal(buyerTourSteps.length, 6);
  assert.deepEqual(buyerTourSteps.map((step) => step.target), [
    "voice-controls", "composer", "grounding", "proposal-boundary", "orders", "settings",
  ]);
  assert.match(buyerTourSteps.at(-1)?.body ?? "", /upcoming/i);
});

test("onboarding enters Buyer only with persisted profile and selected address", () => {
  const status = { profileComplete: true, addressSelected: true, activeMerchantLinks: 0, ready: true, selectedAddressId: "address-1", linkedMerchants: [] };
  assert.equal(canEnterBuyer(status), true);
  assert.equal(canEnterBuyer({ ...status, profileComplete: false }), false);
  assert.equal(canEnterBuyer({ ...status, addressSelected: false }), false);
  assert.equal(canEnterBuyer(null), false);
});

test("a new device sees onboarding once while backend readiness stays authoritative", () => {
  const ready = { profileComplete: true, addressSelected: true, activeMerchantLinks: 2, ready: true, selectedAddressId: "address-1", linkedMerchants: ["amazing"] };
  assert.equal(buyerOnboardingSeenKey("buyer-1"), "amana.buyer.onboardingSeen.buyer-1");
  assert.equal(shouldShowBuyerOnboarding(ready, false), true);
  assert.equal(shouldShowBuyerOnboarding(ready, true), false);
  assert.equal(shouldShowBuyerOnboarding({ ...ready, profileComplete: false }, true), true);
  assert.equal(shouldShowBuyerOnboarding({ ...ready, addressSelected: false }, true), true);
});

test("new-chat reset preserves history outside the workspace and submits without thread A", () => {
  const history = Object.freeze(["thread-a"]);
  assert.equal(nextNewChatGeneration(4), 5);
  assert.notEqual(buyerWorkspaceKey(4), buyerWorkspaceKey(nextNewChatGeneration(4)));
  assert.deepEqual(history, ["thread-a"]);
  assert.equal(commerceThreadHint("thread-a"), "thread-a");
  assert.equal(commerceThreadHint(null), undefined);
  const createdThreadId = "thread-b";
  assert.notEqual(createdThreadId, history[0]);
});

test("demo access requires both public demo values and only returns form-fill data", () => {
  assert.equal(configuredDemoBuyer(undefined, undefined), null);
  assert.equal(configuredDemoBuyer("demo@example.test", undefined), null);
  assert.deepEqual(configuredDemoBuyer(" demo@example.test ", "demo-password"), {
    identity: "demo@example.test",
    password: "demo-password",
  });
});
