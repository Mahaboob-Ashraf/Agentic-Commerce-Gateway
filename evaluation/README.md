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

Retrieval calibration keeps `amazing-labelled-v1.json` unchanged (80 cases). A test-source snapshot
of hybrid-v2 and production hybrid-v3 use the same PostgreSQL 17/pgvector catalogue and cached live
Gemini query embeddings within each run. The bounded threshold grid records both historical discovery
metrics (valid matches followed by related alternatives) and Buyer-eligible valid-match metrics.
The two definitions must not be compared as if they measured the same thing. Per-case raw cosine
scores, identity gates, qualification components, dataset SHA-256 and grid outcomes are retained.

Select the highest threshold achieving the best valid-match recall among configurations that preserve
exact precision, no-match accuracy and wrong-product metrics, including zero newly introduced
unexpected valid candidates anywhere in the result set. The semantic lane activates only when the
normal hybrid lane has no valid match; it excludes explicit SKU/GTIN/brand/variant/size/colour fields.
Threshold calibration uses these labels, so it is not held-out validation or production-scale evidence.

Provider-backed results remain in `proof/results/retrieval.json` and `RETRIEVAL_EVAL.md`. Offline
test runs write `retrieval-offline.json` and `RETRIEVAL_OFFLINE.md`, preserving the latest live
measurement. Historical hybrid-v2 evidence and the rejected candidate-wise semantic experiment are
retained under `proof/results/history/`. `SemanticQualificationTest` reproduces the old ceiling and
checks the corrected wireless-earphones path using deterministic vector scores without network access.

Generated JSON includes the HEAD SHA, dirty-tree flag, UTC timestamp, runtime/host data, sample size,
exact reproduction command, outcome, limitations, and `whatThisDoesNotProve`. The generated aggregate
is `proof/results/SCORECARD.md`; backend, frontend, proof, mutation, attack, evaluation, and concurrency
counts remain separate.
