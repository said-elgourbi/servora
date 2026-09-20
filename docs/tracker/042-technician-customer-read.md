# Tracker 042 — The technician's read of the Customer behind an assigned Job

**Status: implemented; the API is verified at Tier B (full lint, unit and e2e suites, build); Android
verification NOT RUN — the product owner asked for no Gradle/Android commands on this machine; the
Android device QA is the product owner's**

Date: 2026-09-18
Predecessors: `docs/tracker/037-technician-field-experience.md` (the field read this slice extends),
`docs/tracker/017-android-job-details.md` (the Job Details screen it adds the block to),
`docs/tracker/029-photo-evidence-phases.md` and `docs/tracker/012-property-permissions-and-lifecycle-schema.md`
(the dedicated-capability pattern it follows)
Business rules: `BR-001`, `BR-004`, `BR-006`, `BR-007`, `BR-009`, `BR-010`, `BR-011`, `BR-012`, `BR-013`,
`BR-014`, `BR-023`, `BR-031`, `BR-040`, `BR-041`, `BR-042`, `BR-047`, `BR-048`, `BR-057`, `BR-068`,
`BR-080`, **`BR-092` (new)**
Domain model: `docs/domain/job-visit-domain-model.md` §8.3 — **extended by this slice**
API contract: `docs/api/job-details.md` §2 and §3 — **extended by this slice** (the read's second
authorization question, the `customerContactDetails` block, the errors it does not add)
ADR: `docs/decisions/021-technician-customer-read.md` — **new; this slice is its Phase 0**
Database: `api/drizzle/migrations/0013_customer_assigned_permissions.sql` — **new** (one catalogue row and
its default-Technician grant; no schema change)
Offline standard: `docs/architecture/offline-first-architecture.md` §9, §12 adopter 2, §13

## Scope

`ADR-019` D1 let a technician open a Job Details screen, but the projection carried only the Customer's
id and name, and the default Technician role held **no** Customer capability at all — so the Customer row
the screen offered led to a destination the API refuses with `403`. Product ownership decided on
2026-09-18 that a technician must be able, **by default**, to read the Customer of a Job they are assigned
to, view-only, in order to see the phone number, the email address and the notes. That decision is
`BR-092`, and this slice implements it.

| Item | Status |
| --- | --- |
| `BR-092` — the rule: scope, view-only, the fields, the default grant | Written (with its `BR-040` record) |
| `BR-009` — the default Technician set names the new capability | Written (with its `BR-040` record) |
| `customers.view_assigned` in the API catalogue and the Android vocabulary | Implemented (API catalogue; Android needs no constant — see below) |
| Migration `0013`: the catalogue row + the default-Technician grant | Implemented |
| Development seed: the permission template and the Technician role's grant | Implemented |
| `GET /jobs/:id` includes the Customer's own contact details for an admitted caller | Implemented |
| The block is absent — not a refusal — for a caller holding neither customer capability | Implemented |
| Every Job-returning route answers the question the same way (the four action routes included) | Implemented |
| Android: the Job card draws a row per contact detail the API reported | Implemented |
| Android: the office Customer destination is no longer offered where its read would be refused | Implemented |
| Offline: the block travels in the existing Job Details working-set payload | Implemented (no new store) |
| EN/FR copy for the three new labels | Implemented (`Phone`/`Email`/`Notes`, `Téléphone`/`Courriel`/`Notes`) |
| API unit specs (the projection, the catalogue) and e2e (six authorization and projection cases) | Implemented |
| Android JVM tests (the mapping) and Compose device tests (the block and the tap gate) | Written |
| **The Customer's *contacts*** (`customer_contacts`) and the `preferredContactMethod`/`language` pair | **Not implemented — OPEN QUESTION (`BR-092` Notes, `ADR-021` 1–2)** |
| **Tap-to-call / tap-to-email** on the block | **Not implemented — a design-system decision (`ADR-021` 3)** |
| **Any write to a Customer from a technician session** | **Out of scope by `BR-092`**: the read is view-only and no capability for it exists |

