# Failure injection

## Status

Future test plan; no category has a pass status in M0.

Planned categories include provider timeout and malformed response, duplicate and reordered requests,
duplicate/out-of-order webhooks, browser abandonment or spoofed callbacks, unknown order/payment
outcome, signature failure, amount/currency/account mismatch, database contention and rollback,
outbox worker crash/restart, retry exhaustion, stale/expired authorization, proposal hash mismatch,
catalogue no-match, uncertain hard constraints, and refund race/cap violations.

Each bounded feature must identify the expected durable state, allowed retry, operator evidence,
audit record, and automated test. Failure is never converted to success for demo convenience.
