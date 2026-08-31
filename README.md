# Agentic Commerce Gateway

**A Merchant Agentization Agent + Safe AI Buyer for Razorpay Buildathon 2026 — Track 01: AI Growth & Agentic Commerce.**

Agentic Commerce Gateway helps merchants expose their existing commerce capabilities safely to AI buyers, then lets buyers discover, reason, transact, and manage commerce through bounded, auditable agent workflows.

> **AI handles unstructured meaning, planning, and bounded action selection. Deterministic software controls truth, authority, capability readiness, and money.**

---

## The Problem

AI can understand what someone wants to buy. Safely completing the purchase is much harder.

A production commerce agent must know:

- which merchants can actually perform the requested commerce actions;
- which product facts are trustworthy;
- whether hard constraints such as allergens, budget, variant, stock, and delivery are satisfied;
- what information is missing;
- exactly what transaction the buyer authorized;
- whether payment genuinely succeeded;
- how to recover when payment or merchant fulfilment fails halfway through;
- how later cancellation, return, refund, and reorder actions remain tied to the original transaction.

At the same time, most existing merchant systems were not designed to be consumed directly by AI agents.

Agentic Commerce Gateway bridges those two sides.

---

# Two-Agent Architecture

P0 contains exactly two agents.

## 1. Merchant Agentization Agent

The merchant starts one high-level goal:

> **Agentize my store.**

The agent then works through the merchant's available commerce interfaces and determines which capabilities can safely become usable by AI buyers.

Internally, capabilities are still isolated for testing, evidence, and readiness, but the merchant does not have to manually onboard them one at a time.

The agent can:

- inspect OpenAPI specifications and schemas;
- inspect catalogue structure;
- inspect merchant policies;
- identify candidate commerce operations;
- propose normalized capability mappings;
- select and run bounded contract tests;
- observe failures;
- diagnose mapping problems;
- propose safe revisions;
- retest revised mappings;
- request merchant clarification when business meaning cannot safely be inferred;
- request merchant approval where merchant authority is required.

Example:

    Goal: Agentize merchant
            ↓
    inspect APIs / schemas / policies
            ↓
    identify GET_QUOTE candidate
            ↓
    test mapping
            ↓
    MONEY_UNIT_MISMATCH
            ↓
    diagnose rupees vs paise
            ↓
    propose safe transformation
            ↓
    retest
            ↓
    PASS
            ↓
    continue to remaining capabilities

The AI agent can propose and reason.

It cannot declare itself `READY`.

A deterministic readiness reducer evaluates version-bound evidence and publishes capabilities as:

- `READY`
- `BLOCKED`
- `UNTESTED`

The final output is a versioned **Agent Commerce Manifest** plus an agent-readable catalogue, policy layer, and verified commerce capability contracts.

### What if the merchant does not expose a required capability?

The system does not fabricate one.

For example:

    SEARCH_PRODUCTS      READY
    GET_QUOTE            READY
    GET_AVAILABILITY     READY
    PLACE_ORDER          BLOCKED
    RETURN_ITEM          BLOCKED

If no trustworthy machine-accessible order interface exists, the gateway reports the exact capability gap and the minimum adapter/API boundary the merchant would need to expose.

The goal is not to claim that every legacy website can become safely transactable with zero engineering.

The goal is to eliminate the repetitive discovery, mapping, testing, repair, and certification work wherever usable merchant interfaces exist — while precisely identifying what is missing when they do not.

---

## 2. Safe AI Buyer

The Safe AI Buyer operates only over merchant capabilities that have passed the Agentization Gateway.

A buyer performs a one-time onboarding flow with:

- buyer identity;
- recipient/contact information;
- saved delivery addresses;
- supported merchant-account links;
- persistent commerce history.

For supported merchants, account linking is a one-time operation. The normal architecture does not depend on guest checkout or repeatedly logging into merchant websites.

After onboarding, the buyer can express goals through natural language or voice.

Example:

> “500 ke andar do logon ke liye high-protein vegetarian snacks order karo, peanuts bilkul nahi.”

The purchase flow is:

    Voice / Text
        ↓
    Sarvam Saaras v3
        ↓
    Language Gate
        ↓
    Gemini Intent Compiler
        ↓
    READY Merchant Discovery
        ↓
    Hybrid Product Retrieval
        ↓
    Exact Product Identity Gate
        ↓
    Candidate Cart
        ↓
    Authoritative Quote + Availability
        ↓
    Address / Serviceability Validation
        ↓
    Deterministic Hard-Constraint Verification
        ↓
    Immutable TransactionProposal
        ↓
    Reversibility / Action-Risk Engine
        ↓
    Proposal-Bound AuthorizationDecision
        ↓
    Deterministic Execution Gate
        ↓
    Exactly-One Execution
        ↓
    Razorpay Test Mode Checkout
        ↓
    Verified Payment Evidence
        ↓
    Transactional Outbox
        ↓
    Idempotent Merchant Fulfilment
        ↓
    Persistent Commerce Thread

The same thread is designed to later support cancellation, return, refund, reorder, and bounded AutoBuy workflows.

