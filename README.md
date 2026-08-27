# Agentic Commerce Gateway

**A Merchant Agentization Gateway + Safe AI Buyer for Razorpay Buildathon 2026 — Track 01: AI Growth & Agentic Commerce.**

Agentic Commerce Gateway turns existing merchants into AI-transactable merchants without requiring them to rebuild their commerce stack, then lets AI buyers discover, reason, transact, and manage post-purchase actions with bounded, explainable authority.

> **AI handles unstructured meaning. Deterministic software controls truth, authority, and money.**

---

## The Problem

AI agents can understand what a user wants, but safely completing a real purchase is much harder.

A commerce agent must know:

- which merchants are actually capable of serving the request;
- which product facts are trustworthy;
- whether hard constraints such as allergens, budget, model, variant, or delivery are satisfied;
- whether the user needs clarification or confirmation;
- exactly what transaction is being authorized;
- whether payment genuinely succeeded;
- what happens when payment, fulfilment, or refunds fail halfway through.

Most existing merchant systems were not designed for this.

Agentic Commerce Gateway adds that missing layer.

---

## What We Are Building

The system has two major sides.

### Merchant Agentization Gateway

An existing merchant can expose its commerce stack through API/OpenAPI, catalogue data, policies, and Razorpay Test Mode configuration.

The gateway:

- maps merchant APIs into normalized commerce capabilities;
- extracts and structures merchant policies;
- requires merchant approval before AI-generated mappings become authoritative;
- runs capability-specific contract tests;
- produces a versioned Agent Commerce Manifest;
- marks capabilities as `READY`, `BLOCKED`, or `UNTESTED`.

The merchant does not need to rebuild their storefront or backend.

---

### Safe AI Buyer

The buyer can use natural language or voice to express a commerce goal.

Example:

> “500 ke andar do logon ke liye high-protein vegetarian snacks order karo, peanuts bilkul nahi.”

The canonical flow is:

    Voice / Text
        ↓
    Sarvam Saaras v3
        ↓
    Language + Ambiguity Gate
        ↓
    Gemini Intent Compiler
        ↓
    Merchant Discovery
        ↓
    Hybrid Product Retrieval
        ↓
    Exact Product Identity Gate
        ↓
    Candidate Cart
        ↓
    Deterministic Hard-Constraint Verification
        ↓
    Immutable Transaction Proposal
        ↓
    Reversibility / Action-Risk Engine
        ↓
    Proposal-Bound Authorization
        ↓
    Deterministic Execution Gate
        ↓
    Razorpay Test Mode Checkout
        ↓
    Verified Payment Evidence
        ↓
    Merchant Fulfilment
        ↓
    Persistent Commerce Thread

The same thread can later support cancellation, return, refund, reorder, and bounded AutoBuy workflows.

---

## Bounded Agentic Commerce

The AI does not receive unrestricted authority to transact.

Human involvement depends on both **uncertainty** and the **cost of being wrong**.

Every action resolves to one of:

- `AUTO_EXECUTE`
- `CLARIFY`
- `EXPLICIT_CONFIRMATION`
- `BLOCK`

`AUTO_EXECUTE` does **not** mean silently charging the customer.

In P0, it means the system may proceed without another application-level confirmation and automatically open Razorpay Checkout. The customer still authorizes the actual payment inside Razorpay.

---

## Deterministic Money Path

Every money action is represented by an immutable `TransactionProposal`.

Material proposal fields are serialized canonically and hashed:

    material transaction fields
            ↓
    canonical representation
            ↓
          SHA-256
            ↓
       proposal_hash

Any material change creates a new proposal.

User authorization is separately represented by an immutable `AuthorizationDecision` bound to:

- the authenticated actor;
- the exact proposal ID;
- the exact proposal hash;
- the exact action type;
- an expiry.

A previous approval can never silently authorize a changed transaction.

---

## Payment Reliability

The payment control plane is designed around explicit invariants:

- exactly one Execution per TransactionProposal;
- at most one Razorpay Order per Execution;
- duplicate requests cannot create duplicate executions;
- payment retries reuse the same valid Razorpay Order;
- uncertain outcomes are reconciled before retrying;
- browser callbacks are never treated as financial truth;
- successful payment requires verified provider evidence;
- `payment.status = captured` and `order.status = paid` are both required;
- duplicate and out-of-order webhooks are handled idempotently;
- late authorization/capture has an explicit reconciliation path;
- post-payment merchant fulfilment is durable through a PostgreSQL transactional outbox;
- refunds use stable idempotency keys and database-enforced refundable-amount bounds.

---

## AI Responsibility Boundary

Different AI systems are used for different jobs.

### Sarvam AI

**Saaras v3** owns:

- speech-to-text;
- Indian-language transcription;
- code-mixed speech;
- language detection when required.

### Gemini

Gemini owns:

- intent compilation;
- structured reasoning;
- ambiguity handling;
- RAG reasoning;
- merchant API/policy interpretation;
- onboarding intelligence.

Gemini does **not** control:

- payment truth;
- authorization;
- transaction state;
- refunds;
- execution invariants.

All AI outputs cross validated structured boundaries before entering deterministic application logic.

---

## Product Grounding

The buyer does not rely on an LLM's memory for commerce facts.

Product retrieval combines:

- exact matching;
- PostgreSQL Full-Text Search;
- `pg_trgm`;
- dense embeddings;
- metadata filters;
- reranking;
- explicit no-match handling.

Real food-product enrichment uses **Open Food Facts**.

