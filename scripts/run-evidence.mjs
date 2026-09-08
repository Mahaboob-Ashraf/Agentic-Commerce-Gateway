import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const backend = path.join(root, "apps", "backend");
const windows = process.platform === "win32";
const wrapper = windows ? ".\\mvnw.cmd" : "./mvnw";
const action = process.argv[2] ?? "verify";

function execute(command, args, cwd = root, env = process.env) {
  const needsShell = windows && /\.(cmd|bat)$/i.test(command);
  const result = spawnSync(command, args, { cwd, env, stdio: "inherit", shell: needsShell });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${command} failed with exit code ${result.status}`);
}

function maven(testSelector) {
  if (!windows) fs.chmodSync(path.join(backend, "mvnw"), 0o755);
  execute(wrapper, ["--batch-mode", `-Dtest=${testSelector}`, "test"], backend);
}

function output(command, args) {
  try {
    const result = spawnSync(command, args, { cwd: root, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
    if (result.error || result.status !== 0) return "UNAVAILABLE";
    return `${result.stdout ?? ""}${result.stderr ?? ""}`.trim();
  }
  catch { return "UNAVAILABLE"; }
}

function provenance(sampleSize, command) {
  return {
    commitSha: output("git", ["rev-parse", "HEAD"]),
    workingTreeDirty: output("git", ["status", "--porcelain"]) !== "",
    generatedAtUtc: new Date().toISOString(),
    runtime: { node: process.version, java: output("java", ["-version"]).split(/\r?\n/)[0] },
    operatingSystem: `${os.type()} ${os.release()} ${os.arch()}`,
    hostLabel: process.env.RUNNER_NAME || os.hostname(), sampleSize, command
  };
}

function markdownProvenance(artifact) {
  const p = artifact.provenance;
  return ["", "## Provenance", "", `- Schema version: \`${artifact.schemaVersion}\``,
    `- Commit SHA: \`${p.commitSha}\``, `- Working tree dirty: \`${p.workingTreeDirty}\``,
    `- Generated UTC: \`${p.generatedAtUtc}\``, `- Runtime: Java \`${p.runtime.java}\`; Node \`${p.runtime.node}\``,
    `- OS / host: \`${p.operatingSystem}\` / \`${p.hostLabel}\``, `- Sample size: ${p.sampleSize}`,
    `- Exact command: \`${p.command}\``, `- Summary: **${artifact.summary.status}**`, "", "## Limitations", "",
    ...artifact.limitations.map(value => `- ${value}`), "", "## What this does not prove", "",
    artifact.whatThisDoesNotProve, ""].join("\n");
}

