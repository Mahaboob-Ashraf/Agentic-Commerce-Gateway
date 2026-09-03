# DECISIONS.md

These accepted bootstrap ADRs record locked architectural decisions, not implementation claims.

## Change-control precedence

The Master Project Source-of-Truth PDF remains the baseline architecture and specification. For a
decision accepted after that PDF was finalized, an explicit entry in this file overrides only the
conflicting statements it identifies; it does not weaken the PDF's authority elsewhere. `CONTEXT.md`
records current implementation/project state. ADR-024 is an approved post-PDF override under this rule.

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

## ADR-012 — Typed single-step agentization with local-only OpenAPI inspection

- **Decision:** Run merchant agentization as explicit single-step transactions over a typed tool
  registry, a Java state machine, and durable PostgreSQL observations. Inspect bounded OpenAPI JSON
  trees locally and accept only uploaded local component-schema references.
- **Why:** Model reasoning may select a proposed next action, but state, network authority, budgets,
  evidence, and capability readiness must remain deterministic and reviewable.
- **Alternatives considered:** Recursive agent loops in one request, generic agent frameworks,
  unrestricted HTTP/file tools, a full OpenAPI execution engine, and remote `$ref` resolution.
- **Tradeoffs:** The supported OpenAPI subset is deliberately narrow, external references and YAML are
  unavailable, and callers must drive repeated steps explicitly.
- **Effect on correctness:** Every selected tool is checked against the persisted state, every executed
  or denied action consumes one bounded step, and every result is linked to the tenant-owned run.
- **Effect on testing:** State transitions, PostgreSQL constraints, inspection limits, budgets, tenant
  isolation, audit records, and stubbed decisions are reproducible without live Gemini or merchant APIs.
- **Effect on Buildathon demo:** The system demonstrates a genuine observable agent loop without
  granting the model arbitrary execution or readiness authority.

## ADR-013 — Per-request DNS-pinned HTTPS merchant execution

- **Decision:** Execute arbitrary approved merchant operations through one deterministic executor using
  Apache HttpClient 5 with a fresh per-request connection manager whose DNS resolver is pinned to the
  address set resolved and safety-validated immediately before execution. Keep the approved hostname in
  the HTTPS URI, retain default TLS hostname/certificate validation, and disable redirects.
- **Why:** Approval-time DNS validation alone permits rebinding, while resolving safely and then allowing
  an unrelated client resolution leaves an avoidable time-of-check/time-of-use gap. Java's built-in HTTP
  client does not expose the focused resolver binding needed here.
- **Alternatives considered:** Approval-time checks only; a generic client that resolves a second time;
  connecting to a literal IP with TLS verification disabled; a broad networking framework; and a custom
  HTTP/TLS implementation.
- **Tradeoffs:** Each call creates a focused client/connection manager rather than pooling connections
  across requests, and Apache HttpClient 5 becomes a backend dependency. Trusted local fixtures require
  a separate test transport rather than a localhost production exception.
- **Effect on correctness:** Every remote call rechecks every DNS answer, unsafe/mixed/failing resolution
  fails closed, the actual connection uses the validated set, redirects cannot change authority, and TLS
  still authenticates the approved hostname.
- **Effect on testing:** DNS and transport are narrow interfaces, so SSRF classes, rebinding, timeout,
  response bounds, redirects, and tenant ownership are deterministic without live network access.
- **Effect on Buildathon demo:** A merchant operation can be exercised autonomously without exposing a
  raw URL/HTTP tool or weakening the production endpoint boundary for local fixtures.

## ADR-014 — Immutable mapping repair over durable contract truth

- **Decision:** Treat executable mappings as immutable versions selected only after deterministic
  validation. Persist contract outcomes and bounded evidence separately, allow only enum-backed
  code-free transformations, and make revisions reference both the prior mapping and failure evidence.
- **Why:** An agent may diagnose adapter semantics, but it must not edit merchant responses, expected
  contract truth, or readiness rules to manufacture a pass.
- **Alternatives considered:** Mutating a mapping row in place; free-form expression/script transforms;
  model-authored tests; and letting the reasoning provider change orchestration state directly.
- **Tradeoffs:** The first transformation vocabulary and contract harness are intentionally narrow and
  currently deep only for GET_QUOTE.
- **Effect on correctness:** The 499-rupee case fails from actual normalization behavior, a linked revision
  adds `MONEY_RUPEES_TO_PAISE`, integer-safe retesting produces 49,900 paise, and the failed version remains
  auditable. Repeated identical failures escalate after a fixed threshold.
