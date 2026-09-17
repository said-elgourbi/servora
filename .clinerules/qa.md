# qa.md — QA & Verification Policy

> QA is a completion gate, not an afterthought.
>
> An agent must provide evidence that its work passes the applicable automated checks before claiming a task is complete.
>
> Verification is **scoped to the change** rather than repeated repository-wide by reflex: a task-sized change runs the compile/build and the tests that cover it, and the **full** lint, suites and builds run when the feature completes (§3.1).
>
> **Android physical-device acceptance is performed by the product owner.**
>
> The agent is responsible for automated Android verification, backend/API testing, Angular testing, builds, linting, and preparing the Android build and manual QA instructions.

---

# 1. QA Responsibilities

Servora has two QA responsibilities.

| Area                            | Responsible   |
| ------------------------------- | ------------- |
| API / Backend                   | Agent         |
| PostgreSQL / API integration    | Agent         |
| Angular                         | Agent         |
| Angular browser E2E             | Agent         |
| Android unit tests              | Agent         |
| Android build/lint              | Agent         |
| Android automated UI tests      | Agent         |
| Android physical-device testing | Product owner |
| Final Android UX acceptance     | Product owner |

The agent must never claim that an Android feature is fully accepted based only on automated tests.

For Android work, the agent's responsibility ends with:

1. Automated verification passing.
2. Debug build successfully generated.
3. Build installed/testable where practical.
4. A concise manual QA runbook provided to the product owner.
5. Any known limitations clearly reported.

The product owner performs the final real-device verification.

---

# 2. Test Types

| Test type          | Application | Tooling                         | Responsibility | Baseline                                    |
| ------------------ | ----------- | ------------------------------- | -------------- | ------------------------------------------- |
| Unit               | API         | Vitest                          | Agent          | Required for behavior                       |
| Integration / E2E  | API         | Vitest + Supertest + PostgreSQL | Agent          | Required for DB/HTTP behavior               |
| Unit / Component   | Angular     | Vitest                          | Agent          | Required for behavior                       |
| Browser E2E        | Angular     | Playwright                      | Agent          | Required for important user journeys        |
| Unit               | Android     | JUnit + coroutine test tooling  | Agent          | Required for business/presentation logic    |
| UI automation      | Android     | Compose testing                 | Agent          | Required for critical flows where practical |
| Lint               | Android     | Gradle lint                     | Agent          | Required when Android is changed            |
| Physical-device QA | Android     | Product owner's phone           | Product owner  | Required before Android feature acceptance  |

---

# 3. Agent Verification Commands

The repository's actual Makefile/scripts are authoritative for command names.

The expected verification categories are:

```bash
make lint
make test
make test-api-e2e
make e2e-web
make build

make android-test
make android-lint
make android-build
```

Hygiene commands, run after the last Android command of a task:

```bash
make android-stop        # stop the Gradle/Kotlin build daemons Android verification leaves running
make tidy                # the same, plus a report of anything else still running
```

The agent must use the repository's current commands rather than assuming these commands still exist after project restructuring.

If a required command does not exist, the agent must report that rather than silently substituting an unrelated command.

**One category of verification is not the agent's to run.** Commands that talk to an Android device or emulator — `adb` itself, and Gradle tasks that drive a device through it (for example `connectedDebugAndroidTest`) — are the product owner's, because the product owner is the Android QA tester. The agent compiles device-test sources (`assembleDebugAndroidTest`) so they are known to build, and reports any test that would need a device as `NOT RUN — device QA is the product owner's`. See §7.3.

---

## 3.1 Verification scope — targeted per task, full when the feature completes

Verification is not free, and repeating the whole of it after every small change is not what makes a
change correct. A full Android lint or a full Android test run starts the Gradle/Kotlin build daemons and
costs minutes of machine time on every invocation (`dev.md` §18); a full API suite plus a full e2e suite is
a PostgreSQL round trip over every test file in the repository. Paying that cost for a one-line correction,
a review fix or a follow-up inside a feature that is still open buys almost nothing and consumes the
machine the product owner is working on.

Verification is therefore **tiered by the scope of the change**. The tier changes *what* is run — never
*whether* verification is run.

