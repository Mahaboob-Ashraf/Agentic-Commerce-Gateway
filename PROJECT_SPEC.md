# PROJECT_SPEC.md

Status labels in this document mean: **SPECIFIED** is a locked design, **IMPLEMENTED** exists with
code in this repository, and **NOT YET IMPLEMENTED** is future bounded work.

## Project thesis and Buildathon interpretation

The Agentic Commerce Gateway is a Merchant Agentization Gateway plus Safe AI Buyer for Razorpay
Buildathon Track 01. It exposes normalized merchant capability to agents and helps buyers express,
evaluate, authorize, pay for, and manage commerce intent without allowing probabilistic models to
become the authority for money or state.

## Core engineering rule

AI may interpret, retrieve, rank, and propose. Deterministic application code and PostgreSQL own
constraints, authority, idempotency, transitions, provider evidence, and audit truth. **SPECIFIED.**

## High-level and repository architecture

The system is one Spring Boot modular monolith backed by PostgreSQL, with a responsive Next.js buyer
and explicit provider adapters. It is organized under `apps`, `contracts`, `infra`, `evaluation`,
`docs`, and `tasks`. This foundation is **IMPLEMENTED**; product flows are not.

## Locked stack

Java 25, Spring Boot 4.1.1, Spring Web MVC, Security, Validation, JDBC/JdbcClient, Flyway, Actuator,
Micrometer, Maven 3.9.11 Wrapper, PostgreSQL 17 with pgvector/pg_trgm/FTS, Testcontainers, Next.js
16.2.11, Node 24.19.0, strict TypeScript, Tailwind, and pnpm 10.15.0. Flutter is P1. No JPA for
critical state paths, LangChain, Kafka, BullMQ, or P0 Redis.

## Backend modules

`identity`, `agentization`, `catalogue`, `intent`, `commerce`, `risk`, `authorization`, `payment`,
`lifecycle`, `audit`, `outbox`, and `observability` are package boundaries in the modular monolith.
The package structure and tenant/actor identity persistence foundation are **IMPLEMENTED**; the
remaining business modules are **NOT YET IMPLEMENTED**.

## Provider boundaries

`MerchantAdapter`, `CatalogueProvider`, `EmbeddingProvider`, `SpeechProvider`, `LLMProvider`, and
`PaymentProvider` are **IMPLEMENTED** as deliberately minimal interfaces. Future canonical mappings
are approved merchant operations, approved catalogue sources, Gemini `gemini-embedding-2` at 768
dimensions, a replaceable Gemini Live adapter/configuration boundary targeting
`gemini-3.1-flash-live-preview` for P0 realtime voice, Gemini `gemini-3.6-flash` via the official Java
SDK for backend reasoning, and explicit Razorpay Test Mode REST. Implementations and method contracts
are **NOT YET IMPLEMENTED** except where later task context records completed bounded implementations.

## Data authority and financial invariants

PostgreSQL is authoritative for future commerce state, authorization, proposals, executions,
payments, refunds, audit, threads, merchant readiness, policy versions, and outbox work. The fifteen
invariants in `AGENTS.md` are normative. PostgreSQL/Flyway/Testcontainers foundation is
**IMPLEMENTED**; financial tables and behavior are **NOT YET IMPLEMENTED**.

## Major state-machine concepts

Merchant readiness, Commerce Thread, proposal, authorization, execution, payment, fulfilment,
refund, and outbox delivery will each have explicit states, legal transitions, terminal failures,
and evidence. Their specification names are recorded; implementations are **NOT YET IMPLEMENTED**.

## Merchant agentization

The future gateway discovers or accepts merchant data, normalizes catalogue and operations, records
readiness and policy versions, and exposes approved capabilities. It must distinguish unavailable,
unknown, and invalid data. **NOT YET IMPLEMENTED.**

## Buyer pipeline

The future pipeline accepts typed or spoken intent, performs a language gate, extracts structured
intent, retrieves exact products, evaluates hard constraints, proposes a reversible action, asks for
bound authorization, and only then enters the execution gate. **NOT YET IMPLEMENTED.**

## Voice boundary

ADR-024 is the approved post-PDF P0 override: Gemini 3.1 Flash Live is the realtime conversational
front door for microphone/native audio, multilingual switching, server VAD, interruption,
acknowledgement/clarification, bounded commerce invocation, and grounded result narration. The former
`Sarvam STT → transcript → Gemini` P0 path is superseded; no Sarvam runtime dependency is required.
Gemini Live is an interaction layer around Safe AI Buyer, not a third commerce agent and never commerce,
state, safety, authorization, payment, refund, or lifecycle authority.

