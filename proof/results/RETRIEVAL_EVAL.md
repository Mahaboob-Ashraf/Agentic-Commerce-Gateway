# Retrieval evaluation

Status: **PASS**

The baseline preserves the former strict free-form category/brand gate. The post-fix run uses production lexical retrieval.

Historical discovery definition: valid matches followed by related alternatives.

| Metric | Legacy lexical | Post-fix lexical | Hybrid v3 discovery |
|---|---:|---:|---:|
| Recall@1 | 0.4478 | 0.5373 | 0.8358 |
| Recall@5 | 0.4478 | 0.5373 | 0.8358 |
| Exact-identity precision | 1.0 | 1.0 | 1.0 |
| Generic category success | 0.1429 | 0.5 | 1.0 |
| Semantic success | 0.0833 | 0.1667 | 0.6667 |
| Typo/ASR success | 0.6667 | 0.6667 | 1.0 |
| Multilingual success | 0.1667 | 0.1667 | 0.4167 |
| No-match accuracy | 1.0 | 1.0 | 0.9 |
| Fabricated-product rate | 0.0 | 0.0 | 0.0 |
| Wrong-variant substitution rate | 0.0 | 0.0 | 0.0149 |

Hybrid comparison: **READY**. READY=50, FAILED=0, lexical fallbacks=0.
Hybrid versus lexical delta: Recall@1=0.2985, Recall@5=0.2985.
Hybrid fabricated products=0; wrong-variant substitutions=1.

## Paired hybrid-v2 / hybrid-v3 comparison

Same 80 labels, catalogue, PostgreSQL candidate queries and cached live Gemini query vectors.
Valid matches measure Buyer eligibility; related alternatives cannot build a cart.

| Metric | v2 discovery | v3 discovery | v2 valid only | v3 valid only |
|---|---:|---:|---:|---:|
| recallAt1 | 0.7463 | 0.8358 | 0.5522 | 0.7612 |
| recallAt5 | 0.7463 | 0.8358 | 0.5522 | 0.7612 |
| exactIdentityPrecision | 1.0 | 1.0 | 1.0 | 1.0 |
| genericCategorySuccessRate | 1.0 | 1.0 | 0.4286 | 0.7143 |
| semanticSuccessRate | 0.4167 | 0.6667 | 0.1667 | 0.5833 |
| typoAsrSuccessRate | 1.0 | 1.0 | 0.8333 | 1.0 |
| multilingualSuccessRate | 0.1667 | 0.4167 | 0.1667 | 0.4167 |
| noMatchAccuracy | 0.9 | 0.9 | 1.0 | 1.0 |
| fabricatedProductRate | 0.0 | 0.0 | 0.0 | 0.0 |
| wrongVariantSubstitutionRate | 0.0149 | 0.0149 | 0.0 | 0.0 |

## Semantic threshold calibration

Initial 0.65–0.95 sweep found relevant similarities below 0.70; refinement adds 0.50–0.675.
Choose the highest threshold with maximum valid recall among safety-preserving rows. This is calibration, not held-out validation.

| Threshold | Valid R@1 | Valid semantic | Valid category | Valid no-match | Valid wrong variants | New unexpected valid products | Safety preserved |
|---|---:|---:|---:|---:|---:|---:|---|
| 0.5 | 0.8358 | 0.8333 | 0.8571 | 0.2 | 0.0299 | 56 | false |
| 0.55 | 0.791 | 0.75 | 0.7857 | 0.6 | 0.0299 | 15 | false |
| 0.6 | 0.7612 | 0.5833 | 0.7143 | 1.0 | 0.0 | 0 | true |
| 0.625 | 0.7164 | 0.5 | 0.5714 | 1.0 | 0.0 | 0 | true |
| 0.65 | 0.597 | 0.3333 | 0.4286 | 1.0 | 0.0 | 0 | true |
| 0.675 | 0.5522 | 0.1667 | 0.4286 | 1.0 | 0.0 | 0 | true |
| 0.7 | 0.5522 | 0.1667 | 0.4286 | 1.0 | 0.0 | 0 | true |
| 0.75 | 0.5522 | 0.1667 | 0.4286 | 1.0 | 0.0 | 0 | true |
| 0.8 | 0.5522 | 0.1667 | 0.4286 | 1.0 | 0.0 | 0 | true |
| 0.85 | 0.5522 | 0.1667 | 0.4286 | 1.0 | 0.0 | 0 | true |
| 0.9 | 0.5522 | 0.1667 | 0.4286 | 1.0 | 0.0 | 0 | true |
| 0.95 | 0.5522 | 0.1667 | 0.4286 | 1.0 | 0.0 | 0 | true |

Selected threshold: 0.6. Safety non-regression: true. Wireless earphones under 3500: VALID_MATCH.

## Provenance

- Schema version: `amana-retrieval-evidence-v1`
- Commit SHA: `6fc5594f32a8afdccd64b8ff71e150c16650a851`
- Working tree dirty: `true`
- Generated UTC: `2026-09-09T16:23:47.458876900Z`
- Runtime: Java `25.0.4.1+1-LTS`; Node `v24.19.0`
- OS / host: `Windows 11 10.0 amd64` / `DESKTOP-P3MC09V`
- Sample size: 80
- Exact command: `pnpm proof:evaluate:retrieval`
- Summary: **PASS**

## Limitations

- Baseline is a test-source snapshot of the former strict free-form category/brand gate over the same lexical candidate generator.
- Hybrid metrics use live Gemini embeddings and can vary with provider/model revisions.
- Historical discovery metrics concatenate valid matches and related alternatives. Valid-match metrics measure Buyer qualification separately.
- Threshold grid is calibration on these 80 labels, not held-out validation or production-scale benchmarking. Query embeddings are cached within the run for paired comparisons.

## What this does not prove

This labelled fixture does not prove quality on unlabelled traffic, other catalogues, or future provider/model revisions.