| Tier | When it applies | What is run |
| ---- | --------------- | ----------- |
| **A — Targeted** (default) | An ordinary task, a correction, a review fix, a small or medium change inside a feature that is not yet complete | Only the **affected application** and only the **affected target**: the compile/build of the application that changed, its type check, and **the tests that cover the changed code** — named unit spec files, named e2e spec files, named test classes/packages. No full lint and no full suite at this tier. |
| **B — Full, per affected application** | The vertical slice/feature is **complete** — "a big feature is done" — and before the feature is reported Done or handed to the product owner | The full **lint** of every affected application (`make api-lint`, `make android-lint`), the full type check, the full unit suite (`make api-test`, `make android-test`), the full API e2e suite (`make api-test-e2e`), the full build of each affected application (`make api-build`, `make android-build`) and the device-test source compile (`assembleDebugAndroidTest`). |
| **C — Whole repository** | A milestone or a release, or a change whose blast radius cannot be bounded | Lint, type check, tests, e2e and builds of **every** application. |

Rules that keep the tiering honest:

* **Tier A is mandatory and is not an excuse to skip verification.** Targeted means a smaller scope, never
  no verification: a change that alters behavior still runs the tests that cover it (§14), and a change
  with no runnable test states why instead of reporting nothing.
* **Lint is deferred to Tier B, not dropped.** The full lint of every affected application must pass when
  the feature completes and before the feature is reported Done (§15). Findings it surfaces in code the
  feature touched are fixed as part of the feature.
* **Escalate immediately** from Tier A to Tier B or C when the change touches shared infrastructure (§11),
  crosses an application boundary or a shared contract (§12), changes authentication or authorization,
  changes the database schema or a migration, changes offline/synchronization behavior, or when a targeted
  run fails or its result is inconclusive.
* **A correction inside an open feature stays Tier A.** Re-run the targeted check after each correction and
  pay for the full sweep once, when the feature ends.
* **The tier is reported** together with the exact commands and their results (§16). "Verified" without the
  commands is not a report, and a Tier B claim requires the Tier B commands to have actually run.

The targeted forms in use in this repository:

```bash
# Tier A — API: only the spec(s) that cover the change
cd api && npm run typecheck
cd api && npm run build
cd api && npm test -- src/jobs/mp4-audio.spec.ts
cd api && npm run test:e2e -- test/job-audio-notes.e2e-spec.ts

# Tier A — Android: only the test class/package that covers the change
cd android && ./gradlew compileDebugKotlin
cd android && ./gradlew testDebugUnitTest --tests 'com.servora.android.ui.jobs.*'
cd android && ./gradlew assembleDebugAndroidTest      # device-test sources must still compile (§7.3)

# Tier B — full, per affected application, when the feature completes
make api-lint && make api-test && make api-test-e2e && make api-build
make android-lint && make android-test && make android-build
make android-stop                                     # §7.4 — the daemons this started are the task's
```

When the Angular client exists, its targets are chosen the same way: the affected project's tests and build
at Tier A, its full lint, suite and build at Tier B.

---

# 4. API / Backend Testing

## 4.1 Unit tests

Unit tests are required for meaningful business or application behavior.

Examples include:

* Services
* Use cases
* Controllers where logic exists
* Guards
* Authorization logic
* Validation
* Mappers
* State/business rules
* Error handling

Tests should describe behavior rather than implementation details.

Prefer names such as:

```text
should reject an unauthorized job update
should create a customer when the request is valid
should return the correct result when the job does not exist
```

---

## 4.2 API integration / E2E tests

Integration/E2E tests are required when behavior involves:

* HTTP endpoints
* PostgreSQL
* Authentication
* Authorization
* Database persistence
* Transactions
* API contracts
* Error responses
* Cross-module behavior

Tests should use a real PostgreSQL database where database behavior is being verified.

Mocking PostgreSQL does not replace database integration testing.

---

## 4.3 Authorization testing

Every protected API capability must verify authorization.

At minimum, tests should cover:

* Unauthenticated request → `401`
* Authenticated user without required permission → `403`
* Authorized user → successful operation
* Correct permission grants
* Correct permission denial
* Resource ownership/scope rules when those rules exist

The backend is always the final authorization authority.

