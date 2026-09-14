# Servora — Local Development Setup

How to bring up the foundation, **seed test data**, and run a client against a locally running API.

`Project.md` §33 names this file as the development-setup reference. Commands below are the
repository's own `Makefile` targets; the `Makefile` remains authoritative if they ever change.

References: `dev.md` §5 (configuration and secrets), `dev.md` §6 (database), `qa.md` §3
(verification commands), `docs/api/authentication.md`, `docs/tracker/001-foundation.md`.

## 1. Prerequisites

| Tool                                  | Why                                                                      |
| ------------------------------------- | ------------------------------------------------------------------------ |
| Docker Engine with the Compose plugin | Runs PostgreSQL and the API (`make up`)                                  |
| Node.js ≥ 24 + npm                    | Host tooling: migrations, seed, API tests (`api/package.json` `engines`) |
| JDK + Android SDK                     | Only for Android builds (`make android-build`)                           |

## 2. Foundation (database + API)

```bash
cp .env.example .env          # gitignored; docker compose reads it automatically
openssl rand -base64 48       # put the result in JWT_SECRET (≥ 32 characters, no fallback)
make up                       # build + start PostgreSQL and the API
make migrate                  # apply pending Drizzle migrations (host tooling)
curl http://localhost:3000/health
```

Notes:

- `JWT_SECRET` is mandatory. `docker-compose.yml` interpolates
  `${JWT_SECRET:?…}`, so a missing value fails the compose command immediately instead of
  leaving the API container in a crash loop.
- Host tooling (`make migrate`, `make seed`, `npm run test:e2e`) connects with `DATABASE_URL`
  from `.env`, so it must use the **published** `POSTGRES_PORT`. In this working copy that is
  `5434`, which is the same database the API container uses.
- Migrations are generated, never hand-edited (`dev.md` §6, `Project.md` §8):
  `make migration NAME=<short_name>`.

## 3. Development seed data (`make seed`)

The foundation database starts empty and the API has no user-creation endpoint yet, so a
fresh environment cannot sign in. `make seed` creates two active accounts, one per default
foundation role (`BR-003`), inside one organization. It also adds a deterministic operational
demo dataset for local scrolling/filtering checks.

```bash
make seed
```

| What               | Value                                                                               |
| ------------------ | ----------------------------------------------------------------------------------- |
| Organization       | `Servora Development` (`SEED_ORGANIZATION_NAME`)                                    |
| Manager account    | `manager@servora.test` (`SEED_MANAGER_EMAIL`) — default Manager role                |
| Technician account | `technician@servora.test` (`SEED_TECHNICIAN_EMAIL`) — default Technician role       |
| Password           | `SEED_MANAGER_PASSWORD` / `SEED_TECHNICIAN_PASSWORD`, or generated and printed once |

Written per account: the `users` row (address, Argon2id hash, `ACTIVE`), the `user_profiles`
row (`Dev Manager` / `Dev Technician`), and the `organization_members` row (`role_id`,
`ACTIVE`). The seed also upserts the default permission catalogue, default organization roles and
role-permission assignments.

Operational demo data:

- 20 customers in the same organization, mixing companies and individuals, active and inactive
  status, English and French language preferences, and several preferred contact methods.
- Customer contacts, billing addresses, 1-4 service Properties per customer, and active
  Property-Customer relationship rows.
- 44 Jobs numbered from `1001`, spread across `NEW`, `SCHEDULED`, `IN_PROGRESS`,
  `PENDING_REVIEW`, `COMPLETED` and `CANCELED`.
- Visits, visit assignments and visit notes for scheduled/field-work scenarios, including
  scheduled, active, completed and canceled Visit states.
- Rows created by the operational demo are tagged with `[dev-seed-operational]`. Re-running
  `make seed` refreshes those tagged rows instead of appending another copy.

Credential policy:

- **No password is committed.** `.env` is gitignored and `.env.example` ships the variables
  empty; a missing or blank variable makes the command generate a password and print it once
  (24 URL-safe characters). Set the variable locally to keep a password you can type repeatedly.
- Re-running `make seed` is safe and idempotent: it upserts by email, so it repairs a drifted
  account (reactivated, password re-hashed from the configured value) instead of creating a
  second one.
