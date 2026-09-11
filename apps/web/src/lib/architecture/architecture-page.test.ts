import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const architecturePage = readFileSync(
  new URL("../../app/architecture/page.tsx", import.meta.url),
  "utf8",
);
const architectureStyles = readFileSync(
  new URL("../../app/architecture/architecture.module.css", import.meta.url),
  "utf8",
);

test("architecture route renders the reviewer-facing system view", () => {
  assert.match(architecturePage, /export default function ArchitecturePage/);
  assert.match(architecturePage, /30-second system view/);
  assert.match(architecturePage, /AI can reason about commerce/);
});

test("architecture identifies exactly the two P0 runtime commerce agents", () => {
  const agentCards = architecturePage.match(/className={styles\.agentCard}/g) ?? [];
  assert.equal(agentCards.length, 2);
  assert.match(architecturePage, /<h3>Safe AI Buyer<\/h3>/);
  assert.match(architecturePage, /<h3>Merchant Agentization Agent<\/h3>/);
  assert.match(architecturePage, /TWO P0 RUNTIME COMMERCE AGENTS/);
});

test("deterministic control plane and trust boundary are explicit", () => {
  assert.match(architecturePage, /Deterministic Commerce Control Plane/);
  assert.match(architecturePage, /TRUST BOUNDARY/);
  assert.match(architecturePage, /AI output becomes input, never authority/);
  assert.match(architecturePage, /Gemini feeds bounded untrusted application input into the deterministic commerce control plane/);
  assert.match(architecturePage, /data-flow-edge="gemini-control-plane"/);
  assert.match(architecturePage, /Only the\s+Execution Gate can advance to Razorpay/);
});

test("both agents and merchant authority explicitly feed the control plane", () => {
  for (const edge of [
    "safe-buyer-control-plane",
    "merchant-agent-control-plane",
    "merchant-systems-control-plane",
  ]) {
    assert.match(architecturePage, new RegExp(`data-flow-edge="${edge}"`));
  }
  assert.match(architecturePage, /STRUCTURED INTENT TO CONTROL PLANE/);
  assert.match(architecturePage, /BOUNDED PLAN TO CONTROL PLANE/);
  assert.match(architecturePage, /authoritative source facts and evidence to deterministic application processing/);
});

test("transaction authority transition is complete and ordered", () => {
  const proposal = architecturePage.indexOf('title: "TransactionProposal"');
  const authorization = architecturePage.indexOf('title: "AuthorizationDecision"');
  const execution = architecturePage.indexOf('title: "Execution"');

  assert.ok(proposal >= 0);
  assert.ok(proposal < authorization);
  assert.ok(authorization < execution);
  assert.match(architecturePage, /Immutable material intent/);
  assert.match(architecturePage, /Approval bound to proposal, hash, session, action, actor, and expiry/);
  assert.match(architecturePage, /Deterministic revalidation and stable idempotent execution/);
});

test("critical payment path has complete ordered semantics", () => {
  const executionGate = architecturePage.indexOf('data-flow-order="1"');
  const razorpay = architecturePage.indexOf('data-flow-order="2"');
  const reconciliation = architecturePage.indexOf('data-flow-order="3"');
  const fulfilment = architecturePage.indexOf('data-flow-order="4"');

  assert.ok(executionGate >= 0);
  assert.ok(executionGate < razorpay);
  assert.ok(razorpay < reconciliation);
  assert.ok(reconciliation < fulfilment);
  assert.match(architecturePage, /data-flow-edge="control-plane-execution-gate"/);
  assert.match(architecturePage, /data-flow-edge="execution-gate-razorpay"/);
  assert.match(architecturePage, /data-flow-edge="razorpay-reconciliation"/);
  assert.match(architecturePage, /data-flow-edge="reconciliation-fulfilment-refunds"/);
  assert.match(architecturePage, /Razorpay provider evidence flows to reconciliation/);
  assert.match(architecturePage, /only after a verified outcome/);
});

test("PostgreSQL is the authoritative shared state substrate", () => {
  assert.match(architecturePage, /SYSTEM OF RECORD/);
  assert.match(architecturePage, /PostgreSQL 17/);
  assert.match(architecturePage, /Authoritative shared state · payment evidence · audit truth · transactional outbox/);
  assert.match(architecturePage, /control plane, reconciliation, and lifecycle components read and write shared authoritative PostgreSQL state/);
  assert.match(architecturePage, /PostgreSQL is shared authority, not a payment step/);
});

test("browser callback remains evidence rather than payment truth", () => {
  assert.match(architecturePage, /Browser callbacks are evidence, not payment truth/);
  assert.match(architecturePage, /Webhook \/ API reconciliation/);
  assert.match(architecturePage, /Deterministic payment reducer/);
  assert.match(architecturePage, /PAYMENT_CONFIRMED/);
});

test("merchant readiness ends in deterministic publication", () => {
  for (const step of [
    "Approved merchant interface",
    "AI-assisted inspection / mapping",
    "Contract tests + evidence",
    "Merchant approval where required",
    "Deterministic readiness reducer",
    "Agent Commerce Manifest",
  ]) {
    assert.match(architecturePage, new RegExp(step.replace(/[+]/g, "\\+")));
  }
  assert.match(architecturePage, /Only deterministic logic may publish readiness/);
});

test("architecture does not claim direct AI payment authority", () => {
  assert.doesNotMatch(architecturePage, /AI can authorize payment/i);
  assert.doesNotMatch(architecturePage, /AI authorizes (?:a )?(?:purchase|payment)/i);
  assert.doesNotMatch(architecturePage, /Gemini[^\n<]*(?:→|calls|authorizes)[^\n<]*Razorpay/i);
  assert.doesNotMatch(architecturePage, /browser (?:callback|says success)[^\n<]*payment confirmed/i);
});

test("architecture links to reviewer and product destinations", () => {
  for (const destination of ["/security", "/proof", "/buyer/chat", "/merchant"]) {
    assert.match(architecturePage, new RegExp(`href="${destination.replace("/", "\\/")}"`));
  }
});

test("architecture has a vertical mobile authority flow without horizontal overflow", () => {
  assert.match(architectureStyles, /@media \(max-width: 52rem\)/);
  assert.match(architectureStyles, /\.paymentPath \{\s*grid-template-columns: 1fr;/);
  assert.match(architectureStyles, /\.pathConnector i::after \{[\s\S]*?border-top: 0\.42rem solid #5279bb;/);
  assert.match(architectureStyles, /\.agentCard b::after \{\s*content: " ↓";/);
  assert.match(architectureStyles, /\.merchantSystems b::after \{\s*content: " ↓";/);
  assert.match(architectureStyles, /\.controlGrid,\s*\.detailGrid,\s*\.explanationGrid,\s*\.destinations \{\s*grid-template-columns: 1fr;/);
  assert.doesNotMatch(architectureStyles, /overflow-x:\s*auto/);
});

test("desktop connectors use visible directional arrowheads", () => {
  assert.match(architectureStyles, /\.agentCard::before/);
  assert.match(architectureStyles, /border-left: 0\.4rem solid #6888bf/);
  assert.match(architectureStyles, /\.merchantSystems::after/);
  assert.match(architectureStyles, /border-right: 0\.4rem solid #9d803c/);
  assert.match(architectureStyles, /\.pathConnector i::after/);
  assert.match(architectureStyles, /border-left: 0\.42rem solid #5279bb/);
});
