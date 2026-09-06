import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
// @ts-expect-error Node's native type-stripping runner requires the explicit extension.
import { applyAmazingDemoPreset, isAmazingDemoActor, resolveAmazingDemoMerchant } from "./amazing-demo.ts";
// @ts-expect-error Node's native type-stripping runner requires the explicit extension.
import { MERCHANT_TOUR_STEPS, clearMerchantTourSeen, markMerchantTourSeen, merchantTourSeenKey, positionTourCard, selectVisibleTourTarget, shouldStartMerchantTour } from "./merchant-tour.ts";
import type { MerchantAccess, MerchantSetup } from "./types";

const onboardingSource = readFileSync(new URL("../../components/merchant/merchant-onboarding.tsx", import.meta.url), "utf8");
const merchantLayoutSource = readFileSync(new URL("../../app/merchant/layout.tsx", import.meta.url), "utf8");
const merchantSessionSource = readFileSync(new URL("../../components/merchant/merchant-session.tsx", import.meta.url), "utf8");

function access(merchantId: string, merchantKey: string, displayName: string): MerchantAccess {
  return { merchantId, merchantKey, displayName };
}

function setup(): MerchantSetup {
  return {
    version: 1,
    merchantId: null,
    store: { name: "", category: "", baseUrl: "" },
    sources: { openApiReference: "", catalogueReference: "", policyReference: "" },
    connection: { approvedEndpoint: "", credentialReference: "" },
    status: "DRAFT",
    updatedAt: new Date(0).toISOString(),
  };
}

test("a unique authorized Amazing merchant resolves regardless of ordering", () => {
  const amazing = access("merchant-amazing", "amazing", "Amazing");
  const random = access("merchant-random", "random", "Random Store");
  assert.equal(resolveAmazingDemoMerchant([amazing, random]), amazing);
  assert.equal(resolveAmazingDemoMerchant([random, amazing]), amazing);
});

test("missing or malformed Amazing access fails closed", () => {
  assert.equal(resolveAmazingDemoMerchant(undefined), null);
  assert.equal(resolveAmazingDemoMerchant([]), null);
  assert.equal(resolveAmazingDemoMerchant([
    access("", "amazing", "Amazing"),
    access("merchant-lookalike", "not-amazing", "Amazing"),
  ]), null);
});

test("duplicate Amazing records are ambiguous and fail closed", () => {
  assert.equal(resolveAmazingDemoMerchant([
    access("merchant-amazing-a", "amazing", "Amazing"),
    access("merchant-amazing-b", "amazing", "Amazing"),
  ]), null);
});

test("random merchants never become the Amazing fallback", () => {
  assert.equal(resolveAmazingDemoMerchant([
    access("merchant-test", "test-store", "Test Store"),
    access("merchant-abc", "abc", "ABC Merchant"),
    access("merchant-random", "random", "Random Store"),
  ]), null);
});

test("the preset is restricted to the configured canonical demo actor", () => {
  const canonical = "demo-amazing-admin@agentic-commerce.invalid";
  assert.equal(isAmazingDemoActor(canonical, canonical), true);
  assert.equal(isAmazingDemoActor(canonical, undefined), false);
  assert.equal(isAmazingDemoActor("another-admin@example.test", canonical), false);
  assert.equal(isAmazingDemoActor(canonical, "another-admin@example.test"), false);
});

test("the preset copies only grounded merchant identity into local presentation state", () => {
  const current = setup();
  const amazing = access("merchant-amazing", "amazing", "Amazing");
  assert.deepEqual(applyAmazingDemoPreset(current, amazing), {
    ...current,
    merchantId: "merchant-amazing",
    store: { ...current.store, name: "Amazing" },
  });
});

test("demo load is explicit, fill-only, and demo continue selects existing Amazing", () => {
  assert.match(onboardingSource, />Load Amazing demo setup<\/AmanaButton>/);
  assert.match(onboardingSource, /onClick=\{loadAmazingDemoSetup\}[\s\S]*?type="button"/);
  const loadStart = onboardingSource.indexOf("function loadAmazingDemoSetup()");
  const loadEnd = onboardingSource.indexOf("function validateCurrent()", loadStart);
  const loadHandler = onboardingSource.slice(loadStart, loadEnd);
  assert.doesNotMatch(loadHandler, /selectMerchant|persist\(|saveMerchantSetup|merchantApi|router\.push|localStorage|sessionStorage|document\.cookie/);
  assert.match(onboardingSource, /if \(amazingDemoSelected\)[\s\S]*?resolveAmazingDemoMerchant\(merchants\)[\s\S]*?selectMerchant\(amazing\.merchantId\)[\s\S]*?router\.push\("\/merchant\/overview"\)/);
  assert.match(onboardingSource, /Amazing demo merchant is unavailable\. Please verify demo setup\./);
});

test("manual onboarding and shared authorized merchant context remain intact", () => {
  assert.match(onboardingSource, /if \(!validateCurrent\(\)\) return;\s*let nextSetup = persist\(setup\)/);
  assert.match(onboardingSource, /const authorizedSetup = selectedMerchant[\s\S]*?merchantId: selectedMerchant\.merchantId/);
  assert.match(merchantSessionSource, /const selected = merchants\.find\(\(merchant\) => merchant\.merchantId === merchantId\)/);
  assert.match(merchantSessionSource, /setSelectedMerchant\(selected\)/);
  assert.match(merchantLayoutSource, /<MerchantSessionProvider>\{children\}<\/MerchantSessionProvider>/);
});

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
