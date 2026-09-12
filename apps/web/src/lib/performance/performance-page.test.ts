import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
// @ts-expect-error Node's native type-stripping runner requires the explicit extension.
import { buildPerformanceEvidence, formatLatency } from "./performance-evidence.ts";

const page = readFileSync(new URL("../../app/performance/page.tsx", import.meta.url), "utf8");
const architecturePage = readFileSync(new URL("../../app/architecture/page.tsx", import.meta.url), "utf8");
const securityPage = readFileSync(new URL("../../app/security/page.tsx", import.meta.url), "utf8");
const failureLabPage = readFileSync(new URL("../../app/failure-lab/page.tsx", import.meta.url), "utf8");
const latency = JSON.parse(readFileSync(new URL("../../../../../proof/results/latency.json", import.meta.url), "utf8"));
const multilingual = JSON.parse(readFileSync(new URL("../../../../../proof/results/multilingual-e2e.json", import.meta.url), "utf8"));
const concurrency = JSON.parse(readFileSync(new URL("../../../../../proof/results/concurrency.json", import.meta.url), "utf8"));
const retrieval = JSON.parse(readFileSync(new URL("../../../../../proof/results/retrieval.json", import.meta.url), "utf8"));
const index = JSON.parse(readFileSync(new URL("../../../../../proof/results/index.json", import.meta.url), "utf8"));
const evidence = buildPerformanceEvidence(latency, multilingual, concurrency, retrieval, index);

function providerStage(key: string) {
  const stage = evidence.provider.stages.find((candidate) => candidate.key === key);
  assert.ok(stage, `missing ${key}`);
  return stage;
}

test("performance route renders the reviewer-facing measured conclusion", () => {
  assert.match(page, /The database is/);
  assert.match(page, /not the bottleneck/);
  assert.match(page, /The long tail is dominated by/);
  assert.match(page, /evidence\.provider\.dominantLongTailStage/);
});

test("provider-backed and local deterministic measurements are explicitly separated", () => {
  assert.match(page, /A \/ Provider-backed Buyer path/);
  assert.match(page, /B \/ Local deterministic \+ stub measurements/);
  assert.match(page, /PostgreSQL 17 Testcontainers/);
  assert.match(page, /real Gemini intent and embedding providers/);
});

test("Gemini intent, embedding, and deterministic retrieval percentiles come from generated evidence", () => {
  const intent = providerStage("intentCompile");
  const embedding = providerStage("queryEmbedding");
  const localRetrieval = providerStage("localDeterministicRetrieval");
  assert.deepEqual([intent.p50Millis, intent.p95Millis], [3237.0239, 13298.7314]);
  assert.deepEqual([embedding.p50Millis, embedding.p95Millis], [542.1521, 690.8272]);
  assert.deepEqual([localRetrieval.p50Millis, localRetrieval.p95Millis], [32.8302, 45.3321]);
  assert.match(page, /\[intent, embedding, deterministicRetrieval, totalDiscovery\]\.map/);
  assert.match(page, /formatLatency\(stage\.p50Millis\)/);
  assert.match(page, /formatLatency\(stage\.p95Millis\)/);
  assert.equal(formatLatency(intent.p95Millis), "13.30 s");
});

test("local commerce stages retain p50 p95 sample count and environment", () => {
  assert.equal(evidence.local.stages.length, 8);
  assert.ok(evidence.local.stages.every((stage) => stage.sampleSize === 10));
  assert.ok(evidence.local.stages.every((stage) => stage.environment === "TESTCONTAINERS_POSTGRESQL_17_LOCAL_STUB_PROVIDERS"));
  for (const key of ["CANDIDATE_CART_CONSTRUCTION", "AUTHORITATIVE_QUOTE", "CONSTRAINT_VERIFICATION", "PROPOSAL_CONSTRUCTION", "EXECUTION_GATE"]) {
    assert.ok(evidence.local.stages.some((stage) => stage.key === key));
  }
});

test("concurrency evidence renders all passing N=8 and N=32 rows", () => {
  assert.deepEqual(evidence.concurrency.callers, [8, 32]);
  assert.equal(evidence.concurrency.results.length, 10);
  assert.equal(evidence.concurrency.passed, 10);
  assert.match(page, /expectedPersistedRows/);
  assert.match(page, /observedPersistedRows/);
  assert.match(page, /expectedProviderCalls/);
  assert.match(page, /observedProviderCalls/);
  assert.match(page, /Correctness and convergence under concurrent callers/);
});

test("failed, incomplete, or non-finite evidence cannot be presented", () => {
  assert.throws(() => buildPerformanceEvidence({ ...latency, summary: { ...latency.summary, status: "FAIL" } }, multilingual, concurrency, retrieval, index), /Cannot present failed evidence/);
  const missing = structuredClone(multilingual);
  delete missing.summary.latencyMillis.intentCompile.p95;
  assert.throws(() => buildPerformanceEvidence(latency, missing, concurrency, retrieval, index), /Missing or invalid evidence number/);
  const nonFinite = structuredClone(multilingual);
  nonFinite.summary.latencyMillis.queryEmbedding.p50 = Number.NaN;
  assert.throws(() => buildPerformanceEvidence(latency, nonFinite, concurrency, retrieval, index), /Missing or invalid evidence number/);
});

test("hybrid-v3 provider-call proof is explicit and bounded to one pass", () => {
  assert.equal(evidence.provider.ranker, "hybrid-v3");
  assert.match(page, /Query embeddings per retrieval<\/dt><dd>1/);
  assert.match(page, /Vector candidate retrieval passes<\/dt><dd>1/);
  assert.match(page, /Semantic qualification<\/dt><dd>Local reuse/);
  assert.match(page, /No second embedding or semantic search is invoked/);
  assert.match(page, /semanticQualificationReusesOneQueryEmbeddingAndOneVectorCandidatePass/);
});

test("limitations reject SLA and vanity-throughput interpretations", () => {
  assert.match(page, /not production SLAs/);
  assert.match(page, /Production-scale throughput\/load testing has not been performed/);
  assert.match(page, /No maximum throughput is claimed/);
  assert.match(page, /Render cold starts are not represented/);
  assert.match(page, /No deployed smoke latency was measured/);
  assert.doesNotMatch(page, /(?:100|500|1000)[ -](?:concurrent )?(?:buyers|users)/i);
  assert.doesNotMatch(page, /production SLA(?:s)?(?:\s+is|\s*:|\s*=)/i);
});

test("reviewer routes link to performance and performance links back", () => {
  for (const source of [architecturePage, securityPage, failureLabPage]) {
    assert.match(source, /href="\/performance"/);
  }
  for (const destination of ["/architecture", "/security", "/failure-lab", "/proof"]) {
    assert.match(page, new RegExp(`href="${destination.replace("/", "\\/")}"`));
  }
});