---

# Bounded Agentic Commerce

The AI never receives unrestricted transaction authority.

Human involvement depends on both:

**uncertainty**

and

**the cost of being wrong.**

The deterministic action-risk layer resolves actions to:

- `AUTO_EXECUTE`
- `CLARIFY`
- `EXPLICIT_CONFIRMATION`
- `BLOCK`

`AUTO_EXECUTE` does **not** mean silently charging the customer.

For P0 it means another application-level confirmation can be skipped. The customer still authorizes the actual payment through Razorpay Standard Checkout.

---

# Deterministic Money Path

Every money action begins with an immutable `TransactionProposal`.

Material transaction fields are serialized canonically and hashed:

    material transaction fields
            ↓
    canonical representation
            ↓
          SHA-256
            ↓
       proposal_hash

Material changes create a new proposal.

This includes changes to things such as:

- merchant;
- product or variant;
- quantity;
- price;
- currency;
- quote;
- constraint evidence;
- policy snapshot;
- fulfilment/serviceability evidence.

Authorization is represented separately by an immutable `AuthorizationDecision` bound to:

- the authenticated buyer;
- authenticated session context;
- exact proposal ID;
- exact proposal hash;
- exact action type;
- expiry.

A previous approval therefore cannot silently authorize a modified transaction.

---

# Payment Reliability

The Razorpay Test payment control plane is built around explicit invariants:

- exactly one `Execution` per `TransactionProposal`;
- at most one Razorpay Order per Execution;
- stable payment initiation identity across retries;
- uncertain provider outcomes are reconciled before creating anything new;
- browser callbacks are evidence, never financial truth;
- webhook signatures are verified;
- provider evidence is persisted durably;
- duplicate and out-of-order webhooks are idempotent;
- `payment.status = captured` and `order.status = paid` are both required;
- amount, currency, order, merchant/configuration, and execution identities must match;
- late capture has an explicit reconciliation path;
- confirmed payment and merchant fulfilment remain separate states;
- payment confirmation and durable fulfilment work are committed atomically using a PostgreSQL transactional outbox;
- merchant finalization uses a stable operation ID across retries.

This allows the system to safely represent:

    PAYMENT_CONFIRMED
    +
    FULFILLMENT_PENDING

instead of incorrectly retrying the payment when the merchant temporarily fails after a successful charge.

---

# AI Responsibility Boundary

## Sarvam AI

**Saaras v3** owns:

- speech-to-text;
- Indian-language transcription;
- code-mixed speech;
- language metadata.

## Gemini

Gemini owns:

- intent compilation;
- ambiguity handling;
- structured reasoning;
- retrieval reasoning;
- merchant API interpretation;
- policy interpretation;
- Merchant Agentization Agent planning;
- failure diagnosis;
- mapping revision proposals.

Gemini does **not** control:

- merchant capability readiness;
- merchant approval;
- unrestricted network execution;
- financial truth;
- transaction authorization;
- execution invariants;
- payment-state transitions;
- refunds.

AI outputs cross validated structured boundaries before deterministic application logic can act on them.

---

# Product Grounding

The Safe AI Buyer does not rely on an LLM's memory for commerce facts.

Product retrieval combines:

- exact matching;
- PostgreSQL Full-Text Search;
- `pg_trgm`;
- dense embeddings;
- metadata filtering;
- deterministic reranking;
- explicit no-match handling.

Real food enrichment uses **Open Food Facts**.

Hard product identity fields cannot be overridden by semantic similarity.

Examples include:

- brand;
- model;
- SKU;
- variant;
- size;
- colour;
- GTIN/barcode.

For safety-critical facts:

> **Missing or conflicting evidence becomes `UNKNOWN`, never `PASS`.**

---

# Security and Tenant Isolation

The backend uses Spring Security with explicit identity, role, tenant, and authority boundaries.

Canonical roles:

- `BUYER`
- `MERCHANT_ADMIN`
- `PLATFORM_ADMIN`
- `SYSTEM`

A `MERCHANT_ADMIN` role alone does not grant access to every merchant. The actor must also have the correct merchant relationship.

Authentication, RBAC, tenant ownership, transaction authorization, and payment truth are deliberately separate layers.

Merchant network access is also bounded through approved endpoints, DNS/IP validation, runtime revalidation, redirect rejection, and SSRF protections.

---

# Architecture

The backend is intentionally a **modular monolith**.

    Next.js Web
        ↓
    Java 25 / Spring Boot
        ↓
    ┌──────────────────────────────┐
    │ Identity                     │
    │ Merchant Agentization        │
    │ Policy / Readiness           │
    │ Catalogue / Retrieval        │
    │ Buyer Intent                 │
    │ Commerce                     │
    │ Risk                         │
    │ Authorization                │
    │ Payment / Reconciliation     │
    │ Lifecycle                    │
    │ Audit                        │
    │ Transactional Outbox         │
    └──────────────────────────────┘
        ↓
    PostgreSQL 17
        ↓
    Razorpay / Gemini / Sarvam / Merchant APIs

