# ADR-016 — Android image stack: Coil 3 behind the `JobPhotoImages` port, and Telephoto for viewer zoom

**Status:** Accepted (product-owner decision, 2026-09-15; **Phases 4c and 4d implemented 2026-09-15**)

Date: 2026-09-15
Tracker: `docs/tracker/029-photo-evidence-phases.md` (Phase 0 D4b ✓ and D9 ✓; Phase 4c landed the stack,
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

## Implementation record — the image stack (Phase 4c, 2026-09-15)

**The dependency.** `io.coil-kt.coil3:coil-compose:3.6.2`, pinned in `android/gradle/libs.versions.toml` the way
every other version there is (`dev.md` §4). It is the smallest artifact set that provides what D1 needs —
the library, its Compose components and its default Android decoders — and it is deliberately **not**
`coil-network-okhttp`: the API is read through the app's one Retrofit transport, so no second HTTP path and no
duplicated route string exists. Its transitive OkHttp resolves to the pinned 5.5.0, and 3.6.2 is built against
Kotlin 2.x and supports `minSdk 21`, so it is compatible with this module's `minSdk 26`, Kotlin 2.3.21 and
Compose BOM 2026.08.00; the module compiles and its lint passes with the dependency in place (see this ADR's
tracker entry for the commands and their results).

**The shapes that carry the decision.**

| Piece | What it is |
| --- | --- |
| `data/jobs/JobPhotoImage.kt` | What the stack is asked to load: `Local(path)` (the bytes this device still holds, §9) or `Backend(jobId, photoId)` (`BR-015`), plus `jobPhotoImageCacheKey(subjectId, image)` — the session-partitioned key (D2) |
| `data/jobs/JobPhotoImages.kt` | The port, unchanged in shape and in its four names: each answers the request the stack loads (`ImageRequest?`) at the size that call site asks for — a 512 px tile, or `VIEWER_DECODE_EDGE_PX` for the viewer — and `null` when there is nothing to draw (no session, or the `None` implementation) |
| `data/jobs/JobPhotoFetcher.kt` | The reader: `Fetcher.Factory<JobPhotoImage>` and the fetcher it builds. A pending photo is read from its app-private file; evidence is read from this session's disk-cache entry, or downloaded through `JobDetailsApi` with the same `401 → renew once` renewal as every other read (D2) and then cached; anything unreadable answers nothing to draw |
| `data/jobs/JobPhotoImageCacheScope.kt` | The release rule of D2: the caches are released on the first read of a session that is not the one they were cached for |
| `di/JobsOfflineModule.kt` (`JobPhotoImageModule`) + `ServoraApplication` | One `ImageLoader` with the fetcher and a disk cache in `cacheDir/job-photo-cache`, installed as the singleton the composables draw with |

**How each decision is met.**

- **D1 — one stack behind the port.** The library samples, decodes, applies EXIF and caches; the port decides
  *what* and *at what size*; the call sites (`JobPhotoThumbnail`, the tray tile, the review preview, the viewer)
  draw through `SubcomposeAsyncImage` and keep their test tags, their sizes and their "cannot be shown" state.
- **D2 — the read is unchanged in authority.** The renewal is the port's own, not the library's — the fetcher
  reads the route through `JobDetailsApi`, so `401 → renew once` behaves exactly as it did. The cache is keyed
  per session subject, and it is released **on the first read under a session that is not the one it cached**,
  rather than at the instant of sign-out: the session owner (`SessionManager`) does not depend on this feature's
  display cache, and the offline layer's own session seam (`OfflineSessionLifecycle`) releases offline state. That
  is the latest moment at which the previous session's bytes could be served, and the keys alone already make
  them unreadable by the next session. Recorded as a deviation from "cleared when the session ends" in the
  letter, kept in its substance.
- **D3 — the stack is the precondition for 4d.** Zoom is not built here. What 4d needs is a request-driven
  viewer, and the viewer now draws whatever the stack resolves rather than a bitmap it fetched itself.
- **D4 — nothing on the API side.** No `Cache-Control`, no second route, no derived object: the client-side
  cache is the whole change.

**What retired with it.** The hand-rolled decode (`BitmapFactory` + `inSampleSize` + the EXIF turn applied to a
decode), the 24-entry in-memory `LruCache`, and `jobPhotoFittingSampleSize` with its five JVM cases; the EXIF
handling for *display* is now the library's. The shared read/turn leaf `JobPhotoExifOrientation` stays — the
preparation steps still need it (Phase 4b) — and `jobPhotoSampleSize`, the one sampling rule that survives, now
takes the bounds as plain numbers so that its coverage can live in a JVM test (`qa.md` §6.1).

## Implementation record — viewer gestures (Phase 4d, 2026-09-15)

**The dependency.** `me.saket.telephoto:zoomable-image-coil3:0.19.0`, pinned in `android/gradle/libs.versions.toml`
the way every other version there is (`dev.md` §4). It is Telephoto's **Coil 3** integration — `…-coil3`, not
`…-coil`, which is the Coil 2 build — so the library loads *the request this feature already answers with*: there is
no second `ImageLoader`, no second cache and no second HTTP path. Its transitive `coil-compose:3.2.0` resolves up to
the 3.6.2 this module pins, its Kotlin stdlib (2.1.21) and Compose (1.8.0) floors are below what this module already
compiles with (Kotlin 2.3.21, Compose BOM 2026.08.00), and it declares `minSdkVersion 21` against this module's
`minSdk 26` — so nothing in the toolchain is downgraded.

**What carries D3.**

| Piece | What it is |
| --- | --- |
| `ui/jobs/JobPhotoViewer.kt` | The photo is drawn by `ZoomableAsyncImage` instead of `SubcomposeAsyncImage`: a pinch zooms, a drag pans, a double-tap goes to the ceiling, and the request is the port's own — so the fetcher, the `401 → renew once` read and the session-keyed caches are all unchanged |
| `JobPhotoViewerImageState` (same file) | The viewer's own reading / shown / "cannot be shown" states (`BR-042`), observed from the stack's result for the request it already has |
| `ui/jobs/JobPhotoViewer.kt`'s gesture surface | The `Box` the photo occupies — between the top row and the note — so the phase badge, the note and the 48 dp close action are outside the gestures, and the `Dialog` keeps the platform back gesture |

**How D3 is met.**

- **Zoom is real, not a scale-up.** The layer sub-samples the file the stack cached (for evidence, the object Coil
  downloaded and cached; for a photo the device still holds, its app-private file), so what a zoomed photo shows is
  read at the size it is drawn rather than enlarged from the fit-size decode the request bounds.
- **No scale below "fit"**, and the gestures are the library's own: `ZoomSpec`'s minimum factor is 1 (the fit
  scale), its maximum is 2 relative to that, and the double-tap is `DoubleClickToZoomListener.cycle()`. The ceiling
  is the library's default rather than a number invented here **because the capture pipeline prepares evidence at no
  more than 2048 px on its longest edge** (`D3c`), so twice the fit scale already reaches roughly the photo's own
  pixels on a phone. Raising it is a product decision and a one-line change, recorded in the tracker's runbook
  rather than taken here.
- **D2 still holds for the new draw path.** The layer executes the request through the singleton `ImageLoader` this
  feature installs (`ServoraApplication`), so the renewal and the subject-partitioned cache are the ones Phase 4c
  built, and no new read of evidence exists.

**The question D3's letter did not settle, and the answer taken.** Telephoto's composable draws the photo but
exposes **no loading and no error slot** — its Coil 3 source resolves a failed read to a painter-less result, which
draws nothing. Silently losing the viewer's "cannot be shown" report would regress `BR-042`, and re-implementing the
library's Coil source to get it back would rebuild the very integration D1 chose not to write. So the viewer keeps
its own state and observes the stack's **own result for the same request**, through Coil's `ImageRequest.Listener`:
one read, not two, and Coil reports every request through that listener — memory- and disk-cache hits included — so
a photo served from the cache still leaves the reading state. The photo's test tag is applied only once the stack has
drawn it, so one node never claims both "shown" and "cannot be shown". The alternative considered and not taken was
accepting a blank area after a failed read and reporting only a request that could not be built.

**A consequence recorded rather than left implicit.** To sub-sample, the layer needs a file to sub-sample, and it
maps a disabled disk-cache policy to a *write*: so a photo the device still holds — a pending capture — is also
written into the feature's cache directory (`cacheDir/job-photo-cache`) when the viewer draws it. That is a cache
entry beside the authoritative app-private file, not a second copy of evidence: the file is untouched, the entry is
evictable at any time and is keyed per session subject (D2), and `BR-014`/`BR-015` are unaffected. It is recorded
because `DefaultJobPhotoImages`'s own comment (a pending photo is read from the file that already holds it, so
caching it would add nothing) describes the thumbnail path, and the viewer's path now differs from it.

