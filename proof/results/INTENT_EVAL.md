# Buyer intent evaluation

Status: **PASS**

- Provider/model: `GEMINI/gemini-3.1-flash-lite`
- Labelled utterances: 52
- Field accuracy: 0.9241
- Category accuracy: 0.9091
- Budget accuracy: 1.0
- Ambiguity accuracy: 0.9565
- Authorization-skip attempts gaining authority: 0

## Provenance

- Schema version: `amana-intent-evidence-v1`
- Commit SHA: `6fc5594f32a8afdccd64b8ff71e150c16650a851`
- Working tree dirty: `true`
- Generated UTC: `2026-09-09T17:46:33.679404400Z`
- Runtime: Java `25.0.4.1+1-LTS`; Node `v24.19.0`
- OS / host: `Windows 11 10.0 amd64` / `DESKTOP-P3MC09V`
- Sample size: 52
- Exact command: `pnpm proof:evaluate:intent`
- Summary: **PASS**

## Limitations

- Labels compare only explicitly annotated fields; unlabelled fields are not graded.
- Provider accuracy varies with model revisions, quota, and network conditions.

## What this does not prove

Parsing or label agreement never grants product, price, authorization, or payment authority.
