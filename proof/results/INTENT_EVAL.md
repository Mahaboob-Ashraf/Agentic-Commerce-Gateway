# Buyer intent evaluation

Status: **PASS**

- Provider/model: `GEMINI/gemini-3.1-flash-lite`
- Labelled utterances: 52
- Field accuracy: 0.931
- Category accuracy: 0.9545
- Budget accuracy: 1
- Exact identity-field accuracy: 0.8065
- Correction/context accuracy: 1
- Ambiguity accuracy: 0.9565
- Multilingual subset accuracy: 0.8889
- Authorization-skip attempts gaining authority: 0
- Live provider latency (ms): N=52; min=2219.9519; p50=4866.2732; p95=12436.3641; p99=27164.5381; max=27164.5381; failures=0; rate-limit failures=0
- Provider evaluation command: `.\mvnw.cmd "-Dtest=dev.agenticcommerce.gateway.intent.IntentProviderEvaluationTest" test (RUN_PROVIDER_EVALUATION=true; GEMINI_API_KEY supplied via environment)`

Stored normalized outputs were regraded without another provider call after correcting numeric equality in the evaluator.

## Provenance

- Schema version: `amana-intent-evidence-v1`
- Commit SHA: `0e924615763c1147dc7c30a51bab1183da55211a`
- Working tree dirty: `true`
- Generated UTC: `2026-09-08T17:11:37.056Z`
- Runtime: Java `25.0.4.1+1-LTS`; Node `v24.19.0`
- OS / host: `Windows 11 10.0 amd64` / `DESKTOP-P3MC09V`
- Sample size: 52
- Exact command: `node scripts/evidence.mjs regrade-intent`
- Summary: **PASS**

## Limitations

- Labels compare only explicitly annotated fields; unlabelled fields are not graded.
- Provider accuracy varies with model revisions, quota, and network conditions.
- Stored normalized provider outputs were regraded after correcting numeric JsonNode equality; Gemini was not called again.

## What this does not prove

Parsing or label agreement never grants product, price, authorization, or payment authority.
