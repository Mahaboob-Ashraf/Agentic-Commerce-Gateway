import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
// @ts-expect-error Node's native type-stripping runner requires the explicit extension.
import { buildFailureLabEvidence, type FailureLabReport } from "./failure-lab-evidence.ts";

const page = readFileSync(new URL("../../app/failure-lab/page.tsx", import.meta.url), "utf8");
const experience = readFileSync(
  new URL("../../app/failure-lab/failure-lab-experience.tsx", import.meta.url),
  "utf8",
);
const styles = readFileSync(
  new URL("../../app/failure-lab/failure-lab.module.css", import.meta.url),
  "utf8",
);
const backendFixture = readFileSync(
  new URL(
    "../../../../backend/src/test/java/dev/agenticcommerce/gateway/Task010PaymentControlIntegrationTest.java",
    import.meta.url,
  ),
  "utf8",
);
const report = JSON.parse(
  readFileSync(new URL("../../../../../proof/results/failure-lab.json", import.meta.url), "utf8"),
) as FailureLabReport;
const evidence = buildFailureLabEvidence(report);

test("failure-lab route renders the reviewer-facing recovery story", () => {
  assert.match(page, /export default function FailureLabPage/);
  assert.match(page, /What if Razorpay creates the order/);
  assert.match(page, /unknown create-order outcome must be reconciled/);
  assert.match(page, /<FailureLabExperience evidence={evidence} \/>/);
});

test("generated evidence represents an authentic ambiguous side effect", () => {
  assert.equal(evidence.providerSideEffectCreatedOrder, true);
  assert.equal(evidence.responseDelivery, "LOST");
  assert.equal(evidence.exceptionCategory, "TIMEOUT");
  assert.equal(evidence.requestMayHaveReachedProvider, true);
  assert.equal(evidence.uncertainAttemptOutcome, "UNCERTAIN");
  assert.equal(evidence.uncertainPaymentState, "PAYMENT_UNCERTAIN");
});

test("retry cannot create a second provider order", () => {
  assert.equal(evidence.executionReservations, 1);
  assert.equal(evidence.providerCreateCalls, 1);
  assert.equal(evidence.providerOrdersObserved, 1);
  assert.equal(evidence.persistedProviderOrders, 1);
  assert.equal(evidence.duplicateProviderOrders, 0);
  assert.match(experience, /Blind retry refused/);
});

test("reconciliation finds and binds the original provider order", () => {
  assert.equal(evidence.recoveredByReconciliation, true);
  assert.equal(evidence.reconciliationAttempts, 1);
  assert.match(evidence.providerOrderIdRecovered, /^order_/);
  assert.equal(evidence.finalExecutionState, "PAYMENT_PENDING");
  assert.match(experience, /Existing order found/);
  assert.match(experience, /validated and bound to the execution/);
});

test("order recovery never claims payment confirmation", () => {
  assert.equal(evidence.paymentConfirmed, false);
  assert.equal(evidence.finalPaymentState, "PAYMENT_UNCERTAIN");
  assert.match(page, /Recovered order existence is not captured-payment evidence/);
  assert.match(page, /does not claim <code>PAYMENT_CONFIRMED<\/code>/);
  assert.doesNotMatch(page, /payment (?:is|becomes) confirmed/i);
});

test("the replay is generated evidence rather than a fake live execution", () => {
  assert.match(experience, /Replay failure trace/);
  assert.doesNotMatch(experience, /Run failure simulation|Inject lost provider response/);
  assert.match(experience, /No live provider call is performed/);
  assert.match(page, /It replays one passing isolated fixture; it does not run when the button is clicked/);
});

test("the fixture is isolated from Razorpay and deployed payment state", () => {
  assert.equal(evidence.providerBoundary, "INERT_TEST_ADAPTER");
  assert.equal(evidence.canCallRealRazorpay, false);
  assert.equal(evidence.deployedStateTouched, false);
  assert.match(evidence.providerAdapter, /TestPaymentProvider$/);
  assert.match(page, /No real Razorpay call is possible from this page/);
  assert.match(backendFixture, /@Bean @Primary TestPaymentProvider paymentProvider\(\)/);
});

test("the artifact is emitted by the production-service integration fixture", () => {
  for (const service of [
    "ExecutionGate",
    "PaymentControlService.initiate",
    "PaymentControlService.reconcile",
    "PaymentRepository",
    "PaymentEvidenceReducer",
  ]) {
    assert.ok(evidence.productionLogic.includes(service));
  }
  assert.match(backendFixture, /EvidenceSupport\.write\(mapper, "failure-lab\.json"/);
  assert.match(backendFixture, /payments\.reconcile\(/);
  assert.match(backendFixture, /provider\.createCalls\)\.hasValue\(1\)/);
});

test("non-passing or provider-capable evidence fails closed", () => {
  assert.throws(
    () => buildFailureLabEvidence({ ...report, summary: { ...report.summary, duplicateProviderOrders: 1 } }),
    /does not prove the unknown-order recovery invariant/,
  );
  assert.throws(
    () => buildFailureLabEvidence({ ...report, details: { ...report.details, canCallRealRazorpay: true } }),
    /does not prove the unknown-order recovery invariant/,
  );
});

test("navigation links all reviewer surfaces", () => {
  for (const destination of ["/architecture", "/security", "/proof"]) {
    assert.match(page, new RegExp(`href="${destination.replace("/", "\\/")}"`));
  }
});

test("mobile flow is vertical and replay respects reduced motion", () => {
  assert.match(styles, /@media \(max-width: 38rem\)/);
  assert.match(styles, /\.timeline \{\s*grid-template-columns: 1fr;/);
  assert.doesNotMatch(styles, /overflow-x:\s*auto/);
  assert.match(styles, /@media \(prefers-reduced-motion: reduce\)/);
  assert.match(experience, /matchMedia\("\(prefers-reduced-motion: reduce\)"\)/);
});