- **Effect on testing:** The entire failure, diagnosis, revision, validation, and retest sequence runs with
  deterministic DNS, merchant transport, and decision-provider doubles against PostgreSQL 17.
- **Effect on Buildathon demo:** Autonomous repair is genuine and inspectable while merchant approval and
  READY publication remain deferred authority gates.

## ADR-015 — Version-bound merchant authority and deterministic readiness publication

- **Decision:** Bind merchant decisions to canonical mapping/rule hashes and exact versions, publish
  approved policy rules into immutable snapshots, and make one deterministic reducer the only component
  allowed to persist `READY`, `BLOCKED`, or `UNTESTED` and publish immutable Agent Commerce Manifests.
- **Why:** Model-produced mappings and policy interpretations are useful proposals, but capability
  advertising requires current endpoint, test, merchant-authority, clarification, and policy evidence.
- **Alternatives considered:** Let a model set readiness; infer approval from contract success; mutate one
  current manifest; resolve lifecycle policy from raw text at runtime; and treat GET_QUOTE as PURCHASE.
- **Tradeoffs:** Publication persists more version/evidence rows, merchant approval is conservative, and
  PURCHASE remains non-ready until later catalogue/order evidence producers exist.
- **Effect on correctness:** Old approvals cannot certify a new mapping/rule version, conflicting or
  missing approved policy yields `UNKNOWN`, and absent evidence yields `UNTESTED` instead of fabricated PASS.
- **Effect on testing:** PostgreSQL integration tests exercise tenant authority, hashes, snapshots,
  stale evidence, reducer outputs, manifest history, and safe buyer lookup with deterministic providers.
- **Effect on Buildathon demo:** GET_QUOTE can be honestly advertised when fully evidenced while the
  manifest visibly explains why aggregate PURCHASE is not yet executable.

## ADR-016 - Immutable proposal authority and PostgreSQL execution reservation

- **Decision:** Build transaction authority from an explicit current live-evidence refresh into an
  immutable canonical TransactionProposal, then require a session-bound immutable AuthorizationDecision
  and one deterministic AI-free ExecutionGate before PostgreSQL reserves exactly one Execution. Use the
  commerce thread's foreign-key pointers as current-state authority, not timestamp ordering.
- **Why:** A cart, catalogue observation, model interpretation, or browser confirmation is not sufficient
  financial authority. The authorized material and the mutable facts checked at execution time must be
  exact, hash-bound, current, and independently auditable under retries and concurrency.
- **Alternatives considered:** Mutable current proposal rows; client-submitted amount/cart confirmation;
  model-selected action risk; timestamp-based current evidence; JVM-only locking; and treating a browser
  request or execution reservation as payment success.
- **Tradeoffs:** The schema contains more immutable evidence rows and composite foreign keys, every
  execution request performs several current-authority reads, and PostgreSQL timestamp values are
  normalized to UTC microsecond precision before hashing and persistence.
- **Effect on correctness:** Material changes create new proposal hashes, old authorization cannot open a
  new proposal, UNKNOWN fails closed, rapid refreshes resolve through an explicit current pointer, and a
  proposal row lock plus unique proposal constraint makes duplicate reservations converge on one stable
  execution/idempotency key. AUTO_EXECUTE skips only another application confirmation.
- **Effect on testing:** Real PostgreSQL tests cover immutable triggers, canonical material changes,
  authorization/session/expiry bindings, failure injection, rapid refresh replacement, and eight-way
  concurrent reservation without mocks for locks or unique constraints.
- **Effect on Buildathon demo:** The buyer can reach a truthful provider-order-free RESERVED state while
  PURCHASE remains visibly UNTESTED until Task 010 adds Razorpay and merchant order evidence.

## ADR-017 - Evidence-led payment truth and transactional merchant finalization

- **Decision:** Treat callback, webhook, and API responses as immutable provider evidence; let one
  deterministic reducer establish payment truth; and atomically create stable merchant-finalization and
  PostgreSQL outbox work when exact captured-payment plus paid-order evidence confirms payment.
- **Why:** Browser delivery, webhook order, network responses, and merchant acknowledgements can each be
  duplicated, delayed, reordered, or lost. None is individually sufficient financial or fulfilment truth.
- **Alternatives considered:** Callback-only success; last-event-wins webhook state; retrying order creation
  after unknown outcomes; in-memory jobs; and coupling captured payment to immediate merchant fulfilment.
