import assert from "node:assert/strict";
import test from "node:test";
// @ts-expect-error Node's native type-stripping runner requires the explicit extension.
import { MONEY_REPAIR_REPLAY_ID, addReplayClarification, approveMoneyRepair, createMoneyRepairReplay, publishDeterministicReplayReadiness, rejectMoneyRepair, runDeterministicRetest } from "./workbench-replay.ts";
// @ts-expect-error Node's native type-stripping runner requires the explicit extension.
import { manifestCapabilityState } from "./workbench-state.ts";

const actorId = "merchant-admin-actor";

test("money-unit diagnosis records 2999 rupees and the 299900 minor-unit contract", () => {
  const state = createMoneyRepairReplay(actorId);
  assert.equal(state.observedAmount, 2999);
  assert.equal(state.canonicalAmountMinor, 299900);
  assert.equal(state.factor, 100);
  assert.equal(state.capabilityState, "NEEDS_REPAIR");
});

test("merchant approval is attributed but cannot directly declare READY", () => {
  const approved = approveMoneyRepair(createMoneyRepairReplay(actorId), actorId);
  assert.equal(approved.decisionActorId, actorId);
  assert.equal(approved.mappingVersion, 2);
  assert.equal(approved.repairVersion, 1);
  assert.equal(approved.capabilityState, "TESTING");
  assert.equal(approved.readinessDecision, "NOT_EVALUATED");
});

test("retest applies the exact x100 repair but still does not declare READY", () => {
  const retested = runDeterministicRetest(
    approveMoneyRepair(createMoneyRepairReplay(actorId), actorId),
  );
  assert.equal(retested.retestOutcome, "PASS");
  assert.equal(retested.readinessDecision, "NOT_EVALUATED");
  assert.equal(retested.phase, "REDUCING");
});

test("only deterministic publication can produce READY after every gate passes", () => {
  const ready = publishDeterministicReplayReadiness(runDeterministicRetest(
    approveMoneyRepair(createMoneyRepairReplay(actorId), actorId),
  ));
  assert.equal(ready.readinessDecision, "READY");
  assert.equal(ready.capabilityState, "READY");
  assert.deepEqual(ready.missingEvidence, []);
});

test("reject keeps capability blocked and never retests", () => {
  const rejected = rejectMoneyRepair(createMoneyRepairReplay(actorId), actorId);
  assert.equal(rejected.capabilityState, "BLOCKED");
  assert.equal(rejected.retestOutcome, null);
  assert.equal(rejected.readinessDecision, "BLOCKED");
});

test("clarification is bounded, recorded, and does not self-approve", () => {
  const clarified = addReplayClarification(
    createMoneyRepairReplay(actorId),
    actorId,
    "  amount is authoritative in whole rupees  ",
  );
  assert.equal(clarified.clarification, "amount is authoritative in whole rupees");
  assert.equal(clarified.merchantDecision, null);
});

test("replay rejects decisions from a different actor", () => {
  assert.throws(
    () => approveMoneyRepair(createMoneyRepairReplay(actorId), "other-actor"),
    /not authorized/,
  );
});

test("sandbox replay is isolated from Amazing and cannot mutate the live manifest", () => {
  const state = createMoneyRepairReplay(actorId);
  assert.equal(state.replayId, MONEY_REPAIR_REPLAY_ID);
  assert.notEqual(state.replayId.toLowerCase(), "amazing");
  assert.equal(state.authoritativeManifestImpact, "NONE");
});

test("workbench maps only advertised reducer READY state to READY", () => {
  const base = {
    capability: "GET_QUOTE",
    readiness: "READY" as const,
    executableMappingProposalId: "mapping",
    readinessEvaluationId: "evaluation",
  };
  assert.equal(manifestCapabilityState({ ...base, advertised: true }), "READY");
  assert.equal(manifestCapabilityState({ ...base, advertised: false }), "UNKNOWN");
  assert.equal(manifestCapabilityState(undefined), "NOT_CONFIGURED");
  assert.equal(manifestCapabilityState({ ...base, advertised: false, readiness: "BLOCKED" }), "BLOCKED");
});
