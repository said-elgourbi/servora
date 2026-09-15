# ADR-016 — Android image stack: Coil 3 behind the `JobPhotoImages` port, and Telephoto for viewer zoom

**Status:** Accepted (product-owner decision, 2026-09-15; implemented by `docs/tracker/029-photo-evidence-phases.md`
Phase 4c and Phase 4d)

Date: 2026-09-15
Tracker: `docs/tracker/029-photo-evidence-phases.md` (Phase 0 D4b ✓ and D9 ✓; Phase 4c implements the stack,
Phase 4d the gestures)
Contract: **none.** The API, its routes, its answers and `docs/api/job-photos.md` are unchanged by either
decision.
Predecessors: `docs/tracker/027-android-job-photo-updates.md` (the first evidence slice),
`docs/tracker/029-photo-evidence-phases.md` Phase 4 (the hand-rolled viewer and thumbnail decode this ADR
replaces)

References: `BR-001`, `BR-007`, `BR-015`, `BR-027`, `BR-028`, `BR-042`, `ADR-013` (the store the evidence
travels to, and its open question 3), `ADR-014` (the offline engine),
`docs/architecture/offline-first-architecture.md` §9, §13, `Project.md` §23, §31, `dev.md` §4, §10, §14,
`qa.md` §6.2, §7.3.

## Context

Photo evidence is captured, prepared, queued and uploaded without any image library: the pipeline re-encodes
bytes (`JobPhotoProcessing`, D3b/D3c) and the display path decodes them itself (`JobPhotoImages`) with
`BitmapFactory` + `inSampleSize`, a 512 px preview for a tile, a 2560 px ceiling (`VIEWER_MAX_EDGE_PX`,
`jobPhotoFittingSampleSize`) for the viewer, a 24-entry in-memory `LruCache`, and an EXIF turn
(`JobPhotoOrientation`) applied to the decode.

That was a deliberate answer, not an oversight. Tracker 029 Phase 0 asked what happens when a photo is
tapped (**D4**), and product ownership took option **(a)**: the viewer, and nothing else. The caching and
thumbnail options the answer did not take were recorded as **D4b** rather than assumed (`BR-042`), alongside
two costs the answer accepted knowingly:

1. **Every tile downloads full-resolution bytes** over `GET /jobs/:id/photos/:photoId/content`. The OkHttp
   client is built with no cache (`data/.../di/NetworkModule.kt`), the API sends no `Cache-Control` on that
   answer, and the only cache is the in-memory one — so a cold start or an eviction re-downloads every photo
   again.
2. **The viewer has no gestures.** Zoom, pan and a gallery-wide pager were left out of `D4`, and the design
   spec asked only for "an appropriate preview/viewer" and "useful thumbnails".

Both questions were taken on 2026-09-15 and answered **D4b = (a)** (an image-loading library behind the
existing port) and **D9 = (b)** (pinch-zoom and pan on the one photo). This ADR records those decisions and
the constraints they must respect.

## Decisions

### D1 — The image stack is Coil 3, adopted behind the existing port

`data/jobs/JobPhotoImages` — four methods: local thumbnail, backend thumbnail, local full size, backend full
size — is the seam. It stays: every tile, the review preview and the viewer keep their call sites and their
test doubles (`JobPhotoImages.None`, the recording fake). What changes is the implementation behind it and
the drawing state at the call sites.

The library, rather than hand-written code, does the sampling, the orientation and the caching, so a photo is
decoded once at the size it is drawn and read again from memory or disk instead of from the API.

