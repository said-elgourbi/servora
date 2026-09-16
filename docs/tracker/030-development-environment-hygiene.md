# Tracker 030 — Development Environment Hygiene (build daemons)

Housekeeping recorded as its own milestone because it changes how a task is finished, not what the
product does. No business rule, API contract, database schema, Android behavior or localization
changes.

- Status: **COMPLETE**
- Date: 2026-09-16
- Rules updated: `.clinerules/dev.md` §17/§18 (new), `.clinerules/qa.md` §3/§7.4 (new)/§15/§16,
  `.clinerules/Project.md` §28/§32
- Development doc: `docs/development/setup.md` §5 and §8 (new)
- ADR: none — no architectural decision is involved. The reasoning is recorded here and in
  `docs/development/setup.md` §8.

## Problem

Android verification left the Gradle and Kotlin build daemons running after every task. Both are
built to outlive the command that starts them, so several tasks in a row accumulated idle JVMs until
the working machine stopped being usable.

Measured on the product owner's machine, 2026-09-16, after ordinary Android build/verification work:

| Process                               | PID   | Resident memory | Idle timeout (default) |
| ------------------------------------- | ----- | --------------- | ---------------------- |
| Gradle daemon (`GradleDaemon`)        | 21106 | 4.9 GB          | 3 h                    |
| Kotlin daemon (`KotlinCompileDaemon`) | 21397 | 2.4 GB          | 2 h                    |

`free -m` at that moment: 15.8 GB total, 11.2 GB used, **3.1 GB available**.

## Change

| Item | Where | What |
| ---- | ----- | ---- |
| `make android-stop` | `Makefile` | `cd android && ./gradlew --stop` — stops the Gradle daemon, and the Kotlin daemon that belongs to it exits with it |
| `make tidy` | `Makefile` | `android-stop`, then reports remaining Java build daemons, Node/API processes and foundation container states. Report only: it never stops compose, never deletes a volume and never touches a device session |
| `org.gradle.daemon.idletimeout=600000` | `android/gradle.properties` | A daemon nobody stopped releases its heap after 10 idle minutes instead of holding it for 3 hours. One daemon is still reused across the builds of a single working session |
| Hygiene rules | `.clinerules/dev.md` §18 (new) | Processes and workspace: what to stop, what is not the agent's to touch (the product owner's stack, their `adb`/device session, their editor) and what to report |
| Hygiene in QA | `.clinerules/qa.md` §7.4 (new), §15, §16 | The agent stops the daemons after its last Android command, checks with `make tidy`, and reports what is left running in the completion summary |
| Project-level duty | `.clinerules/Project.md` §28, §32 | Stopping what the task started is part of finishing it |

## Verification (2026-09-16)

- **The reported leak, released.** `make android-stop` (`./gradlew --stop`) printed
  `Stopping Daemon(s) / 1 Daemon stopped`. Both daemons were gone from `ps` a few seconds later, and
  `free -m` moved from 11.2 GB used / 3.1 GB available to 3.9 GB used / **10.5 GB available**
  (≈ 7.3 GB released). The Kotlin daemon is not in Gradle's daemon registry; in this toolchain pair
  (Gradle 9.7.1, Kotlin 2.3.21) it exited together with its owning Gradle daemon.
- **`make tidy` end to end.** Stopped a freshly started daemon, then reported
  `Java build daemons: none`, `Node/API processes: none`, `Foundation containers: none running`
  (the stack is down on this machine).
- **Detection works in both directions.** After `./gradlew help`, the check listed the real daemon
  (PID 62385) instead of a false positive. The patterns are bracketed (`Gradle[D]aemon`) so the
  recipe's own shell command line cannot match itself — the first draft did, and that defect was
  fixed before this entry was written.
- **The idle timeout is honoured, not just written.** With `org.gradle.daemon.idletimeout` set to
  `8000` for the duration of a scripted experiment, a daemon started by `./gradlew help` was still
  present at t+15 s and gone by t+20 s; the file was restored to `600000` afterwards (confirmed by
  `grep -n idletimeout android/gradle.properties`). The experiment script lived in `/tmp` and was
  deleted; nothing of it remains in the repository.
- `make help` lists both new targets.
- No application source, API contract, migration or Android resource changed, so no API/Android test,
  lint or build verification was applicable to this change; `git diff` contains docs, rules, the
  Makefile and `android/gradle.properties` only.

## Out of scope / next

- Reusing one daemon across a whole session stays the default. `--no-daemon` was rejected: it would
  slow every build instead of fixing the leak.
- The Kotlin daemon's own 2-hour idle timeout and its `-Xmx4g` heap were left alone: tuning compiler
  performance is a performance question, not a hygiene one. If a future Kotlin version stops exiting
  with its Gradle daemon, `make tidy` reports the leftover rather than hiding it.
- Product work continues in `docs/tracker/029-photo-evidence-phases.md` (Phase 6a is next).