A frontend test that hides a button is **not** an authorization test.

---

# 5. Angular Testing

## 5.1 Unit/component tests

Tests are required when an Angular component or service contains meaningful behavior.

Examples:

* Form validation
* User interactions
* Conditional rendering
* Permission-based UI
* Loading states
* Error states
* Data transformations
* State management
* API interactions
* Navigation guards

Purely presentational components do not require artificial tests when they contain no meaningful behavior.

---

## 5.2 Angular E2E

Playwright covers important end-to-end user journeys.

Examples include:

* Login
* Permission-based navigation
* Customer management
* Technician management
* Job creation
* Job editing
* Job assignment
* Job status changes
* Reporting workflows
* Important management workflows

Every new major user journey should add or update appropriate E2E coverage.

---

# 6. Android Automated Testing

The agent is responsible for automated Android verification.

## 6.1 Android unit tests

Unit tests should cover meaningful application logic such as:

* ViewModels
* State management
* Permission decisions
* Validation
* Data mapping
* Repository behavior
* Offline state transitions
* Synchronization logic
* Error handling

For a new ViewModel, tests should normally cover:

* Initial state
* Loading
* Success
* Error
* Permission-dependent behavior
* Relevant offline behavior

---

## 6.2 Android UI automation

Compose/UI tests should cover critical flows where practical.

Examples:

* Login
* Navigation
* Permission-dependent UI
* Job actions
* Customer/technician workflows
* Offline indicators
* Important error states

Automated UI tests complement physical-device testing; they do not replace it.

---

# 7. Android Physical-Device QA

## 7.1 Product owner owns physical-device testing

The product owner performs Android QA on a real phone.

This is the authoritative test for:

* Real device UX
* Touch interaction
* Layout
* Navigation feel
* Performance
* Camera behavior
* Network transitions
* Offline behavior
* Real-world field workflow
* Device-specific behavior

The agent must not claim these have passed without the product owner's verification.

---

## 7.2 Android QA handoff

When Android work is ready for physical-device testing, the agent must provide:

1. The debug build.
2. Installation instructions if required.
3. The API/environment required to exercise the feature.
4. A short list of exact test steps.
5. Expected results.
6. Any known limitations or areas requiring particular attention.

The runbook should focus on the changed behavior rather than asking the product owner to perform a complete regression test every time.

Example:

```text
Android QA — Job Assignment

1. Sign in as Manager.
2. Open Jobs.
3. Open an unassigned job.
4. Select Assign.
5. Select a technician.
6. Confirm the assignment.

Expected:
- Technician is assigned.
- Job details display the assigned technician.
- The assignment remains after leaving and reopening the job.
```

---

## 7.3 The agent never runs `adb` or any device command

**The agent must never run `adb`.** No `adb` subcommand, for any reason: not to list devices, install or launch a build, forward a port, read `logcat`, take a screenshot or a UI dump, or to run instrumented tests.

This also covers anything that drives a device through `adb` on the agent's behalf, including Gradle tasks that require a connected device or emulator (`connectedDebugAndroidTest`, `installDebug`, and the like).

The reason is not only caution: the product owner is the Android QA tester and the phone is theirs, so a device command can install over, restart, interrupt or reconfigure the very build under test.

What the agent does instead:

* Compile the device-test sources (`./gradlew assembleDebugAndroidTest`) so they are known to build.
* Run the device-free Android verification: JVM unit tests (`make android-test`), lint (`make android-lint`) and the debug build (`make android-build`) — at the tier the change requires (§3.1), so a task-sized change compiles and runs the tests that cover it rather than the full sweep.
* Report every test that needs a device as `NOT RUN — device QA is the product owner's`, with the exact command the product owner would run.
* Hand the product owner the build and the manual runbook described in §7.2, including the `adb` steps the product owner may choose to run themselves.

If a task appears to require device interaction, the agent stops at that boundary and asks the product owner rather than working around it.

---

## 7.4 Release the build daemons when Android verification is finished

The Android verification commands start build daemons that outlive the command (`dev.md` §18). A Gradle daemon holds roughly 5 GB resident and a Kotlin daemon roughly 2.4 GB, and both idle for hours, so a series of tasks exhausts the machine even though every command reported success.

