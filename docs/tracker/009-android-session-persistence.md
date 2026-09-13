# Tracker 009 — Android session persistence and startup restoration

**Status: COMPLETE** (restore the session across process death; silent refresh; sign-out)

Date: 2026-09-12
Predecessor: `docs/tracker/008-android-customers-filter.md`
Architecture decision: `docs/decisions/009-android-session-persistence.md`
Business rules: `BR-001`, `BR-013`, `BR-014`, `BR-031`, `BR-042`, `BR-046`
API contract reference: `docs/api/authentication.md` §3.1–§3.3

## Defect

Closing the app, or the OS reclaiming the process, sent the user back to sign-in even though the
session was still valid. Sign-in, access-token renewal and authenticated calls all worked while the
process lived, so only restoration was broken.

**Cause.** `SessionStore` held the session in memory only, and the signed-in UI state lived in
per-screen ViewModel flags (`signedIn`). Both die with the process. Nothing read a persisted session
at startup, because none was persisted.

## Scope

| Item | Status |
| --- | --- |
| `SessionStorage` port + `EncryptedSessionStorage` (Android Keystore AES-256-GCM) | Done |
| `SessionStore` persists and restores; mutators run off the main thread | Done |
| `SessionManager` with `AuthState = Checking \| SignedOut \| SignedIn` | Done |
| Startup restore: valid token in, expired token renewed, refused token cleared | Done |
| Explicit loading state, so sign-in never flashes before restore | Done |
| `AuthApi.signOut` + clear-then-revoke logout that works offline | Done |
| UI driven by `AuthState`; per-screen `signedIn` flags and "Signed in" alerts removed | Done |
| Settings → Sign out; session-scoped customer list reset on sign-out | Done |
| JVM tests for the store, the manager and the storage mapper | Done |
| Restart integration test: persist in one process, restore in the next | Done |
| Instrumented Keystore round-trip test (on-device) | Done |
| Instrumented cross-process test (write → force-stop → read) | Done |
| Tracker + ADR documentation | Done |

Deliberately **not** done: biometric-gated session keys, stored-format migration, a "reconnect"
affordance for an offline launch with an expired token, and the remaining Settings surfaces (see
`ADR-009` Deferred).

## Startup authentication flow

```text
Process start
    │
    ▼
AuthState.Checking  ── SessionStore.restore() reads the encrypted preferences blob
    │
    ├─ no session ─────────────────────────────────────────────► SignedOut → sign-in
    │
    ├─ access token not expired ───────────────────────────────► SignedIn  → home
    │
    └─ access token expired
            │
            ├─ POST /auth/refresh succeeds ────────────────────► renew stored → SignedIn → home
            ├─ refresh refused (401/400) ──────────────────────► clear stored  → SignedOut → sign-in
            └─ backend unreachable ────────────────────────────► keep stored   → SignedIn → home
                                                                  (existing 401→renew retry later)
```

Sign-in (password or SMS) stores the new session and moves to `SignedIn`. Sign-out clears the stored
session, moves to `SignedOut`, and then asks `POST /auth/sign-out` to revoke it; a failure to reach
the backend leaves the user signed out locally.

## Files changed

| Area | File |
| --- | --- |
| Storage port | `data/session/SessionStorage.kt` (new) |
| Stored format | `data/session/StoredSession.kt` (new) |
| Encrypted impl | `data/session/EncryptedSessionStorage.kt` (new) |
| Store | `data/session/SessionStore.kt` (persist + suspend mutators) |
| Manager | `data/session/SessionManager.kt` (new: `AuthState`, `SessionManager`, `DefaultSessionManager`) |
| API | `data/auth/AuthApi.kt` (`signOut`) |
| Repository | `data/auth/AuthRepository.kt` (persist + notify the manager) |
| DI | `di/DataModule.kt` (bind storage + manager; provide `Clock`) |
| UI | `ui/auth/SessionViewModel.kt` (new), `ui/auth/AuthFlowScreen.kt` (state-driven + loading) |
| UI | `ui/signin/*`, `ui/sms/*` (remove per-screen session flags and success alerts) |
| UI | `ui/customers/CustomersScreen.kt` (`onSignOut`, Settings sign-out), `CustomersViewModel.kt` (`reset`) |
| Entry | `MainActivity.kt` |
| Resources | `values/strings.xml`, `values-fr/strings.xml` |

## Lifecycle cases