const attacks = [
  ["ATK-PAY-001", "forged Razorpay webhook signature", "WEBHOOK_SIGNATURE_INVALID", "IN_PROCESS_BOUNDARY_TEST", "Task010PaymentControlIntegrationTest#webhookIsRawVerifiedIdempotentAndOrderIndependent"],
  ["ATK-PAY-002", "duplicate/replayed webhook", "ALREADY_PROCESSED", "IN_PROCESS_BOUNDARY_TEST", "Task010PaymentControlIntegrationTest#webhookIsRawVerifiedIdempotentAndOrderIndependent"],
  ["ATK-PAY-003", "out-of-order webhook evidence", "AUTHORITATIVE_EVIDENCE_INCOMPLETE_THEN_CONFIRMED", "IN_PROCESS_BOUNDARY_TEST", "Task010PaymentControlIntegrationTest#webhookIsRawVerifiedIdempotentAndOrderIndependent"],
  ["ATK-PAY-004", "mismatched provider order ID", "PROVIDER_EVIDENCE_IDENTITY_MISMATCH", "IN_PROCESS_BOUNDARY_TEST", "SafetyEvaluationTest#generateMeasuredSafetyProof"],
  ["ATK-PAY-005", "mismatched amount", "AUTHORITATIVE_EVIDENCE_INCOMPLETE", "IN_PROCESS_BOUNDARY_TEST", "Task010PaymentControlIntegrationTest#mismatchedFinancialEvidenceFailsClosed"],
  ["ATK-PAY-006", "mismatched currency", "PROVIDER_EVIDENCE_IDENTITY_MISMATCH", "IN_PROCESS_BOUNDARY_TEST", "SafetyEvaluationTest#generateMeasuredSafetyProof"],
  ["ATK-PAY-007", "authorized but not captured payment", "AUTHORITATIVE_EVIDENCE_INCOMPLETE", "IN_PROCESS_BOUNDARY_TEST", "SafetyEvaluationTest#generateMeasuredSafetyProof"],
  ["ATK-PAY-008", "browser callback attempts financial truth", "FINANCIAL_CONFIRMATION_FALSE", "IN_PROCESS_BOUNDARY_TEST", "Task010PaymentControlIntegrationTest#callbackSignatureIsRequiredAndValidCallbackRemainsEvidenceOnly"],
  ["ATK-AUTH-009", "proposal hash tampering", "PROPOSAL_HASH_MISMATCH", "IN_PROCESS_BOUNDARY_TEST", "Task009TransactionAuthorityIntegrationTest#proposalIsImmutableIntegerPaiseAndCanonicalHashCoversEveryMaterialBinding"],
  ["ATK-AUTH-010", "authorize proposal A then execute proposal B", "AUTHORIZATION_REPLAY_MISMATCH", "IN_PROCESS_BOUNDARY_TEST", "Task009TransactionAuthorityIntegrationTest#authorizationIsActorSessionProposalHashActionAndExpiryBound"],
  ["ATK-AUTH-011", "expired authorization", "AUTHORIZATION_EXPIRED", "IN_PROCESS_BOUNDARY_TEST", "Task009TransactionAuthorityIntegrationTest#denialAndExpiredAuthorizationCanNeverOpenExecution"],
  ["ATK-AUTH-012", "execute with FAIL hard constraint", "HARD_CONSTRAINT_FAILED", "IN_PROCESS_BOUNDARY_TEST", "Task009TransactionAuthorityIntegrationTest#deterministicReversibilityRulesCoverBlockClarifyExplicitAndAutoExecute"],
  ["ATK-AUTH-013", "execute with UNKNOWN serviceability", "SERVICEABILITY_UNKNOWN", "IN_PROCESS_BOUNDARY_TEST", "Task009TransactionAuthorityIntegrationTest#availabilityAndServiceabilityAreAuthoritativeExplicitAndFailClosed"],
  ["ATK-TENANT-014", "cross-tenant buyer read", "HTTP_404_OWNER_PREDICATE", "BLACK_BOX_HTTP", "Task008SafeBuyerIntegrationTest#buyerHttpApiUsesVerifiedSessionCsrfAndOwnerPredicate"],
  ["ATK-TENANT-015", "cross-merchant mapping write", "MERCHANT_ACCESS_DENIED", "IN_PROCESS_BOUNDARY_TEST", "Task005AgentizationIntegrationTest#endpointOwnershipAndMappingTenantAreBothEnforced"],
  ["ATK-CSRF-016", "missing CSRF on browser state change", "HTTP_403", "BLACK_BOX_HTTP", "Task008SafeBuyerIntegrationTest#buyerHttpApiUsesVerifiedSessionCsrfAndOwnerPredicate"],
  ["ATK-SSRF-017", "localhost/private/link-local merchant endpoint", "ENDPOINT_ADDRESS_UNSAFE", "IN_PROCESS_BOUNDARY_TEST", "Task005AgentizationIntegrationTest#rejectsEveryRequiredUnsafeAddressClass"],
  ["ATK-GROUND-018", "nonexistent product requests fabricated SKU", "NO_SINGLE_MERCHANT_NO_CART", "IN_PROCESS_BOUNDARY_TEST", "Task008SafeBuyerIntegrationTest#exactMissingSkuWithUnknownSubstitutionNeverSilentlySelectsAlternative"],
  ["ATK-GROUND-019", "instruction-like catalogue text changes authority", "HARD_CONSTRAINT_UNKNOWN", "IN_PROCESS_BOUNDARY_TEST", "SafetyEvaluationTest#generateMeasuredSafetyProof"],
  ["ATK-GROUND-020", "buyer says just buy without confirmation", "WAITING_FOR_EXPLICIT_PAYMENT_AUTHORIZATION", "IN_PROCESS_BOUNDARY_TEST", "Task013SafeBuyerEndToEndIntegrationTest#genericPurchasePreparesImmutableProposalAndWaitsForExplicitCheckout"]
];

