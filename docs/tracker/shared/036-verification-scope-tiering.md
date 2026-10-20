# Tracker 036 — Verification scope (targeted per task, full at feature completion)

Housekeeping recorded as its own milestone because it changes how a task is verified and finished, not what
the product does. No business rule, API contract, database schema, Android behavior or localization changes.

- Status: **COMPLETE**
- Date: 2026-09-16
- Rules updated: `.clinerules/qa.md` §3.1 (new), banner, §7.3, §15, §16, §17; `.clinerules/dev.md` §13;
  `.clinerules/Project.md` §24, §25, §32
- Development doc: `docs/development/setup.md` §5 ("How much verification to run")
- ADR: none — no architectural decision is involved. The reasoning is recorded here.

## Problem

The verification policy said *what* to verify, but not **how much of it** a given change owes. In practice
every task ran the whole set — full API lint, the full API unit and e2e suites, full Android lint, the full
Android suite and both Android builds — even for a one-line correction inside a feature that was still open.
Tracker 026's verification block is the pattern: a targeted `testDebugUnitTest --tests …` run, then the full
suite, `compileDebugAndroidTestKotlin`, `lintDebug`, `assembleDebug` and `assembleDebugAndroidTest`, for the
same slice.

The cost is measurable and was already recorded elsewhere in this repository:

| Cost                                                                                            | Source                            |
| ----------------------------------------------------------------------------------------------- | --------------------------------- |
| A Gradle daemon holds 4.9 GB and a Kotlin daemon 2.4 GB, both idling for hours after the command | tracker 030 (measured 2026-09-16) |
| The full API e2e suite is 18 files / 267 tests, each a PostgreSQL round trip                     | tracker 035                       |
| The full Android suite is 364 tests / 36 classes                                                 | tracker 026                       |

What the repetition bought was close to nothing: the tests that can fail because of a change are the tests
that cover the change, and those are already run. What it cost was machine time — on the same machine the
product owner works on — and lint/build output nobody re-read.

## Decision

Verification is **tiered by the scope of the change** (`.clinerules/qa.md` §3.1). The tier changes *what* is
run, never *whether* verification is run:

| Tier                                   | When                                                                              | What                                                                                                        |
| -------------------------------------- | --------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| **A — Targeted** (default)             | An ordinary task, a correction, a review fix, a small/medium change inside an open feature | The affected application only: its compile/build, its type check, and the tests that cover the changed code |
| **B — Full, per affected application** | The feature is complete, before it is reported Done                               | Full lint, full type check, full unit suite, full API e2e suite, full builds, device-test source compile     |
| **C — Whole repository**               | A milestone or release, or an unbounded change                                    | Lint, type check, tests, e2e and builds of every application                                                |

Guardrails that keep the tiering honest:

- Tier A is **mandatory**; targeted means a smaller scope, never no verification (`qa.md` §14 still decides
  when tests are mandatory).
- Lint is **deferred to Tier B, not dropped**: the full lint of every affected application must pass before a
  feature is reported Done, and findings in code the feature touched are fixed inside the feature.
- Escalate to Tier B/C immediately for shared infrastructure (`qa.md` §11), cross-application contracts
  (§12), authentication/authorization, database schema or migrations, synchronization — or when a targeted run
  fails or is inconclusive.
- A correction inside an open feature stays Tier A; the full sweep is paid once, at the feature's end.
- The tier, the exact commands and their results are reported (`qa.md` §16).

## Change

| Item                               | Where                                                     | What                                                                                                       |
| ---------------------------------- | --------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| The tiering itself, with its table | `.clinerules/qa.md` §3.1 (new)                            | Tier A/B/C, the escalation triggers, "lint deferred not dropped", and the targeted command forms in use     |
| The banner states the principle    | `.clinerules/qa.md` intro                                 | Verification is scoped to the change, not repeated repository-wide by reflex                               |
| Definition of Done is tier-aware   | `.clinerules/qa.md` §15                                   | The checklist is applied at the tier the change warrants; the lint item names the tier it is satisfied at  |
| Android device-free verification   | `.clinerules/qa.md` §7.3                                  | The JVM tests, lint and debug build are run at the tier the change requires, not as a fixed full sweep      |
| The QA report records the scope    | `.clinerules/qa.md` §16                                   | A `Scope` block in the summary, so a targeted run is distinguishable from a full one                       |
| Final QA principle                 | `.clinerules/qa.md` §17                                   | "Verification is scoped, not skipped: targeted for the task, full when the feature completes"              |
| Testing during development         | `.clinerules/dev.md` §13                                  | The scope rule, cross-referenced to `qa.md` §3.1                                                           |
| Coding standard and DoD            | `.clinerules/Project.md` §24, §25, §32                    | "Linting must pass" and "tests exist and pass" now name the scope they are judged at                       |
| Developer-facing note              | `docs/development/setup.md` §5                            | "How much verification to run", with the targeted commands                                                 |

## Verification (2026-09-16)

This change is itself a **Tier A** change: it touches rules and documentation, no application source. Its
verification is therefore a check of the commands it documents, not a full sweep — which is the policy's own
subject.

```text
cd api && npm test -- src/jobs/mp4-audio.spec.ts                      PASS  1 file / 7 tests, 1.17 s
cd api && npm run typecheck                                           PASS
cd api && npm run test:e2e -- test/job-audio-notes.e2e-spec.ts        PASS  1 file / 21 tests, 7.81 s
                                                                      (against the running foundation stack)
cd android && ./gradlew --dry-run compileDebugKotlin assembleDebugAndroidTest
                                                                      PASS  BUILD SUCCESSFUL in 14 s
                                                                            :app:compileDebugKotlin SKIPPED
                                                                            :app:assembleDebugAndroidTest SKIPPED
cd android && ./gradlew lintDebug (full Android lint)                 NOT RUN — deliberately deferred (Tier B)
```

- The two API forms are the new `npm test -- <spec>` / `npm run test:e2e -- <spec>` targeted invocations; the
  e2e one ran a single file of the 18-file / 267-test suite (tracker 035) because that is the whole point.
- The Android dry run proves the two documented task names resolve to the `:app` module. A dry run resolves
  the task graph and does not compile, and that is all that is claimed for it.
- The targeted Android **test** form (`testDebugUnitTest --tests '<package>.*'`) is not new and is not
  re-proved here: tracker 026 ran it for real (184 tests / 15 classes) beside `compileDebugAndroidTestKotlin`,
  `lintDebug`, `assembleDebug` and `assembleDebugAndroidTest`.
- Full lint was **not run** for this task. Nothing in this change can be reported as linted; that is the
  policy working as written, and it is stated rather than implied.
- No `adb` and no device/emulator command was run (`qa.md` §7.3).
- Hygiene (`dev.md` §18, `qa.md` §7.4): `make android-stop` stopped the single daemon the dry run started
  (`1 Daemon stopped`); `make tidy` then reported `Java build daemons: none` and `Node/API processes: none`;
  the two temporary `/tmp` logs were deleted; `git status` lists only the files this change touched. The
  product owner's foundation stack was left running, untouched.

## Out of scope / next

- Nothing here weakens `qa.md` §14 (when tests are mandatory), §11 (regression) or §12 (contract testing):
  those keep their full scope, and the escalation triggers carry them into the higher tier.
- The Angular wording is deliberately generic because the Angular client does not exist yet (the Makefile says
  so); its concrete targets are named when the workspace lands.
- The Android lint/test/build cost is cited from tracker 030's measurements rather than re-measured here —
  running the full lint to measure the thing being deferred would be its own contradiction.