* After the last Gradle command of a task (`make android-test`, `make android-lint`, `make android-build`, `./gradlew assembleDebugAndroidTest`, `compileDebugKotlin`, …), run `make android-stop`.
* Check the result with `make tidy` (or `pgrep -af 'GradleDaemon|KotlinCompileDaemon'`). Both daemons are gone when nothing is listed.
* Do not stop a build that is not yours: if a Gradle build started by the product owner is in progress, leave the daemons alone and report that.
* The same expectation applies to any other long-running process a verification starts (a dev server, a file watcher, a browser-automation run).

---

# 8. Offline & Synchronization Testing

Offline behavior is a critical Servora capability.

When a change affects offline-capable behavior, testing must consider the relevant failure modes.

Depending on the feature, this may include:

* No connectivity
* Connectivity restored
* Intermittent connectivity
* API unavailable
* App restart while work is pending
* Device restart while work is pending
* Duplicate synchronization attempts
* Failed synchronization
* Retry behavior
* Permanent failure
* Conflicting changes
* Multiple devices
* File/photo synchronization
* Storage pressure
* Local data migration
* Authentication/session expiration

The exact tests required depend on the feature being changed.

The agent must not mark offline work complete simply because the happy path works while online.

---

# 9. Database Testing

Database changes require verification of:

* Migration correctness
* Schema constraints
* Foreign keys
* Unique constraints
* Indexes where behavior depends on them
* Default values
* Nullable/non-nullable behavior
* Data compatibility
* API persistence and retrieval

A schema change must include a committed migration.

The application must not silently rely on automatic schema changes at runtime unless explicitly approved by the architecture rules.

---

# 10. Error & Boundary Testing

New behavior should include appropriate failure-path testing.

Depending on the feature, consider:

* Invalid input
* Missing required data
* Unauthorized access
* Forbidden access
* Missing resource
* Duplicate data
* Empty result
* API failure
* Database failure
* Network interruption
* Expired authentication
* Invalid state transition
* Concurrent modification
* Pagination boundaries
* Date/time boundaries

Happy-path testing alone is insufficient for business-critical behavior.

---

# 11. Regression Testing

A bug fix must include a regression test when the affected behavior can reasonably be automated.

The regression test should:

1. Fail against the buggy behavior.
2. Pass after the fix.
3. Protect against recurrence.

Changes to shared infrastructure require broader regression testing.

Examples:

* Authentication
* Authorization
* API client
* Database layer
* Shared types/contracts
* Routing
* Error handling
* Synchronization
* Core domain services

---

# 12. Contract Testing

Changes to shared API contracts or shared domain types require verification across affected applications.

Examples:

* DTOs
* Request/response structures
* Error envelopes
* Pagination
* Authentication responses
* Permission representations
* Job/customer/technician models

The API remains authoritative.

Angular and Android must consume the shared contract rather than creating incompatible local interpretations.

---

# 13. Security Verification

For every new protected capability, verify:

* Authentication is required where appropriate.
* Authorization is enforced by the backend.
* Unauthorized users receive the correct response.
* Input is validated.
* Sensitive information is not unnecessarily exposed.
* Secrets are not committed.
* Development-only authentication shortcuts are not available in production.
* Client-side permission checks cannot bypass backend authorization.

Security must be tested at the API boundary, not only through the UI.

---

# 14. When Tests Are Mandatory

Tests are mandatory when a change:

1. Adds or changes business behavior.
2. Adds or changes an API endpoint.
3. Changes an API contract.
4. Changes authentication or authorization.
5. Changes database schema or migrations.
6. Changes state management.
7. Changes offline/synchronization behavior.
8. Fixes a bug.
9. Changes a critical user workflow.

A behavior-preserving refactor does not necessarily require new tests, but all existing relevant tests must continue to pass.

---

# 15. Definition of Done

A task may be reported as **Done** only when all applicable items have been verified.

The checklist is applied **at the tier the change warrants** (§3.1): a task-sized change satisfies it with
targeted verification of the affected application, while the full-lint, full-suite and device-source items
(*Quality* and *Android* below) are satisfied when the feature completes.

### Code

