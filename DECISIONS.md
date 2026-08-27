# DECISIONS.md

These accepted bootstrap ADRs record locked architectural decisions, not implementation claims.

## ADR-001 — Java 25 and Spring Boot 4.1.1

- **Decision:** Use Java 25 and Spring Boot 4.1.1 for the backend.
- **Why:** A current LTS JVM and cohesive web/security/data/operations platform fit the bounded build.
- **Alternatives considered:** Older Java/Spring versions; another server framework.
- **Tradeoffs:** Requires a new local/CI toolchain and ecosystem compatibility validation.
- **Effect on correctness:** Strong typing and mature transaction/security primitives.
- **Effect on testing:** JUnit/Spring integration support; the exact Java runtime is mandatory.
- **Effect on Buildathon demo:** One deployable backend with production-shaped foundations.

## ADR-002 — Modular monolith

- **Decision:** Build one process with explicit domain package boundaries.
- **Why:** Financial invariants need local transactions; the team needs low operational overhead.
- **Alternatives considered:** Microservices and an unstructured monolith.
- **Tradeoffs:** Module discipline is enforced in code rather than network boundaries.
- **Effect on correctness:** Cross-module invariants can be atomic in PostgreSQL.
- **Effect on testing:** Integration tests can exercise the whole path without distributed fixtures.
- **Effect on Buildathon demo:** Fewer deployment failure modes and a coherent trace.

## ADR-003 — PostgreSQL is authoritative

- **Decision:** PostgreSQL 17 owns commerce, authorization, payment evidence, audit, and durable work.
- **Why:** Transactions, constraints, locking, indexing, FTS, trigram, and vectors coexist reliably.
- **Alternatives considered:** Split operational stores, document databases, in-memory authority.
- **Tradeoffs:** Schema/SQL discipline and PostgreSQL-specific tests are required.
- **Effect on correctness:** One transactionally consistent source of financial truth.
- **Effect on testing:** PostgreSQL behavior must be exercised with Testcontainers, never H2 stand-ins.
- **Effect on Buildathon demo:** State and evidence remain inspectable during success and failure.

## ADR-004 — PostgreSQL transactional outbox with Spring dispatcher

- **Decision:** Persist money-critical work beside state, then dispatch it with Spring workers.
- **Why:** State and durable intent must commit atomically without a broker dependency.
- **Alternatives considered:** Kafka, direct after-commit calls, best-effort background jobs.
- **Tradeoffs:** Polling, locking, retry, and poison-work operations must be built explicitly.
- **Effect on correctness:** Eliminates the commit-versus-publish gap and supports stable retries.
- **Effect on testing:** Requires real transaction, lock, restart, and `SKIP LOCKED` tests.
- **Effect on Buildathon demo:** Recoverable failure can be shown without extra infrastructure.

## ADR-005 — Spring JDBC and explicit SQL for critical paths

- **Decision:** Use Spring JDBC/JdbcClient and explicit SQL for state-machine persistence.
- **Why:** Locks, constraints, idempotent inserts, and transition predicates must be visible.
- **Alternatives considered:** JPA/Hibernate for critical paths; raw JDBC everywhere.
- **Tradeoffs:** More SQL and mapping code, with less ORM convenience.
- **Effect on correctness:** Database concurrency semantics are deliberate and reviewable.
- **Effect on testing:** SQL behavior is covered against real PostgreSQL.
- **Effect on Buildathon demo:** Easier to explain why duplicate execution is prevented.

## ADR-006 — Explicit orchestration; no LangChain

- **Decision:** Orchestrate AI calls and deterministic gates in application code.
- **Why:** Authority boundaries, prompts, parsing, retries, and evidence need direct visibility.
- **Alternatives considered:** LangChain or another general agent framework.
- **Tradeoffs:** More owned integration code and fewer framework conveniences.
- **Effect on correctness:** Model output cannot silently bypass deterministic policy.
- **Effect on testing:** Each boundary can be contract-tested and failure-injected.
- **Effect on Buildathon demo:** The safety story is inspectable rather than framework-implicit.

