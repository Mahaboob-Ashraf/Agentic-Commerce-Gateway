# Local infrastructure

Task 000 provides one service: PostgreSQL 17 with pgvector, using the exact image
`pgvector/pgvector:0.8.1-pg17`. The database volume is persistent. `pg_trgm` ships with PostgreSQL;
Flyway enables both it and `vector`.

From the repository root:

```powershell
docker compose --env-file .env -f infra/compose.yml up -d
docker compose --env-file .env -f infra/compose.yml down
```

If no `.env` exists, Compose uses the local-only defaults in `compose.yml`. Use `down -v` only when
you intentionally want to delete local database data.
