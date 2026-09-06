import type { MerchantSetup } from "./types";

const STORAGE_PREFIX = "amana.merchant.setup.v1";

export function emptyMerchantSetup(): MerchantSetup {
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

function storageKey(actorId: string) {
  return `${STORAGE_PREFIX}.${actorId}`;
}

export function loadMerchantSetup(actorId: string): MerchantSetup {
  if (typeof window === "undefined") return emptyMerchantSetup();
  try {
    const stored = window.localStorage.getItem(storageKey(actorId));
    if (!stored) return emptyMerchantSetup();
    const parsed = JSON.parse(stored) as MerchantSetup;
    if (parsed.version !== 1) return emptyMerchantSetup();
    return { ...parsed, merchantId: parsed.merchantId ?? null };
  } catch {
    return emptyMerchantSetup();
  }
}

export function saveMerchantSetup(actorId: string, setup: MerchantSetup): MerchantSetup {
  const next = { ...setup, updatedAt: new Date().toISOString() };
  window.localStorage.setItem(storageKey(actorId), JSON.stringify(next));
  return next;
}

export function hasApprovedSource(setup: MerchantSetup) {
  return Boolean(
    setup.sources.openApiReference.trim() ||
      setup.sources.catalogueReference.trim() ||
      setup.sources.policyReference.trim(),
  );
}

export function setupIsReviewable(setup: MerchantSetup) {
  return Boolean(
    setup.store.name.trim() &&
      setup.store.category.trim() &&
      hasApprovedSource(setup) &&
      setup.connection.approvedEndpoint.trim(),
  );
}
