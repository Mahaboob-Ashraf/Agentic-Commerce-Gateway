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
    merchantId: "merchant-1", merchantDisplayName: "Amazing", merchantLogoUrl: "/amana/merchant/amazing.png", catalogueVersionId: "catalogue-1", catalogueVersion: "1",
    cartId: "cart-1", cartHash: "hash", products: [{ productId: "product-1", merchantSku: "AMZ-1", productName: "Auralink Buds", brand: "Auralink", variant: "Buds", sizeStorage: null, colour: "Black", category: "audio", quantity: 1, unitAmountMinor: 299900, lineAmountMinor: 299900, facts: [] }],
    quoteRecordId: "quote-1", merchantQuoteId: "merchant-quote-1", merchantQuoteVersion: "1", subtotalMinor: 299900,
    taxMinor: 0, deliveryMinor: 0, feesMinor: 0, authoritativeFinalAmountMinor: 299900, authoritativeCurrency: "INR", quoteExpiresAt: "2099-01-01T00:00:00Z",
    availabilityRefreshId: "availability-1", availabilityOutcome: "PASS", availabilityReasonCode: null,
    serviceabilityEvidenceId: "service-1", serviceabilityOutcome: "PASS", serviceabilityReasonCode: null,
    constraintCertificateId: "certificate-1", constraintCertificateHash: "certificate-hash", constraintOverall: "PASS", constraints: [],
    transactionProposalId: "proposal-1", transactionProposalHash: "proposal-hash", proposalExpiresAt: "2099-01-01T00:00:00Z",
    riskOutcome: "EXPLICIT_CONFIRMATION", riskReasonCodes: [], explicitAuthorizationRequired: true, paymentReady: true,
    authorizationState: "WAITING_FOR_EXPLICIT_PAYMENT_AUTHORIZATION", nextAction: "AUTHORIZE_RAZORPAY_CHECKOUT",
    progress: [], evidenceReferences: [], failureCode: null, visualObservation: null, visualMatchType: null, visualMatchReasons: [],
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
  assert.equal(state.orb, "THINKING");
  assert.equal(state.connected, true);
  state = reduceVoiceSession(state, { type: "MODEL_AUDIO_STARTED" });
  state = reduceVoiceSession(state, { type: "MODEL_AUDIO_ENDED" });
  assert.equal(state.orb, "LISTENING");
  assert.equal(state.connected, true);
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

test("a corrected voice turn uses its new payload and remains independently bounded", async () => {
  const guard = new VoiceToolCallGuard();
  const queries: string[] = [];
  const execute = async (query: string) => { queries.push(query); return result({ requestId: `request-${queries.length}` }); };

  await executeProductionVoiceCommerce("call-1", "Ora Link buds bluetooth earphones", guard, execute);
  const corrected = await executeProductionVoiceCommerce(
    "call-2",
    "AURALINK no space in between, Auralink Buds Bluetooth Earphones",
    guard,
    execute,
  );

  assert.equal(corrected?.requestId, "request-2");
  assert.deepEqual(queries, [
    "Ora Link buds bluetooth earphones",
    "AURALINK no space in between, Auralink Buds Bluetooth Earphones",
  ]);
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
  assert.match(chat, /threadIdRef\.current/);
  assert.doesNotMatch(chat, /window\.history\.replaceState/);
  assert.match(chat, /inputModeRef\.current\s*!==\s*"VOICE"\) router\.replace/);
  assert.match(chat, /if \(!initialThreadId&&activeThreadId\) router\.replace/);
  assert.match(chat, /await activeCommerceCompletion\.current/);
  assert.doesNotMatch(productionVoice, /closeSocket\([^)]*commerce/i);
});

test("commerce results cannot stabilize the thread route until the active Live session is explicitly closed", () => {
  const chat = readFileSync(new URL("../../components/buyer/commerce-chat.tsx", import.meta.url), "utf8");
  const resultUpdate = chat.indexOf("setThreadId(next.threadId)");
  const voiceGuard = chat.indexOf('if (inputModeRef.current !== "VOICE") router.replace', resultUpdate);
  const voicePanel = chat.indexOf("<BuyerVoice", voiceGuard);
  const closeThenRoute = chat.indexOf("setInputMode((current) => reduceBuyerInputMode(current, \"TEXT_SELECTED\"))", voicePanel);
  const delayedRoute = chat.indexOf("router.replace(`/buyer/chat/${activeThreadId}`)", closeThenRoute);
  assert.ok(resultUpdate >= 0 && voiceGuard > resultUpdate);
  assert.ok(closeThenRoute > voicePanel && delayedRoute > closeThenRoute);
});

test("completed application result is injected into the same Live socket for narration", () => {
  const productionVoice = readFileSync(new URL("../../components/buyer/buyer-voice.tsx", import.meta.url), "utf8");
  assert.match(productionVoice, /sendClientContent\([\s\S]*APP_COMMERCE_RESULT/);
  assert.match(productionVoice, /turnComplete:\s*true/);
  assert.match(productionVoice, /scheduling:\s*"SILENT"/);
  assert.match(productionVoice, /message\.toolCall\?\.functionCalls/);
  assert.doesNotMatch(productionVoice, /match\([^\n]*start_commerce_request|exec\([^\n]*start_commerce_request/);
});

test("production voice prepares local media before minting its short-lived token", () => {
  const productionVoice = readFileSync(new URL("../../components/buyer/buyer-voice.tsx", import.meta.url), "utf8");
  const lab = readFileSync(new URL("../../app/labs/gemini-live/gemini-live-lab.tsx", import.meta.url), "utf8");
  const runtime = readFileSync(new URL("../gemini-live/runtime.ts", import.meta.url), "utf8");
  const microphone = runtime.indexOf("navigator.mediaDevices.getUserMedia");
  const playback = runtime.indexOf("player = await PcmAudioPlayer.create");
  const token = runtime.indexOf("const token = await options.acquireToken");
  const socket = runtime.indexOf("socket = await GeminiLiveSocket.connect");
  assert.ok(microphone >= 0 && playback > microphone && token > playback && socket > token);
  assert.match(productionVoice, /startGeminiLiveRuntime/);
  assert.match(lab, /startGeminiLiveRuntime/);
  assert.match(runtime, /first-audio-frame/);
  assert.match(productionVoice, /onDiagnostic/);
});

test("Buyer voice session proof delegates masked CSRF validation to Spring", () => {
  const tokenRoute = readFileSync(new URL("../../app/api/buyer-voice/token/route.ts", import.meta.url), "utf8");
  assert.match(tokenRoute, /\/api\/auth\/buyer-session-validation/);
  assert.doesNotMatch(tokenRoute, /timingSafeEqual|equalSecret/);
});

test("commerce grid pins the stream and compact composer to explicit rows", () => {
  const css = readFileSync(new URL("../../components/buyer/workspace.module.css", import.meta.url), "utf8");
  assert.match(css, /\.chatStream[^}]*grid-row:\s*2/);
  assert.match(css, /\.commerceComposer[^}]*height:\s*fit-content[^}]*grid-row:\s*3[^}]*align-self:\s*end/);
});
