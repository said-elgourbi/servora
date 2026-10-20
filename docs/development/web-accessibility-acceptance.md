# Web accessibility acceptance

Phase 1 covers sign-in, the workspace, both Home views and read-only Job/Visit details.
Automated keyboard and role/label assertions are run with `npm run test:acceptance --prefix web`.
They do not establish screen-reader acceptance. Record the OS, browser, screen reader, versions,
language, results and any defects when executing the following review.

Use a development account with the required office/assigned-work permissions. Keep credentials,
customer details and authentication responses out of recordings and reports.

## Screen-reader review — pending

Keyboard/semantic follow-up, 2026-10-19: fixed skip-link activation so it focuses the current
main landmark without navigating through the document's base URL. All main targets now support
programmatic focus; the session-check live status sits inside the main landmark rather than
replacing its role. Path navigation moves focus into the new main content after rendering;
Home view/query changes preserve the selected control's focus. Home's current-page marker now
tracks the actual route, ignoring its view query. Page titles identify sign-in, Home, Job details,
status and session-check/error views and update with EN/FR language changes.

`web/e2e/accessibility.spec.ts` adds six Chromium checks covering EN/FR required-field descriptions,
invalid-email semantics, localized titles, skip-link focus at 390/768/1440px, and Home → Job → Home
focus/current-page behavior. These are browser keyboard and semantic assertions, not a spoken
screen-reader review. The checklist below remains pending; do not mark Phase 1 manually accepted
from these checks. Firefox/Edge were not rerun in this follow-up.

1. Open sign-in with a screen reader enabled. Navigate by headings, landmarks and form controls.
   Confirm the main landmark, page heading, email/password labels, password-visibility button,
   language and appearance controls have useful names. Submit an empty form and confirm validation
   is discoverable. Check phone sign-in, the code field, generic errors and resend cooldown.
2. Sign in and activate “Skip to content” using the keyboard. Confirm focus reaches the main content.
   Read the Home heading, summary counts, attention items, Visit cards and refresh status in order.
   For an account with both capabilities, switch Office/My work and verify the current view is clear.
3. Open a Visit's Job. Confirm the Job title, lifecycle status, Visit purpose/status/outcome, crew,
   address and contact actions are understandable independently. Follow Back to Home and verify
   the reader can locate the new content without losing its place.
4. Trigger a controlled network failure and retry. Confirm loading and error messages are announced,
   previous protected content is removed, and recovery is understandable. Sign out and verify that
   the reader cannot navigate into the previous account's work.
5. Repeat key steps in French and verify pronunciation follows the document language. Check light
   and dark appearance, 200% zoom, and phone/tablet widths. At compact widths, expand the account
   disclosure with the keyboard and reach language, appearance and sign-out controls.

Record failures with the affected step and expected/actual behavior. Keep screen-reader acceptance
open until this review is performed; responsive emulation does not establish physical iPhone or
installed-PWA acceptance. Physical iPhone review remains deferred by the owner under tracker 064.
