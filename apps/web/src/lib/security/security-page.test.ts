import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
// @ts-expect-error Node's native type-stripping runner requires the explicit extension.
import { buildSecurityHeadline, buildSecurityScenarios, getSecurityScenario, type AttackProofCase, type SafetyProofCase } from "./security-evidence.ts";

const securityPage = readFileSync(
  new URL("../../app/security/page.tsx", import.meta.url),
  "utf8",
);
const securityExperience = readFileSync(
  new URL("../../app/security/security-experience.tsx", import.meta.url),
  "utf8",
);
const attackReport = JSON.parse(
  readFileSync(new URL("../../../../../proof/results/attacks.json", import.meta.url), "utf8"),
) as { details: AttackProofCase[] };
const safetyReport = JSON.parse(
  readFileSync(new URL("../../../../../proof/results/latest.json", import.meta.url), "utf8"),
) as {
  totalCases: number;
  passed: number;
  failed: number;
  hardSafetyViolations: number;
  defendedInvariantCount: number;
  provenance: { command: string };
  cases: SafetyProofCase[];
};
const mutationReport = JSON.parse(
  readFileSync(new URL("../../../../../proof/results/mutation.json", import.meta.url), "utf8"),
) as { summary: { status: string; mutationsAttempted: number; killed: number; survived: number } };
const attackSummary = JSON.parse(
  readFileSync(new URL("../../../../../proof/results/attacks.json", import.meta.url), "utf8"),
) as { summary: { status: string; attacks: number; blocked: number; breaches: number } };

const scenarios = buildSecurityScenarios(
  attackReport.details,
  safetyReport.cases,
  safetyReport.provenance.command,
);

test("security route renders generated evidence and the reviewer story", () => {
  assert.match(securityPage, /Attack the agent/);
  assert.match(securityPage, /The authority boundary stays intact/);
  assert.match(securityPage, /<SecurityExperience scenarios={scenarios} \/>/);
  assert.match(securityPage, /recorded generated evidence/);
});

test("headline safety counts are read from current generated artifacts", () => {
  assert.equal(safetyReport.passed, 250);
  assert.equal(safetyReport.totalCases, 250);
  assert.equal(safetyReport.hardSafetyViolations, 0);
  assert.equal(safetyReport.defendedInvariantCount, 15);
  const headline = buildSecurityHeadline(safetyReport, attackSummary.summary, mutationReport.summary);
  assert.equal(headline.passedCases, safetyReport.passed);
  assert.equal(headline.totalCases, safetyReport.totalCases);
  assert.equal(headline.killedMutations, mutationReport.summary.killed);
  assert.match(securityPage, /headline\.passedCases/);
  assert.match(securityPage, /headline\.hardSafetyViolations/);
});

test("all eight requested scenarios resolve to exact generated proof cases", () => {
  assert.deepEqual(
    scenarios.map(({ title, proofCaseId }) => [title, proofCaseId]),
    [
      ["Prompt injection", "ATK-GROUND-019"],
      ["Fake product", "ATK-GROUND-018"],
      ["Changed price", "ATK-PAY-005"],
      ["Stale authorization", "ATK-AUTH-011"],
      ["Duplicate checkout", "AMANA-PAYMENT-IDEMPOTENCY-001"],
      ["Webhook replay", "ATK-PAY-002"],
      ["Wrong merchant / account", "AMANA-MONEY-041"],
      ["Wrong currency", "ATK-PAY-006"],
    ],
  );
});

test("attack detail switching resolves distinct expected and observed verdicts", () => {
  const promptInjection = getSecurityScenario(scenarios, "prompt-injection");
  const duplicateCheckout = getSecurityScenario(scenarios, "duplicate-checkout");

  assert.equal(promptInjection.expected, "BLOCKED");
  assert.equal(promptInjection.observed, "BLOCKED");
  assert.equal(duplicateCheckout.expected, "NO_DUPLICATE_PROVIDER_ORDER");
  assert.equal(duplicateCheckout.observed, "NO_DUPLICATE_PROVIDER_ORDER");
  assert.notEqual(promptInjection.proofCaseId, duplicateCheckout.proofCaseId);
  assert.match(securityExperience, /setSelectedKey\(key\)/);
  assert.match(securityExperience, /selected\.expected/);
  assert.match(securityExperience, /selected\.observed/);
});

test("a failed proof result cannot be presented as BLOCKED", () => {
  const failedAttacks = structuredClone(attackReport.details);
  const promptInjection = failedAttacks.find(({ attackId }) => attackId === "ATK-GROUND-019");
  assert.ok(promptInjection);
  promptInjection.result = "BREACHED";
  assert.throws(
    () => buildSecurityScenarios(failedAttacks, safetyReport.cases, safetyReport.provenance.command),
    /is not a passing BLOCKED result/,
  );

  const failedSafety = structuredClone(safetyReport.cases);
  const duplicateCheckout = failedSafety.find(({ id }) => id === "AMANA-PAYMENT-IDEMPOTENCY-001");
  assert.ok(duplicateCheckout);
  duplicateCheckout.passed = false;
  assert.throws(
    () => buildSecurityScenarios(attackReport.details, failedSafety, safetyReport.provenance.command),
    /is not a passing matched result/,
  );
});

test("a failed aggregate cannot be presented as PASS", () => {
  assert.throws(
    () => buildSecurityHeadline(
      { ...safetyReport, failed: 1 },
      attackSummary.summary,
      mutationReport.summary,
    ),
    /headline evidence is not fully passing/,
  );
});

test("selected negative-control result and limitation are rendered", () => {
  assert.equal(mutationReport.summary.killed, 12);
  assert.equal(mutationReport.summary.mutationsAttempted, 12);
  assert.match(securityPage, /headline\.killedMutations/);
  assert.match(securityPage, /headline\.attemptedMutations/);
  assert.match(securityPage, /selected negative-control coverage, not/);
  assert.match(securityPage, /repository-wide mutation testing/);
});

test("security navigation links to proof", () => {
  assert.match(securityPage, /href="\/proof"/);
  assert.match(securityPage, /href="\/proof#negative-controls"/);
});

test("security navigation links to the whole-system architecture", () => {
  assert.match(securityPage, /<Link href="\/architecture">Architecture<\/Link>/);
});

test("attack selectors expose keyboard-native controls and selection state", () => {
  assert.match(securityExperience, /<button/);
  assert.match(securityExperience, /type="button"/);
  assert.match(securityExperience, /aria-pressed=/);
  assert.match(securityExperience, /aria-controls="attack-detail"/);
  assert.match(securityExperience, /aria-live="polite"/);
  assert.match(securityExperience, /Replay evidence trace/);
  assert.doesNotMatch(securityExperience, /Replay deterministic proof/);
});

test("security replay is explicitly a visualization, not a live attack", () => {
  assert.match(
    securityExperience,
    /Visualization of an executed deterministic proof case\. No live attack is performed\./,
  );
  assert.match(securityExperience, /selected\.proofCaseId/);
  assert.match(securityExperience, /selected\.expected/);
  assert.match(securityExperience, /selected\.observed/);
});
