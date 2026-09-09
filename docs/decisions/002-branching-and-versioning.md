# ADR-002 — Git Branching & Versioning Strategy

- Status: Accepted
- Date: 2026-09-09
- Milestone: `docs/tracker/002-repository-foundation.md`

## Context

The API foundation milestone (`docs/tracker/001-foundation.md`) was completed and verified on
disk but was never placed under version control: the working directory had no Git repository,
and the canonical GitHub repository (`https://github.com/said-elgourbi/servora.git`) was empty.

Before domain features begin (customer → jobs → tasks/execution), the repository layout and
versioning rules must be established so future developers and agents do not produce
inconsistent Git history, undocumented database changes, incompatible API contracts, or a
competing remote.

## Decisions

### D1 — Canonical repository and remote

`origin = https://github.com/said-elgourbi/servora.git`. Servora stays a single monorepo in this
one repository. No other repository is created, renamed, or used as a competing remote.

### D2 — `main` + `develop` branching model

Two long-lived branches:

- `main` — stable, release-ready; only verified work is merged in; releases are tagged here.
- `develop` — the primary integration and development branch.

GitFlow-style `release/*`, `hotfix/*`, and extra long-lived branches are avoided.

### D3 — Short-lived feature branches

Substantial work branches from `develop` as `feature/<name>`, `fix/<name>`, `docs/<name>`,
`refactor/<name>` and merges back into `develop`. Small documentation/configuration changes may
be committed directly to `develop`. Flow: `feature → develop → main → release tag`.

### D4 — Conventional commits

Scoped conventional commit messages (`feat(customer): …`, `fix(customer): …`,
`docs(versioning): …`). Logically focused, easy to revert; no meaningless messages.

### D5 — Semantic versioning

`MAJOR.MINOR.PATCH`, staying in `0.x` during early development. Git tags (`v0.1.0`, …) are
created only from `main` for verified release milestones — never for arbitrary development
commits. Versions are milestones, not per-commit numbers.

### D6 — Database migrations are immutable and numbered

The PostgreSQL schema is part of the versioned source. All schema changes are new numbered
migrations under `api/drizzle/migrations`; an applied/committed migration is never edited.
A clone must reproduce the schema with `make up` + `make migrate`.

### D7 — Single API contract first

No `/api/vN` prefix is introduced pre-emptively. The API evolves backward-compatibly; a breaking
change is an explicit decision with a documented migration strategy. Android never depends on
PostgreSQL implementation details.

### D8 — Android versioning fields

`versionCode` is a monotonic integer per published build; `versionName` is the semver-aligned
display value (`0.1.0` ↔ `versionCode 1`). These are distinct from Git commits and tags. They are
wired into the Gradle foundation in the Android foundation slice.

### D9 — Cross-component change discipline

A domain change is traced through PostgreSQL → API → Android (→ Angular later). A breaking
contract change updates migration, API, Android, verification, and documentation as one
coherent commit; no component is left silently incompatible.

### D10 — Bootstrap state

The repository starts from the verified API foundation. Both `main` and `develop` are seeded at
the same foundation HEAD (history: foundation import, then versioning/governance documentation).
Divergence begins with the first feature slice: `develop` receives feature merges; `main`
advances only at releases.

### D11 — Versioning documentation is authoritative

`docs/versioning.md` is the single source of truth for repository/versioning rules, supported by
ADRs (`docs/decisions/`) and the lightweight milestone tracker (`docs/tracker/`).

## Consequences

- Agents start from `git status` / `git branch` / `git log --oneline` and read
  `docs/versioning.md` before working.
- First release flow will be `feature/… → develop → main → v0.1.0`.
- The on-disk `android/` tree is empty (empty directories only), so Git tracks nothing there;
  the Gradle foundation (version catalog, `versionCode`/`versionName`) will be scaffolded in its
  own Android foundation slice before any Android business screen.
- The empty GitHub remote receives `main` and `develop` from the local bootstrap history.
