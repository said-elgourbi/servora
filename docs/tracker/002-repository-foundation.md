# Tracker 002 — Repository & Versioning Foundation

Milestone status for establishing Git history, the canonical remote, and the versioning rules
for Servora (governance over the verified API foundation from `docs/tracker/001-foundation.md`).

- Status: **COMPLETE**
- Date: 2026-09-09
- Decisions: `docs/decisions/002-branching-and-versioning.md`
- Authoritative rules: `docs/versioning.md`

## Scope

| Item | Status |
| --- | --- |
| Inspect GitHub repository (`https://github.com/said-elgourbi/servora.git`) — was empty | Done |
| Attach canonical remote `origin` | Done |
| Establish local Git history from the verified API foundation (import commit) | Done |
| Create `main` and `develop` seeded at the verified foundation HEAD | Done |
| `docs/versioning.md` — single source of truth | Done |
| ADR-002 decision record | Done |
| README updated with repository/versioning pointers | Done |
| Foundation committed to `develop` (current working branch) | Done |
| Pushed `main` + `develop` to `origin` | Done |
| Clone-reproducibility verified (no `.env`, `node_modules`, `dist` in history) | Done |

## Verification (2026-09-09)

- `git log --oneline --graph` shows the two bootstrap commits on `develop`.
- `git branch` shows `develop` (checked out) and `main` at the same foundation HEAD.
- `git ls-files` contains no `.env`, `node_modules/`, `dist/`, or `*.tsbuildinfo`.
- Fresh clone of the repository lists the same source tree (API, docs, tooling).
- API unit tests / lint / build re-run green on the committed tree (no API source changed in
  this milestone — baseline verified in `docs/tracker/001-foundation.md`).

## Out of scope / next

- **Customer** is the first domain feature slice (`feature/customer-foundation` from `develop`):
  PostgreSQL → Node.js API → Android. Not started in this milestone.
- The on-disk `android/` tree currently contains only empty directories (nothing Git can track).
  The Android Gradle foundation — version catalog, `versionCode`/`versionName` wiring — is its own
  slice before any Android business screen.
- Angular client is not started.
