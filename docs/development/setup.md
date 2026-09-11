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
fresh environment cannot sign in. `make seed` creates the smallest dataset that can:
two active accounts, one per default foundation role (`BR-003`), inside one organization.

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

Implementation: `api/src/database/development-seed.ts` resolves the dataset from the
environment (pure, unit-tested in `development-seed.spec.ts`) and
`api/src/database/run-development-seed.ts` performs the writes (`npm run db:seed`).

Customers, jobs, assignments and evidence are intentionally **not** seeded: those rows are not
needed for authentication smoke testing.

## 4. Android against a local API (physical device or emulator)

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
| `make up` / `make down` / `make down-v`                | Start / stop / stop + delete the database volume |
| `make logs` / `make ps`                                | Follow logs / show service status                |
| `make migrate`                                         | Apply migrations (host tooling)                  |
| `make migration NAME=<name>`                           | Generate a migration from the Drizzle schema     |
| `make seed`                                            | Seed the development dataset (§3)                |
| `make db-shell`                                        | `psql` against the local database                |
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

## 7. Troubleshooting

| Symptom                                                                         | Cause                                                                | Fix                                                                                                             |
| ------------------------------------------------------------------------------- | -------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| Every `/auth/*` route returns `404`                                             | The API container is running an image built before the route existed | `make up` (rebuilds; the compose image is not rebuilt by a plain restart)                                       |
| Compose exits immediately or the API container restarts repeatedly              | `JWT_SECRET` missing, or shorter than 32 characters                  | Set a valid `JWT_SECRET` in `.env`                                                                              |
| Sign-in shows a generic network/server error instead of "incorrect credentials" | The device cannot reach the base URL                                 | Check `adb reverse --list`, confirm `servora.api.baseUrl=http://127.0.0.1:3000/` (trailing slash), then rebuild |
| A base-URL change has no effect                                                 | `API_BASE_URL` is compiled into the APK                              | `./gradlew installDebug` again                                                                                  |
| `make seed` reports an invalid `SEED_*_PASSWORD`                                | Password shorter than the 8-character domain minimum                 | Use a longer value, or leave the variable empty to generate one                                                 |
| `make seed` / `make migrate` cannot reach PostgreSQL                            | `DATABASE_URL` does not match the published `POSTGRES_PORT`          | Keep both values in sync in `.env`                                                                              |
