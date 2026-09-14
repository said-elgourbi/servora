# Tracker 019 — Job Details hierarchy and contextual actions (Android)

**Status: COMPLETE for the Android implementation; physical-device QA is the product owner's**

**Superseded in part by `docs/tracker/020-android-job-details-status-control.md`:** the Job's status
action is now the status chip itself rather than a separate "Change status" action beside it, so the
QA steps below that ask for "Change status" describe the state as it was before tracker 020.

Date: 2026-09-14
Predecessor: `docs/tracker/018-android-job-actions.md`
Business rules: `BR-001`, `BR-006`, `BR-007`, `BR-010`, `BR-012`, `BR-028`, `BR-041`, `BR-042`,
`BR-047`, `BR-051`, `BR-052`, `BR-058`, `BR-059`, `BR-066`, `BR-068`, `BR-069`, `BR-073`, `BR-074`,
`BR-080`, `BR-081`
Domain model: `docs/domain/job-visit-domain-model.md` §5 – §7, §12
API contract: `docs/api/job-details.md`, `docs/api/job-actions.md` — **unchanged by this slice**
Design reference: `Figma/src/screens/JobDetails.tsx` (Manager view), `servora-job-details-spec.md`
Design system: `docs/design/android-design-system.md`

## Scope

The Manager Job Details screen's information hierarchy, and where each action lives. The slice is
presentation plus one behaviour change (the success report); it adds no API, no table, no capability
and no business rule, and it touches no other screen.

| Item                                                                                | Status      |
| ----------------------------------------------------------------------------------- | ----------- |
| Header is the Job's identity: status, Job number, title, description                 | Implemented |
| The Job number is stated once and never repeated inside the title (`BR-052`)          | Implemented |
| The global action row (Reassign / Reschedule / Status) is removed                    | Implemented |
| Job status action moves beside the Job's status, in the identity section             | Implemented |
| Reschedule moves onto the card of the Visit it edits (`BR-073`)                      | Implemented |
| **Manage technicians** replaces **Reassign** and moves with the crew (`BR-068`)      | Implemented |
| Every assigned technician is still listed, Lead first, Lead badged (`BR-068`)        | Implemented |
| No technician relationship on the Job is introduced                                  | Implemented |
| Action outcome is a transient Material Snackbar, not a standing banner               | Implemented |
| Page order: Job identity → Visit (schedule, status, address, customer) → Technicians | Implemented |
| EN/FR copy for the new and changed strings                                           | Implemented |
| **Notes section**                                                                    | **Not implemented — no API source, see below** |
| **Job Activity section** (`BR-080`) and **+ Add update**                              | **Not implemented — no read and no write, see below** |
| **A Visit "Change status" action**                                                   | **Not implemented — no API route, see below** |
| Offline behaviour                                                                    | Unchanged — the actions remain online-only |

## Decisions taken here

1. **An action is drawn with the record it affects, not in one global row.** The row presented
   Reassign, Reschedule and Status as three equally weighted tiles for the whole screen, so the action
   the user wanted sat as far from the data it changes as an action irrelevant to it. Each action now
   sits in the section whose data it changes (`BR-066`).
2. **The Job's status action belongs to the Job's status, not to the Visit's data.** Job status and
   Visit status are two state machines (`BR-059`), and the only status action the API exposes is the
   Job's (`PATCH /jobs/:id/status`). It is drawn beside the Job's status chip, where the state it
   changes is presented, and its menu is still built from the Job's `allowedStatusTransitions`
   (`BR-058`, `BR-041`).
3. **"Reassign" is replaced by "Manage technicians".** A Visit's crew is stated as a whole
   (`PUT /jobs/:id/visits/:visitId/technicians`), and one action that states the whole crew is what
   adds a technician, removes one and names the Lead (`BR-068`, `BR-069`). "Reassign" described only
   the last of those, and the client must not suggest a technician is attached to the Job.
4. **A Job with no Visit is offered neither Visit action.** A reschedule edits a Visit and a crew is
   stated on one (`BR-051`, `BR-068`), so an action with nothing to act on is not drawn.
5. **What an action did is reported transiently; a refusal is not.** The standing banner repeated what
   the Job the API answered with already shows, so a completed action is now a
   `SnackbarDuration.Short` Snackbar whose state is released as soon as it has been shown (`BR-001`).
   A refusal is the opposite case — nothing on screen reflects a change that did not happen — so it
   waits for the user (`SnackbarDuration.Indefinite` with **Dismiss**), which also keeps a long refusal
   readable instead of truncating it into a two-line transient message.
6. **The Visit card is titled.** `BR-047` separates the four concepts and the card holds the Visit's
   schedule, status, address and customer, so a section label names it: the page reads as the Job's
   identity, the Visit, then the crew.
7. **No new model was invented for Notes or Activity.** See the next section: the API has neither, so
   the screen ends at the technicians rather than duplicating `visit_notes` behind a parallel model.

## Missing backend support this slice reports instead of inventing

The slice was asked for a **Notes** section and a **Job Activity** section below it
(`servora-job-details-spec.md` §1 – §4; `BR-027`, `BR-080`). Neither has a backend behind it, and
`BR-042` forbids implementing the behaviour anyway. What exists today:

