# API documentation

Feature APIs are **not yet implemented**. The only M0 HTTP surface intentionally exposed without
authentication is `GET /actuator/health`; all other requests are denied by bootstrap security.

Future HTTP APIs must have an OpenAPI contract under `contracts/openapi`, stable error identifiers,
explicit authentication/authorization, request idempotency where relevant, and examples that are
verified against tests. Provider payload contracts belong under `contracts/json-schema` when a
bounded provider task defines them. Documentation must state versioning and never imply that browser
responses are authoritative payment evidence.
