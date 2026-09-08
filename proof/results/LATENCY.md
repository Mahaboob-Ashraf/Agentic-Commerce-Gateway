# Measured latency

Status: **PASS**

One cold/warm-up journey was excluded. Values are milliseconds.

| Path | N | min | p50 | p95 | p99 | max | Environment |
|---|---:|---:|---:|---:|---:|---:|---|
| BUYER_INTENT_COMPILATION | 10 | 8.4727 | 9.658 | 10.6855 | 10.6855 | 10.6855 | TESTCONTAINERS_POSTGRESQL_17_LOCAL_STUB_PROVIDERS |
| CATALOGUE_RETRIEVAL | 10 | 11.8998 | 12.6643 | 14.8864 | 14.8864 | 14.8864 | TESTCONTAINERS_POSTGRESQL_17_LOCAL_STUB_PROVIDERS |
| CANDIDATE_CART_CONSTRUCTION | 10 | 23.2515 | 24.4747 | 30.572 | 30.572 | 30.572 | TESTCONTAINERS_POSTGRESQL_17_LOCAL_STUB_PROVIDERS |
| AUTHORITATIVE_QUOTE | 10 | 12.0641 | 12.551 | 13.3592 | 13.3592 | 13.3592 | TESTCONTAINERS_POSTGRESQL_17_LOCAL_STUB_PROVIDERS |
| CONSTRAINT_VERIFICATION | 10 | 17.2931 | 17.9502 | 19.0145 | 19.0145 | 19.0145 | TESTCONTAINERS_POSTGRESQL_17_LOCAL_STUB_PROVIDERS |
| PROPOSAL_CONSTRUCTION | 10 | 16.1674 | 16.9382 | 22.7294 | 22.7294 | 22.7294 | TESTCONTAINERS_POSTGRESQL_17_LOCAL_STUB_PROVIDERS |
| EXECUTION_GATE | 10 | 14.8223 | 15.5169 | 23.6677 | 23.6677 | 23.6677 | TESTCONTAINERS_POSTGRESQL_17_LOCAL_STUB_PROVIDERS |
| RAZORPAY_ORDER_CREATE_STUB | 10 | 9.5719 | 9.8282 | 12.5448 | 12.5448 | 12.5448 | TESTCONTAINERS_POSTGRESQL_17_LOCAL_STUB_PROVIDERS |

Not an SLA; live Gemini and Razorpay measurements are separate and not run here.

## Provenance

- Schema version: `amana-latency-evidence-v1`
- Commit SHA: `0e924615763c1147dc7c30a51bab1183da55211a`
- Working tree dirty: `true`
- Generated UTC: `2026-09-08T17:07:15.759572Z`
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