- The command refuses to run when `NODE_ENV=production`; it is development tooling, never part
  of the API process.
- The addresses use the reserved `.test` suffix (RFC 2606), so a seeded account can never reach
  a real inbox.

### Verifying the seeded credentials

`POST /auth/sign-in` requires a `device` object next to the credentials
(`docs/api/authentication.md` §3.1), so a request without it is rejected with `400
VALIDATION_FAILED` before the password is ever checked:

```bash
curl -s -X POST http://localhost:3000/auth/sign-in -H 'content-type: application/json' \
  -d '{"email":"manager@servora.test","password":"<seeded password>","device":{"platform":"WEB","deviceId":"qa-curl","deviceName":"QA curl"}}'
```

Expected: `200` with `sessionId`, `accessToken` and `refreshToken`. A wrong password returns
`401 INVALID_CREDENTIALS` with the same body shape, and a request without `device` returns `400`.

Implementation: `api/src/database/development-seed.ts` resolves the credential dataset from the
environment (pure, unit-tested in `development-seed.spec.ts`) and
`api/src/database/run-development-seed.ts` performs the account, permission and operational demo
writes (`npm run db:seed`).

### What `make seed` writes

Beyond the two accounts, the seed writes operational demo data so the management screens have something
real to present:

- 20 customers, their contacts, addresses and Properties, and Jobs spread across every Job status;
- **four further Technician members** (`Sarah Moreau`, `John Tremblay`, `Priya Raman`, `Luc Gagnon`) so
  a Visit can carry a real crew (`BR-068`). They are members only: the two documented credentials above
  remain the only seeded logins and no password is generated for them;
- **two or three technicians per Visit** with exactly one `LEAD`, rotating the Lead between Visits, with
  the assignment **history** `BR-069` describes — a Lead who was first assigned as an ordinary technician
  and promoted later, and a technician who was removed again — recorded as history rows rather than a
  rewritten assignment;
- **three notes per Visit** from the manager and the crew (`BR-027`);
- the **Visit and Job status history** along the only paths `BR-074` and `BR-058` permit.

Re-running `make seed` refreshes this demo data rather than duplicating it: the previous run's customers,
Properties, Jobs, Visits and their history are removed first.

## 4. Android against a local API (physical device or emulator)

> **Who runs the `adb` steps below.** The commands in this section are for the **product owner**, who
> is the Android QA tester and owns the phone. An AI agent must never run `adb` — not to list devices,
> install, launch, forward a port or read `logcat` — and must not run Gradle tasks that drive a device
> through it (`installDebug`, `connectedDebugAndroidTest`). The agent's device-free Android
> verification is `make android-test`, `make android-lint` and `make android-build`, plus compiling
> the device-test sources. See `.clinerules/qa.md` §7.3.

The Android client reads a **compile-time** base URL. It is resolved in
`android/app/build.gradle.kts` in this order:

1. `-Pservora.api.baseUrl=…` (one-off or CI build)
2. `servora.api.baseUrl` in `android/local.properties` (gitignored, per-developer)
3. fallback `http://10.0.2.2:3000/`

The winner is baked into `BuildConfig.API_BASE_URL` and consumed in
`NetworkModule.provideRetrofit()`, so **changing it requires a rebuild and reinstall** — a
restart of the app is not enough.

### Physical device

```bash
# 1. Connect the device (USB, or wireless with `adb connect <host>:<port>`).
adb devices                      # the device must be listed as `device`, not `unauthorized`

# 2. Forward the device's own localhost to this machine's published API port.
adb reverse tcp:3000 tcp:3000
adb reverse --list               # verify the mapping exists

# 3. Point the app at the device-local address (note the trailing slash).
#    android/local.properties:
#    servora.api.baseUrl=http://127.0.0.1:3000/

# 4. Build, install and launch the debug variant.
cd android && ./gradlew installDebug
adb shell monkey -p com.servora.android.debug -c android.intent.category.LAUNCHER 1
adb logcat --pid=$(adb shell pidof -s com.servora.android.debug)
```

