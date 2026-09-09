# Retrieval evaluation

Status: **PASS**

The baseline preserves the former strict free-form category/brand gate. The post-fix run uses production lexical retrieval.

| Metric | Baseline | Post-fix lexical | Hybrid |
|---|---:|---:|---:|
| Recall@1 | 0.4478 | 0.5373 | 0.7463 |
| Recall@5 | 0.4478 | 0.5373 | 0.7463 |
| Exact-identity precision | 1.0 | 1.0 | 1.0 |
| Generic category success | 0.1429 | 0.5 | 1.0 |
| Typo/ASR success | 0.6667 | 0.6667 | 1.0 |
| Multilingual success | 0.1667 | 0.1667 | 0.1667 |
| No-match accuracy | 1.0 | 1.0 | 0.9 |
| Fabricated-product rate | 0.0 | 0.0 | 0.0 |
| Wrong-variant substitution rate | 0.0 | 0.0 | 0.0149 |

Hybrid comparison: **READY**. READY=50, FAILED=0, lexical fallbacks=0.
Hybrid versus lexical delta: Recall@1=0.209, Recall@5=0.209.
Hybrid fabricated products=0; wrong-variant substitutions=1.

## Provenance

- Schema version: `amana-retrieval-evidence-v1`
- Commit SHA: `0e924615763c1147dc7c30a51bab1183da55211a`
- Working tree dirty: `true`
- Generated UTC: `2026-09-08T17:10:02.309005200Z`
- Runtime: Java `25.0.4.1+1-LTS`; Node `v24.19.0`
- OS / host: `Windows 11 10.0 amd64` / `DESKTOP-P3MC09V`
- Sample size: 80
- Exact command: `pnpm proof:evaluate:retrieval`
- Summary: **PASS**

## Limitations

- Baseline is a test-source snapshot of the former strict free-form category/brand gate over the same lexical candidate generator.
- Hybrid metrics use live Gemini embeddings and can vary with provider/model revisions.

## What this does not prove

This labelled fixture does not prove quality on unlabelled traffic, other catalogues, or future provider/model revisions.
