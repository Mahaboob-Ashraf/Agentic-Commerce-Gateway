# Evaluation

## Deterministic safety proof

The offline safety suite executes 250 parameterized cases against Amana's production deterministic
domain components. Persistence and provider boundaries are inert Mockito fixtures; they supply records
and capture effects but never supply a safety verdict. The evaluated code includes:

- `ReversibilityEngine` for fail-closed evidence and policy decisions
- `TransactionProposalCanonicalizer` and `AuthorizationService` for immutable proposal and replay binding
- `CapabilityBinding.ready()` for capability advertisement/readiness
- `PaymentEvidenceReducer` and `PaymentControlService` for financial truth, callbacks, and order idempotency
- `RefundService` for refund evidence binding and terminal replay behavior

Run it from the repository root:

```powershell
.\apps\backend\mvnw.cmd "-Dtest=dev.agenticcommerce.gateway.proof.SafetyEvaluationTest" test
```

The run writes both machine-readable and concise human-readable artifacts:

- `proof/results/latest.json`
- `proof/results/SUMMARY.md`

The suite does not require Docker, PostgreSQL, Razorpay, Gemini, credentials, or an external API. It
does not mutate a production payment. The standalone `/proof` web route imports the generated JSON at
build time, so the video surface remains available without a live backend or model call.
