# Tracker 023 — Object storage: MinIO in the local stack behind the S3 contract

**Status: COMPLETE for the local stack and its documentation — no application code in this slice**

Date: 2026-09-14
Decision: `docs/decisions/013-object-storage-minio-and-s3.md`
Predecessor: none (infrastructure slice; the last feature slice is
`docs/tracker/022-android-job-activity-timeline.md`)
Business rules: `BR-001`, `BR-007`, `BR-015` (provenance only), `BR-027` (provenance only),
`BR-041`, `BR-042`
References: `ADR-001` (foundation stack), `docs/architecture/offline-first-architecture.md` §9,
`dev.md` §4, §5, §6, §11 and §14, `Project.md` §13, §14 and §23, `qa.md` §15

## Scope

The local development stack gains an S3-compatible object store so that the evidence features can
be built against it, and so that production — a VPS with a third-party S3-compatible provider —
needs configuration rather than code. This slice is **infrastructure and documentation only**: no
API route, no database table, no Android code, no business rule and no bucket layout is invented
(`BR-042`).

| Item                                                                             | Status      |
| -------------------------------------------------------------------------------- | ----------- |
| `minio` service in `docker-compose.yml`, pinned to an exact release (`ADR-013` D2) | Implemented |
| `minio-data` named volume, removed by `make down -v`                               | Implemented |
| Console pinned to `:9001` through `--console-address` (`ADR-013` D3)               | Implemented |
| `minio-init` one-shot bucket bootstrap, idempotent and private (`ADR-013` D4)       | Implemented |
| `MINIO_*` / `S3_BUCKET` documented in `.env.example`, local-only credentials        | Implemented |
| `S3_*` application contract documented, commented until the adapter lands (`D6`)    | Implemented |
| `make minio-console` / `minio-shell` / `minio-ls` targets                           | Implemented |
| `up` / `down-v` help text now describes the storage service                         | Implemented |
| `ADR-013` records the S3-only contract and the client path decision (`D6`, `D7`)    | Implemented |
| Local setup and troubleshooting documentation                                       | Implemented |
| API, database, Android, permissions and existing trackers                           | Unchanged   |

## Decisions taken here

1. **MinIO is a local stand-in, not an integration.** It exists in `docker-compose.yml` only. The
   application's contract is S3, so the provider swap later is configuration (`ADR-013` D1, D6).
2. **The bucket is provisioned by the stack, not by the application.** `minio-init` is idempotent
   and private; a deployment provisions its bucket out of band, and the API must not assume it may
   create one (`ADR-013` D4).
3. **Evidence will travel through the API on the API port.** The client keeps one network path
   locally and in production, bytes are validated server-side before becoming evidence, and
   authorization stays per request (`ADR-013` D7).
4. **Presigning is not decided.** The Host-binding constraint of SigV4 is recorded so that a later
   presigned-read optimization cannot be designed by accident (`ADR-013` D7).
5. **No evidence domain model is invented.** Attachment tables, object keys, retention and
   thumbnails stay open until product ownership decides them (`BR-027`, `BR-033`, `BR-042`).

## Verification

| Check                                                                         | Result                     |
| ----------------------------------------------------------------------------- | -------------------------- |
| `docker compose config -q` (interpolation and structure)                       | PASS                       |
| `docker compose config --services` → `minio`, `minio-init` present             | PASS                       |
| `docker compose config --volumes` → `minio-data` present                       | PASS                       |
| `make help` lists the three new targets                                        | PASS                       |
| `make up` starts `minio` **healthy** and `minio-init` exits `0`                 | PASS                       |
| `curl -fsS http://localhost:9000/minio/health/live`                            | PASS — HTTP 200            |
| `minio-init` bootstrap log: alias added, bucket created, policy `private`       | PASS                       |
| `make minio-ls` lists the bucket (empty on a fresh stack)                      | PASS                       |
| Anonymous `GET /servora-dev/` is refused                                       | PASS — HTTP 403            |
| Object round trip through the stack: write 20 B → list → read back → delete     | PASS — bucket left empty   |
| `make api-test` — no regression from the new service                           | PASS — 42 files, 363 tests |
| `make api-test-e2e` — no regression from the new service                       | PASS — 16 files, 216 tests |
| API / Android / database behaviour changed by this slice                       | None — no application code |

One defect was found and fixed during verification: MinIO's server image ships an **unauthenticated**
`local` alias, so the first `minio-ls` target failed with `Access Denied` against the private
bucket. The `make` targets now set their own alias from the container's credentials, which is also
why the documentation calls that alias out explicitly.

Manual QA the product owner may choose to run:

```bash
make up
make minio-console      # then open the printed URL in a host browser
make minio-ls           # empty listing for the bucket, not an error
docker compose ps       # minio healthy, minio-init exited 0
```

## Not in this slice

1. The evidence domain model and object-key layout.
2. Retention, lifecycle and versioning (`BR-027`, `BR-033`).
3. Thumbnails or other derived objects.
4. The API storage adapter (`S3_*` becomes active configuration there).
5. Presigned URLs, for uploads or reads.
6. The production provider, its bucket and its provisioning.