**No Android `Permission` constant was added, deliberately.** Whether a session may read the Customer is
the API's answer, and it says so by including the block or not (`BR-001`, `BR-007`); a client-side gate on
top would be a second answer that can disagree with the first, and a constant nothing draws on would be
dead code. The screen's only change is the tap: it is drawn on the existing `canOpenCustomers`, because a
technician following it would reach a `403` (`BR-011`).

## Open questions recorded by this slice

| # | Question | Rule | How it is handled now | Blocks |
| --- | --- | --- | --- | --- |
| 1 | Whether a technician may read the Customer's **contacts** (the additional people, with their own name, email and phone) | `BR-023`, `BR-092` | Not implemented: the block carries the Customer's own fields only | Widening the block, or a contacts section on the field card |
| 2 | Whether `preferredContactMethod` and `language` belong in the field read | `BR-092` | Not implemented: they describe how Servora communicates with the Customer | Any "how to reach them" affordance beyond the raw fields |
| 3 | Tap-to-call and tap-to-email on the block | `BR-038` direction, `BR-042` | Not implemented: the maps row's glyph is the precedent, and the design system owns the decision | Any device dialer/mail affordance |
| 4 | The `customers.*` capability set | `BR-006`, `BR-042` | `customers.view_assigned` joins the existing set; the set now carries an audience split the way the Visit codes do | Re-review of the Customer guards together (`ADR-019` open question 5 covers the Job side) |
| 5 | Reaching the Customer from a Job the technician no longer serves | `BR-069`, `BR-092` | Not decided: the read follows **current** assignment, which stays in place until a manager removes the technician | Any time-bounded or Visit-bounded scope |
| 6 | Notifications, GPS and retention of the Customer's own history | `BR-029`, `BR-038`, `BR-033` | Untouched by this slice | — |

## Manual QA (product owner, on a physical device)

This slice is verified automatically on the API side. The Android half needs a device, and no Gradle or
`adb` command was run on this machine, so the following is the product owner's to run.

1. Sign in as the **Technician** account and open an assigned Job.
   Expected: the Customer row shows the name, and beneath it the **Phone**, **Email** and **Notes** rows
   the office recorded — with no tap affordance on the row and no way to edit anything.
2. Open the same Job as the **Manager** account.
   Expected: the same details, and the Customer row still opens the office customer screen.
3. Turn the device's connectivity off and reopen the Job from the technician home.
   Expected: the contact details are still there (`BR-013`, `BR-092`).
4. Sign in as technician B and open Job A, where B is on none of A's Visits.
   Expected: `Job not found` — never a customer, and never a "forbidden" that would confirm A exists.
5. Switch the device to French and repeat 1.
   Expected: `Téléphone`, `Courriel`, `Notes`.

## Phase log

