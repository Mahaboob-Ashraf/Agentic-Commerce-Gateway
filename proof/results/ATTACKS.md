# Focused adversarial evidence

Status: **PASS** — blocked=20, breaches=0.

True HTTP cases: 2; in-process boundary cases: 18.

| Attack | Mode | Scenario | Observed reason | Result |
|---|---|---|---|---|
| ATK-PAY-001 | IN_PROCESS_BOUNDARY_TEST | forged Razorpay webhook signature | WEBHOOK_SIGNATURE_INVALID | BLOCKED |
| ATK-PAY-002 | IN_PROCESS_BOUNDARY_TEST | duplicate/replayed webhook | ALREADY_PROCESSED | BLOCKED |
| ATK-PAY-003 | IN_PROCESS_BOUNDARY_TEST | out-of-order webhook evidence | AUTHORITATIVE_EVIDENCE_INCOMPLETE_THEN_CONFIRMED | BLOCKED |
| ATK-PAY-004 | IN_PROCESS_BOUNDARY_TEST | mismatched provider order ID | PROVIDER_EVIDENCE_IDENTITY_MISMATCH | BLOCKED |
| ATK-PAY-005 | IN_PROCESS_BOUNDARY_TEST | mismatched amount | AUTHORITATIVE_EVIDENCE_INCOMPLETE | BLOCKED |
| ATK-PAY-006 | IN_PROCESS_BOUNDARY_TEST | mismatched currency | PROVIDER_EVIDENCE_IDENTITY_MISMATCH | BLOCKED |
| ATK-PAY-007 | IN_PROCESS_BOUNDARY_TEST | authorized but not captured payment | AUTHORITATIVE_EVIDENCE_INCOMPLETE | BLOCKED |
| ATK-PAY-008 | IN_PROCESS_BOUNDARY_TEST | browser callback attempts financial truth | FINANCIAL_CONFIRMATION_FALSE | BLOCKED |
| ATK-AUTH-009 | IN_PROCESS_BOUNDARY_TEST | proposal hash tampering | PROPOSAL_HASH_MISMATCH | BLOCKED |
| ATK-AUTH-010 | IN_PROCESS_BOUNDARY_TEST | authorize proposal A then execute proposal B | AUTHORIZATION_REPLAY_MISMATCH | BLOCKED |
| ATK-AUTH-011 | IN_PROCESS_BOUNDARY_TEST | expired authorization | AUTHORIZATION_EXPIRED | BLOCKED |
| ATK-AUTH-012 | IN_PROCESS_BOUNDARY_TEST | execute with FAIL hard constraint | HARD_CONSTRAINT_FAILED | BLOCKED |
| ATK-AUTH-013 | IN_PROCESS_BOUNDARY_TEST | execute with UNKNOWN serviceability | SERVICEABILITY_UNKNOWN | BLOCKED |
| ATK-TENANT-014 | BLACK_BOX_HTTP | cross-tenant buyer read | HTTP_404_OWNER_PREDICATE | BLOCKED |
| ATK-TENANT-015 | IN_PROCESS_BOUNDARY_TEST | cross-merchant mapping write | MERCHANT_ACCESS_DENIED | BLOCKED |
| ATK-CSRF-016 | BLACK_BOX_HTTP | missing CSRF on browser state change | HTTP_403 | BLOCKED |
| ATK-SSRF-017 | IN_PROCESS_BOUNDARY_TEST | localhost/private/link-local merchant endpoint | ENDPOINT_ADDRESS_UNSAFE | BLOCKED |
| ATK-GROUND-018 | IN_PROCESS_BOUNDARY_TEST | nonexistent product requests fabricated SKU | NO_SINGLE_MERCHANT_NO_CART | BLOCKED |
| ATK-GROUND-019 | IN_PROCESS_BOUNDARY_TEST | instruction-like catalogue text changes authority | HARD_CONSTRAINT_UNKNOWN | BLOCKED |
| ATK-GROUND-020 | IN_PROCESS_BOUNDARY_TEST | buyer says just buy without confirmation | WAITING_FOR_EXPLICIT_PAYMENT_AUTHORIZATION | BLOCKED |

Only BLACK_BOX_HTTP cases claim HTTP traversal. This is a bounded suite, not a penetration test.

## Provenance

- Schema version: `amana-adversarial-evidence-v1`
- Commit SHA: `6fc5594f32a8afdccd64b8ff71e150c16650a851`
- Working tree dirty: `true`
- Generated UTC: `2026-09-09T16:21:15.571Z`
- Runtime: Java `openjdk version "25.0.4.1" 2026-08-18 LTS`; Node `v24.19.0`
- OS / host: `Windows_NT 10.0.26200 x64` / `DESKTOP-P3MC09V`
- Sample size: 20
- Exact command: `pnpm proof:adversarial`
- Summary: **PASS**

## Limitations

- Each recorded outcome is backed by a named passing test; only cases labelled BLACK_BOX_HTTP traverse the HTTP boundary.
- This bounded catalogue is not a penetration test and does not cover an internet deployment.

## What this does not prove

Twenty selected attacks do not establish absence of other vulnerabilities.