Why `127.0.0.1` and not `10.0.2.2`: `10.0.2.2` is an emulator-only alias for the host, and on a
real phone `127.0.0.1` is the phone itself. `adb reverse` makes the phone's `localhost:3000` a
tunnel to this machine, which also survives host IP changes and needs no LAN exposure.

Notes:

- **Emulator**: `adb reverse` is unnecessary; the fallback `10.0.2.2:3000` already reaches the
  host.
- `adb reverse` is dropped when the device disconnects (wireless sessions in particular) — run
  it again after reconnecting, otherwise the app silently loses connectivity.
- Debug builds use application id `com.servora.android.debug` and permit cleartext HTTP via
  `android/app/src/debug/res/xml/network_security_config.xml`. That permission is **debug-only**;
  release builds must use HTTPS.

## 5. Command reference

| Command                                                | Purpose                                          |
| ------------------------------------------------------ | ------------------------------------------------ |
| `make up` / `make down` / `make down-v`                | Start / stop / stop + delete the database and storage volumes |
| `make logs` / `make ps`                                | Follow logs / show service status                |
| `make migrate`                                         | Apply migrations (host tooling)                  |
| `make migration NAME=<name>`                           | Generate a migration from the Drizzle schema     |
| `make seed`                                            | Seed the development dataset (§3)                |
| `make db-shell`                                        | `psql` against the local database                |
| `make minio-ls` / `minio-shell` / `minio-console`      | Object storage: list objects / shell / Console (§7) |
| `make api-build` / `api-start` / `api-start-dev`       | Build / run the API on the host                  |
| `make api-test` / `api-test-e2e`                       | API unit tests / API e2e tests (needs `make up`) |
| `make api-lint` / `api-format`                         | Lint / format the API sources                    |
| `make android-build` / `android-test` / `android-lint` | Assemble debug APK / JVM tests / lint            |
| `make test` / `lint` / `build`                         | Aliases for the API targets                      |

## 6. Transactional email (`ADR-008`)

Password-reset messages are delivered through the `EMAIL_PROVIDER` port. Every value has an
approved default, so a local stack and the test suite need no configuration at all.

| Variable                  | Default | Meaning                                                                                          |
| ------------------------- | ------- | ------------------------------------------------------------------------------------------------ |
| `PASSWORD_RESET_DELIVERY` | `noop`  | Which port delivers a reset: `noop`, `file` (development sink, refused in production) or `email` |
| `EMAIL_PROVIDER`          | `noop`  | Which service sends email: `noop` or `resend`                                                    |
| `RESEND_API_KEY`          | —       | Required when `EMAIL_PROVIDER=resend`; the API refuses to start without it                       |
| `EMAIL_FROM`              | —       | Required when `EMAIL_PROVIDER=resend`; an address on a domain verified in Resend                 |

```bash
# Deliver reset codes by email in a local run. Never commit these values (dev.md §5).
cd api
EMAIL_PROVIDER=resend \
  RESEND_API_KEY=re_... \
  EMAIL_FROM=no-reply@your-verified-domain.example \
  PASSWORD_RESET_DELIVERY=email \
  npm run start:prod
```

Verifying the sending domain with Resend is an operational step, not code. Without a provider
account, keep the defaults and use the development file sink instead (run the API on the host and
set `PASSWORD_RESET_DELIVERY=file`); `docs/tracker/004-authentication-domain-model.md` has the
step-by-step reset checklist, and `.env.example` documents every variable.

A provider rejection never changes the API response: the reset request answers `202` either way
(`BR-044`), the failure is logged without the message body (`BR-046`), and a misconfigured
`EMAIL_PROVIDER=resend` stops the process at startup rather than at the first reset request.

## 7. Object storage (MinIO, `ADR-013`)

The local stack includes **MinIO**, an S3-compatible object store, so the evidence features can be
built against real storage. It is a development stand-in: the application's contract is S3, and a
deployment runs against a third-party S3-compatible provider through configuration only
(`docs/decisions/013-object-storage-minio-and-s3.md`).

| What          | Value                                                                    |
| ------------- | ------------------------------------------------------------------------ |
| S3 API (host) | `http://localhost:9000`                                                  |
| Console       | `http://localhost:9001` (`make minio-console`)                            |
| Credentials   | `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` in the repository-root `.env`   |
| Bucket        | `S3_BUCKET` (default `servora-dev`), provisioned **private**              |
| Data          | the `minio-data` named volume; `make down-v` deletes it                   |

