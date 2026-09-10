export type AttackProofCase = {
  attackId: string;
  scenario: string;
  expectedSafetyResult: string;
  observedResult: string;
  result: string;
  reasonCode: string;
  executionMode: string;
  evidenceTest: string;
};

export type SafetyProofCase = {
  id: string;
  invariantId: string;
  category: string;
  title: string;
  adversarialInput: string;
  expected: string;
  actual: string;
  passed: boolean;
};

export type SecurityScenario = {
  key: string;
  order: number;
  title: string;
  attackInput: string;
  boundary: string;
  expected: string;
  observed: string;
  status: "BLOCKED";
  reason: string;
  reasonCode: string;
  proofCaseId: string;
  proofArtifact: "attacks.json" | "latest.json";
  proofBoundary: string;
  guard: string;
  evidenceTest: string;
};

export type SecurityHeadline = {
  passedCases: number;
  totalCases: number;
  hardSafetyViolations: number;
  defendedInvariants: number;
  blockedAttacks: number;
  totalAttacks: number;
  killedMutations: number;
  attemptedMutations: number;
};

type ScenarioPresentation = {
  key: string;
  order: number;
  title: string;
  source: "attack" | "safety";
  proofCaseId: string;
  boundary: string;
  reason: string;
  guard: string;
};

// Display copy lives here; verdicts, inputs, case IDs, and test boundaries are resolved from
// generated evidence below. A missing or failed proof record makes the /security build fail.
export const scenarioPresentation: readonly ScenarioPresentation[] = [
  {
    key: "prompt-injection",
    order: 1,
    title: "Prompt injection",
    source: "attack",
    proofCaseId: "ATK-GROUND-019",
    boundary: "Catalogue and policy authority",
    reason: "Instruction-like catalogue text could not replace trusted safety evidence.",
    guard: "Candidate-cart grounding and hard-constraint reducer",
  },
  {
    key: "fake-product",
    order: 2,
    title: "Fake product",
    source: "attack",
    proofCaseId: "ATK-GROUND-018",
    boundary: "Authoritative catalogue identity",
    reason: "An exact missing SKU remained missing; no alternative was silently promoted.",
    guard: "Exact product identity and candidate-cart grounding",
  },
  {
    key: "changed-price",
    order: 3,
    title: "Changed price",
    source: "attack",
    proofCaseId: "ATK-PAY-005",
    boundary: "Authorized amount and payment evidence",
    reason: "Mismatched financial evidence could not confirm the authorized amount.",
    guard: "Authoritative payment-evidence validation",
  },
  {
    key: "stale-authorization",
    order: 4,
    title: "Stale authorization",
    source: "attack",
    proofCaseId: "ATK-AUTH-011",
    boundary: "Authorization expiry",
    reason: "An expired authorization could not open the execution gate.",
    guard: "Actor, proposal, action, and expiry-bound authorization",
  },
  {
    key: "duplicate-checkout",
    order: 5,
    title: "Duplicate checkout",
    source: "safety",
    proofCaseId: "AMANA-PAYMENT-IDEMPOTENCY-001",
    boundary: "Execution-to-provider-order idempotency",
    reason: "A repeated payment initiation reused the existing provider order.",
    guard: "Stable execution and provider-order identity",
  },
  {
    key: "webhook-replay",
    order: 6,
    title: "Webhook replay",
    source: "attack",
    proofCaseId: "ATK-PAY-002",
    boundary: "Provider event idempotency",
    reason: "The replayed provider event was recognized as already processed.",
    guard: "Verified, idempotent webhook ingestion",
  },
  {
    key: "wrong-account",
    order: 7,
    title: "Wrong merchant / account",
    source: "safety",
    proofCaseId: "AMANA-MONEY-041",
    boundary: "Provider account binding",
    reason: "Evidence from a different provider account left payment unconfirmed.",
    guard: "Merchant and provider-account evidence binding",
  },
  {
    key: "wrong-currency",
    order: 8,
    title: "Wrong currency",
    source: "attack",
    proofCaseId: "ATK-PAY-006",
    boundary: "Authorized currency binding",
    reason: "Currency-mismatched provider evidence failed identity validation.",
    guard: "Authoritative payment-evidence identity validation",
  },
] as const;