- **Tradeoffs:** The payment path persists more evidence and attempt rows, uncertain outcomes require bounded
  reconciliation, and fulfilment exposes a separate pending/failure lifecycle after payment confirmation.
- **Effect on correctness:** One execution has at most one Razorpay Order; confirmation requires exact
  configuration/account/order/amount/currency plus captured and paid status; an outbox crash gap is impossible;
  and merchant retries always reuse one operation identity.
- **Effect on testing:** Real PostgreSQL tests exercise uniqueness, immutable evidence, duplicate/out-of-order
  ingestion, late capture, atomic outbox creation, `SKIP LOCKED`, retry recovery, and honest readiness.
- **Effect on Buildathon demo:** A lost merchant response visibly remains payment-confirmed and fulfilment-pending,
  then retries without a second execution, provider order, charge, or merchant order.

## ADR-018 - Direct merchant-account linking is normal P0 purchase authority

- **Decision:** Require each buyer to link a supported merchant account through a typed merchant-specific
  provider boundary before purchase or lifecycle execution. Persist only opaque customer/delegated credential
  references and versioned link hashes; do not provide a guest-checkout branch or store merchant passwords.
- **Why:** Merchant customer identity is required for real fulfilment and post-purchase actions, while generic
  password automation would violate the gateway's authority and privacy boundaries.
- **Alternatives considered:** Guest checkout, generic browser/password automation, raw delegated-token storage,
  and sending merchant credentials through Gemini or arbitrary HTTP tools.
- **Tradeoffs:** Unsupported merchants cannot transact in P0, and each real merchant needs OAuth, delegated-token,
  customer-session, or another legitimate adapter.
- **Effect on correctness:** Purchase and lifecycle gates fail closed on missing, expired, revoked, wrong-owner,
  or wrong-version links, while the original merchant customer identity remains explainable.
- **Effect on testing:** Trusted synthetic exchange tests can prove valid/invalid linking, owner isolation,
  revocation, opaque persistence, and absence of raw passwords from durable evidence.
- **Effect on Buildathon demo:** One onboarding step establishes reusable merchant identity without claiming
  unsafe generic password automation.

## ADR-019 - Immutable fulfilment snapshots are purchase material

- **Decision:** Create an immutable fulfilment snapshot for each authority refresh and bind its exact recipient,
  contact, structured address/version, delivery option, and merchant-link ID/version/hash into serviceability,
  quote, TransactionProposal canonical material, and PLACE_ORDER finalization.
- **Why:** Editing a saved address or relinking an account must not rewrite historical delivery authority or leave
  an authorization valid for materially different fulfilment.
- **Alternatives considered:** Referencing only a mutable default address, city-level serviceability, storing an
  address string outside the proposal hash, and mutating an existing proposal after selection changes.
- **Tradeoffs:** Each purchase refresh creates more immutable evidence and requires fresh downstream authority
  whenever address, delivery option, or link material changes.
- **Effect on correctness:** Historical orders remain explainable; exact-address UNKNOWN fails closed; any
  fulfilment change yields a new proposal hash and requires fresh authorization.
- **Effect on testing:** PostgreSQL tests cover snapshot immutability, exact binding, revoked links, serviceability,
  canonical hash changes, and normalized PLACE_ORDER fulfilment input.
- **Effect on Buildathon demo:** The merchant receives an exact deliverable identity rather than a generic-city
  promise, while delegated credentials remain server-side.

## ADR-020 - Lifecycle authority is separate and P0 return/refund scope is full-order only

- **Decision:** Compile post-purchase messages into buyer-owned typed lifecycle intents, evaluate the original
  approved policy snapshot, and require separate immutable lifecycle proposals and action/session-bound
  authorizations. P0 supports full-order return and full refund only; partial return/refund and replacement remain
  typed unsupported/P1 outcomes.
- **Why:** Purchase consent cannot authorize cancellation, return, or refund, and partial-money allocation adds
  policy, tax, fee, inventory, and concurrency semantics outside P0.
- **Alternatives considered:** Reusing purchase authorization, applying today's policy silently, allowing model IDs
  to select orders, and coercing partial requests into unrecorded partial or full effects.
- **Tradeoffs:** Lifecycle actions add their own evidence/proposal/authorization sequence and conservative UNKNOWN
  outcomes; the P0 user must explicitly confirm full-order scope.
