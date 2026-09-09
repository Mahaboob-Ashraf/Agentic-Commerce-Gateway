# Amana evidence scorecard

Generated: 2026-09-09T16:25:28.669Z
Commit: 6fc5594f32a8afdccd64b8ff71e150c16650a851 (workingTreeDirty=true)

Evidence completeness: **OFFLINE_COMPLETE_PROVIDER_PARTIAL**. Backend and frontend counts remain separate and are never summed.

| Evidence | Actual result | What it proves | What it does not prove | Reproduction command |
|---|---|---|---|---|
| Backend report snapshot | 272 tests; 0 failures; 0 errors; 1 skipped | Latest Surefire reports; focused reruns can replace individual class totals. | This snapshot is not a full-suite run total; the measured full verify result is reported separately in README. | .\apps\backend\mvnw.cmd test |
| Frontend tests | 98 tests; 0 failures; 0 skipped | Buyer and Merchant Node test results, kept separate. | It is not a backend or proof count. | pnpm web:test |
| Existing deterministic safety | PASS; 250/250; hard violations=0 | Production deterministic guards pass their labelled corpus. | It is not mutation, HTTP, concurrency, or provider evidence. | .\apps\backend\mvnw.cmd "-Dtest=dev.agenticcommerce.gateway.proof.SafetyEvaluationTest" test |
| Retrieval discovery | PASS; legacy lexical R@5=0.4478; post-fix lexical=0.5373; hybrid-v2=0.7463; hybrid-v3=0.8358 | Same labelled Amazing corpus; valid matches plus related alternatives. | Related alternatives are not Buyer-eligible matches; no production-traffic claim. | pnpm proof:evaluate:retrieval |
| Retrieval valid matches | v2 R@5=0.5522; v3=0.7612; semantic threshold=0.6; new unexpected valid products=0 | Measured Buyer qualification with deterministic authority gates. | Calibration on 80 labels is not held-out validation or execution authority. | pnpm proof:evaluate:retrieval |
| Intent | PASS; fields=0.931; multilingual=0.8889; authority gained=0 | Typed intent accuracy against human labels when provider-backed evaluation runs. | It does not grant financial authority or prove all languages. | pnpm proof:evaluate:intent |
| Negative controls | PASS; killed=12/12; survivors=0 | Whether selected safety tests detect intentional guard weakening. | It is not exhaustive mutation coverage. | pnpm proof:mutation |
| Adversarial | PASS; blocked=20/20; breaches=0 | Observed outcomes for the bounded attack catalogue. | It is not a penetration test. | pnpm proof:adversarial |
| Concurrency | PASS; passed=10/10 operation/N scenarios | Database/idempotency convergence at N=8 and N=32. | It is not production load testing. | pnpm proof:concurrency |
| Latency | PASS; BUYER_INTENT_COMPILATION: p50=10.2125ms/p95=15.3357ms<br>CATALOGUE_RETRIEVAL: p50=13.2994ms/p95=19.123ms<br>CANDIDATE_CART_CONSTRUCTION: p50=25.7652ms/p95=35.9215ms<br>AUTHORITATIVE_QUOTE: p50=13.4741ms/p95=15.2813ms<br>CONSTRAINT_VERIFICATION: p50=18.7141ms/p95=21.5218ms<br>PROPOSAL_CONSTRUCTION: p50=16.9563ms/p95=19.0369ms<br>EXECUTION_GATE: p50=15.4691ms/p95=17.5583ms<br>RAZORPAY_ORDER_CREATE_STUB: p50=9.9466ms/p95=11.2789ms | Measured local/stub path latency. | It is not an SLA and excludes unrun live providers. | pnpm proof:latency |

## Dataset inventory

- Retrieval cases: 80
- Intent utterances: 52

## Provenance

- Schema version: `amana-evidence-index-v1`
- Commit SHA: `6fc5594f32a8afdccd64b8ff71e150c16650a851`
- Working tree dirty: `true`
- Generated UTC: `2026-09-09T16:25:28.669Z`
- Runtime: Java `openjdk version "25.0.4.1" 2026-08-18 LTS`; Node `v24.19.0`
- OS / host: `Windows_NT 10.0.26200 x64` / `DESKTOP-P3MC09V`
- Sample size: 132
- Exact command: `node scripts/evidence.mjs index`
- Summary: **PASS**

## Limitations

- Provider-backed and Testcontainers-dependent rows retain explicit NOT_RUN states when unavailable.

## What this does not prove

The index aggregates distinct suites; it is not a combined test count or a certification.
