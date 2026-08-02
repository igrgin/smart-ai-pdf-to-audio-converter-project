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
| Listener deletes one Private Audiobook | The request returns `202`, playback is denied transactionally, the authorization generation advances, and a tombstone plus exact erasure obligations are durable | `PrivateContentDeletionITest` audiobook path and `App.test.tsx` listener control |
| Listener deletes the account | The Listener, sessions, sign-in sources, grants, and every Private Audiobook are denied before the `202` response; external identities are tombstoned | `PrivateContentDeletionITest` account path and `library/api.test.ts` |
| Erasure work is delivered more than once | Working and finalized object deletion is idempotent, every finalized object generation is deleted, relational private data is removed last, and content-free evidence is recorded once | `PrivateContentDeletionITest`, filesystem asset-store tests, and `GoogleCloudAudiobookAssetStoreTest` |
| An erasure target, provider proof, or retry deadline is missed | Reconciliation creates a content-free compliance incident that projects into the urgent Security Reviewer Action Queue | `ErasureDeadlinePolicyTest`, `PrivateContentDeletionITest`, and database constraints |
| A backup resurrects deleted references | Tombstones replay while private traffic is gated, access and authorization generations are denied again, and fresh idempotent obligations remove restored data | `PrivateContentDeletionITest` restore-replay path and restore safety gate |
| Retention configuration drifts beyond the contract | Startup rejects targets beyond 24 hours, day 23, 30 days, or 90 days; Terraform schedules erasure/reconciliation and validates the backup ceiling | `RetentionPropertiesTest` and Terraform validation |

The public skeleton has no request payload or user-controlled identifier, so its invalid-input coverage
remains limited to deployment configuration validation. Later lifecycle slices now cover retry,
cancellation, idempotency, retention, and restore behavior at their owning feature boundaries.
