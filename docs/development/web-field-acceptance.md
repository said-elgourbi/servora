# Browser field workflow acceptance

Implementation: tracker 064 Phase 2, ADR-027, and `docs/api/field-scope.md`.
This runbook does not establish physical iPhone or installed-PWA readiness.

## Automated checks

- API scope integration: exactly one active field membership, forged/partial/repeated scope,
  mixed office/field reach, tenant isolation, revocation/capability loss, changes after guards,
  duplicate commands and unscoped-client compatibility.
- Connected Chromium/API/PostgreSQL: browser authentication, assigned Home/details, note, photo,
  MP4/AAC file upload/playback, completion with an outcome, follow-up request/clarification reply,
  ad-hoc reporting, assignment denial, restoration and logout. Uses seeded test users and the
  existing in-memory object storage port; it does not verify a deployed storage provider.
- Mocked browser recovery with real IndexedDB/Web Locks: provisional offline snapshots/drafts,
  immutable submission, two-tab replay/logout, response loss with intervening versions,
  dependency refusal, version conflict and explicit online review, quota failure, account partitioning,
  cold offline reload locking and incompatible-schema preservation.
- Unit policies: seven-day/50-Job unpinned retention, pinned pending context, refusal versus offline
  fallback, dependency ordering, Retry-After/backoff, auth generation fences, provisional refresh
  failure, MIME alias canonicalization and update/navigation checkpoint refusal/recovery.

Run the web production build before Playwright. `npm run test:connected --prefix web` uses the real
local API test harness. An existing Chromium can be selected with `PLAYWRIGHT_CHROMIUM_EXECUTABLE`.
Connected tests deliberately disable traces/screenshots/video because real session credentials exist
in that run. Browser transport mocks contain only fictitious tokens.

## Owner's deferred device checks

1. On a physical iPhone, sign in with one field membership. Open assigned work and switch Visits;
   verify customer contacts, directions, crew, purpose and activity. Confirm unassigned work is denied.
2. Exercise permission prompts, camera/gallery, file selection, MP4/AAC recording, stop, preview,
   removal from a draft, phases, upload and accepted-media playback. Unsupported audio must remain
   unsupported; WebM must never be relabeled MP4. Verify five-minute recording limits and interruptions.
3. Disconnect after establishing a session. Save a note and photo/audio draft, navigate away/back,
   submit an update and completion, then reconnect. Pending bytes/notes must precede completion.
   Server refusals retain work; changed versions require explicit review and new intent.
4. Close/reopen offline: work must remain locked. Reopen online in the original scope to unlock;
   another account or membership must not see it. Repeat with two installed/open tabs and logout.
5. Exercise browser quota/storage denial, app closure mid-upload, OS interruption, eviction/site-data
   clearing, blocked schema upgrades and an offered app update. Failed saving must preserve the editor
   and block reload. Browser clearing/eviction can lose unsent work; no device durability is promised.
6. Verify EN/FR, light/dark, phone/landscape layouts, keyboard and VoiceOver reading/focus. Follow-up
   and clarification writes need connectivity, preserve failed editors and have no automatic write retry.

Do not claim successful background replay or cold-start offline authentication. The accepted media
bytes are online-only; only pending media belongs to durable local drafts/commands. Office writes,
quote actions and evidence removal stay online-only.