P0 commerce remains application-managed: Live acknowledges and invokes a bounded function, application
code starts/continues the workflow outside the model, and only structured authoritative results return for
grounded speech. Browser capture/playback uses AudioWorklets, server VAD owns speech boundaries, and
health-based recovery creates a fresh constrained session with compact deterministic state. Selectable
Gemini-native voice is **P0 desired / implementation pending provider verification** and grants no authority.

## RAG, exact product, and no-match

Retrieval will combine PostgreSQL FTS, `pg_trgm`, and 768-dimensional pgvector embeddings. Final
product identity must resolve to canonical merchant catalogue data. An honest no-match is a valid
outcome; fabricated products are forbidden. **NOT YET IMPLEMENTED.**

## Hard constraints

Constraints have `PASS`, `FAIL`, or `UNKNOWN`; hard `UNKNOWN` never becomes `PASS`, and safety-critical
unknowns fail closed. Deterministic evaluators, not an LLM, make the final decision. **NOT YET
IMPLEMENTED.**

## TransactionProposal and AuthorizationDecision

Proposals will be immutable and content-hashed across all material fields. Authorization will bind
actor, proposal ID and hash, action type, and expiry. Any material change requires a new proposal and
new authorization. **NOT YET IMPLEMENTED.**

## Reversibility engine and Execution Gate

The reversibility engine will classify actions and surface consequences before authority is granted.
The execution gate will atomically enforce proposal integrity, authorization, expiry, constraints,
and exactly-one-execution idempotency. **NOT YET IMPLEMENTED.**

## Razorpay canonical path

The canonical P0 path is explicit Razorpay Test Mode REST order creation, Standard Checkout, signed
webhook verification, provider API reconciliation, and exact merchant/order/amount/currency/status
checks. Browser callbacks are UX signals only. **NOT YET IMPLEMENTED.**

## Transactional outbox

Money-critical follow-up work will be inserted in the same PostgreSQL transaction as authoritative
state and dispatched by Spring workers with locking, retry, stable idempotency, and observable dead
states. The design is **SPECIFIED**; tables and dispatcher are **NOT YET IMPLEMENTED**.

## Lifecycle, refunds, and Persistent Commerce Threads

Fulfilment, cancellation, returns, and refunds will remain linked to persistent commerce threads and
verified payment evidence. Refund retry identity is stable, and pending plus completed refunds cannot
exceed captured refundable value. **NOT YET IMPLEMENTED.**

## Scope by priority

- **P0:** responsive web, merchant foundation, buyer intelligence, safe transaction authority,
  Razorpay canonical payment, lifecycle basics, evaluation, and demo-critical failure handling.
- **P1:** native Flutter buyer and approved extensions that do not block P0.
- **P2:** scale or convenience capabilities justified after correctness and evaluation evidence.

Milestones in `ROADMAP.md` sequence delivery without changing these priorities.

## Evaluation and failure injection

Evaluation will cover retrieval correctness, intent/constraint decisions, state invariants,
idempotency, provider evidence, latency, and graceful failure. Injection will cover timeouts,
duplicates, reordering, unknown outcomes, malformed responses, database contention, and worker
restarts. Baselines and results are **NOT YET IMPLEMENTED** and **Not measured yet**.

## Audit and security

No secrets enter source control. Authorization and provider evidence must be actor-bound, minimally
exposed, and auditable. Merchant identities, application actors, canonical roles, and merchant-admin
memberships are **IMPLEMENTED**. Authenticated principal resolution, login/session mechanisms, API
authorization, and proposal-bound `AuthorizationDecision` remain **NOT YET IMPLEMENTED**.

## Canonical demo

The eventual demo has four connected parts: merchant agentization, AI buyer, graceful
payment/finalization failure, and lifecycle/refund. It is **NOT YET IMPLEMENTED**.

## Non-goals

Task 000 does not implement merchant onboarding, RAG, AI orchestration, AutoBuy, payment logic,
commerce schemas/state machines, refunds, provider calls, a full Flutter app, or distributed-service
infrastructure.

## Change control

Do not alter locked architecture, financial invariants, provider authority, or P0/P1/P2 semantics
without explicit approval and an ADR. Update `CONTEXT.md` after each task; record only measured facts.