* [ ] Implementation follows project development rules.
* [ ] No unrelated changes.
* [ ] No dead code introduced.
* [ ] No unnecessary placeholders or TODOs introduced.
* [ ] Business behavior matches `Business Rules.md`.
* [ ] No OPEN QUESTION was implemented as an assumption.

### Tests

* [ ] Required unit tests exist.
* [ ] Required API integration/E2E tests exist.
* [ ] Required Angular tests exist.
* [ ] Required Android automated tests exist when Android is changed.
* [ ] Regression coverage exists for bug fixes.
* [ ] Relevant existing tests pass.
* [ ] The applicable verification tier was run — targeted per task, full when the feature completes (§3.1) — and its exact commands are reported (§16).

### Quality

* [ ] Lint passes at the tier the change requires: the full lint of every affected application when the feature completes (§3.1); a task-sized change reports its targeted compile/build instead.
* [ ] Formatting passes.
* [ ] Type checking passes where applicable.
* [ ] Builds pass for affected applications.

### API / Database

* [ ] API authorization is enforced server-side.
* [ ] Input validation is present.
* [ ] Database migrations are committed when required.
* [ ] Database behavior has been tested where applicable.
* [ ] API contracts remain consistent.

### Android

* [ ] Android automated tests pass at the tier the change requires (§3.1).
* [ ] Android lint passes at the tier the change requires (§3.1) — the full `lintDebug` when the feature completes.
* [ ] Android build succeeds for the affected application.
* [ ] The agent ran no `adb` and no device/emulator command (§7.3), and compiled any device-test sources instead.
* [ ] Physical-device QA is either completed by the product owner or explicitly marked **Awaiting Physical QA**.
* [ ] The agent does not claim physical-device acceptance.

### Documentation

* [ ] Documentation is updated when behavior or workflows change.
* [ ] Relevant API/product/architecture documentation is updated.
* [ ] New environment/configuration requirements are documented.

### Security

* [ ] No secrets or credentials introduced.
* [ ] No authorization bypass introduced.
* [ ] New input surfaces are validated.
* [ ] Authentication/authorization behavior is tested where applicable.

### Hygiene

* [ ] The build daemons the task started were stopped after the last Android command (§7.4, `make android-stop`).
* [ ] `make tidy` shows nothing left running by the agent.
* [ ] No scratch files, logs, screenshots or temporary scripts remain; `git status` shows only the intended changes.
* [ ] Any process or file the task could not clean up is reported in the completion summary (§16).

### Self-review

* [ ] Agent reviewed its own diff.
* [ ] Agent verified the applicable QA commands.
* [ ] Agent reported actual results rather than assumed results.
* [ ] Any checks that could not be executed are explicitly identified.
* [ ] Any remaining manual QA is clearly identified.

---

# 16. Reporting QA Results

When reporting completion, the agent should provide a concise verification summary.

Example:

```text
QA

Scope (§3.1)
- Tier A — targeted: API only (the changed service, its unit spec and its e2e spec).
- Full lint and full suites: Tier B, at feature completion.

Backend
- Unit: PASS
- API E2E: PASS
- PostgreSQL integration: PASS

Angular
- Unit/component: PASS
- Playwright: PASS
- Build: PASS

Android
- Unit: PASS
- Lint: PASS
- Build: PASS
- Physical device: AWAITING PRODUCT OWNER

Left running
- Gradle/Kotlin build daemons: stopped (make android-stop)
- Foundation stack (make up): untouched
- Editor tabs opened by the task: none

Manual QA required
- Verify job assignment on physical Android device.
- Verify offline assignment display after reconnect.
```

The agent must never report `PASS` for a test it did not actually execute.

`Scope` records the tier the change was verified at (§3.1), so a reader can tell a targeted run from a full
one without reading the commands.

If the environment prevents a test from running, report:

```text
NOT RUN — reason
Command to execute:
<command>
```

---

# 17. Final QA Principle

> **Automated verification proves that the implementation behaves correctly under the tested conditions.**
>
> **Physical-device QA proves that the Android product works correctly in the real user environment.**
>
> **Neither replaces the other.**
>
> **The agent owns automated verification. The product owner owns Android physical-device acceptance.**
>
> **Verification is scoped, not skipped: targeted for the task, full when the feature completes (§3.1).**
