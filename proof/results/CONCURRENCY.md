# Concurrency and idempotency evidence

Status: **PASS**

| Operation | N | Expected rows | Observed rows | Expected provider calls | Observed provider calls | Result |
|---|---:|---:|---:|---:|---:|---|
| EXECUTION_RESERVATION | 8 | 1 | 1 | 0 | 0 | PASS |
| PROVIDER_ORDER_CREATION | 8 | 1 | 1 | 1 | 1 | PASS |
| WEBHOOK_INGESTION | 8 | 1 | 1 | 0 | 0 | PASS |
| TRANSACTIONAL_OUTBOX_CLAIM | 8 | 1 | 1 | 1 | 1 | PASS |
| REFUND_LEDGER_RESERVATION | 8 | 1 | 1 | 0 | 0 | PASS |
| EXECUTION_RESERVATION | 32 | 1 | 1 | 0 | 0 | PASS |
| PROVIDER_ORDER_CREATION | 32 | 1 | 1 | 1 | 1 | PASS |
| WEBHOOK_INGESTION | 32 | 1 | 1 | 0 | 0 | PASS |
| TRANSACTIONAL_OUTBOX_CLAIM | 32 | 1 | 1 | 1 | 1 | PASS |
| REFUND_LEDGER_RESERVATION | 32 | 1 | 1 | 0 | 0 | PASS |

This is a bounded concurrency/idempotency proof, not production load testing.

## Provenance

- Schema version: `amana-concurrency-evidence-v1`
- Commit SHA: `6fc5594f32a8afdccd64b8ff71e150c16650a851`
- Working tree dirty: `true`
- Generated UTC: `2026-09-09T16:21:36.452557500Z`
- Runtime: Java `25.0.4.1+1-LTS`; Node `v24.19.0`
- OS / host: `Windows 11 10.0 amd64` / `DESKTOP-P3MC09V`
- Sample size: 10
- Exact command: `pnpm proof:concurrency`
- Summary: **PASS**

## Limitations

- Bounded PostgreSQL/Testcontainers concurrency at N=8 and N=32; provider calls use the deterministic test adapter.

## What this does not prove

This is not production load testing, multi-region testing, or a throughput claim.
