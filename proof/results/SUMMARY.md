# Amana deterministic safety proof

Safety is measured, not claimed.

- Suite: `amana-safety-proof-v1`
- Generated: `2026-09-05T13:44:48.054148700Z`
- Total cases: **250**
- Passed: **250**
- Failed: **0**
- Hard safety violations: **0**
- Fail-closed enforcement: **100.0%**
- Deterministic invariants defended: **15**

## Categories

- Evidence & policy: **48**
- Capability readiness: **36**
- Proposal integrity: **60**
- Money integrity: **48**
- Callback truth: **12**
- Payment idempotency: **16**
- Refund integrity: **20**
- Refund idempotency: **10**

## Reproduce

From the repository root:

```powershell
.\apps\backend\mvnw.cmd "-Dtest=dev.agenticcommerce.gateway.proof.SafetyEvaluationTest" test
```

The suite runs offline. It uses production deterministic reducers and guards with inert repository/provider boundaries. It does not call Gemini, Docker, PostgreSQL, Razorpay, or any external API, and it does not mutate a production payment.
