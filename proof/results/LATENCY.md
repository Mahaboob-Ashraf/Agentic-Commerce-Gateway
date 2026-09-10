# Measured latency

Status: **PASS**

One cold/warm-up journey was excluded. Values are milliseconds.

| Path | N | min | p50 | p95 | p99 | max | Environment |
|---|---:|---:|---:|---:|---:|---:|---|
| BUYER_INTENT_COMPILATION | 10 | 7.679 | 7.9045 | 8.4053 | 8.4053 | 8.4053 | TESTCONTAINERS_POSTGRESQL_17_LOCAL_STUB_PROVIDERS |
| CATALOGUE_RETRIEVAL | 10 | 9.9113 | 10.3383 | 10.8844 | 10.8844 | 10.8844 | TESTCONTAINERS_POSTGRESQL_17_LOCAL_STUB_PROVIDERS |
| CANDIDATE_CART_CONSTRUCTION | 10 | 19.5051 | 20.2279 | 21.4802 | 21.4802 | 21.4802 | TESTCONTAINERS_POSTGRESQL_17_LOCAL_STUB_PROVIDERS |
| AUTHORITATIVE_QUOTE | 10 | 10.0029 | 10.4022 | 10.9183 | 10.9183 | 10.9183 | TESTCONTAINERS_POSTGRESQL_17_LOCAL_STUB_PROVIDERS |
| CONSTRAINT_VERIFICATION | 10 | 14.9019 | 15.4029 | 17.0208 | 17.0208 | 17.0208 | TESTCONTAINERS_POSTGRESQL_17_LOCAL_STUB_PROVIDERS |
| PROPOSAL_CONSTRUCTION | 10 | 13.8177 | 14.1575 | 25.8816 | 25.8816 | 25.8816 | TESTCONTAINERS_POSTGRESQL_17_LOCAL_STUB_PROVIDERS |
| EXECUTION_GATE | 10 | 12.133 | 12.8483 | 17.1645 | 17.1645 | 17.1645 | TESTCONTAINERS_POSTGRESQL_17_LOCAL_STUB_PROVIDERS |
| RAZORPAY_ORDER_CREATE_STUB | 10 | 8.3318 | 8.5178 | 10.148 | 10.148 | 10.148 | TESTCONTAINERS_POSTGRESQL_17_LOCAL_STUB_PROVIDERS |

Not an SLA; live Gemini and Razorpay measurements are separate and not run here.

## Provenance

- Schema version: `amana-latency-evidence-v1`
- Commit SHA: `2fb8c630ed488a4dc493d1703022768b64735990`
- Working tree dirty: `true`
- Generated UTC: `2026-09-10T17:22:36.857720100Z`
- Runtime: Java `25.0.4.1+1-LTS`; Node `v24.19.0`
- OS / host: `Windows 11 10.0 amd64` / `DESKTOP-P3MC09V`
- Sample size: 80
- Exact command: `pnpm proof:latency`
- Summary: **PASS**

## Limitations

- Local repeatable measurement uses PostgreSQL Testcontainers plus deterministic merchant/payment adapters.
- Gemini and live Razorpay latency are not mixed into these measurements.

## What this does not prove

These measurements are not SLAs, production load results, Render cold starts, or live-provider latency.
