export const MONEY_REPAIR_REPLAY_ID = "amana-sandbox-money-unit-v1";

export type WorkbenchCapabilityState =
  | "READY"
  | "TESTING"
  | "NEEDS_REPAIR"
  | "BLOCKED"
  | "UNKNOWN"
  | "NOT_CONFIGURED";

export type ReplayStage =
  | "INSPECT"
  | "DISCOVER"
  | "MAP"
  | "TEST"
  | "OBSERVE"
  | "DIAGNOSE"
  | "REPAIR"
  | "RETEST"
  | "REDUCE";

export type ReplayTimelineEntry = {
  id: string;
  offset: string;
  stage: ReplayStage;
  outcome: "INFO" | "PASS" | "FAIL" | "PROPOSAL" | "DECISION";
  title: string;
  detail: string;
};

export type MoneyRepairReplay = {
  replayId: typeof MONEY_REPAIR_REPLAY_ID;
  isolation: "LOCAL_DETERMINISTIC_REPLAY";
  authoritativeManifestImpact: "NONE";
  authorizedActorId: string;
  capability: "GET_QUOTE";
  capabilityState: WorkbenchCapabilityState;
  phase: "AWAITING_APPROVAL" | "REJECTED" | "RETESTING" | "REDUCING" | "COMPLETE";
  currentStage: ReplayStage;
  observedAmount: 2999;
  canonicalAmountMinor: 299900;
  factor: 100;
  mappingVersion: 1 | 2;
  repairVersion: null | 1;
  merchantDecision: null | "APPROVE" | "REJECT";
  decisionActorId: string | null;
  clarification: string | null;
  retestOutcome: null | "PASS";
  readinessDecision: "NOT_EVALUATED" | "READY" | "BLOCKED";
  satisfiedEvidence: string[];
  missingEvidence: string[];
  timeline: ReplayTimelineEntry[];
};

const INITIAL_EVIDENCE = [
  "APPROVED_ENDPOINT",
  "VALID_MAPPING",
  "SCHEMA_VALIDATION",
  "NO_OPEN_CLARIFICATION",
];

const REQUIRED_EVIDENCE = [
  "VALID_MAPPING",
  "APPROVED_EXECUTABLE_CONTRACT",
  "CURRENT_PASSING_QUOTE_CONTRACT_TEST",
  "AMOUNT_CURRENCY_NORMALIZATION",
  "SCHEMA_VALIDATION",
  "NO_OPEN_CLARIFICATION",
  "APPROVED_ENDPOINT",
];

export function createMoneyRepairReplay(actorId: string): MoneyRepairReplay {
  return {
    replayId: MONEY_REPAIR_REPLAY_ID,
    isolation: "LOCAL_DETERMINISTIC_REPLAY",
    authoritativeManifestImpact: "NONE",
    authorizedActorId: actorId,
    capability: "GET_QUOTE",
    capabilityState: "NEEDS_REPAIR",
    phase: "AWAITING_APPROVAL",
    currentStage: "REPAIR",
    observedAmount: 2999,
    canonicalAmountMinor: 299900,
    factor: 100,
    mappingVersion: 1,
    repairVersion: null,
    merchantDecision: null,
    decisionActorId: null,
    clarification: null,
    retestOutcome: null,
    readinessDecision: "NOT_EVALUATED",
    satisfiedEvidence: INITIAL_EVIDENCE,
    missingEvidence: REQUIRED_EVIDENCE.filter((item) => !INITIAL_EVIDENCE.includes(item)),
    timeline: [
      { id: "inspect", offset: "T+00:01", stage: "INSPECT", outcome: "PASS", title: "Quote capability inspected", detail: "Approved POST /quotes operation is inside the configured source boundary." },
      { id: "discover", offset: "T+00:02", stage: "DISCOVER", outcome: "INFO", title: "Money field discovered", detail: "Response schema exposes integer field amount with currency INR." },
      { id: "map", offset: "T+00:03", stage: "MAP", outcome: "INFO", title: "Canonical amount mapped", detail: "Provisional mapping v1 binds response.amount to quote.amount_minor." },
      { id: "test", offset: "T+00:04", stage: "TEST", outcome: "FAIL", title: "Quote contract test failed", detail: "test_quote_money_001 returned an amount inconsistent with the canonical minor-unit contract." },
      { id: "observe", offset: "T+00:05", stage: "OBSERVE", outcome: "FAIL", title: "Observed 2999", detail: "Merchant semantics describe ₹2,999; canonical expectation is 299900 minor units." },
      { id: "diagnose", offset: "T+00:06", stage: "DIAGNOSE", outcome: "INFO", title: "Unit mismatch diagnosed", detail: "Upstream expresses whole rupees while Amana requires integer paise." },
      { id: "proposal", offset: "T+00:07", stage: "REPAIR", outcome: "PROPOSAL", title: "Bounded repair proposed", detail: "Normalize rupees to minor units (×100) for Quote only. Merchant authority is required." },
    ],
  };
}

