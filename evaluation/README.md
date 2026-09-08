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

## Final evidence commands

From the repository root, run all offline reproducible evidence with:

```powershell
pnpm proof:verify
```

This validates both labelled datasets and runs the deterministic safety proof, selected test-source
mutants, bounded adversarial boundary tests, PostgreSQL concurrency scenarios at N=8 and N=32, and
local/stub latency measurements. It fails non-zero on a failed invariant, surviving expected mutant,
breach, concurrency mismatch, or invalid dataset.

Provider-backed evaluation remains separate from pull-request CI:

```powershell
$env:GEMINI_API_KEY = '<configured outside source control>'
$env:CATALOGUE_EMBEDDING_ENABLED = 'true'
pnpm proof:evaluate:retrieval
pnpm proof:evaluate:intent
```

`proof:evaluate:retrieval` always records the legacy-gate baseline and post-fix lexical mode. It only
reports hybrid deltas when the provider is active and the ingested catalogue has READY embeddings.
`proof:evaluate:intent` uses `gemini-3.1-flash-lite` and rejects malformed structured output through
the production compiler contract. Neither command grants product, price, authorization, or payment
authority.

Generated JSON includes the HEAD SHA, dirty-tree flag, UTC timestamp, runtime/host data, sample size,
exact reproduction command, outcome, limitations, and `whatThisDoesNotProve`. The generated aggregate
is `proof/results/SCORECARD.md`; backend, frontend, proof, mutation, attack, evaluation, and concurrency
counts remain separate.
