import type { MerchantAccess, MerchantSetup } from "./types";

const AMAZING_DEMO_ADMIN_IDENTITY = "demo-amazing-admin@agentic-commerce.invalid";
const AMAZING_MERCHANT_KEY = "amazing";
const AMAZING_MERCHANT_NAME = "amazing";

export function isAmazingDemoActor(
  authenticatedIdentity: string | undefined,
  configuredDemoIdentity: string | undefined,
): boolean {
  const actor = authenticatedIdentity?.trim().toLowerCase();
  const configured = configuredDemoIdentity?.trim().toLowerCase();
  return Boolean(
    actor
    && configured === AMAZING_DEMO_ADMIN_IDENTITY
    && actor === configured,
  );
}

export function resolveAmazingDemoMerchant(
  merchants: readonly MerchantAccess[] | null | undefined,
): MerchantAccess | null {
  if (!merchants) return null;
  const matches = merchants.filter((merchant) => (
    typeof merchant?.merchantId === "string"
    && merchant.merchantId.trim().length > 0
    && typeof merchant.merchantKey === "string"
    && merchant.merchantKey.trim().toLowerCase() === AMAZING_MERCHANT_KEY
    && typeof merchant.displayName === "string"
    && merchant.displayName.trim().toLowerCase() === AMAZING_MERCHANT_NAME
  ));
  return matches.length === 1 ? matches[0] : null;
}

export function applyAmazingDemoPreset(
  setup: MerchantSetup,
  merchant: MerchantAccess,
): MerchantSetup {
  return {
    ...setup,
    merchantId: merchant.merchantId,
    store: { ...setup.store, name: merchant.displayName },
    status: "DRAFT",
  };
}