PostgreSQL remains authoritative for durable commerce state, capability evidence, authorization, payment truth, audit records, and background work.

No free-form agent framework controls the financial state machine.

---

# Tech Stack

### Backend

- Java 25 LTS
- Spring Boot 4.1.1
- Spring Security
- Spring JDBC / JdbcClient
- Flyway
- PostgreSQL 17
- pgvector
- pg_trgm
- Spring Actuator
- Micrometer
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
- Orders API
- Standard Checkout
- signed callbacks
- signed webhooks
- payment reconciliation

### Testing

- JUnit 5
- Spring Boot Test
- Testcontainers
- PostgreSQL integration tests
- concurrency tests
- failure-injection scenarios
- WireMock / deterministic provider fixtures

---

# Current Implementation Status

The backend is currently implemented through the payment and merchant-finalization control plane.

Completed foundations include:

- tenant-aware identity and authentication;
- durable Merchant Agentization Agent state;
- bounded tool execution;
- hardened merchant endpoint execution and SSRF protection;
- autonomous test → diagnose → revise → retest loops;
- merchant clarification and approval;
- deterministic capability readiness;
- Agent Commerce Manifest;
- versioned catalogue and Open Food Facts enrichment;
- hybrid PostgreSQL retrieval;
- Safe AI Buyer thread and intent compiler;
- grounded candidate carts;
- authoritative quotes;
- hard-constraint certificates;
- immutable transaction proposals;
- deterministic reversibility decisions;
- proposal-bound authorization;
- exactly-once execution reservation;
- Razorpay Test payment control plane;
- signed callback/webhook verification;
- payment reconciliation;
- deterministic captured+paid payment reducer;
- PostgreSQL transactional outbox;
- idempotent merchant `PLACE_ORDER` finalization.

Current backend verification:

> **139 tests — 0 failures — 0 errors**

Remaining P0 work includes buyer fulfilment/account-link onboarding, post-purchase lifecycle, web/voice product surfaces, deployment, and final evaluation/demo hardening.

---

# Deployment Target

The evaluator-facing application is designed to run with the developer machine completely offline.

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

CI/CD uses GitHub Actions.

Local Docker and Testcontainers are development and correctness-testing environments, not runtime dependencies of the deployed product.

---

# Evaluation

The final evaluation suite covers:

- multilingual intent understanding;
- product retrieval and no-match behavior;
- hard-constraint enforcement;
- merchant agentization;
- capability readiness;
- reversibility and interruption decisions;
- payment reliability;
- concurrency;
- recovery from partial failures;
- end-to-end commerce journeys.

Important failure scenarios include:

- duplicate execution requests;
- duplicate/out-of-order webhooks;
- late payment capture;
- stale quote or stock;
- unknown allergen evidence;
- incorrect product identity;
- lost Razorpay Order responses;
- merchant failure after captured payment;
- transactional-outbox recovery;
- malicious merchant endpoints.

---

# Canonical Demo

## 1. Agentize a Merchant

A merchant begins with ordinary APIs, catalogue data, and policies.

The Merchant Agentization Agent is given the goal:

> **Make this merchant Agent Ready.**

It inspects interfaces, proposes capability mappings, runs deterministic tests, intentionally encounters a quote money-unit mismatch, diagnoses rupees vs paise, proposes a safe mapping revision, retests successfully, escalates one genuine business ambiguity to the merchant, and continues.

Only the deterministic readiness reducer can publish the final Agent Commerce Manifest.

## 2. Safe AI Buyer

The buyer asks:

> “500 ke andar do logon ke liye high-protein vegetarian snacks order karo, peanuts bilkul nahi.”

The system performs multilingual intent understanding, READY merchant discovery, grounded retrieval, exact identity checks, authoritative quoting, hard-constraint validation, proposal creation, authorization, deterministic execution gating, and Razorpay Test Checkout.

## 3. Graceful Failure

Payment becomes captured and the Razorpay Order becomes paid.

Merchant fulfilment temporarily fails.

The system demonstrates:

- no duplicate charge;
- no duplicate Razorpay Order;
- payment remains confirmed;
- fulfilment remains pending;
- durable outbox retry;
- stable merchant operation identity;
- eventual successful fulfilment.

## 4. Post-Purchase Lifecycle

The same commerce thread is later used for bounded cancellation, return, refund, or reorder actions using historical transaction and policy evidence.

---

# Repository

    apps/
      backend/        Java / Spring Boot control plane
      web/            Buyer and merchant web surfaces
      buyer_flutter/  P1 native buyer

    contracts/        OpenAPI / JSON Schema contracts
    evaluation/       Evaluation datasets and harnesses
    infra/            Local/deployment infrastructure
    docs/             Architecture and operational documentation

---

# Project Documentation

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

# Buildathon

**Razorpay Buildathon 2026**  
**Track 01 — AI Growth & Agentic Commerce**

The goal is not merely to build an AI shopping assistant.

The goal is to demonstrate that an existing merchant can be made safely usable by AI buyers while preserving deterministic authority, capability verification, payment correctness, auditability, reversibility, and real end-to-end execution.