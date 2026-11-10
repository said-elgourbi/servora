# Servora project guidance

## Start every task on a task-matched branch

- At the start of every new user request, read this `AGENTS.md` before inspecting or changing project files. Then check the current Git branch, its upstream, and the working-tree status.
- Never implement a task on a branch whose name does not clearly describe that task. Create a new branch from the latest `origin/develop` with a concise, task-matched name before editing.
- If the current branch is unrelated to the new request, first preserve and finish its existing work: commit it, push it, and merge it into `develop`. Then create the task branch from the updated `develop`. Do not mix work from separate tasks in one branch.
- If the unrelated branch is already clean and merged into `develop`, do not create a duplicate commit or merge; create the new task branch from the current `origin/develop` tip.
- Keep the new task branch checked out while working. Before delivery, report the branch name and whether it was pushed or merged.

## Angular web application

- Treat `web/` as the desktop and browser application for office staff and field technicians. Design for non-IT users: use plain labels, obvious actions, readable summaries, and low-friction workflows.
- Build browser layouts for the web instead of copying mobile screens into Angular. Preserve responsive behavior, but use the space available to show the information office staff need at a glance.
- Never skip responsiveness: every web UI change must be checked and designed for desktop, tablet, and mobile widths. Prevent clipping and awkward wrapping while keeping text readable and controls usable.
- For supervisor home and work-list changes, assume supervisors are busy and should not need to open a job to understand its current state. Put key job, visit, schedule, assigned technician, visit number, and visit type details directly in the list when available. Job detail pages are for updates and deeper review.
- Keep technician/field workflows distinct from office/supervisor workflows. Do not remove field-specific functionality when simplifying office home.
- Use the shared English and French dictionaries for user-facing copy, and keep Angular types aligned with the shared API contracts.


## Pre-delivery UI verification

- Before delivering any web UI change, inspect the final rendered page at desktop, tablet, and mobile widths, including the interactive states changed by the work. Do not rely on source inspection or a successful build alone to claim the UI is verified.
- After the final edit, inspect the changed-file diff and run the relevant production build. Fix any visual, behavior, or build regression found before reporting completion.
