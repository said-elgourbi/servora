# ADR-017 — The photo viewer pages, and a photo can be saved or shared

**Status:** Accepted (product-owner decision, 2026-09-15; implemented by `docs/tracker/029-photo-evidence-phases.md` Phase 4e)

Date: 2026-09-15
Tracker: `docs/tracker/029-photo-evidence-phases.md` (Phase 0 D11, D12, D13; Phase 4e implements them)
Contract: **none.** The API, its routes, its answers and `docs/api/job-photos.md` are unchanged: nothing
here records, edits or deletes evidence, and no request carries a new field.
Predecessor decisions: `docs/decisions/016-android-image-stack-and-viewer-zoom.md` (the viewer and its
gestures, `D4b`/`D9`), `docs/decisions/015-evidence-capabilities.md` (the evidence capability set)

References: `BR-001`, `BR-006`, `BR-007`, `BR-011`, `BR-012`, `BR-015`, `BR-027`, `BR-028`, `BR-041`,
`BR-042`, `BR-067`, `BR-080`, `Project.md` §4, §6, §11, §31, `dev.md` §1, §8, §9, `qa.md` §6.1, §6.2,
§7.3, §15.

## Context

`D4` built the viewer and `D9` added pinch-zoom and pan to it, and both left two things explicitly
undrawn:

- **no swiping between photos, and no gallery-wide pager** — *"A tap opens the one photo it was tapped
  on and nothing swipes to the next"*, recorded in the design system's viewer row and in the tracker's
  "not implemented, and not to be assumed" list;
- **no way to take a photo out of Servora** — saving a photo into the device's own gallery and sharing
  it with another application were not in any plan.

`D10` had decided one thing about the device's media library — that Servora **never reads** it, and takes
a source photo only from the device's own picker — and decided nothing about **writing** into it.

Two facts made the requests reachable rather than speculative:

- the viewer already resolves a tapped photo through `viewedJobPhoto`, which prefers the device's own
  bytes and falls back to the backend's evidence, so a sequence of photos is a resolution of the records
  the screen already holds rather than a new read;
- an evidence read already exists on one path — `JobPhotoFetcher` downloads a photo with the
  `401 → renew once` renewal every other read of evidence has — so an export needs no new route, no new
  permission and no new storage endpoint (`ADR-013` D7).

Product ownership answered, on 2026-09-15, with the bundle the tracker records as **D11**, **D12** and
**D13**.

## Decisions

### D11 — The viewer pages through the Job's photos, in the order Job Details presents them

The viewer shows the Job's photos as one sequence, swiped left and right: the evidence the backend holds
**first** — in the order Job Activity reports it, which is the order the gallery draws it — and then the
photos this device still holds, **oldest first**, which is the order the tray lists them. A photo both
records hold is listed **once**, and which copy is read stays `viewedJobPhoto`'s decision: the device's
own bytes while they exist, the backend's evidence once they do not.

The order is deliberately *the order the screen already presents*, not a new ordering the viewer invents:
a swipe from a photo the technician tapped continues through exactly what they see around it, and neither
list has to be re-sorted to explain itself. It is also why a tap on a tray tile opens the same sequence as
a tap on a gallery tile rather than a second, tray-only one.

**A swipe pages only while the photo on screen is at fit.** Zoomed, the same gesture pans the photo within
its own bounds (`D9`), so the two gestures never compete for one drag: `jobPhotoViewerPagingEnabled`
answers from Telephoto's own `zoomFraction` — the property the library's documentation uses to answer "is
this zoomed in?" — and a page the pager has left resets its zoom, so returning to a photo starts it at fit
rather than at whatever scale it was left on. This is the library's documented recipe for a pager, not a
rule this slice invented.

The chrome follows the photo on screen: the phase badge, a localized position (`3 / 12`), the note — which
belongs to its photo and pages with it — and the two actions below. The gesture surface stays the photo's
own area, and the viewer is still closed by its own action or the platform's back gesture.

### D12 — A photo can be saved into the device's own gallery

The viewer gains an explicit **Save to device** action. It writes the photo the technician is looking at
into the device's shared image collection, in a **`Servora`** album beside the platform's own Pictures
folders, so the copy is visible in whatever gallery application the user has rather than buried in
app-private storage.

- From **API 29** an app may insert into the shared image collection without any permission, and
  `IS_PENDING` keeps the photo out of the gallery until all of its bytes are there. A write that fails
  removes its own row rather than leaving a half-written photo to be found.
- On **API 26–28** the same write is still guarded by `WRITE_EXTERNAL_STORAGE`, so the manifest declares
  it with `android:maxSdkVersion="28"` — for those two releases only — and the app asks for it when a save
  needs it, retries the same save when it is granted, and reports a declined one rather than pretending
  the save happened.
- `D10` is unchanged: the declaration is a **write** permission with a two-release ceiling, no media-read
  permission is declared, and no part of Servora reads the device's media library.

Saving copies evidence out of Servora into storage the app does not control. That is the technician's own
explicit action on a photo they are already looking at, and Servora records nothing and claims nothing
about the copy (`BR-027`, `BR-067`).

### D13 — A photo can be shared with another application

