# Walking-skeleton verification matrix

The platform skeleton deliberately exposes one content-free feature boundary: the public web shell
loads the versioned platform-status contract from its own origin. The following inventory records the
behaviors and failure modes considered before implementing its tests.

| Behavior or failure mode | Expected observable result | Coverage |
| --- | --- | --- |
| Status request with a reachable PostgreSQL 17 database | HTTP 200, API `v1`, configured build identity, and core/database `AVAILABLE` | `PlatformStatusITest` with Testcontainers |
| Database dependency fails while producing status | Core remains `AVAILABLE`, database becomes `DEGRADED`, and no exception or connection detail enters the response | `PlatformStatusServiceImplTest` |
| Migration sentinel is missing | Database becomes `DEGRADED` even when a connection can be opened | `PlatformStatusServiceImplTest` |
| Controller receives a normal status result | The public contract is serialized and marked `Cache-Control: no-store` | `PlatformStatusControllerTest` |
| PWA loads the same-origin endpoint | Build and availability values become visible without handling user content | `App.test.tsx` happy path |
| Status endpoint is unreachable | The public sample and transcript remain usable while a bounded `Status delayed` state is shown | `App.test.tsx` network-failure path |
| Theme control is activated | The root theme changes between Clear Signal light and Midnight Library dark, and the accessible control label follows it | `App.test.tsx` theme path |
| Audio playback starts, pauses, ends, or is rejected by the browser | Player state follows successful playback and returns to a ready state after pause, end, or rejection | `App.test.tsx` media paths |
| Worker starts with a valid stage, without a stage, or is interrupted while idle | Non-idle entrypoints complete, invalid configuration fails before readiness, and cancellation propagates | `WorkerEntrypointTest` |
| Local service topology drifts from the production contract | Verification fails if PostgreSQL major, migration gate, worker stage, or separate stores are absent | `scripts/verify-local-environment.sh` in CI |
| A migration is incompatible with the pinned database major | CI fails while applying Flyway migrations to PostgreSQL 17 | CI `core` job |
| PWA shell is offline after a successful visit | Shell resources may be served from the service-worker cache; `/api/` and `/samples/` are never cached | `public/sw.js` policy plus production build verification |
| A mutable container reference is supplied to Terraform | Input validation rejects any core image without a SHA-256 digest | Terraform variable validation |
| Infrastructure or dependency definitions drift or become unsafe | Formatting, validation, dependency, secret, filesystem, CodeQL, and container scans fail CI | CI security and environment jobs |

There is no request payload or user-controlled identifier in this feature, so invalid-input coverage is
limited to deployment configuration validation. Retry, cancellation, idempotency, retention, and
restore behavior belong to later ingestion and lifecycle slices; this skeleton only pins queue retry
bounds, working-store expiry, database point-in-time recovery, and disposable-environment teardown.
