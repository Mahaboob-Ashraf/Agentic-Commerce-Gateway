# Architecture

## Status

M0 foundation is implemented in source. Product behavior is not implemented.

The system is a Java 25 / Spring Boot 4.1.1 modular monolith and a responsive Next.js 16.2.11 web
client. PostgreSQL 17 is the authoritative datastore and will host commerce state, audit, retrieval,
and transactional outbox work. This intentionally avoids distributed consistency boundaries before
there is measured need.

Backend packages are `identity`, `agentization`, `catalogue`, `intent`, `commerce`, `risk`,
`authorization`, `payment`, `lifecycle`, `audit`, `outbox`, and `observability`. Dependencies between
them must follow domain authority rather than bypassing state gates.

External capabilities are isolated by `MerchantAdapter`, `CatalogueProvider`, `EmbeddingProvider`,
`SpeechProvider`, `LLMProvider`, and `PaymentProvider`. Under the approved post-PDF ADR-024 override,
`gemini-3.1-flash-live-preview` is the replaceable P0 realtime conversational front door; the former
Sarvam STT-first P0 path is superseded and Sarvam is not a runtime dependency. Gemini Live handles native
audio, multilingual interaction, server VAD, interruption, bounded commerce invocation, and grounded
presentation around Safe AI Buyer. It is not a third commerce agent. Gemini backend models may own bounded
reasoning/intent/RAG/onboarding proposals, and Razorpay owns provider payment operations. None owns
financial or deterministic commerce truth; validated application logic and PostgreSQL do.

Long-running commerce is application-managed: Live acknowledges, requests a bounded start, and receives
structured authoritative results later. The acknowledgement gate drains a pre-function PCM playback
watermark. AudioWorklets own 16 kHz PCM16 capture and buffered 24 kHz playback; server VAD is authoritative
and client RMS is telemetry only. Deterministic health controls may replace a degraded Live session with a
fresh constrained session at a safe boundary, seeding compact state without resumption or history replay.

Critical persistence will use Spring JDBC/JdbcClient and explicit SQL. Schema evolution always uses
Flyway. PostgreSQL-specific behavior is tested with Testcontainers. The P0 durable-work design is a
PostgreSQL transactional outbox dispatched by Spring, not Kafka, Redis, or BullMQ.