This is **not** a rewrite. The port was written to be that seam (`ADR-013` D7 reads the same route through
it), so the slice is one leaf plus two draw sites (`JobPhotoThumbnail` in `ui/jobs/JobPhotoComponents.kt` and
the viewer's `Image`), not a re-implementation of the feature.

### D2 — The API read keeps its authorization behaviour, and the cache is subject-scoped

Two constraints follow from `BR-007` and are requirements of the implementation, not implementation details:

- **The `401 → renew once` path is preserved.** The content route is read with the session token and a `401`
  is answered by renewing the session once through `SessionAuthenticator`; that is what the hand-rolled
  `download()` does today. The library's fetch of that route must be wired to the same path, or the swap
  would silently lose the renewal.
- **Cached evidence is partitioned by session subject and cleared when the session ends.** Cached bytes
  belong to one member's organization. A cache keyed only by job id and photo id would let a second member
  signing in on the same device read the previous member's photos without the API answering — a read around
  `evidence.view`. The local evidence store already takes this position
  (`filesDir/job-photos/<subjectId>/`, whose own comment states that one signed-in user's pending evidence is
  never read by another), and the cache follows it.

### D3 — Viewer zoom is Telephoto, on top of the stack

**D9 = (b)**: the single opened photo is pinch-zoomable and pannable, using Telephoto over the Coil 3 stack. A
bounded decode is replaced by sub-sampled decoding, which is what makes zoom legible rather than a scale-up
of a bitmap decoded for the screen. The phase badge, the note, the close target and the platform back gesture
keep working and stay legible.

**Swiping between the Job's photos and a gallery-wide pager are not decided** and stay unbuilt: the gallery
strip remains how photos are browsed, and a tap still opens exactly the photo it was tapped on.

### D4 — What this ADR deliberately does not decide or build

- **Server-derived thumbnails.** `ADR-013`'s open question 3 stays open: no derived object and no second
  route. A tile may therefore still download a full-resolution object — but **once**, not on every cold start,
  because the client-side cache is what changed.
- **`Cache-Control` on the content answer, and an OkHttp cache.** The decision is client-side caching; the API
  contract is untouched.
- **Offline visibility of accepted evidence** (`D5`, **deferred** on 2026-09-15). A cache is not a retention
  policy: it is evictable, has no retention rule, and must never be described as making evidence readable
  offline.
- **Reading the device's media library** (`D10 = (a)`): no `MediaStore`, no media-read permission. The image
  library draws app-private files and the API route, so it needs no media access.
- **Video, audio and files** (`D8`, still open).

## Consequences

- `android/gradle/libs.versions.toml` + `android/app/build.gradle.kts` gain the library (and Telephoto), in the
  file's "pinned deliberately" convention. Phase 4c/4d resolve the versions and record, in this ADR, the
  compatibility result against the pinned toolchain (Kotlin, Compose BOM, OkHttp, `minSdk 26`) — the
  dependency justification `dev.md` §4 requires before the dependency is added.
- `data/jobs/JobPhotoImages.kt` keeps its interface and both sources; its internals move to the library, and
  the `401 → renew` path is wired into the library's fetch of the content route.
- The call sites draw with the library's composable instead of a hand-held `ImageBitmap`, keeping their test
  tags, their sizes and their "cannot be shown" report (`BR-042`).
- **`jobPhotoFittingSampleSize` and `JobPhotoSamplingTest` retire** (the viewer's ceiling rule), along with the
  in-memory `LruCache`. **`JobPhotoOrientation` does not retire**: the preparation pipeline needs exactly that
  rule for `D7b` (Phase 4b), so its JVM coverage stays and the tag read is shared rather than written twice.
- `ui/jobs/JobPhotoViewer.kt` gains the zoom/pan layer; the viewer's resolution logic (`viewedJobPhoto`,
  `JobPhotoViewerTest`) is untouched, because it answers which photo and from where, not how it is drawn.
- Verification is partly **device-bound**: gesture behaviour and the library's on-device decoding are
  Compose-instrumented and are the product owner's QA (`qa.md` §6.2, §7.3). The agent compiles the device-test
  sources and runs the device-free checks; it runs no `adb` and no device/emulator command.
- Documentation: tracker 029 (state table, decision log, phases 4b/4c/4d, risks), the viewer row of
  `docs/design/android-design-system.md`, and `README.md`'s Android stack line.

## Alternatives considered and not taken

1. **Cache at the transport only** — `Cache-Control` on the content answer plus an OkHttp cache, keeping
   `BitmapFactory`. Cheaper, but it leaves the decode, the EXIF turn and the full-resolution previews
   hand-rolled, adds no cache of decoded images, and gives zoom nothing. It would also pay for a client-side
   problem with a server-side answer header.
2. **Keep the platform decoder and add nothing.** The per-cold-start download and the absence of gestures
   would both remain, and the tracker's risk row would stay open indefinitely.
3. **Server-derived thumbnails now** (`D4b` option (d)). It changes the storage contract and `ADR-013`'s open
   question 3, which is a bigger decision than the problem demands; the client cache removes the repeated cost
   without it.
4. **Adopting zoom without the image stack** (`D9` without `D4b`). Telephoto's sub-sampled decode is what makes
   zoom real, so the two were decided together, in that order.

## Open questions not decided here

Recorded rather than guessed (`BR-042`); tracker 029 carries them with the phases that depend on them.

1. Server-derived thumbnails and any derived object in the object store (`ADR-013` open question 3).
2. `Cache-Control` on the content answer, and any server-side cache policy for evidence.
3. Offline visibility of accepted evidence (`D5`, deferred) and any retention rule for bytes kept on device
   (`D6d`).
4. The exact library versions, pinned when Phase 4c/4d add them, with their compatibility check.
