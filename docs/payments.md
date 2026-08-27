# Payment safety specification

## Status

Implementation pending. No Razorpay credential, call, order, payment, or refund behavior exists in M0.

The canonical future path is Razorpay Test Mode REST order creation, Standard Checkout, verified
webhooks, provider API reconciliation, and exact evidence matching. A browser callback is a UX signal,
never financial truth. One immutable authorized proposal creates exactly one execution, and an
execution can own at most one Razorpay order.

Success requires the correct merchant/account, correct Razorpay order, exact amount, correct currency,
captured payment, and paid order. Unknown provider outcome requires reconciliation before retry.
Money-critical follow-up uses the PostgreSQL transactional outbox. Refunds use stable idempotency,
and pending plus completed refund value cannot exceed captured refundable value.

The complete normative invariants are in `AGENTS.md`. Later payment tasks must add explicit schemas,
state transitions, signature/evidence validation, failure paths, and real PostgreSQL concurrency tests.
