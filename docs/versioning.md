# Servora — Versioning & Repository Strategy

> Single source of truth for Servora's Git layout, releases, and component versioning.
> Read this before branching, releasing, or changing database/API/Android contracts.
> Companion to `Project.md`, `dev.md`, `qa.md`, and the ADRs in `docs/decisions/`.
> Decision record: `docs/decisions/002-branching-and-versioning.md`.

## 1. Canonical repository

- Canonical remote: `https://github.com/said-elgourbi/servora.git` (remote name `origin`).
- This is the only authoritative repository. Do not create, rename, or add a competing remote.
- Servora is one monorepo: `api/` (NestJS + PostgreSQL), `android/` (Kotlin/Compose), Angular (added later), `docs/`.

## 2. Branch model

Two long-lived branches only:

| Branch    | Purpose                                                                                                    |
| --------- | ---------------------------------------------------------------------------------------------------------- |
| `main`    | Stable, release-ready. Only verified work is merged in. Releases are tagged from `main`. Never developed on directly. |
| `develop` | Primary integration and development branch. Completed work is combined and tested here before release. Must remain functional. |

Substantial work uses short-lived branches from `develop`:

```text
feature/<short-name>    e.g. feature/customer-foundation
fix/<short-name>        e.g. fix/customer-validation
docs/<short-name>       e.g. docs/versioning
refactor/<short-name>
```

Rules:

- Do not develop features directly on `main`.
- Small documentation/configuration changes may be committed directly to `develop`.
- Avoid GitFlow-style `release/*`, `hotfix/*` and extra long-lived branches.

## 3. Development flow

```text
feature branch → develop → main → release tag
```

1. From `develop`, branch off (`git checkout -b feature/...`).
2. Implement, test, review.
3. Merge into `develop`.
4. After sufficient integration testing, merge `develop` into `main`.
5. Tag the release from `main` (`v0.1.0`).

## 4. Commits

Conventional Commit style, scoped where useful:

```text
feat(customer): ...
fix(customer): ...
docs(versioning): ...
refactor: ...
test: ...
chore: ...
build: ...
ci: ...
```

Keep commits logically focused, easy to understand and revert. Never use meaningless messages
such as `update`, `changes`, or `stuff`.

## 5. Semantic versioning (releases)

Format `MAJOR.MINOR.PATCH`. During early development Servora stays in `0.x`.

- `PATCH` — backward-compatible bug fix.
- `MINOR` — new backward-compatible functionality.
- `MAJOR` — breaking change, relevant once a stable public contract exists.

Rules:

- Versions represent meaningful releases or milestones, not individual commits.
- Git tags look like `v0.1.0`, `v0.2.0`, `v1.0.0`.
- Tags are created only from `main`, for verified releases. Never tag arbitrary development commits.
- Related but distinct concepts: Git commit ≠ Git tag ≠ application version ≠ Android `versionCode`.

## 6. Database versioning

- The PostgreSQL schema is part of the versioned source code (`api/drizzle/migrations`).
- Every schema change is represented by a new, numbered migration
  (`001_create_customers`, `002_add_customer_phone`, …).
- Never modify a migration after it has been committed and applied elsewhere.
- Never rely on manual database edits, undocumented SQL, or "the database already has this column".
- A fresh clone reproduces the schema with `make up` followed by `make migrate`.
- The first domain feature creates the Drizzle migration journal (none exists yet, see ADR-001).

## 7. API versioning

- Start with a single clear API contract. Do not add `/api/v1`, `/api/v2`, … pre-emptively.
- Prefer backward-compatible API evolution whenever practical.
- A breaking API change is an explicit decision: document the migration strategy rather than
  silently breaking Android clients.
- Android communicates through the API and never depends on PostgreSQL implementation details.
  The API is the boundary between application and persistence.

## 8. Android versioning

- Android uses two distinct fields:
  - `versionName` — human/semver-aligned display value, e.g. `0.1.0`.
  - `versionCode` — monotonic integer that must increase for every new published build.
- Example: release `0.1.0` → `versionName = "0.1.0"`, `versionCode = 1`;
  next release `0.2.0` → `versionName = "0.2.0"`, `versionCode = 2`.
- Wiring these fields into the Gradle foundation is part of the Android foundation slice
  (before any Android business screens).

## 9. Cross-component compatibility

Components: `PostgreSQL → Node.js API → Android` (→ Angular later).

A domain change ripples down the chain. For any contract change (for example a customer field):

1. Identify affected components (database, API DTO/validation, Android models, Android UI).
2. Document the change.
3. Add the database migration.
4. Update the API.
5. Update Android (and Angular when present).
6. Run verification.
7. Record the decision.
8. Commit the complete change as one coherent unit.

No component may be silently left incompatible with another.

## 10. Release discipline

Before promoting `develop` to `main`:

- run the applicable automated tests;
- verify database migrations;
- verify API behavior;
- verify the Android build (when Android changed);
- review the Git diff/history;
- ensure documentation and the tracker are updated;
- ensure no known incomplete change is included.

Then merge into `main` and create the appropriate Git tag.

## 11. Agent startup procedure

Before modifying code, check:

```text
git status
git branch
git log --oneline
```

and read the applicable project documentation. An agent must know:

- which branch it is on;
- whether there are uncommitted changes;
- the latest meaningful commits;
- the current application version;
- pending database migrations;
- relevant architecture decisions.

Rules:

- Do not blindly modify a dirty working tree.
- Do not discard existing user changes.
- Do not force-push or rewrite shared Git history unless explicitly instructed.

## 12. Documentation & decision tracking

- `docs/versioning.md` — this document (authoritative for versioning).
- `docs/decisions/` — ADRs for architectural and process decisions.
- `docs/tracker/` — lightweight milestone status so agents can see what is done and what is next.
- Keep documents small and focused.

