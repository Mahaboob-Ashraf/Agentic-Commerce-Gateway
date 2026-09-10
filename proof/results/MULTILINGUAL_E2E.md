# Multilingual Buyer end-to-end retrieval

Status: **PASS**

Pipeline: multilingual utterance → real Gemini intent compile → production SearchRequest mapping → real Gemini query embedding → PostgreSQL 17/pgvector hybrid-v3 retrieval.

Recall cohort: 12 cases; negative controls: 5. Provider/model: `GEMINI/gemini-3.1-flash-lite`.

| Metric | Result |
|---|---:|
| Valid Recall@1 | 0.6667 |
| Valid Recall@5 | 0.6667 |
| Discovery Recall@1 | 0.8333 |
| Discovery Recall@5 | 0.8333 |
| No-match accuracy | 1.0 |
| Fabricated valid product rate | 0.0 |
| Wrong valid product / variant rate | 0.0 |

## Per language

| Language | Cases | Intent normalization correct | Valid R@1 | Valid R@5 |
|---|---:|---:|---:|---:|
| hi | 3 | 3 | 0.6667 | 0.6667 |
| hinglish | 3 | 3 | 0.6667 | 0.6667 |
| te | 3 | 3 | 0.6667 | 0.6667 |
| ur | 3 | 3 | 0.6667 | 0.6667 |

## Latency (milliseconds)

| Stage | p50 | p95 | max |
|---|---:|---:|---:|
| intentCompile | 3237.0239 | 13298.7314 | 13298.7314 |
| queryEmbedding | 542.1521 | 690.8272 | 690.8272 |
| localDeterministicRetrieval | 32.8302 | 45.3321 | 45.3321 |
| retrievalIncludingEmbedding | 579.8595 | 720.926 | 720.926 |
| totalPreCartDiscovery | 3837.6999 | 13838.6417 | 13838.6417 |

READY embeddings: 50; FAILED embeddings: 0; provider errors: 0; vector fallbacks: 0.

Authorization attempts: 0. This evaluator stops before cart construction and cannot grant purchase authority.

## Provenance

- Schema version: `amana-multilingual-e2e-evidence-v1`
- Commit SHA: `f7aff693c363fce7c3c56a7c98c5146aaaf8f4d3`
- Working tree dirty: `true`
- Generated UTC: `2026-09-10T14:31:26.310409800Z`
- Runtime: Java `25.0.4.1+1-LTS`; Node `v24.19.0`
- OS / host: `Windows 11 10.0 amd64` / `DESKTOP-P3MC09V`
- Sample size: 17
- Exact command: `pnpm proof:evaluate:multilingual`
- Summary: **PASS**

## Limitations

- This is a small labelled fixture evaluation: 12 recall cases and 5 negative controls, not production-scale benchmarking.
- Intent and embedding results can vary with provider/model revisions, quota, and network conditions.
- Intent compile timing is wall-clock time around GeminiBuyerIntentCompiler; local parsing is included and expected to be small.
- Local deterministic retrieval is estimated as hybrid-v3 wall time minus the separately timed query-embedding call.
- The pipeline stops at pre-cart discovery and therefore does not exercise authorization or payment.

## What this does not prove

This run does not establish production SLAs, quality on unlabelled languages or catalogues, or permission to execute a purchase.
