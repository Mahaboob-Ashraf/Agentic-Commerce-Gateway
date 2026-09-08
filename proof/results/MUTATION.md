# Selected negative controls

Status: **PASS**

- Attempted: 12
- Killed: 12
- Survived: 0
- Kill rate: 1.0

| Mutation | Guard weakened | Expected detecting tests | Result |
|---|---|---|---|
| MUT-001 | proposal hash validation | PROPOSAL-001..060 | KILLED |
| MUT-002 | authorization expiry | AUTH-013..016 | KILLED |
| MUT-003 | authorization/proposal binding | AUTH-005..012 | KILLED |
| MUT-004 | captured versus authorized payment | MONEY-001..048 | KILLED |
| MUT-005 | browser callback truth boundary | CALLBACK-001..012 | KILLED |
| MUT-006 | webhook signature validation | webhookIsRawVerifiedIdempotentAndOrderIndependent | KILLED |
| MUT-007 | payment amount comparison | MONEY-001..008 | KILLED |
| MUT-008 | payment currency comparison | MONEY-009..016 | KILLED |
| MUT-009 | capability READY evidence | CAPABILITY-001..036 | KILLED |
| MUT-010 | current mapping/version binding | CAPABILITY-025..036 | KILLED |
| MUT-011 | refund over-allocation prevention | REFUND-001..020 | KILLED |
| MUT-012 | serviceability UNKNOWN fail-closed | EVIDENCE-021..028 | KILLED |

## Provenance

- Schema version: `amana-mutation-evidence-v1`
- Commit SHA: `0e924615763c1147dc7c30a51bab1183da55211a`
- Working tree dirty: `true`
- Generated UTC: `2026-09-08T17:06:19.773137200Z`
- Runtime: Java `25.0.4.1+1-LTS`; Node `v24.19.0`
- OS / host: `Windows 11 10.0 amd64` / `DESKTOP-P3MC09V`
- Sample size: 12
- Exact command: `pnpm proof:mutation`
- Summary: **PASS**

## Limitations

- Mutants are narrow test-source predicates representing selected domain guard removals; production code has no mutation switch.
- PIT 1.25.8 Java 25 compatibility was investigated, but generic bytecode operators did not provide the required bounded one-mutant-per-domain-guard catalogue.

## What this does not prove

A killed selected mutant does not establish exhaustive mutation coverage of the repository.