function humanize(value: string): string {
  return value
    .toLowerCase()
    .split("_")
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(" ");
}

export function buildSecurityScenarios(
  attackCases: readonly AttackProofCase[],
  safetyCases: readonly SafetyProofCase[],
  safetyEvidenceCommand: string,
): SecurityScenario[] {
  return scenarioPresentation.map((presentation) => {
    if (presentation.source === "attack") {
      const proof = attackCases.find((candidate) => candidate.attackId === presentation.proofCaseId);
      if (!proof) {
        throw new Error(`Missing generated attack proof ${presentation.proofCaseId}`);
      }
      if (
        proof.result !== "BLOCKED" ||
        proof.observedResult !== "BLOCKED" ||
        proof.expectedSafetyResult !== "BLOCKED"
      ) {
        throw new Error(`Generated attack proof ${proof.attackId} is not a passing BLOCKED result`);
      }

      return {
        ...presentation,
        attackInput: proof.scenario,
        expected: proof.expectedSafetyResult,
        observed: proof.observedResult,
        status: "BLOCKED",
        reasonCode: proof.reasonCode,
        proofCaseId: proof.attackId,
        proofArtifact: "attacks.json",
        proofBoundary: humanize(proof.executionMode),
        evidenceTest: proof.evidenceTest,
      };
    }

    const proof = safetyCases.find((candidate) => candidate.id === presentation.proofCaseId);
    if (!proof) {
      throw new Error(`Missing generated safety proof ${presentation.proofCaseId}`);
    }
    if (!proof.passed || proof.expected !== proof.actual) {
      throw new Error(`Generated safety proof ${proof.id} is not a passing matched result`);
    }

    return {
      ...presentation,
      attackInput: proof.adversarialInput,
      expected: proof.expected,
      observed: proof.actual,
      status: "BLOCKED",
      reasonCode: proof.actual,
      proofCaseId: proof.id,
      proofArtifact: "latest.json",
      proofBoundary: "Deterministic component proof",
      evidenceTest: safetyEvidenceCommand,
    };
  });
}

export function getSecurityScenario(
  scenarios: readonly SecurityScenario[],
  key: string,
): SecurityScenario {
  const scenario = scenarios.find((candidate) => candidate.key === key);
  if (!scenario) {
    throw new Error(`Unknown security scenario ${key}`);
  }
  return scenario;
}

export function buildSecurityHeadline(
  safety: {
    totalCases: number;
    passed: number;
    failed: number;
    hardSafetyViolations: number;
    defendedInvariantCount: number;
  },
  attacks: {
    status: string;
    attacks: number;
    blocked: number;
    breaches: number;
  },
  mutations: {
    status: string;
    mutationsAttempted: number;
    killed: number;
    survived: number;
  },
): SecurityHeadline {
  if (
    safety.failed !== 0 ||
    safety.passed !== safety.totalCases ||
    safety.hardSafetyViolations !== 0 ||
    attacks.status !== "PASS" ||
    attacks.breaches !== 0 ||
    attacks.blocked !== attacks.attacks ||
    mutations.status !== "PASS" ||
    mutations.survived !== 0 ||
    mutations.killed !== mutations.mutationsAttempted
  ) {
    throw new Error("Generated security headline evidence is not fully passing");
  }

  return {
    passedCases: safety.passed,
    totalCases: safety.totalCases,
    hardSafetyViolations: safety.hardSafetyViolations,
    defendedInvariants: safety.defendedInvariantCount,
    blockedAttacks: attacks.blocked,
    totalAttacks: attacks.attacks,
    killedMutations: mutations.killed,
    attemptedMutations: mutations.mutationsAttempted,
  };
}