| The screen needs                           | What exists now                                                                                                                                                                                               | What is missing |
| ------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------- |
| A Notes section on Job Details             | `visit_notes` exists (migration `0003`, domain model §12.2), is append-only, and the seed writes notes on every Visit. There is **no** `GET`/`POST` for notes, and `GET /jobs/:id` does not return them.       | A notes read (and, for an "add note" action, a write) plus the capability that authorizes each. A Job-level notes field is a separate open question (`BR-053`). |
| A Job Activity timeline (`BR-080`)         | The authoritative history is real: `job_status_history`, `visit_status_history`, `visit_schedule_history`, `visit_technician_history`, `visit_outcome_history` and `visit_notes`. No activity read is exposed. | A projected read over that history. Its **event vocabulary** crosses application boundaries and is an open question (`BR-080`, `BR-041`, `docs/api/job-details.md` §5.2), so no vocabulary was invented here. |
| A **+ Add update** action on the timeline  | Nothing. A user update would be a Visit note or a Job note; both are undecided (see above).                                                                                                                   | The write route, its target (Visit or Job), and the capability. |
| A Visit **Change status** contextual action | `PATCH /jobs/:id/status` moves the **Job**. `docs/api/job-actions.md` §8 states explicitly that the office actions expose no Visit status transition: advancing a Visit's field status is the technician's field lifecycle (`BR-074`, `BR-077`). | A Visit status route and the permitted set. `BR-075` confirms only the `EN_ROUTE → SCHEDULED` correction and leaves the complete set and its permission open, so the set was not invented. |

The Visit card therefore carries the contextual action the API can honour (**Reschedule**), and its
action row is where a Visit status action joins it once the route exists.

## Verification

```text
Android
- JVM unit tests: PASS — 275 tests, 0 failures
- Lint (lintDebug): PASS
- Debug build (assembleDebug): PASS
- Device-test sources (assembleDebugAndroidTest): PASS, because Compose tests need a device
- Physical device: AWAITING PRODUCT OWNER

API
- No API, schema or capability change in this slice; nothing was re-run for it.
```

The Compose test of this screen is a device test. It now covers the capability gate for all three
actions, each action's placement, a Job with no Visit being offered no Visit action, the Job number
being stated once, and the two reports (a refusal that waits for the user, a completed action that is
transient). It compiles in the agent's environment and runs on the product owner's device.

## Manual QA (product owner, physical device)

```text
Android QA — Job Details hierarchy

Sign in as the Manager development account (`make seed`, API running).

1. Open a Job from Home.
   Expected: the screen opens with the status chip and, immediately beside it, "Change status". The
   line below reads "Job #1042" and the Job's title under it, then its description. There is no
   Reassign / Reschedule / Status button row anywhere on the screen.

2. Read the section under the header (labelled "Visit").
   Expected: Scheduled with the date, time and the visit's status chip, then Address, then Customer.

3. Tap Reschedule at the foot of the Visit card and confirm a new date and time.
   Expected: the schedule row shows the new date and time, and "Visit rescheduled successfully"
   appears briefly at the bottom and then disappears on its own. Nothing stays in the layout.

4. Open a Job whose Visit is En route.
   Expected: Reschedule is still shown but dimmed, and it does not open the dialog.

5. Read the Technicians section.
   Expected: every assigned technician is listed with their initials avatar and name, exactly one
   carries the "Lead" badge, and "Manage technicians" sits at the foot of the card.

6. Tap Manage technicians on a Job with a crew. Untick the Lead, tick another technician, tap
   "Make lead" on them and save.
   Expected: the crew updates with the new Lead first, and "Visit crew updated successfully" appears
   briefly. The Job itself shows no technician field.

7. Open a Job that has no Visit.
   Expected: the Visit card says "Not scheduled" with no Reschedule action, and the Technicians
   section shows "Unassigned" with no Manage technicians action.

8. Tap Change status and choose a transition the API permits.
   Expected: the pill changes and "Job status updated successfully" appears briefly.

9. Trigger an action the API refuses.
   Expected: the refusal message appears at the bottom and stays there with a "Dismiss" button until
   it is tapped, and the Job keeps the values it had.

10. Switch the app language to French and repeat steps 1 to 5.
    Expected: "Visite", "Replanifier", "Gérer les techniciens", "Changer le statut" and the French
    report messages.

11. Switch the device to dark mode.
    Expected: the chips, the cards, the dividers and both report styles (tinted success, error-tinted
    refusal) follow the dark scheme.
```

Expected limitations
- No Notes section and no Job Activity timeline: neither has an API (see above).
- A manager cannot change a **Visit's** status from this screen: the API exposes no such route.
- The actions remain online-only, unchanged from tracker 018.

## Open questions recorded by this slice

| # | Question | Rule | How it is handled now | Blocks |
| - | -------- | ---- | --------------------- | ------ |
| 1 | Job Activity's event vocabulary and its read route | `BR-080`, `BR-041` | No timeline is drawn and no vocabulary is invented | The Job Activity section on both clients |
| 2 | Where a user update lives — a Visit note or a Job note — and who may add one | `BR-027`, `BR-053` | `visit_notes` is left unused by the screen rather than duplicated, and "Add update" is not drawn | The **+ Add update** action |
| 3 | Do managers get a Visit status action, and which transitions | `BR-074`, `BR-075`, `BR-066` | No Visit status route exists, so only Reschedule is drawn on the Visit card | The Visit status contextual action |
| 4 | The `jobs.*` capability set | `BR-006`, `BR-008` | Unchanged from 017/018: the read reuses `customers.view` and the actions reuse `JOB_UPDATE` | Per-action authorization |
| 5 | Offline behaviour of these actions | `BR-031`, `BR-032`, `BR-086` | Unchanged from 018: online-only, because the screen holds no offline working set yet | Field work with no connectivity |
| 6 | Job deletion behaviour | `BR-021` | Unchanged from 017: not implemented, no delete capability implied | Deleting a Job and its history |