- **Effect on correctness:** Ownership, historical policy, current merchant state/readiness, exact action, proposal
  hash, session, expiry, and link authority are checked before one durable effect.
- **Effect on testing:** Tests cover typed partial-return rejection, immutable lifecycle proposals, stable cancel
  identity, full return transitions, separate refund state, and owner-bound order resolution.
- **Effect on Buildathon demo:** A completed purchase can be safely tracked, cancelled, returned, and refunded
  without implying unsupported reverse-logistics or partial-refund behavior.

## ADR-021 - PostgreSQL refund ledger and stable provider idempotency own refund safety

- **Decision:** Derive full refund amount server-side from captured payment truth, serialize reservations under a
  PostgreSQL ledger-row lock, create exactly one RefundExecution per immutable proposal, and reuse one stable
  Razorpay idempotency key plus identical canonical request bytes through the transactional outbox and reconciliation.
- **Why:** Timeouts, retries, duplicate requests, and concurrent proposals must never over-refund or create a second
  provider effect; a successful HTTP response is not final refund truth.
- **Alternatives considered:** Client-supplied refund amounts, JVM locking, new idempotency keys per retry,
  marking create-response success as refunded, and best-effort non-durable retry work.
- **Tradeoffs:** Refunds carry explicit uncertain/pending/manual-review states, immutable provider evidence, a
  persisted retry budget, and provider reconciliation work.
- **Effect on correctness:** Reserved plus completed value cannot exceed captured refundable value; only exact
  configuration/payment/refund/amount/currency evidence with provider status `processed` establishes `REFUNDED`.
- **Effect on testing:** Real PostgreSQL concurrency proves one full reservation; adapter/outbox tests verify stable
  identity/body, pending reduction, exact reconciliation, and fail-closed evidence mismatch.
- **Effect on Buildathon demo:** A lost or pending refund response is safely visible and recoverable without a
  duplicate refund or premature success claim.

## ADR-022 - AutoBuy P0 is versioned plan authority with explicit triggers only

- **Decision:** Store owner-scoped, versioned AutoBuy plans bound to merchant link, selected address, product/category
  constraints, exact-substitution rules, safety constraints, and maximum paise. Evaluate only an authenticated
  explicit trigger ID; create fresh purchase authority every time and stop at customer-authorized Razorpay Checkout.
- **Why:** A plan is a constraint set, not reusable transaction authorization, and P0 has neither a scheduler nor a
  mandate capable of unattended debit.
- **Alternatives considered:** Cron/inventory triggers, reusing old proposals/evidence, silently substituting products,
  treating the plan as payment consent, and simulating automatic payment success.
- **Tradeoffs:** Plans pause frequently and machine-readably when any fresh link, address, identity, safety,
  capability, availability, serviceability, policy, or price fact is invalid/FAIL/UNKNOWN.
- **Effect on correctness:** Unique plan-plus-trigger evaluation prevents duplicate proposals; fresh deterministic
  gates enforce plan bounds, and AUTO_EXECUTE skips only another application confirmation—not Razorpay authorization.
- **Effect on testing:** Tests cover versioned plan creation, duplicate-trigger idempotency, price fail-closed pause,
  persisted evaluation retrieval, no execution on block, and absence of a scheduled AutoBuy worker.
- **Effect on Buildathon demo:** A buyer can explicitly demonstrate bounded repeat purchasing while the interface
  truthfully states that Checkout still requires customer payment authorization.

## ADR-023 - One merchant agentization goal owns isolated capability targets

- **Decision:** Represent the merchant's top-level agentization request as one bounded goal containing the required
  capability target set, while retaining separate runs, evidence, readiness evaluations, and reducer outcomes per
  capability. One advance starts or progresses at most one target run.
- **Why:** Merchants should not manually bootstrap every capability, but aggregate convenience must not let evidence
  from GET_QUOTE or another target certify PURCHASE, lifecycle, or refund readiness.
- **Alternatives considered:** Manual run creation for every capability, one monolithic cross-capability run, and
  aggregate readiness inferred from partial evidence.
- **Tradeoffs:** The goal service coordinates more target state and may stop an individual target for clarification
  while independently ready targets remain usable.
- **Effect on correctness:** Capability evidence and deterministic READY authority stay isolated, bounded, and
  inspectable even though progression begins from one merchant-level goal.
- **Effect on testing:** PostgreSQL tests verify one goal creates the complete target set and advances only one
  non-ready target without changing already-ready capability evidence.