| Case | Covered by |
| --- | --- |
| login → close → reopen → still authenticated | `SessionRestartIntegrationTest.a sign-in survives a process restart…`, `PersistenceAcrossProcess` (device), `SessionManagerTest…`, `SessionStoreTest…` |
| login → access token expires → close/reopen → refresh succeeds | `SessionRestartIntegrationTest.an expired access token is renewed on startup…`, `SessionManagerTest.renews an expired session before signing in` |
| refresh/session expired or revoked → reopen → login required | `SessionRestartIntegrationTest.a session the backend refuses on startup…`, `SessionManagerTest.clears an expired session the backend refuses`; device run below |
| logout → close/reopen → login required | `SessionRestartIntegrationTest.sign-out clears the session for the next process`, `SessionManagerTest.sign-out clears the session…`, `SessionStoreTest.clears the session in memory and on disk`; device run below |
| process killed by OS → reopen → session restored | `SessionPersistenceAcrossProcessTest` (write → `force-stop` → read); device run below |
| offline reopen with an expired token keeps the session | `SessionManagerTest.keeps an expired session when the renewal cannot reach the backend` |

`SessionManagerTest` hands the manager a store that already holds the session in memory, so it does
not exercise the startup disk read. `SessionRestartIntegrationTest` wires the real repository →
store → manager graph over one storage and rebuilds it for the "restart", which closes that gap.

## Verification

```text
Android
  ./gradlew testDebugUnitTest          PASS   BUILD SUCCESSFUL — 136 tests, 0 failures
  ./gradlew lintDebug                  PASS   0 errors
  ./gradlew assembleDebug              PASS   debug APK
  ./gradlew connectedDebugAndroidTest  PASS   Pixel_10 AVD (API 37), all classes
```

Device-runtime verification (`emulator-5554`, the real API reached through
`adb reverse tcp:3000 tcp:3000`):

1. Sign in as `manager@servora.test`; the customer list loads (17 customers).
2. Force-stop the app (process death) and reopen → **home**, the session restored from the
   encrypted `servora.session.xml`. `SessionPersistenceAcrossProcessTest` additionally writes the
   session in one instrumentation process, force-stops, and reads it back in the next.
3. A stored session whose access token has expired **and** whose refresh token the backend refuses
   → reopen shows **sign-in** and `servora.session.xml` is emptied.
4. Settings → Sign out → sign-in shown and `servora.session.xml` emptied; reopen stays signed out.

To run the cross-process check explicitly (`SessionPersistenceAcrossProcessTest`):

```text
adb shell am instrument -w -e class \
  com.servora.android.data.session.SessionPersistenceAcrossProcessTest#step1WriteSessionForTheNextProcess \
  com.servora.android.debug.test/androidx.test.runner.AndroidJUnitRunner
adb shell am force-stop com.servora.android.debug
adb shell am instrument -w -e class \
  com.servora.android.data.session.SessionPersistenceAcrossProcessTest#step2ReadSessionWrittenByThePreviousProcess \
  com.servora.android.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Physical-device QA: **Awaiting product owner.** The emulator run above is device-runtime
verification, not physical-device acceptance (`qa.md` §7).

## Android QA runbook — session persistence

```text
1. Sign in as Manager. Force-stop the app (Settings → Apps → Servora → Force stop).
   Open it again.
Expected: the app opens on the signed-in home screen, not sign-in.

2. "Process killed by OS": with the app signed in, force-stop it, then reopen.
Expected: still signed in.

3. Expired access token: leave the app idle longer than the access-token lifetime (default 15m),
   force-stop, reopen.
Expected: the app opens signed in; the session was renewed through POST /auth/refresh. No sign-in
screen appears.

4. Revoked session: sign in, then revoke the session from another client (or wait out the session
   lifetime), force-stop, reopen.
Expected: the app shows sign-in; the stored session was cleared.

5. Logout: Settings tab → Sign out. Force-stop, reopen.
Expected: sign-in is shown. Signing out with the network unavailable also signs out locally.

6. No flash: watch the launch after step 1.
Expected: a short loading state, never a sign-in screen that is replaced by home.

7. French: switch the app language to French and confirm the Sign out action reads
   "Se déconnecter" (Settings tab).
```

## Notes

* Restoring an offline launch with an expired access token keeps the session (see `ADR-009` D4); the
  exact offline UX for that state is an open question and is not implemented.
* The Keystore path is device-only, so `EncryptedSessionStorageTest` must run on a device/emulator.

