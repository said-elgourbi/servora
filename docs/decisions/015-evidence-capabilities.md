# ADR-015 — Evidence capabilities: per-kind evidence permissions and the Job photo routes

**Status:** Accepted (product-owner decision, 2026-09-15; implemented by `docs/tracker/029-photo-evidence-phases.md` Phase 1)

Date: 2026-09-15
Tracker: `docs/tracker/029-photo-evidence-phases.md` (Phase 0 D1, D1b; Phase 1 implements them)
Contract: `docs/api/job-photos.md` §2
Predecessor of the feature: `docs/tracker/027-android-job-photo-updates.md`, `docs/api/job-photos.md`

References: `BR-001`, `BR-004`, `BR-006`, `BR-007`, `BR-009`, `BR-011`, `BR-015`, `BR-027`, `BR-041`,
`BR-042`, `ADR-012` (the same pattern for the Property capability set), `ADR-013` (the store evidence
travels to), `Project.md` §19, §31, `dev.md` §6, §7, §9, `qa.md` §4.3, §13.

## Context

`BR-015` and `BR-027` make a photo field evidence, and the first evidence slice
(`docs/tracker/027-android-job-photo-updates.md`) implemented it end to end: the `job_photos` table, the
object store behind `ADR-013`, `POST /jobs/:id/photos` and `GET /jobs/:id/photos/:photoId/content`, and
the Android capture flow.

That slice had no capability to authorize the two routes with, so it used an **interim** authorization:
the write required `JOB_UPDATE` and the read required `customers.view`. Both are Manager-facing
capabilities, and the default Technician role (`BR-009`) holds neither. The result was a feature whose
stated author — the technician in the field — is refused `403` by the route that accepts the photo.

Two things had to be decided, and both were product decisions rather than implementation choices
(`BR-042`): whether evidence gets a capability of its own, and its exact shape. Tracker 029 Phase 0
raised them as D1 and D1b, and product ownership answered both on 2026-09-15.

## Decisions

### D1 — Evidence is authorized by capabilities that exist for evidence

Evidence is added and read through dedicated evidence capabilities, revocable **per member** like every
other capability (`BR-006`). The Job photo routes stop depending on `JOB_UPDATE` and `customers.view`.

The reused capabilities were never a claim that updating Jobs is the same act as recording evidence.
`BR-006` authorizes actions by permission, and a permission is supposed to name a capability a role can
plausibly hold on its own; a Manager capability borrowed by a technician's feature is the opposite.
`ADR-012` D1 took the same position for Property creation, which moved off `customers.edit` onto
`properties.create`.

### D2 — The catalogue is per kind, and reserved kinds are not created

The catalogue is `evidence.view` and `evidence.photo.add`. It is **per kind** rather than one
kind-agnostic `add`, so a company can withdraw one kind of evidence from a member without withdrawing
the others: a technician may be trusted with a photo and not with an audio recording.

`evidence.audio.add` is the **agreed but reserved** extension point for audio evidence. It is
deliberately **not** created: `BR-027` leaves audio undefined (no table, no content-type vocabulary, no
playback, no Activity kind — tracker 029 D8), and a capability for a kind nothing can add would be
invented behaviour (`BR-042`). When audio lands it is added additively, in the same per-kind shape.

`evidence.view` and `evidence.photo.add` are independent: adding evidence does not grant reading it
back, and reading does not grant adding. The API enforces each route on its own capability, so a hidden
or disabled control is never the boundary (`BR-007`, `BR-011`).

### D3 — The default Manager and Technician roles hold both

Both capabilities are granted to the default **Manager** and **Technician** roles
(`0010_evidence_permissions.sql` for the organizations the migration finds, and the development seed for
the local QA accounts). This **extends `BR-009`**: the default field role gains a capability the rule did
not originally list, because recording evidence is field work.

The codes are stable and machine-readable, their names and descriptions are bilingual (`BR-005`), and no
route, client or check mentions a role name (`BR-004`, `BR-006`): a custom role or a direct member grant
confers the same capability.

### D4 — This decides the evidence capability only

The `jobs.*` capability set remains the Jobs feature's own open question: the Job read and its activity
are still guarded by `customers.view` and the Job and Visit actions by `JOB_UPDATE`
(`docs/api/job-details.md` §2, `docs/api/job-actions.md` §2). Nothing here widens or renames them.

## Consequences

- `api/src/auth/permissions.ts` carries an `EVIDENCE_PERMISSIONS` catalogue following the
  `PROPERTY_PERMISSIONS` pattern, and `PermissionCode` includes it.
- `api/drizzle/migrations/0010_evidence_permissions.sql` adds the two permission rows and grants them to
  the two default system roles. It is a **new** migration; `0004`–`0009` are applied history and are not
  modified (`dev.md` §6).
- `api/src/jobs/jobs.controller.ts` guards `POST :id/photos` with `evidence.photo.add` and
  `GET :id/photos/:photoId/content` with `evidence.view`.
- `api/src/database/run-development-seed.ts` seeds both capabilities and grants them to the Manager and
  Technician role templates, so the local QA accounts demonstrate the new authorization.
- The Android client's own permission gate is **not** changed by this implementation: the client entry
  point that reads a permission for the technician's capture flow is tracker 029 Phase 3's work, and it
  reads these same codes (`BR-011`). **Delivered by tracker 029 Phase 3 (2026-09-15):** the client reads
  `evidence.photo.add` (`Permission.EVIDENCE_PHOTO_ADD`, `CustomerPermissionsUiState.canAddEvidencePhoto`)
  to draw the Job Details update action and its two photo sources, and the note kind keeps the Job update
  capability as its own — so a default Technician reaches the camera and the picker without holding a
  Manager capability.
- `BR-008`/`BR-009` are the business record of the default role grants, and `BR-040` requires the rule
  text to be updated with this decision. That edit is product ownership's and is outstanding as recorded
  in tracker 029's Phase 1 entry; this ADR records the decision it will state.

## Open questions not decided here

Recorded rather than guessed (`BR-042`); tracker 029 carries them with the phases that depend on them.

1. Audio and file evidence, and the model behind them (D8).
2. Evidence scope — Job versus Visit (D2). **Decided 2026-09-15: Job-level; the domain document is corrected
   with it** (`docs/tracker/029-photo-evidence-phases.md` D2).
3. A viewer, and any thumbnail or derived-object strategy (D4). **Partly decided:** the viewer landed in
   tracker 029 Phase 4, the preview/caching strategy is `docs/decisions/016-android-image-stack-and-viewer-zoom.md`
   (D4b ✓, Coil 3), and server-derived thumbnails stay open there.
4. Offline visibility of accepted evidence (D5). **Deferred 2026-09-15** — still open, and Phase 5 with it.
5. Retention, deletion and lifecycle of evidence (D6), which `BR-027` and `BR-033` leave open. **Partly
   decided:** a refused photo may be explicitly discarded (`D6c` ✓, tracker 029 Phase 6a); delete/edit,
   retention and Job-deletion objects stay open.
