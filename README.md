# Folio — private AI audiobooks

Folio is the deployable walking skeleton for an invitation-only platform that turns authorized
PDF and DRM-free EPUB publications into private AI-narrated audiobooks. This repository currently
proves the narrow production path: responsive installable PWA → same-origin Spring API → PostgreSQL,
with local service substitutes, stage-specific worker entrypoints, disposable GCP infrastructure,
and an immutable CI container pipeline.

## What is running

- `apps/web`: React/TypeScript PWA using the Clear Signal light palette and Midnight Library dark
  palette, with an original 27-second public sample, transcript, compact/tablet/desktop layouts,
  self-hosted Space Grotesk, and shell-only service-worker caching.
- `apps/core`: stateless Spring Boot API exposing `GET /api/v1/platform/status`. The response contains
  only API/build revisions and core/database availability—never content, object coordinates, database
  addresses, or Listener identifiers.
- `compose.yaml`: PostgreSQL 17, a separate Flyway migration run, Pub/Sub emulator, isolated working
  and finalized MinIO stores, all eight worker stages, Grafana OpenTelemetry LGTM, Mailpit, WireMock,
  the core, and the same-origin web proxy.
- `infra/terraform`: a disposable `europe-west1` topology with zero-minimum/three-maximum Cloud Run,
  private Cloud SQL, private buckets, Pub/Sub, stage jobs, KMS, Secret Manager, least-privilege service
  accounts, Artifact Registry, and Firebase Hosting.

## Pinned toolchain

| Tool | Version |
| --- | --- |
| Java (Temurin) | 25.0.3 |
| Maven wrapper | 3.9.11 |
| Node.js | 24.18.1 |
| npm | 11.8.0 |
| Spring Boot | 4.1.0 |
| PostgreSQL | 17 |
| Terraform | 1.15.8 |

Use `mise install`, `asdf install`, `nvm use`, or the matching JDK/Node installations. The repository
contains `.tool-versions`, `.java-version`, `.node-version`, and `.nvmrc` for common managers.

## Build and test

```bash
npm ci --ignore-scripts
make verify
```

`make verify` runs Java compilation/unit/implementation tests, PWA tests/typechecking/build, and the
rendered local environment contract. `PlatformStatusITest` uses Testcontainers PostgreSQL and skips
only when Docker is unavailable; CI has Docker and runs it as an implementation test.

## Run the complete local environment

```bash
docker compose --profile workers up --build
```

Open [http://localhost:3000](http://localhost:3000). Supporting local surfaces are:

- API: `http://localhost:8080/api/v1/platform/status`
- Telemetry: `http://localhost:3001`
- Fake notifications: `http://localhost:8025`
- Working-object console: `http://localhost:9001`
- Finalized-object console: `http://localhost:9011`

All credentials in `compose.yaml` are explicit local-only values. Production configuration uses
runtime environment variables and Secret Manager; `.env` files are ignored.

## Delivery

`.github/workflows/ci.yaml` compiles both applications, applies migrations, runs unit and
Testcontainers tests, checks Terraform and Compose, performs dependency review, CodeQL, secret and
filesystem scans, builds both containers, scans them, and publishes SHA-tagged images with SBOM and
maximum provenance attestations.

`.github/workflows/deploy-disposable.yaml` uses GitHub OIDC/workload identity to create or destroy a
state-isolated disposable GCP/Firebase environment. See
[docs/operations/disposable-environments.md](docs/operations/disposable-environments.md).

Private publication content and direct Listener identifiers must never appear in logs, metrics,
traces, analytics, queue attributes, URLs, or operational summaries. See
[docs/operations/content-free-telemetry.md](docs/operations/content-free-telemetry.md).
