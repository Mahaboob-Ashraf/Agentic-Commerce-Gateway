# Amana deterministic safety proof

Safety is measured, not claimed.

- Suite: `amana-safety-proof-v1`
- Generated: `2026-09-10T17:21:50.130480200Z`
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

## Provenance

- Schema version: `amana-deterministic-safety-evidence-v1`
- Commit SHA: `2fb8c630ed488a4dc493d1703022768b64735990`
- Working tree dirty: `true`
- Generated UTC: `2026-09-10T17:21:50.107479400Z`
- Runtime: Java `25.0.4.1+1-LTS`; Node `v24.19.0`
- OS / host: `Windows 11 10.0 amd64` / `DESKTOP-P3MC09V`
- Sample size: 250
- Exact command: `.\apps\backend\mvnw.cmd "-Dtest=dev.agenticcommerce.gateway.proof.SafetyEvaluationTest" test`
- Summary: **PASS**

## Limitations

- Offline deterministic component proof with inert persistence/provider boundaries.

## What this does not prove

This does not prove mutation sensitivity, HTTP security, PostgreSQL concurrency, provider behavior, or production traffic safety.
