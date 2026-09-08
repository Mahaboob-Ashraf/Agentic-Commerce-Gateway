# Amana evidence scorecard

Generated: 2026-09-08T17:11:37.163Z
Commit: 0e924615763c1147dc7c30a51bab1183da55211a (workingTreeDirty=true)

Evidence completeness: **OFFLINE_COMPLETE_PROVIDER_PARTIAL**. Backend and frontend counts remain separate and are never summed.

| Evidence | Actual result | What it proves | What it does not prove | Reproduction command |
|---|---|---|---|---|
| Backend tests | 258 tests; 0 failures; 0 errors; 1 skipped | Canonical Maven test result, kept separate. | It is not a frontend or proof count. | .\apps\backend\mvnw.cmd test |
| Frontend tests | 88 tests; 0 failures; 0 skipped | Buyer and Merchant Node test results, kept separate. | It is not a backend or proof count. | pnpm web:test |
| Existing deterministic safety | PASS; 250/250; hard violations=0 | Production deterministic guards pass their labelled corpus. | It is not mutation, HTTP, concurrency, or provider evidence. | .\apps\backend\mvnw.cmd "-Dtest=dev.agenticcommerce.gateway.proof.SafetyEvaluationTest" test |
| Retrieval | PASS; baseline R@5=0.4478; lexical post-fix R@5=0.5373; hybrid=READY | Labelled Amazing retrieval quality when executed. | It does not establish unlabelled or production-traffic quality. | pnpm proof:evaluate:retrieval |
| Intent | PASS; fields=0.931; multilingual=0.8889; authority gained=0 | Typed intent accuracy against human labels when provider-backed evaluation runs. | It does not grant financial authority or prove all languages. | pnpm proof:evaluate:intent |
| Negative controls | PASS; killed=12/12; survivors=0 | Whether selected safety tests detect intentional guard weakening. | It is not exhaustive mutation coverage. | pnpm proof:mutation |
| Adversarial | PASS; blocked=20/20; breaches=0 | Observed outcomes for the bounded attack catalogue. | It is not a penetration test. | pnpm proof:adversarial |
| Concurrency | PASS; passed=10/10 operation/N scenarios | Database/idempotency convergence at N=8 and N=32. | It is not production load testing. | pnpm proof:concurrency |
| Latency | PASS; BUYER_INTENT_COMPILATION: p50=9.658ms/p95=10.6855ms<br>CATALOGUE_RETRIEVAL: p50=12.6643ms/p95=14.8864ms<br>CANDIDATE_CART_CONSTRUCTION: p50=24.4747ms/p95=30.572ms<br>AUTHORITATIVE_QUOTE: p50=12.551ms/p95=13.3592ms<br>CONSTRAINT_VERIFICATION: p50=17.9502ms/p95=19.0145ms<br>PROPOSAL_CONSTRUCTION: p50=16.9382ms/p95=22.7294ms<br>EXECUTION_GATE: p50=15.5169ms/p95=23.6677ms<br>RAZORPAY_ORDER_CREATE_STUB: p50=9.8282ms/p95=12.5448ms | Measured local/stub path latency. | It is not an SLA and excludes unrun live providers. | pnpm proof:latency |

## Dataset inventory

- Retrieval cases: 80
- Intent utterances: 52

## Provenance

- Schema version: `amana-evidence-index-v1`
- Commit SHA: `0e924615763c1147dc7c30a51bab1183da55211a`
- Working tree dirty: `true`
- Generated UTC: `2026-09-08T17:11:37.163Z`
- Runtime: Java `openjdk version "25.0.4.1" 2026-08-18 LTS`; Node `v24.19.0`
- OS / host: `Windows_NT 10.0.26200 x64` / `DESKTOP-P3MC09V`
- Sample size: 132
- Exact command: `node scripts/evidence.mjs index`
- Summary: **PASS**

## Limitations

- Provider-backed and Testcontainers-dependent rows retain explicit NOT_RUN states when unavailable.

## What this does not prove

The index aggregates distinct suites; it is not a combined test count or a certification.