- **Effect on Buildathon demo:** Merchant agentization appears as one coherent objective rather than a sequence of
  manual capability setup screens, without weakening the existing readiness reducer.

## ADR-024 — 2026-09-01 — Gemini 3.1 Live is the P0 conversational front door

- **Status and precedence:** Accepted post-PDF change control. This entry supersedes ADR-007 and any older PDF,
  specification, roadmap, or architecture statement that requires `Sarvam STT → transcript → Gemini` for P0 voice;
  those statements remain historical and are superseded only for this explicitly changed decision.
- **Decision:** Target `gemini-3.1-flash-live-preview` behind a replaceable Live adapter/configuration boundary for
  P0 microphone input, native audio output, multilingual conversation/language switching, server VAD, interruption,
  acknowledgement/clarification, bounded `start_commerce_request(...)` invocation, and grounded spoken presentation.
  No Sarvam dependency is required in the P0 runtime. `gemini-2.5-flash-native-audio-preview-12-2025` remains an
  evaluated comparison/fallback experiment; its temporary lab-only `NON_BLOCKING` mode does not shape P0 architecture.
- **Agent and authority boundary:** The two commerce agents remain Merchant Agentization Agent and Safe AI Buyer.
  Gemini Live is an interaction layer around Safe AI Buyer, not a third commerce agent. It owns no catalogue,
  capability-readiness, product-identity, price, stock, serviceability, safety, proposal, risk, authorization, payment,
  refund, or lifecycle truth; deterministic application logic, Java commerce services, and PostgreSQL retain authority.
- **Application-managed async commerce:** The model speaks a short acknowledgement and requests
  `start_commerce_request(query)`. After the pre-call acknowledgement audio drains, the app starts durable commerce,
  returns a bounded `STARTED`/request ID promptly, continues work outside Live, and injects structured authoritative
  results into the active session. Gemini may explain only those supplied results and never executes purchases or
  establishes financial truth.
- **Acknowledgement and audio:** The acknowledgement barrier captures a monotonic PCM playback watermark at function
  receipt and waits only for that pre-call prefix—not later audio or global generation completion. Browser capture uses
  `getUserMedia` with requested echo cancellation/noise suppression/automatic gain control plus an AudioWorklet that
  sends PCM16/16 kHz. Playback uses an AudioWorklet for buffered 24 kHz Gemini PCM, prebuffering, depth/underrun metrics,
  and interruption flush. Server VAD alone owns speech boundaries; client RMS is telemetry; `ScriptProcessorNode` is out.
  This watermark rule is the accepted fix for the observed `ACKNOWLEDGEMENT_AUDIO_NOT_COMPLETED` failure.
- **Session health and recovery:** Primary latency is `SERVER_USER_SPEECH_END → FIRST_MODEL_AUDIO_PLAYBACK`, excluding
  intentional commerce-job time. Current P0 controls are `HEALTHY` at ≤5 s, `SLOW` after one eligible turn >5 s, and
  `DEGRADED` after one >10 s or two consecutive >5 s. Safe recovery closes the session, mints a fresh constrained token,
  creates fresh Live state, and seeds compact deterministic state only—no transcript/audio replay or session resumption.
  Automatic rotation is forbidden during user speech, acknowledgement drain, model audio, an undelivered commerce job,
  or pending authoritative result. These are engineering controls, not SLA guarantees.
- **Evidence and rationale:** Manual 3.1 evaluation showed fast healthy-session responses; strong Hindi, Hinglish,
  English, Telugu, and Urdu with natural switching/interruption; application-managed calls and waiting; and grounded
  narration. When synthetic results returned protein snacks for milkshake, shoes, or biryani requests, 3.1 identified
  the mismatch rather than fabricating the requested category, and it declined to invent absent ratings/reviews. One
  session degraded to roughly 10–30 s while a fresh session stayed fast across many multilingual turns and commerce
  calls. No single provider, context, browser, or language-switching root cause is established, and reliability is not
  claimed perfect. Gemini 2.5 showed worse progressive latency and a less attractive overall experience in evaluation.
- **Voice-selection requirement:** A small curated set of Gemini-native Live voices with short previews and a persisted
  buyer preference is `P0 desired / implementation pending provider verification`. `speechConfig`/`voiceConfig` support
  exists at the provider surface, but 3.1 voice selection is unverified in this application. Voice changes presentation
  only. If 3.1 does not reliably honor selection, retain one stable Gemini voice and defer the selector rather than add
  an external TTS/Sarvam dependency that compromises native audio or latency.
