# Unknown provider-order creation recovery

**PASS — NO DUPLICATE PROVIDER ORDER**

The inert provider created an order and then raised a timeout marked as possibly delivered. Amana
persisted the uncertain attempt, reconciled the stable receipt, bound the existing provider order,
and retained one execution, one creation call, and one provider order. Execution advances to
`PAYMENT_PENDING`; payment state remains `PAYMENT_UNCERTAIN`, not `PAYMENT_CONFIRMED`.

## Provenance

- Schema version: `amana-failure-lab-evidence-v1`
- Commit SHA: `f01ea09f9aecbdb7d85c042b1ab667533c604e24`
- Working tree dirty: `true`
- Generated UTC: `2026-09-11T14:47:48.302876600Z`
- Runtime: Java `25.0.4.1+1-LTS`; Node `v24.19.0`
- OS / host: `Windows 11 10.0 amd64` / `DESKTOP-P3MC09V`
- Sample size: 1
- Exact command: `.\apps\backend\mvnw.cmd "-Dtest=dev.agenticcommerce.gateway.Task010PaymentControlIntegrationTest#lostCreateResponseIsReconciledByStableReceiptWithoutBlindRecreation" "-Damana.failure-lab.evidence=true" test`
- Summary: **PASS**

## Limitations

- Runs production execution/payment/reconciliation services against PostgreSQL 17 and an inert in-process provider adapter.
- Proves recovery of provider-order existence only; it does not prove captured payment or PAYMENT_CONFIRMED.

## What this does not prove

This isolated test does not call Razorpay, mutate deployed commerce state, or prove live-provider availability.
