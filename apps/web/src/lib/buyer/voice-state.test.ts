import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import {
  executeProductionVoiceCommerce,
  initialVoiceSessionState,
  reduceVoiceSession,
  toAuthoritativeVoiceResult,
  VoiceToolCallGuard,
// @ts-expect-error Node's native type-stripping runner requires the explicit extension.
} from "./voice-state.ts";
import type { CommerceRequestResult } from "./types.ts";

function result(overrides: Partial<CommerceRequestResult> = {}): CommerceRequestResult {
  return {
    requestId: "request-1", threadId: "thread-1", state: "WAITING_FOR_USER", requestStatus: "COMPLETED",
    clarificationRequired: false, clarificationQuestion: null, currentIntentVersion: 1, goal: "PURCHASE_PRODUCT",
    category: "audio", budgetAmountMinor: 300000, budgetCurrency: "INR", hardRequirements: [], softPreferences: [],
    merchantId: "merchant-1", merchantDisplayName: "Amazing", catalogueVersionId: "catalogue-1", catalogueVersion: "1",
    cartId: "cart-1", cartHash: "hash", products: [{ productId: "product-1", merchantSku: "AMZ-1", productName: "Auralink Buds", brand: "Auralink", variant: "Buds", sizeStorage: null, colour: "Black", category: "audio", quantity: 1, unitAmountMinor: 299900, lineAmountMinor: 299900, facts: [] }],
    quoteRecordId: "quote-1", merchantQuoteId: "merchant-quote-1", merchantQuoteVersion: "1", subtotalMinor: 299900,
    taxMinor: 0, deliveryMinor: 0, feesMinor: 0, authoritativeFinalAmountMinor: 299900, authoritativeCurrency: "INR", quoteExpiresAt: "2099-01-01T00:00:00Z",
    availabilityRefreshId: "availability-1", availabilityOutcome: "PASS", availabilityReasonCode: null,
    serviceabilityEvidenceId: "service-1", serviceabilityOutcome: "PASS", serviceabilityReasonCode: null,
    constraintCertificateId: "certificate-1", constraintCertificateHash: "certificate-hash", constraintOverall: "PASS", constraints: [],
    transactionProposalId: "proposal-1", transactionProposalHash: "proposal-hash", proposalExpiresAt: "2099-01-01T00:00:00Z",
    riskOutcome: "EXPLICIT_CONFIRMATION", riskReasonCodes: [], explicitAuthorizationRequired: true, paymentReady: true,
    authorizationState: "WAITING_FOR_EXPLICIT_PAYMENT_AUTHORIZATION", nextAction: "AUTHORIZE_RAZORPAY_CHECKOUT",
    progress: [], evidenceReferences: [], failureCode: null,
    ...overrides,
  };
}

test("voice session reducer covers entry, interruption, commerce, authorization and exit", () => {
  let state = reduceVoiceSession(initialVoiceSessionState, { type: "CONNECT" });
  assert.equal(state.orb, "CONNECTING");
  state = reduceVoiceSession(state, { type: "CONNECTED" });
  state = reduceVoiceSession(state, { type: "MODEL_AUDIO_STARTED" });
  assert.equal(state.orb, "AGENT_SPEAKING");
  state = reduceVoiceSession(state, { type: "INTERRUPTED" });
  assert.equal(state.orb, "LISTENING");
  state = reduceVoiceSession(state, { type: "COMMERCE_STARTED" });
  assert.equal(state.orb, "COMMERCE_RUNNING");
  state = reduceVoiceSession(state, { type: "COMMERCE_FINISHED", awaitingAuthorization: true });
  assert.equal(state.orb, "AWAITING_AUTHORIZATION");
  assert.deepEqual(reduceVoiceSession(state, { type: "STOP" }), initialVoiceSessionState);
});

test("session failure fails back to text without retaining a running commerce state", () => {
  const running = reduceVoiceSession(
    reduceVoiceSession(initialVoiceSessionState, { type: "CONNECTED" }),
    { type: "COMMERCE_STARTED" },
  );
  const failed = reduceVoiceSession(running, { type: "FAIL", message: "Continue by typing" });
  assert.equal(failed.orb, "ERROR");
  assert.equal(failed.connected, false);
  assert.equal(failed.commerceRunning, false);
  assert.match(failed.error ?? "", /typing/);
});

test("production voice tool invokes the supplied real commerce boundary once", async () => {
  const guard = new VoiceToolCallGuard();
  const queries: string[] = [];
  const execute = async (query: string) => { queries.push(query); return result(); };
  assert.equal((await executeProductionVoiceCommerce("call-1", " Find Auralink Buds ", guard, execute))?.requestId, "request-1");
  assert.equal(await executeProductionVoiceCommerce("call-1", "Find Auralink Buds", guard, execute), null);
  assert.deepEqual(queries, ["Find Auralink Buds"]);
});

test("authoritative narration event preserves backend facts and cannot authorize spending", () => {
  const event = toAuthoritativeVoiceResult(result());
  assert.equal(event.products[0]?.name, "Auralink Buds");
  assert.equal(event.amount.finalAmountMinor, 299900);
  assert.equal(event.proposal?.authorizationBoundary, "ON_SCREEN_ONLY");
  assert.equal(event.paymentState, "NOT_STARTED");
  assert.equal("authorizationDecision" in event, false);
});

test("uncertain result remains clarification-only and has no authorization surface", () => {
  const event = toAuthoritativeVoiceResult(result({
    clarificationRequired: true,
    clarificationQuestion: "Which model did you mean?",
    products: [],
    transactionProposalId: null,
    paymentReady: false,
    riskOutcome: "CLARIFY",
  }));
  assert.equal(event.clarificationRequired, true);
  assert.equal(event.proposal, null);
  assert.equal(event.verification.risk, "CLARIFY");
});

test("production chat lazy-loads voice and production files contain no lab fake-commerce path", () => {
  const chat = readFileSync(new URL("../../components/buyer/commerce-chat.tsx", import.meta.url), "utf8");
  const productionVoice = readFileSync(new URL("../../components/buyer/buyer-voice.tsx", import.meta.url), "utf8");
  assert.match(chat, /lazy\(\(\) => import\("\.\/buyer-voice"\)/);
  assert.doesNotMatch(productionVoice, /FakeCommerceResult|runFakeCommerce|Demo Protein Snack/);
  assert.match(productionVoice, /buyerApi\.createVoiceToken/);
  assert.match(chat, /buyerApi\.createCommerceRequest/);
});
