import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const proofPage = readFileSync(
  new URL("../../app/proof/page.tsx", import.meta.url),
  "utf8",
);
const landingPage = readFileSync(
  new URL("../../app/page.tsx", import.meta.url),
  "utf8",
);

test("proof page reads every generated evidence dataset", () => {
  for (const artifact of [
    "index.json",
    "latest.json",
    "retrieval.json",
    "intent-eval.json",
    "mutation.json",
    "concurrency.json",
    "latency.json",
  ]) {
    assert.match(proofPage, new RegExp(`proof/results/${artifact.replace(".", "\\.")}`));
  }
});

test("proof page still renders generated deterministic safety evidence", () => {
  assert.match(proofPage, /safetyReport\.totalCases/);
  assert.match(proofPage, /safetyReport\.hardSafetyViolations/);
  assert.match(proofPage, /safetyReport\.failClosedRate/);
  assert.match(proofPage, /safetyReport\.invariants\.map/);
  assert.match(proofPage, /Adversarial safety examples/);
});

test("proof page exposes the required same-page evidence navigation", () => {
  const destinations = [
    ["Overview", "#overview"],
    ["Safety", "#safety"],
    ["Retrieval", "#retrieval"],
    ["Intent", "#intent"],
    ["Negative Controls", "#negative-controls"],
    ["Concurrency", "#concurrency"],
    ["Latency", "#latency"],
  ];

  for (const [label, destination] of destinations) {
    assert.match(proofPage, new RegExp(`\\["${label}", "${destination}"\\]`));
    assert.match(proofPage, new RegExp(`id="${destination.slice(1)}"`));
  }
});

test("proof overview keeps backend and frontend verification counts separate", () => {
  assert.match(proofPage, /evidenceIndex\.testCounts\.backend\.tests/);
  assert.match(proofPage, /evidenceIndex\.testCounts\.frontend\.tests/);
  assert.match(proofPage, /Test counts are never combined/);
});

test("retrieval evidence includes hybrid, lexical, error, and multilingual measurements", () => {
  assert.match(proofPage, /retrieval\.hybrid\.recallAt5/);
  assert.match(proofPage, /retrieval\.postFixLexicalOnly\.recallAt5/);
  assert.match(proofPage, /retrieval\.hybridVsLexicalRecallAt5Delta/);
  assert.match(proofPage, /retrieval\.hybrid\.fabricatedProductCount/);
  assert.match(proofPage, /retrieval\.hybrid\.wrongVariantSubstitutionCount/);
  assert.match(proofPage, /not evidence of strong multilingual retrieval/);
});

test("intent evidence preserves the AI authority boundary", () => {
  assert.match(proofPage, /intent\.fieldAccuracy/);
  assert.match(proofPage, /intent\.exactProductIdentityFieldAccuracy/);
  assert.match(proofPage, /intent\.authorizationSkipAttemptsThatGainedAuthority/);
  assert.match(proofPage, /It grants no financial authority/);
});

test("negative-control evidence states its selected-coverage limitation", () => {
  assert.match(proofPage, /mutation\.mutationsAttempted/);
  assert.match(proofPage, /mutation\.killRate/);
  assert.match(proofPage, /not repository-wide mutation coverage/);
});

test("concurrency and latency evidence retain their qualifications", () => {
  assert.match(proofPage, /concurrencyReport\.details\.map/);
  assert.match(proofPage, /not production-scale load testing/);
  assert.match(proofPage, /latencyReport\.details\.map/);
  assert.match(proofPage, /not production SLAs/);
  assert.match(proofPage, /deterministic stub measurement, not live Razorpay latency/);
});

test("proof page links to the required raw evidence on main", () => {
  assert.match(
    proofPage,
    /github\.com\/Mahaboob-Ashraf\/Agentic-Commerce-Gateway\/blob\/main\/proof\/results/,
  );
  for (const artifact of [
    "SCORECARD.md",
    "RETRIEVAL_EVAL.md",
    "INTENT_EVAL.md",
    "MUTATION.md",
    "CONCURRENCY.md",
    "LATENCY.md",
    "latest.json",
  ]) {
    assert.match(proofPage, new RegExp(`"${artifact.replace(".", "\\.")}"`));
  }
});

test("landing navigation stays high-level", () => {
  const navigation = landingPage.match(
    /<div className={styles\.navLinks}>([\s\S]*?)<\/div>/,
  )?.[1];

  assert.ok(navigation, "landing navigation should exist");
  for (const label of ["How it works", "Proof", "Buyer", "Merchant"]) {
    assert.match(navigation, new RegExp(`>${label}<`));
  }
  assert.doesNotMatch(
    navigation,
    />Retrieval<|>Intent<|>Negative Controls<|>Concurrency<|>Latency</,
  );
});