## ADR-007 — Sarvam → Language Gate → Gemini

- **Decision:** Sarvam handles raw speech/STT/language; a deterministic gate precedes Gemini reasoning.
- **Why:** Speech uncertainty and reasoning uncertainty require separate evidence and policy.
- **Alternatives considered:** Send raw audio directly to one model; trust language inference silently.
- **Tradeoffs:** Adds a pipeline boundary and explicit unsupported/uncertain outcomes.
- **Effect on correctness:** Low-confidence or unsupported input cannot become financial intent silently.
- **Effect on testing:** STT, language-gate, and intent contracts can fail independently.
- **Effect on Buildathon demo:** Voice intelligence is shown with a clear safety boundary.

## ADR-008 — Responsive web P0; Flutter P1

- **Decision:** Deliver Next.js responsive buyer first and defer native Flutter initialization.
- **Why:** Web maximizes P0 reach while one complete safe path matters more than two partial clients.
- **Alternatives considered:** Flutter-first or parallel full clients.
- **Tradeoffs:** Native UX arrives later.
- **Effect on correctness:** Authority remains server-side and client-independent.
- **Effect on testing:** P0 E2E has one primary client surface.
- **Effect on Buildathon demo:** A broadly accessible client is available earlier.

## ADR-009 — Razorpay REST, Standard Checkout, webhook/API reconciliation

- **Decision:** Use explicit Test Mode REST, Standard Checkout, signed webhooks, and API evidence.
- **Why:** Orders, checkout UX, and authoritative confirmation have different trust roles.
- **Alternatives considered:** Browser callback truth, SDK-hidden semantics, custom card handling.
- **Tradeoffs:** Signature verification, reconciliation, and unknown outcomes require explicit code.
- **Effect on correctness:** Success needs exact captured/paid provider evidence and account/order match.
- **Effect on testing:** Adapter contracts, duplicate events, callback spoofing, and reconciliation are tested.
- **Effect on Buildathon demo:** Both success and graceful finalization failure can be demonstrated safely.

## ADR-010 — Redis is not a P0 dependency

- **Decision:** Use PostgreSQL for P0 state, locks, idempotency, and durable queues.
- **Why:** Redis adds another consistency/operations boundary without a measured P0 requirement.
- **Alternatives considered:** Redis caching, locks, queues, or session authority.
- **Tradeoffs:** PostgreSQL bears early workload; later evidence may justify cache-only use.
- **Effect on correctness:** No second authority or distributed-lock ambiguity.
- **Effect on testing:** One durable datastore covers correctness-critical behavior.
- **Effect on Buildathon demo:** Local and hosted infrastructure remains reproducible and compact.

## ADR-011 — PostgreSQL-backed Spring Security sessions

- **Decision:** Use Spring Security authentication with Spring Session JDBC tables managed by Flyway
  in PostgreSQL for P0 browser sessions.
- **Why:** Cloud Run instances can restart or scale to zero, so authenticated state cannot depend on
  one JVM's volatile memory; PostgreSQL is already the durable P0 authority.
- **Alternatives considered:** In-memory servlet sessions, Redis-backed sessions, JWT bearer tokens,
  and Supabase or another hosted authentication provider.
- **Tradeoffs:** Session reads and writes add PostgreSQL load, session schema/cleanup remain operational
  concerns, and browser clients must use cookies plus CSRF tokens.
- **Effect on correctness:** Server-generated, revocable authentication state survives instance
  replacement without making client-supplied identity claims authoritative.
- **Effect on testing:** Login, session restoration, CSRF, fixation protection, and logout are exercised
  through HTTP against real PostgreSQL with Testcontainers.
- **Effect on Buildathon demo:** Authentication works across stateless backend instances without adding
  Redis, JWT key management, or an external identity service to P0.
