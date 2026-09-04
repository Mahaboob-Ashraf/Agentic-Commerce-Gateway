import assert from "node:assert/strict";
import test from "node:test";
// @ts-expect-error Node's native type-stripping runner requires the explicit extension.
import { deriveJourneySnapshot, hasSafetyCriticalUnknown, SingleFlightGate } from "./commerce-state.ts";
import type { CommerceRequestResult, FulfillmentView, PaymentStateView } from "./types.ts";

const future = "2099-01-01T00:00:00Z";
const result = (overrides: Partial<CommerceRequestResult> = {}): CommerceRequestResult => ({
  requestId: "request", threadId: "thread", state: "WAITING_FOR_USER", requestStatus: "COMPLETED",
  clarificationRequired: false, clarificationQuestion: null, currentIntentVersion: 1, goal: "PURCHASE_PRODUCT",
  category: "audio", budgetAmountMinor: null, budgetCurrency: "INR", hardRequirements: [], softPreferences: [],
  merchantId: "merchant", merchantDisplayName: "Amazing", catalogueVersionId: "catalogue", catalogueVersion: "1",
  cartId: "cart", cartHash: "hash", products: [{ productId: "product", merchantSku: "SKU", productName: "Product", brand: "Brand", variant: "Model", sizeStorage: null, colour: null, category: "audio", quantity: 1, unitAmountMinor: 100, lineAmountMinor: 100, facts: [] }],
  quoteRecordId: "quote", merchantQuoteId: "merchant-quote", merchantQuoteVersion: "1", subtotalMinor: 100,
  taxMinor: 0, deliveryMinor: 0, feesMinor: 0, authoritativeFinalAmountMinor: 100, authoritativeCurrency: "INR", quoteExpiresAt: future,
  availabilityRefreshId: "availability", availabilityOutcome: "PASS", availabilityReasonCode: null,
  serviceabilityEvidenceId: "service", serviceabilityOutcome: "PASS", serviceabilityReasonCode: null,
  constraintCertificateId: "certificate", constraintCertificateHash: "certificate-hash", constraintOverall: "PASS", constraints: [],
  transactionProposalId: "proposal", transactionProposalHash: "proposal-hash", proposalExpiresAt: future,
  riskOutcome: "EXPLICIT_CONFIRMATION", riskReasonCodes: [], explicitAuthorizationRequired: true, paymentReady: true,
  authorizationState: "WAITING_FOR_EXPLICIT_PAYMENT_AUTHORIZATION", nextAction: "AUTHORIZE_RAZORPAY_CHECKOUT",
  progress: [], evidenceReferences: [], failureCode: null, ...overrides,
});

const payment = (paymentState: PaymentStateView["paymentState"]): PaymentStateView => ({
  executionId: "execution", proposalId: "proposal", paymentState, reasonCode: null, providerOrderId: "order",
  confirmedPaymentId: paymentState === "PAYMENT_CONFIRMED" ? "payment" : null, amountMinor: 100, currency: "INR",
  fulfillmentState: "PENDING", merchantOrderId: null, reconciliationAttempts: 1, reconciliationMaximumAttempts: 4, updatedAt: future,
});

test("no exact match remains a no-match clarification", () => {
  assert.equal(deriveJourneySnapshot(result({ products: [], transactionProposalId: null, paymentReady: false, clarificationRequired: true }), null, false, false, null, null).phase, "NO_MATCH");
});

test("a PASS proposal exposes explicit authorization", () => {
  assert.equal(deriveJourneySnapshot(result(), null, false, false, null, null).canAuthorize, true);
});

test("safety-critical UNKNOWN blocks purchase", () => {
  const unsafe = result({ constraints: [{ key: "allergen", result: "UNKNOWN", safetyCritical: true, normalizedRequirement: null, evidenceReferences: [] }] });
  assert.equal(hasSafetyCriticalUnknown(unsafe), true);
  assert.equal(deriveJourneySnapshot(unsafe, null, false, false, null, null).phase, "BLOCKED");
});

test("authorization plus prepared checkout permits opening only that checkout", () => {
  const authorization = { authorizationId: "authorization", proposalId: "proposal", proposalHash: "proposal-hash", decision: "AUTHORIZED" as const, authorizationMethod: "EXPLICIT_CONFIRMATION", issuedAt: future, expiresAt: future, consumedAt: null, consumedByExecutionId: null };
  assert.equal(deriveJourneySnapshot(result(), authorization, true, false, null, null).canOpenCheckout, true);
});

test("browser callback evidence is not payment confirmation", () => {
  const submitted = deriveJourneySnapshot(result(), null, true, true, payment("PAYMENT_PENDING"), null);
  assert.equal(submitted.phase, "PAYMENT_SUBMITTED");
  assert.equal(submitted.canOpenCheckout, false);
});

test("uncertain, confirmed and fulfilled backend states remain distinct", () => {
  assert.equal(deriveJourneySnapshot(result(), null, true, true, payment("PAYMENT_UNCERTAIN"), null).phase, "PAYMENT_UNCERTAIN");
  assert.equal(deriveJourneySnapshot(result(), null, true, true, payment("PAYMENT_CONFIRMED"), null).phase, "FINALIZING");
  const fulfilled: FulfillmentView = { executionId: "execution", paymentState: "PAYMENT_CONFIRMED", fulfillmentState: "FULFILLED", merchantOperationId: "operation", merchantOrderId: "merchant-order", attemptCount: 1, lastErrorCode: null };
  assert.equal(deriveJourneySnapshot(result(), null, true, true, payment("PAYMENT_CONFIRMED"), fulfilled).phase, "FULFILLED");
});

test("single-flight guard rejects rapid duplicate actions and releases cleanly", () => {
  const gate = new SingleFlightGate();
  assert.equal(gate.enter("authorize"), true);
  assert.equal(gate.enter("authorize"), false);
  gate.leave("authorize");
  assert.equal(gate.enter("authorize"), true);
});

test("refresh reconstruction derives the same authoritative uncertain state", () => {
  const first = deriveJourneySnapshot(result(), null, true, false, payment("PAYMENT_UNCERTAIN"), null);
  const reconstructed = deriveJourneySnapshot(result(), null, true, false, payment("PAYMENT_UNCERTAIN"), null);
  assert.deepEqual(reconstructed, first);
});