function requireActor(state: MoneyRepairReplay, actorId: string) {
  if (!actorId || actorId !== state.authorizedActorId) {
    throw new Error("Merchant decision actor is not authorized for this replay");
  }
}

export function approveMoneyRepair(state: MoneyRepairReplay, actorId: string): MoneyRepairReplay {
  requireActor(state, actorId);
  if (state.phase !== "AWAITING_APPROVAL") return state;
  return {
    ...state,
    phase: "RETESTING",
    currentStage: "RETEST",
    capabilityState: "TESTING",
    mappingVersion: 2,
    repairVersion: 1,
    merchantDecision: "APPROVE",
    decisionActorId: actorId,
    satisfiedEvidence: [...state.satisfiedEvidence, "APPROVED_EXECUTABLE_CONTRACT"],
    missingEvidence: state.missingEvidence.filter((item) => item !== "APPROVED_EXECUTABLE_CONTRACT"),
    timeline: [...state.timeline, {
      id: "approval",
      offset: "T+00:12",
      stage: "REPAIR",
      outcome: "DECISION",
      title: "Merchant approved mapping v2",
      detail: "Repair v1 is attributed to the authenticated Merchant Admin. Approval does not set readiness.",
    }],
  };
}

export function rejectMoneyRepair(state: MoneyRepairReplay, actorId: string): MoneyRepairReplay {
  requireActor(state, actorId);
  if (state.phase !== "AWAITING_APPROVAL") return state;
  return {
    ...state,
    phase: "REJECTED",
    capabilityState: "BLOCKED",
    merchantDecision: "REJECT",
    decisionActorId: actorId,
    readinessDecision: "BLOCKED",
    timeline: [...state.timeline, {
      id: "rejection",
      offset: "T+00:12",
      stage: "REPAIR",
      outcome: "DECISION",
      title: "Merchant rejected proposed repair",
      detail: "Quote remains blocked. No transformation, retest, or manifest publication occurred.",
    }],
  };
}

export function addReplayClarification(
  state: MoneyRepairReplay,
  actorId: string,
  clarification: string,
): MoneyRepairReplay {
  requireActor(state, actorId);
  const value = clarification.trim().replace(/\s+/g, " ").slice(0, 500);
  if (!value) return state;
  return {
    ...state,
    clarification: value,
    timeline: [...state.timeline, {
      id: `clarification-${state.timeline.length}`,
      offset: "T+00:10",
      stage: "REPAIR",
      outcome: "DECISION",
      title: "Merchant clarification recorded",
      detail: value,
    }],
  };
}

export function runDeterministicRetest(state: MoneyRepairReplay): MoneyRepairReplay {
  if (state.phase !== "RETESTING" || state.merchantDecision !== "APPROVE") return state;
  const normalizedAmount = state.observedAmount * state.factor;
  const passes = normalizedAmount === state.canonicalAmountMinor;
  if (!passes) {
    return { ...state, phase: "COMPLETE", capabilityState: "BLOCKED", readinessDecision: "BLOCKED" };
  }
  const satisfiedEvidence = [
    ...state.satisfiedEvidence,
    "CURRENT_PASSING_QUOTE_CONTRACT_TEST",
    "AMOUNT_CURRENCY_NORMALIZATION",
  ];
  return {
    ...state,
    phase: "REDUCING",
    currentStage: "REDUCE",
    retestOutcome: "PASS",
    satisfiedEvidence,
    missingEvidence: REQUIRED_EVIDENCE.filter((item) => !satisfiedEvidence.includes(item)),
    timeline: [...state.timeline, {
      id: "retest",
      offset: "T+00:15",
      stage: "RETEST",
      outcome: "PASS",
      title: "Deterministic contract retest passed",
      detail: "2999 × 100 = 299900 minor units. Readiness remains unpublished until reduction.",
    }],
  };
}

/** The only replay operation permitted to produce READY. Mirrors server reducer ownership. */
export function publishDeterministicReplayReadiness(state: MoneyRepairReplay): MoneyRepairReplay {
  if (state.phase !== "REDUCING") return state;
  const missingEvidence = REQUIRED_EVIDENCE.filter((item) => !state.satisfiedEvidence.includes(item));
  const ready = state.retestOutcome === "PASS" && missingEvidence.length === 0;
  return {
    ...state,
    phase: "COMPLETE",
    capabilityState: ready ? "READY" : "BLOCKED",
    readinessDecision: ready ? "READY" : "BLOCKED",
    missingEvidence,
    timeline: [...state.timeline, {
      id: "reducer",
      offset: "T+00:16",
      stage: "REDUCE",
      outcome: ready ? "PASS" : "FAIL",
      title: ready ? "Readiness reducer published READY" : "Readiness reducer kept Quote blocked",
      detail: ready
        ? "All seven required evidence gates are satisfied in the isolated replay. Live manifest unchanged."
        : `${missingEvidence.length} required evidence gate(s) remain unsatisfied.`,
    }],
  };
}
