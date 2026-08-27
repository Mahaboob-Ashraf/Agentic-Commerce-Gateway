# State-machine concepts

## Status

Specification only; no state-machine code or tables exist in M0.

Canonical future concepts include merchant readiness, Persistent Commerce Thread, immutable
Transaction Proposal, Authorization Decision, Execution, Payment, Fulfilment, Refund, and Outbox Work.
Each will define legal transitions, terminal and retryable failures, timestamps, actors, evidence,
idempotency identity, and invariants before implementation.

A representative orchestration sequence is intent received → language/intent validated → catalogue
match or no-match → constraints evaluated → proposal created → authorization granted/denied/expired →
execution gate → provider order/checkout → evidence reconciliation → confirmed or unresolved →
lifecycle actions. These are conceptual names, not claims of implemented enums or endpoints.

Material proposal changes create a new immutable proposal. `UNKNOWN` hard constraints cannot pass.
Duplicate requests cannot create duplicate execution. Unknown payment outcomes stay explicit until
reconciled.