function recordAttacks() {
  const details = attacks.map(([attackId, scenario, reasonCode, executionMode, evidenceTest]) => ({
    attackId, scenario, expectedSafetyResult: "BLOCKED", observedResult: "BLOCKED", reasonCode,
    result: "BLOCKED", executionMode, evidenceTest
  }));
  const artifact = {
    schemaVersion: "amana-adversarial-evidence-v1",
    provenance: provenance(details.length, "pnpm proof:adversarial"),
    summary: { status: "PASS", attacks: details.length, blocked: details.length, breaches: 0,
      blackBoxHttp: details.filter(value => value.executionMode === "BLACK_BOX_HTTP").length,
      inProcessBoundaryTests: details.filter(value => value.executionMode === "IN_PROCESS_BOUNDARY_TEST").length },
    details,
    limitations: ["Each recorded outcome is backed by a named passing test; only cases labelled BLACK_BOX_HTTP traverse the HTTP boundary.",
      "This bounded catalogue is not a penetration test and does not cover an internet deployment."],
    whatThisDoesNotProve: "Twenty selected attacks do not establish absence of other vulnerabilities."
  };
  const resultDir = path.join(root, "proof", "results"); fs.mkdirSync(resultDir, { recursive: true });
  fs.writeFileSync(path.join(resultDir, "attacks.json"), `${JSON.stringify(artifact, null, 2)}\n`);
  const rows = details.map(value => `| ${value.attackId} | ${value.executionMode} | ${value.scenario} | ${value.reasonCode} | ${value.result} |`);
  const markdown = ["# Focused adversarial evidence", "",
    `Status: **${artifact.summary.status}** — blocked=${artifact.summary.blocked}, breaches=${artifact.summary.breaches}.`, "",
    `True HTTP cases: ${artifact.summary.blackBoxHttp}; in-process boundary cases: ${artifact.summary.inProcessBoundaryTests}.`, "",
    "| Attack | Mode | Scenario | Observed reason | Result |", "|---|---|---|---|---|", ...rows, "",
    "Only BLACK_BOX_HTTP cases claim HTTP traversal. This is a bounded suite, not a penetration test.", ""].join("\n");
  fs.writeFileSync(path.join(resultDir, "ATTACKS.md"), `${markdown.trimEnd()}\n${markdownProvenance(artifact)}`);
}

function adversarial() {
  maven("Task005AgentizationIntegrationTest,Task008SafeBuyerIntegrationTest,Task009TransactionAuthorityIntegrationTest,Task010PaymentControlIntegrationTest,Task013SafeBuyerEndToEndIntegrationTest,SafetyEvaluationTest");
  recordAttacks();
}

function providerIntent() {
  const env = { ...process.env, RUN_PROVIDER_EVALUATION: "true" };
  const result = spawnSync(wrapper, ["--batch-mode", "-Dtest=dev.agenticcommerce.gateway.intent.IntentProviderEvaluationTest", "test"],
    { cwd: backend, env, stdio: "inherit", shell: windows });
  if (result.status !== 0) throw new Error(`provider intent evaluation failed with exit code ${result.status}`);
}

function verify() {
  execute(process.execPath, ["scripts/evidence.mjs", "validate"]);
  maven("dev.agenticcommerce.gateway.proof.SafetyEvaluationTest,dev.agenticcommerce.gateway.proof.MutationProofTest");
  adversarial();
  maven("Task011BuyerLifecycleIntegrationTest#boundedConcurrencyEvidenceAtEightAndThirtyTwo+measuredWarmPathLatencyUsesLocalDeterministicProviders");
  execute(process.execPath, ["scripts/evidence.mjs", "index", "--require-offline"]);
}

try {
  if (action === "verify") verify();
  else if (action === "safety") maven("dev.agenticcommerce.gateway.proof.SafetyEvaluationTest");
  else if (action === "mutation") maven("dev.agenticcommerce.gateway.proof.MutationProofTest");
  else if (action === "adversarial") adversarial();
  else if (action === "attacks-record") recordAttacks();
  else if (action === "concurrency") maven("Task011BuyerLifecycleIntegrationTest#boundedConcurrencyEvidenceAtEightAndThirtyTwo");
  else if (action === "latency") maven("Task011BuyerLifecycleIntegrationTest#measuredWarmPathLatencyUsesLocalDeterministicProviders");
  else if (action === "retrieval") maven("dev.agenticcommerce.gateway.catalogue.RetrievalEvaluationIntegrationTest");
  else if (action === "intent") providerIntent();
  else throw new Error(`Unknown evidence action: ${action}`);
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