The viewer gains an explicit **Share** action. It hands the photo to the platform's own share sheet
(`ACTION_SEND`, the photo's own MIME type), so WhatsApp, mail, Drive — whatever the device offers — is the
user's choice, and Servora names no provider and builds no integration of its own.

- The bytes are staged in one app-private cache directory, cleared before each export so it cannot grow,
  and exposed through the **app's existing `FileProvider`**, extended by exactly that one `cache-path`
  entry. The receiving application is granted read access to that one file and to nothing else.
- Only the photo travels: no note text, no Job or customer detail, no identifier beyond the file's own
  name, which is `Servora-<photoId>.<ext>` — the id the record already uses, so a copy found later still
  names the photo it came from.
- A device with nothing that accepts an image is reported rather than looking as though the share
  happened.

### D14 (consequence, not a separate question) — The two actions are drawn on the evidence read capability

Saving and sharing both **read** the photo's bytes, so both are drawn on `evidence.view`, the capability
the API already enforces on `GET /jobs/:id/photos/:photoId/content` (`ADR-015` D2). The client gains that
code in its own permission mirror (`Permission.EVIDENCE_VIEW`, `canViewEvidence`) for the first time;
nothing about the API's authorization changes, and the client gate remains a UX gate only (`BR-007`,
`BR-011`).

Both actions are available for evidence the backend holds **and** for a photo the device still holds, since
both are bytes the technician is entitled to look at. A pending photo also keeps reporting its upload
state inside the viewer, so evidence the office already holds is never confused with a photo that exists
only on this device.

## Consequences

- `ui/jobs/JobPhotoViewer.kt` becomes a pager: `viewedJobPhotoSequence`, `jobPhotoViewerInitialPage` and
  `jobPhotoViewerPagingEnabled` are **pure rules** over plain values, so the ordering, the initial page and
  the zoom/paging rule are covered by JVM tests rather than by a device (`qa.md` §6.1).
- `data/jobs/JobPhotoContentReader.kt` is extracted from `JobPhotoFetcher`: the evidence download with its
  `401 → renew once` renewal now exists **once**, used by both the image stack and the export, so the two
  can never diverge on how a refusal is handled (`BR-007`, `BR-018`).
- `data/jobs/JobPhotoExport.kt` holds the export's own vocabulary (`JobPhotoExportSource`,
  `JobPhotoExportOutcome`, `JobPhotoExporter`) and the read that proves a photo's type from its bytes
  before anything is written; `data/jobs/AndroidJobPhotoExportTarget.kt` holds the two device writes
  (MediaStore and the share sheet) and is the only file that touches `Intent` or `ContentResolver` for
  this.
- `AndroidManifest.xml` gains `WRITE_EXTERNAL_STORAGE` with `android:maxSdkVersion="28"`;
  `res/xml/file_paths.xml` gains the `evidence-share` cache path.
- `JobDetailsUiState` gains `photoSaveAwaitingPermission` and the four export failures
  (`EXPORT_UNREADABLE`, `EXPORT_FAILED`, `SHARE_UNAVAILABLE`, `SAVE_PERMISSION_DENIED`) plus the two
  confirmations (`SAVED_TO_DEVICE`, `SHARED`); the screen resolves the chooser's title from resources and
  owns the permission request (`BR-028`).
- `BR-027` is unaffected in substance: nothing here edits, deletes or re-records evidence, and a copy a
  user makes is their own action outside Servora. Whether Servora should be able to remove evidence
  remains `D6`, and `D6c` (a refused photo is discardable) remains Phase 6a.

## Offline behaviour (`dev.md` §10, offline standard §13)

This slice adds no offline-capable mutation and no local store, so nothing about it is queued, replayed or
conflicted: **it is not an operation the backend has to accept**. It reads bytes and writes a copy outside
the app, and the check §13 asks of a new read or mutation is answered as follows.

- **A photo the device still holds** is read from the app-private file the capture wrote (§9) — the same
  file the tray, the review preview and the upload read. Viewing, saving and sharing it therefore work with
  no connectivity, and neither action creates a second copy inside the app or a second record in the local
  database.
- **Evidence the backend holds** is read through the API, exactly as the viewer already read it, so saving
  or sharing it is **online-only** (`D5` deferred; §13's adopter list already records that read as one). A
  read that fails writes nothing and is reported, rather than leaving an empty file behind.
- **The gallery write and the share-sheet staging file** are the platform's storage and an evictable cache
  directory. Neither is Servora's record of anything, neither is reconciled with the backend, and a failure
  in either is reported rather than retried silently.
- **No synchronization, idempotency key, expected version or conflict rule is introduced**, because no
  operation is sent anywhere: the evidence is unchanged, and a copy that leaves the app is the user's own.
  The existing outbox and its replay engine are untouched.

## Open questions not decided here

Recorded rather than guessed (`BR-042`), and carried by the tracker:

1. **Swiping between the photos of different Jobs, or a device-wide gallery** — not decided by `D11`,
   which scopes paging to the Job whose screen is open.
2. **Retention, deletion and lifecycle** (`D6`): `D6a`/`D6b` (deleting or editing a recorded photo),
   `D6d` (retention) and `D6e` (Job deletion and orphaned objects) stay open, as does `D6c`'s own phase.
3. **Copying more than the photo** — sharing the technician's note, or Job context, as part of the share.
   `D13` decided the photo only.
4. **A saved copy's relationship to the record** — Servora neither knows nor tracks where a saved or
   shared copy ends up, and does not intend to (`BR-027`).
5. **Server-derived thumbnails and presigned reads** (`ADR-013` open questions 3 and 4) are untouched by
   this slice; the export reads the same API port as the viewer.