**What did not change.** The API, its routes, `docs/api/job-photos.md`, the permission catalogue, the capture
pipeline, the database and the evidence itself are untouched: a zoom is a change in how much of the bytes is drawn.
The viewer's own resolution logic (`viewedJobPhoto`) and its JVM cases are unchanged, and so are every other call
site's previews.

## Consequences

- `android/gradle/libs.versions.toml` + `android/app/build.gradle.kts` gain the library (and Telephoto), in the
  file's "pinned deliberately" convention. **Resolved for Coil by Phase 4c (2026-09-15): 3.6.2**, with its
  compatibility against the pinned toolchain recorded above; **Telephoto by Phase 4d (2026-09-15):
  `me.saket.telephoto:zoomable-image-coil3:0.19.0`**, with its compatibility recorded in this ADR's Phase 4d
  implementation record.
- `data/jobs/JobPhotoImages.kt` keeps its interface and both sources; its internals move to the library, and
  the `401 → renew` path is wired into the library's fetch of the content route.
- The call sites draw with the library's composable instead of a hand-held `ImageBitmap`, keeping their test
  tags, their sizes and their "cannot be shown" report (`BR-042`).
- **`jobPhotoFittingSampleSize` and `JobPhotoSamplingTest` retire** (the viewer's ceiling rule), along with the
  in-memory `LruCache`. **`JobPhotoOrientation` does not retire**: the preparation pipeline needs exactly that
  rule for `D7b`, so its JVM coverage stays and the tag read is shared rather than written twice. That share
  **exists as of Phase 4b** (landed 2026-09-15): `data/jobs/JobPhotoExifOrientation.kt` holds the platform
  tag read and the pixel turn for both the display path and the preparation steps, so Phase 4c keeps it for the
  pipeline even where the library takes over the display decode (**landed 2026-09-15**).
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
4. The exact library versions, pinned when Phase 4c/4d add them, with their compatibility check. **Both are pinned
   and recorded (2026-09-15)**: Coil `3.6.2` by Phase 4c and `me.saket.telephoto:zoomable-image-coil3:0.19.0` by
   Phase 4d.
