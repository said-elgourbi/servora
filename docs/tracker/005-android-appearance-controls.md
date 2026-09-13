# Tracker 005 — Android app appearance controls

**Status: COMPLETE** (Android only — no API, database, contract or Angular change)

Date: 2026-09-10
Decision record: `docs/decisions/007-android-appearance-controls.md`
Design reference: `docs/design/android-design-system.md` → "Appearance controls (sign-in top bar)"

## Scope

The approved sign-in design (`Figma/src/screens/Login.tsx`) pins two controls in the page top bar:
an app-language pill and a light/dark appearance pill. This slice implements them for Android and
leaves every other application untouched.

| Item                                                                                    | Status |
| --------------------------------------------------------------------------------------- | ------ |
| `AppLanguage` — stable `en`/`fr` tags, unknown tag resolves to English                   | Done   |
| `AppTheme` — light/dark only, `nightMode` mapping, unknown stored value resolves to light | Done   |
| `AppearancePreferences` — stores the theme, applies both choices via `AppCompatDelegate`  | Done   |
| `ServoraApplication` — applies the stored theme before the first activity                | Done   |
| `MainActivity` → `AppCompatActivity`, derives appearance from platform state             | Done   |
| `AppAppearance` + `LocalAppAppearance` — values the controls read, changes they request   | Done   |
| `AppearanceControls` — `LanguageControl`, `ThemeControl` (radio-group segments)           | Done   |
| Bilingual strings (`values/`, `values-fr/`) and sun/moon vector drawables                 | Done   |
| Window themes descend from `Theme.AppCompat.DayNight.NoActionBar`                         | Done   |
| Manifest: `android:localeConfig` + `AppLocalesMetadataHolderService` (`autoStoreLocales`) | Done   |
| Unit tests: `AppThemeTest`, `AppLanguageTest`                                             | Done   |

Deliberately **not** done (recorded in ADR-007): the design's third appearance option
("follow the device") — `BR-042` forbids inventing it; and no API/database/sync work, because the
choices are device-local presentation preferences (`BR-001`, `BR-031`).

## Verification

```text
Android build (make android-build → assembleDebug)          PASS
Android unit tests (make android-test → testDebugUnitTest)  PASS
Android lint (make android-lint → lintDebug)                PASS
```

Lint detail: `abortOnError` is enabled in `app/build.gradle.kts`, so a passing `lintDebug` proves
zero lint errors. The report holds 15 warnings, all in pre-existing code; none is in a file added by
this slice. No `MissingTranslation` finding, which confirms `values-fr/strings.xml` covers the new
strings.

Physical-device QA (`qa.md` §7): **AWAITING PRODUCT OWNER**. The agent claims automated
verification only, per `qa.md` §17.

## Manual QA (Android, sign-in screen)

1. Install the debug build and open the app.
2. Tap **FR** in the language pill → every string on the screen switches to French.
3. Tap the moon icon in the appearance pill → the app turns dark, including the window background.
4. Kill and relaunch the app → still French and still dark (both choices are remembered).
5. Repeat step 2/3 to return to **EN** and the light theme.

Expected: the visible abbreviation and the pill's selected state always match the applied
appearance, no flash of the wrong theme on launch, and the choice survives a process restart.

## Insets follow-up — status-bar overlap (2026-09-11)

Device review found the two pills rendering **under the system status bar**. The window draws under
the system bars (edge-to-edge is enforced from Android 15, and the app never opts out), while the
top bar positioned itself with a fixed 20 dp margin only.

Fix (Android only, `SignInScreen`): the pinned row now offsets itself by the inset the platform
reports — `Modifier.windowInsetsPadding(WindowInsets.statusBars)` — so the designed 20 dp gutters
sit inside the status-bar safe area. No fixed status-bar height is introduced (`Project.md` §11,
`dev.md` §1). The form column below the row is unchanged.

```text
Android build (make android-build → assembleDebug)          PASS
Android unit tests (make android-test → testDebugUnitTest)  PASS — 64 tests, 0 failures, 0 errors
Android lint (make android-lint → lintDebug)                PASS
```

Physical-device QA (`qa.md` §7): **AWAITING PRODUCT OWNER** — re-check that both pills are fully
visible below the status bar and that their distance from it still matches the design. If a device
older than Android 15 shows the row sitting lower than designed, the inset must be conditioned on
the window actually being edge-to-edge.


## Follow-up — system bar content contrast (2026-09-13)

Device review found the status bar content (clock, Wi-Fi, battery) still drawn in white while the
app showed the **light** appearance on a phone running dark mode, leaving the icons unreadable
against the light screen.

Cause: the app never declared the system bar content appearance. `Theme.Servora` descends from
`Theme.AppCompat.DayNight.NoActionBar`, whose `DayNight` variant sets no
`android:windowLightStatusBar`, so the platform default — light content — applied in both
appearances.

Fix: `ServoraTheme` now paints the system bar content from the same resolved `darkTheme` it hands to
the colour scheme, through the new `ui/theme/SystemBarAppearance.kt`
(`WindowInsetsControllerCompat.isAppearanceLightStatusBars` /
`isAppearanceLightNavigationBars`). Dark content on the light appearance, light content on the dark
one, for both the status bar and the navigation bar.

Only the content appearance is set: the window's edge-to-edge layout and the insets the screens
apply are unchanged, and no deprecated `statusBarColor`/`navigationBarColor` API is used. Because
the value is the *resolved app appearance*, a system-following option would be covered by the same
code should the product add one — it is not offered today (ADR-007 D2).

```text
Android build (make android-build → assembleDebug, assembleDebugAndroidTest)  PASS
Android unit tests (make android-test → testDebugUnitTest)                    PASS — 160 tests, 0 failures, 0 errors
Android lint (make android-lint → lintDebug)                                  PASS
Android instrumentation (SystemBarAppearanceTest)                             NOT RUN — no device/emulator attached to the agent; APK compiles
```

Physical-device QA (`qa.md` §7): **AWAITING PRODUCT OWNER** — see the manual QA below. Per `qa.md`
§17 the agent claims automated verification only.

## Manual QA — system bar content (Android, appearance control)

Run each step with the control on the sign-in screen and note the status bar icons (clock, Wi-Fi,
battery) and the navigation bar handle.

1. Set the **phone** itself to Dark theme. In Servora, select **Light theme**.
   Expected: the app is light and the status bar icons are **dark**.
2. Still on the dark phone, select **Dark theme**.
   Expected: the app is dark and the status bar icons are **light**.
3. Set the **phone** itself to Light theme and repeat steps 1–2.
   Expected: the same result in both choices — the icons follow the Servora appearance, never the
   phone setting.
4. Kill and relaunch the app after each choice.
   Expected: the icons match the remembered appearance from the first frame (no white-on-light
   flash).
5. Change the appearance while a signed-in screen is open, then reopen the sign-in screen.
   Expected: the icons flip with the appearance on every screen, with no content shift — the
   edge-to-edge layout and the existing insets are unchanged.


