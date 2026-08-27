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
`SpeechProvider`, `LLMProvider`, and `PaymentProvider`. Sarvam will own raw voice/STT/language,
Gemini will own reasoning/intent/RAG/onboarding intelligence, and Razorpay will own provider payment
operations. None owns financial truth; validated application logic and PostgreSQL do.

Critical persistence will use Spring JDBC/JdbcClient and explicit SQL. Schema evolution always uses
Flyway. PostgreSQL-specific behavior is tested with Testcontainers. The P0 durable-work design is a
PostgreSQL transactional outbox dispatched by Spring, not Kafka, Redis, or BullMQ.
