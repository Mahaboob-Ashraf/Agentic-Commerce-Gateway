# Retrieval evaluation

Status: **PASS**

The baseline preserves the former strict free-form category/brand gate. The post-fix run uses production lexical retrieval.

Historical discovery definition: valid matches followed by related alternatives.

| Metric | Legacy lexical | Post-fix lexical | Hybrid v3 discovery |
|---|---:|---:|---:|
| Recall@1 | 0.4478 | 0.5373 | NOT_RUN |
| Recall@5 | 0.4478 | 0.5373 | NOT_RUN |
| Exact-identity precision | 1.0 | 1.0 | NOT_RUN |
| Generic category success | 0.1429 | 0.5 | NOT_RUN |
| Semantic success | 0.0833 | 0.1667 | NOT_RUN |
| Typo/ASR success | 0.6667 | 0.6667 | NOT_RUN |
| Multilingual success | 0.1667 | 0.1667 | NOT_RUN |
| No-match accuracy | 1.0 | 1.0 | NOT_RUN |
| Fabricated-product rate | 0.0 | 0.0 | NOT_RUN |
| Wrong-variant substitution rate | 0.0 | 0.0 | NOT_RUN |

Hybrid comparison: **NOT_RUN_VECTOR_PROVIDER_INACTIVE_OR_NO_READY_VECTORS**. READY=0, FAILED=50, lexical fallbacks=80.
Hybrid versus lexical delta: Recall@1=NOT_RUN, Recall@5=NOT_RUN.
Hybrid fabricated products=NOT_RUN; wrong-variant substitutions=NOT_RUN.

Semantic calibration: NOT_RUN (provider inactive).

## Provenance

- Schema version: `amana-retrieval-evidence-v1`
- Commit SHA: `6fc5594f32a8afdccd64b8ff71e150c16650a851`
- Working tree dirty: `true`
- Generated UTC: `2026-09-09T17:48:28.891499100Z`
- Runtime: Java `25.0.4.1+1-LTS`; Node `v24.19.0`
- OS / host: `Windows 11 10.0 amd64` / `DESKTOP-P3MC09V`
- Sample size: 80
- Exact command: `pnpm proof:evaluate:retrieval`
- Summary: **PASS**

## Limitations

- Baseline is a test-source snapshot of the former strict free-form category/brand gate over the same lexical candidate generator.
- The offline run deliberately disables embeddings; hybrid comparison remains NOT_RUN until a provider-backed run has READY vectors.

## What this does not prove

This labelled fixture does not prove quality on unlabelled traffic, other catalogues, or future provider/model revisions.
