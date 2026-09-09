# Measured latency

Status: **PASS**

One cold/warm-up journey was excluded. Values are milliseconds.

| Path | N | min | p50 | p95 | p99 | max | Environment |
|---|---:|---:|---:|---:|---:|---:|---|
| BUYER_INTENT_COMPILATION | 10 | 9.4563 | 10.2125 | 15.3357 | 15.3357 | 15.3357 | TESTCONTAINERS_POSTGRESQL_17_LOCAL_STUB_PROVIDERS |
| CATALOGUE_RETRIEVAL | 10 | 12.8043 | 13.2994 | 19.123 | 19.123 | 19.123 | TESTCONTAINERS_POSTGRESQL_17_LOCAL_STUB_PROVIDERS |
| CANDIDATE_CART_CONSTRUCTION | 10 | 24.9377 | 25.7652 | 35.9215 | 35.9215 | 35.9215 | TESTCONTAINERS_POSTGRESQL_17_LOCAL_STUB_PROVIDERS |
| AUTHORITATIVE_QUOTE | 10 | 12.3154 | 13.4741 | 15.2813 | 15.2813 | 15.2813 | TESTCONTAINERS_POSTGRESQL_17_LOCAL_STUB_PROVIDERS |
| CONSTRAINT_VERIFICATION | 10 | 17.8201 | 18.7141 | 21.5218 | 21.5218 | 21.5218 | TESTCONTAINERS_POSTGRESQL_17_LOCAL_STUB_PROVIDERS |
| PROPOSAL_CONSTRUCTION | 10 | 15.974 | 16.9563 | 19.0369 | 19.0369 | 19.0369 | TESTCONTAINERS_POSTGRESQL_17_LOCAL_STUB_PROVIDERS |
| EXECUTION_GATE | 10 | 14.5885 | 15.4691 | 17.5583 | 17.5583 | 17.5583 | TESTCONTAINERS_POSTGRESQL_17_LOCAL_STUB_PROVIDERS |
| RAZORPAY_ORDER_CREATE_STUB | 10 | 9.2925 | 9.9466 | 11.2789 | 11.2789 | 11.2789 | TESTCONTAINERS_POSTGRESQL_17_LOCAL_STUB_PROVIDERS |

Not an SLA; live Gemini and Razorpay measurements are separate and not run here.

## Provenance

- Schema version: `amana-latency-evidence-v1`
- Commit SHA: `6fc5594f32a8afdccd64b8ff71e150c16650a851`
- Working tree dirty: `true`
- Generated UTC: `2026-09-09T16:21:31.471928500Z`
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