`make up` starts `minio` and then runs the one-shot `minio-init` service, which creates the bucket
if it does not exist and leaves it private. The bootstrap is idempotent, so it runs harmlessly on
every `make up`. A deployment provisions its own bucket out of band, which is why the API never
assumes it may create one.

```bash
make minio-ls          # list the bucket's objects (empty on a fresh stack)
make minio-shell       # a shell in the container, with an authenticated `mc` alias
make minio-console     # the Console URL and how to sign in
```

The image ships an **unauthenticated** `local` alias, so the `make` targets set their own alias
from the container's own credentials. Running `mc` against `local` directly reports `Access Denied`
on the private bucket — that is expected, not a broken stack.

### Moving to an S3-compatible provider later

Nothing in the application names MinIO. When the API is deployed to a VPS with a provider, the
`S3_*` values documented in `.env.example` are set instead, and the `minio` services stay out of the
deployment entirely. `S3_BUCKET` remains the one name the stack and the application share.

`S3_ENDPOINT` differs by where the process runs, exactly like `DATABASE_URL`: the API container
uses the service name (`http://minio:9000`) while host tooling uses the published port
(`http://localhost:9000`).

### Evidence and the Android device

Evidence travels through the API, so the device keeps the single network path it already has — the
API base URL, reached today through `adb reverse`. **MinIO's port is not reversed and does not need
to be.** If reads ever move to presigned URLs, the signature is bound to the exact host in the URL:
that change would need `S3_PUBLIC_ENDPOINT` plus a second tunnel (`adb reverse tcp:9000 tcp:9000`)
locally, and an HTTPS endpoint in a release build. The reasoning is recorded in `ADR-013` D7.

## 8. Troubleshooting

| Symptom                                                                         | Cause                                                                | Fix                                                                                                             |
| ------------------------------------------------------------------------------- | -------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| Every `/auth/*` route returns `404`                                             | The API container is running an image built before the route existed | `make up` (rebuilds; the compose image is not rebuilt by a plain restart)                                       |
| Compose exits immediately or the API container restarts repeatedly              | `JWT_SECRET` missing, or shorter than 32 characters                  | Set a valid `JWT_SECRET` in `.env`                                                                              |
| Sign-in shows a generic network/server error instead of "incorrect credentials" | The device cannot reach the base URL                                 | Check `adb reverse --list`, confirm `servora.api.baseUrl=http://127.0.0.1:3000/` (trailing slash), then rebuild |
| A base-URL change has no effect                                                 | `API_BASE_URL` is compiled into the APK                              | `./gradlew installDebug` again                                                                                  |
| `make seed` reports an invalid `SEED_*_PASSWORD`                                | Password shorter than the 8-character domain minimum                 | Use a longer value, or leave the variable empty to generate one                                                 |
| `make seed` / `make migrate` cannot reach PostgreSQL                            | `DATABASE_URL` does not match the published `POSTGRES_PORT`          | Keep both values in sync in `.env`                                                                              |
| `make up` fails because ports 9000 or 9001 are taken                            | Another local service publishes them                                 | Set `MINIO_PORT` / `MINIO_CONSOLE_PORT` in `.env` and re-run `make up`                                           |
| `mc` reports `Access Denied` on the bucket                                      | The image's built-in `local` alias is not authenticated              | Use `make minio-ls` or `make minio-shell`, which set their own alias from the container's credentials            |
| The `minio` container restarts repeatedly, or its healthcheck never goes healthy | The pinned image cannot run on this CPU                              | Use the `-cpuv1` variant of `quay.io/minio/minio` at the same release in `docker-compose.yml`                     |
| `http://localhost:9001` does not answer                                         | The Console binds a random port when `--console-address` is missing  | Keep `--console-address ":9001"` in the `minio` command                                                          |
| A presigned URL returns `SignatureDoesNotMatch` (once presigning exists)        | SigV4 covers the `Host` header, so the URL was signed for another host | Sign for exactly the host the client calls; see `ADR-013` D7 and `S3_PUBLIC_ENDPOINT`                             |