- **Consequences:** Future P0 voice work and Task 012 must use this boundary, preserve ephemeral constrained sessions and
  grounded structured-result injection, and keep commerce truth model-independent so a future Live model can replace
  the preview model without redesigning financial or deterministic authority.

## ADR-025 - Deterministic P0 merchants use the standard HTTP authority path

- **Decision:** Host the bounded Amazing and FreshBasket operation surfaces inside the modular monolith, but expose and
  agentize them as public HTTPS merchant endpoints. Keep catalogue inputs as checked-in provenance-bearing snapshots,
  store inventory/order state in PostgreSQL, and run an explicit environment-gated bootstrap through existing domain
  services. A newly published manifest carries each capability's latest independently evaluated state and advertises
  it only when that latest state is READY; it does not infer readiness from the run that triggered publication. For
  the P0 Cloud Run topology, deployment of the current build is a strict prerequisite to bootstrap:
  `DEMO_MERCHANT_PUBLIC_BASE_URL` must identify that deployed public HTTPS service before the bootstrap agentizes its
  `/api/demo-merchants/**` capabilities. Every demo route is machine-authenticated with a secret sourced only from
  `DEMO_MERCHANT_API_SECRET`; approved endpoints retain only its opaque environment reference and the executor resolves
  and transmits it at invocation time. A completion marker is authority evidence, so it is written only after exact
  merchant/catalogue/link/mapping/READY counts and an empty blocker set have been verified.
- **Why:** The remotely persistent P0 demo must be reproducible while exercising the same approved endpoint, mapping,
  contract-test, readiness, buyer-link, and merchant executor boundaries used by an external merchant.
- **Alternatives considered:** Runtime third-party catalogue calls, direct service invocation from the buyer, ad-hoc READY
  inserts, a separate merchant service/database, and replacing prior READY capabilities with only the current run target.
- **Tradeoffs:** The deployed application temporarily serves both gateway and demo-merchant roles, and richer production
  conformance suites remain future work. Public demo routes must remain bounded and contain no payment authority. The
  operator must deploy first and then run bootstrap against the deployment; localhost cannot be used as a bootstrap
  shortcut because endpoint SSRF validation, contract tests, and readiness must observe a publicly reachable merchant.
- **Effect on correctness:** Stable operation IDs plus canonical request hashes and PostgreSQL uniqueness prevent duplicate
  merchant orders. Public callers without the machine credential cannot mutate demo inventory or order state. PLACE_ORDER
  recomputes merchant-authoritative item prices and delivery, requires the final amount/currency from finalization, and
  rejects drift before mutation. Capability evidence remains isolated, missing safety facts stay UNKNOWN, and
  Razorpay/payment truth is unchanged.
- **Effect on testing:** Testcontainers exercises snapshot ingestion, exact catalogue overlap, all seven HTTP contracts,
  cumulative manifests, buyer links, persistent inventory/order idempotency, and zero bootstrap payment side effects.
- **Effect on Buildathon demo:** A single Cloud Run deployment and Supabase database can demonstrate two meaningfully
  different READY merchants without making buyer requests depend on live public product APIs. The completion log and
  persisted bootstrap summary state the public base URL and deployment precondition used for the run.

## ADR-026 - Buyer state transitions are deterministic; model reasoning is candidate-bounded

- **Decision:** Derive Safe Buyer tool transitions from the persisted state machine instead of asking a model to select
  the only permitted action. Retain Gemini for unstructured intent compilation and, only when multiple non-exact
  grounded candidates survive deterministic retrieval, one bounded candidate-selection call whose output is limited to
  supplied product IDs and evidence references. Exact identity and single-candidate selection remain deterministic.
- **Why:** A model call cannot add judgment when authoritative state permits exactly one transition, and sequential calls
  added dominant latency and provider failure exposure. Candidate preference is the point where bounded semantic
  reasoning can add value.
- **Effect on correctness:** Merchant readiness, retrieval, exact identity, transitions, hard constraints, quote,
  proposal, risk, and money remain deterministic. A selected product is membership-checked, re-retrieved through the
  hard filters, and reloaded from the authoritative catalogue before cart persistence.
- **Effect on testing:** Action evidence identifies deterministic state-machine selection; tests reject product or
  evidence IDs outside the offered set, prove exact selection avoids the provider, retain ambiguity clarification, and
  verify non-READY search capability exclusion.