Hard identity fields such as brand, model, SKU, variant, storage, size, colour, GTIN, and barcode cannot be overridden by semantic similarity.

For safety-critical facts:

> **Missing or conflicting evidence becomes `UNKNOWN`, not `PASS`.**

---

## Security and Tenant Isolation

The backend uses Spring Security with explicit identity, role, and ownership boundaries.

Canonical roles:

- `BUYER`
- `MERCHANT_ADMIN`
- `PLATFORM_ADMIN`
- `SYSTEM`

Merchant-scoped data is tenant-bound.

A merchant admin role alone does not grant access to every merchant; the actor must also have the appropriate merchant relationship.

Authentication, RBAC, tenant ownership, and proposal-bound payment authorization remain separate security layers.

---

## Architecture

The backend is intentionally a **modular monolith**, not a microservice collection.

    Next.js Web
        ↓
    Java 25 + Spring Boot
        ↓
    ┌─────────────────────────────┐
    │ Identity                    │
    │ Agentization                │
    │ Catalogue / Retrieval       │
    │ Intent                      │
    │ Commerce                    │
    │ Risk                        │
    │ Authorization               │
    │ Payment                     │
    │ Lifecycle                   │
    │ Audit                       │
    │ Transactional Outbox        │
    └─────────────────────────────┘
        ↓
    PostgreSQL 17
        ↓
    Razorpay / Gemini / Sarvam / Merchant APIs

PostgreSQL remains the authoritative store for commerce state, authorization, payments, refunds, audit records, and durable background work.

No LangChain or free-form agent framework controls the financial state machine.

---

## Tech Stack

### Backend

- Java 25 LTS
- Spring Boot 4.1.1
- Spring Security
- Spring JDBC / JdbcClient
- Flyway
- PostgreSQL 17
- pgvector
- pg_trgm
- Spring Actuator + Micrometer
- PostgreSQL transactional outbox

### Web

- Next.js 16.2.11
- React
- TypeScript
- Tailwind CSS
- shadcn/ui primitives

### AI

- Gemini 3.6 Flash
- Gemini Embedding 2
- Sarvam AI Saaras v3

### Payments

- Razorpay Test Mode
- Standard Checkout
- Orders API
- Webhooks
- Payment reconciliation
- Refund APIs

### Testing

- JUnit
- Spring Boot Test
- Testcontainers
- real PostgreSQL integration tests
- property/concurrency tests
- Playwright

---

## Deployment

The evaluator-facing application is designed to work with the developer machine completely offline.

    Evaluator
        ↓
    Vercel
    Next.js
        ↓
    Google Cloud Run
    Java / Spring Boot
        ↓
    Supabase
    PostgreSQL

CI/CD is handled through GitHub Actions.

Local Docker and Testcontainers remain development and correctness-testing environments, not dependencies of the live application.

The Buildathon deployment targets zero platform spend using available free tiers and Test Mode infrastructure.

---

## Evaluation

Evaluation is treated as part of the product, not an afterthought.

The project includes dedicated evaluation tracks for:

- multilingual intent understanding;
- product retrieval and no-match detection;
- deterministic constraint enforcement;
- merchant agentization accuracy;
- reversibility and human-interruption quality;
- payment reliability and concurrency;
- end-to-end commerce journeys.

Failure testing includes scenarios such as:

- duplicate execution requests;
- duplicate/out-of-order webhooks;
- payment failure followed by late capture;
- stale price or stock;
- unknown allergen evidence;
- incorrect product variant;
- lost Razorpay Order responses;
- merchant fulfilment failure after payment;
- crashes before durable work is dispatched;
- concurrent refund requests;
- malicious merchant API endpoints.

---

## Demo Journey

The canonical Buildathon demo demonstrates four things:

### 1. Agentize a Merchant

Connect an existing merchant API and policy documents, approve AI-proposed mappings, run contract tests, and make commerce capabilities Agent Ready.

### 2. AI Buyer

Use multilingual voice input, compile it into structured intent, retrieve grounded products, verify hard constraints, generate an immutable proposal, authorize it, and complete payment through Razorpay Test Mode.

### 3. Graceful Failure

Simulate a captured payment followed by temporary merchant fulfilment failure and show that the system does not create another charge or order, while the transactional outbox safely recovers or compensates.

### 4. Post-Purchase Lifecycle

Reopen the same commerce thread and perform a bounded return/refund using the historical policy snapshot and stable refund idempotency.

---

## Repository

    apps/
      backend/        Java / Spring Boot control plane
      web/            Buyer, merchant and platform web surfaces
      buyer_flutter/  P1 native buyer

    contracts/        OpenAPI and JSON Schema contracts
    evaluation/       Evaluation datasets and harnesses
    infra/            Local development infrastructure
    docs/             Architecture, payments, failure and demo documentation

---

## Project Documentation

- [Project Specification](PROJECT_SPEC.md)
- [Architecture](docs/architecture.md)
- [API](docs/api.md)
- [Payments](docs/payments.md)
- [State Machines](docs/state-machines.md)
- [Failure Handling](docs/failures.md)
- [Benchmarks](docs/benchmarks.md)
- [Setup](docs/setup.md)
- [Demo](docs/demo.md)

---

## Buildathon

**Razorpay Buildathon 2026**  
**Track 01 — AI Growth & Agentic Commerce**

The goal is not merely to build an AI shopping assistant.

The goal is to prove that an existing merchant can become safely transactable by AI while preserving deterministic authority, payment correctness, reversibility, auditability, and real end-to-end execution.