# Local development

The local stack intentionally mirrors managed-service boundaries without pretending that emulators
prove managed-service behavior. It uses the production PostgreSQL major, executes the same Flyway SQL,
starts the same core image in API and stage-worker modes, and keeps working/finalized objects in two
different stores.

## Start and stop

```bash
docker compose --profile workers up --build
docker compose --profile workers down
```

Use `docker compose down --volumes` only when you deliberately want to erase local database and object
data. The environment contract can be checked without a running daemon:

```bash
./scripts/verify-local-environment.sh
```

## Service boundaries

| Boundary | Local implementation | Production counterpart |
| --- | --- | --- |
| Relational authority | PostgreSQL 17 | Private Cloud SQL PostgreSQL 17 |
| Migrations | Flyway one-shot container | Spring/Flyway release startup |
| At-least-once work | Pub/Sub emulator | Pub/Sub |
| Working assets | Dedicated MinIO | Private lifecycle-bound GCS bucket |
| Finalized assets | Dedicated MinIO | Private versioned GCS bucket |
| Worker entrypoints | Eight core-image processes | Eight Cloud Run Jobs |
| Telemetry | Grafana OpenTelemetry LGTM | Cloud Operations integration |
| Notifications | Mailpit | Replaceable Resend port |
| Analytics | WireMock capture sink | Replaceable PostHog EU port |

Emulator success is not managed-service qualification. Later tickets must add disposable-environment
contracts for IAM denial, resumable/range storage behavior, Cloud SQL roles, KMS, Pub/Sub delivery,
and restore behavior.
