# 067 — Job activity as a readable work story

Date: 2026-10-20
Scope: office activity presentation, superseding tracker 066's default feed and History semantics.

The actual completed-job screenshot still required managers to interpret audit terminology and small,
scattered metadata. Updates is now the initial view and contains only operational updates. Routine
changes remain in All activity's expandable summaries and in History, which now contains **every**
returned record, including notes and evidence. Existing API queries, audit permissions, immutable
evidence rules, lifecycle actions and session boundaries are unchanged.

Updates uses flat rows: an icon, a sentence such as “Sarah added a note,” quiet visit/time context, and
then the actual note, recorded outcome, photo or voice note. Content aligns in one reading column;
photo thumbnails sit beside captions on desktop and stack on phones. Date separators omit weekdays.
The feed has no event-type headings, record IDs, status-transition rows, audit instructions or repeated
Event details disclosures. Unknown/unspecified media phases are omitted from the reading view; exact
metadata remains available in History and an accessible row options menu's details dialog.

The options menu preserves authorized evidence removal and its existing reason/confirmation flow.
The record dialog uses Material focus/Escape handling and closes when the owning entry is destroyed.
The removed-evidence audit toggle appears in History. All history remains newest-first and progressively
rendered; no record is deleted or hidden from authorized historical reads. The distinction is now a
reading view versus a complete record view, rather than two disjoint partitions.

Validation uses empty, short, long, mixed-visit, crew-heavy and substantial comment/evidence histories.
A completed-job fixture reproduces the screenshot's pattern: six human updates across several dates,
twelve routine records, captionless photos/audio and UNSPECIFIED media phases. Tests assert the default
has six updates and no routine rows, raw record references, repeated detail controls or “Status
unavailable” labels. They verify complete History, per-row details, focus restoration, evidence removal
and read-only menu permissions. Fixture photos are illustrative SVGs, not real customer evidence.

Checks: typecheck, lint, production build; 66 unit tests and 32 Chromium browser tests. Light/dark EN/FR
layouts are checked at 1440, 768 and 390px. Screenshots are in Playwright's ignored test-results directory,
including `activity-realistic-light.png` and `job-realistic-light.png`. Browser requests are fixture-backed;
no live-backend or other-browser acceptance is claimed. The initial bundle warning remains (about
537 kB against a 500 kB warning budget); component style budgets pass. Test servers and browsers stop
when Playwright finishes. Existing unrelated changes and the foundation stack remain untouched.
