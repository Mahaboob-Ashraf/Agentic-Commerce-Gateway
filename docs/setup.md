# Local setup

## Prerequisites

- Java 25 (the Maven Enforcer rule rejects other major versions)
- Docker Engine/Desktop with Compose v2
- Node.js 24.19.0
- Corepack and pnpm 10.15.0

Do not substitute H2 for integration tests. The Maven Wrapper pins Maven 3.9.11 and downloads it on
first use. The repository pins Node in `.nvmrc`/`.node-version` and pnpm in `package.json`.

## Environment

Copy `.env.example` to `.env` and keep it local. Defaults are development-only. The backend consumes
`DB_URL`, `DB_USERNAME`, and `DB_PASSWORD`; Compose consumes `POSTGRES_*`. No provider secret is
needed or supported by M0.

## Infrastructure

From the repository root:

```sh
docker compose --env-file .env -f infra/compose.yml config
docker compose --env-file .env -f infra/compose.yml up -d
docker compose --env-file .env -f infra/compose.yml ps
```

Stop without deleting the persistent volume:

```sh
docker compose --env-file .env -f infra/compose.yml down
```

Use `.env.example` in place of `.env` for validation if you have not created a local file.

## Backend

Linux/macOS:

```sh
cd apps/backend
./mvnw spring-boot:run
./mvnw verify
```

Windows PowerShell:

```powershell
cd apps/backend
.\mvnw.cmd spring-boot:run
.\mvnw.cmd verify
```

The application requires PostgreSQL when run locally. `verify` starts its own pgvector PostgreSQL 17
container through Testcontainers, runs Flyway, validates `vector` and `pg_trgm`, and checks health.
When running, health is available at `http://localhost:8080/actuator/health`.

## Web

From the repository root:

```sh
corepack enable
corepack prepare pnpm@10.15.0 --activate
pnpm install --frozen-lockfile
pnpm web:dev
```

Checks:

```sh
pnpm web:lint
pnpm web:typecheck
pnpm web:build
```

`apps/buyer_flutter` is a P1 placeholder and is intentionally excluded from the pnpm workspace.