| # | Date | What was done | Files | Verification |
| - | ---- | ------------- | ----- | ------------ |
| 0 | 2026-09-18 | **`BR-092` (new) and the `BR-009` amendment**, each with its `BR-040` change record, and `ADR-021` with its six decisions and five open questions. | `.clinerules/Business Rules.md`, `docs/decisions/021-technician-customer-read.md` | Documentation only — nothing to execute. The decision is the product owner's of 2026-09-18 (`Project.md` §31) |
| 1 | 2026-09-18 | The capability: `customers.view_assigned` in the API catalogue, migration `0013` (its bilingual catalogue row plus the default-Technician grant), the journal entry and the development seed. | `api/src/auth/permissions.ts`, `api/drizzle/migrations/0013_customer_assigned_permissions.sql`, `api/drizzle/migrations/meta/_journal.json`, `api/src/database/run-development-seed.ts` | **Tier A:** `npm run typecheck` (no new error), `npx vitest run src/auth/permissions.spec.ts` PASS |
| 2 | 2026-09-18 | The read: the second authorization question at the boundary (`jobDetailsReadOptionsOf`), asked by the read **and** the four action routes; `JobDetailsReadOptions`, `JobCustomerContact` and the projection's conditional block; the service resolves the Customer's own columns from the row it already joins. | `api/src/jobs/job-details.dto.ts`, `jobs.controller.ts`, `jobs.service.ts`, `job-details.dto.spec.ts` | **Tier A:** `npm run typecheck` (no new error), `npx vitest run src/jobs/job-details.dto.spec.ts` PASS (the block asked for, not asked for, defaulted away, and its exact key set) |
| 3 | 2026-09-18 | The e2e: the field caller with the capability is given the block; without it the Job is returned with `null`; a partly recorded customer keeps its absent fields as `null`; the office caller is unchanged; a Job the caller is not assigned to discloses no customer; the customer capability alone admits nobody to a Job. **Escalated to Tier B** because the change crosses a contract and an authorization boundary. | `api/test/job-details.e2e-spec.ts` | **Tier B, API complete: PASS.** `npm test` 480 tests / 54 files; `npm run test:e2e` **338 tests / 21 files**, 0 failures (the migration is applied by the e2e global setup); `npm run lint` 0 warnings, 0 errors (203 files); `npm run build` PASS; `npm run typecheck` clean apart from the four pre-existing errors below. The changed suites on their own: `job-details` 24 tests (6 new), and the Job suites together 132 |
| 4 | 2026-09-18 | Android: the DTO and domain block, the repository mapping, the Job card's rows and the tag set, the gate on the office Customer tap, and EN/FR copy. | `android/app/src/main/java/` (`data/jobs/JobDetailsDtos.kt`, `JobDetailsRepository.kt`, `domain/model/JobDetails.kt`, `ui/jobs/JobDetailsScreen.kt`, `ui/navigation/ServoraNavHost.kt`), `res/values*/strings.xml` | **NOT RUN** — no Gradle command on this machine (below) |
| 5 | 2026-09-18 | The Android tests: the mapping's two cases (the block as reported, and a field the office never recorded), and the screen's three (the field session's in-place details with no tap, only the details the customer has, and nothing when the API included none). | `android/app/src/test/java/…/data/jobs/JobDetailsRepositoryTest.kt`, `android/app/src/androidTest/java/…/ui/jobs/JobDetailsScreenTest.kt` | **NOT RUN** (device tests are the product owner's; `qa.md` §7.3) |
| 6 | 2026-09-18 | The documentation: the API contract, the domain model's §8.3, the ADR, this tracker and the README pointer. | `docs/api/job-details.md`, `docs/domain/job-visit-domain-model.md`, `docs/decisions/021-technician-customer-read.md`, `README.md`, this tracker | Reviewed against `BR-001`, `BR-006`, `BR-007`, `BR-009`, `BR-011`, `BR-023`, `BR-041`, `BR-042`, `BR-068`, `BR-092` |

### The pre-existing typecheck errors, stated rather than claimed clean

`npm run typecheck` reports four errors this slice did not introduce and does not touch:
`test/technician-home.e2e-spec.ts` (two `scheduledEnd` possibly-null, recorded as pre-existing on this
branch by tracker 037 Phase 5a) and `test/visit-field-lifecycle.e2e-spec.ts` (two `roleCode` widenings in
the uncommitted schedule work's `crew` fixtures). All four are outside the files this slice changed, and
none of them is silenced, weakened or worked around.

**Hygiene:** no Gradle or Android command was run, so no build daemon was started and none needed stopping
(`dev.md` §18, `qa.md` §7.4). The foundation stack (`make up`) and its volumes were left exactly as they
were; the e2e run applied migration `0013` to the local development database, which is what the suite's
global setup exists to do. No scratch file was left behind.
