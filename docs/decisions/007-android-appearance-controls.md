# ADR-007 — App language and light/dark appearance controls on Android

**Status: Accepted** (product-owner instruction, 2026-09-10)

Supersedes nothing. Extends `ADR-006` D8 (the Android authentication flow) with the two controls
the approved sign-in design pins in the page's top bar.

References: `BR-001`, `BR-028`, `BR-042`, `Project.md` §10–§11 and §23, `dev.md` §4 and §9–§11,
`qa.md` §6 and §15, `docs/design/android-design-system.md`.

## Context

The approved sign-in design (`Figma/src/screens/Login.tsx`) pins two controls in the page's top
bar: a language pill on the left and an appearance pill on the right. They are product surface,
and `BR-028` already requires the app to support English and French.

Both choices change the app *itself* rather than one screen: the language changes every string the
app draws, the theme changes every colour. Neither is business data. They are device-local
presentation preferences (`BR-001`), they never reach the API, and they are not part of the offline
working set (`BR-031`).

Before this change the Android client drew the light appearance only, with a framework Material
window theme (`android:Theme.Material.Light.NoActionBar`) and no per-app locale support.

## Decisions

### D1 — The appearance is applied through `AppCompatDelegate`

`MainActivity` becomes an `AppCompatActivity`, `Theme.Servora` becomes a
`Theme.AppCompat.DayNight.NoActionBar` descendant, and `androidx.appcompat` is added.

The language is the part that forces this. A language choice has to change the *resources* the app
reads, so it must go through the platform locale mechanism, and the platform's own
`LocaleManager.setApplicationLocales` only exists from API 33 while this app supports API 26. The
AppCompat support-library backport (`AppCompatDelegate.setApplicationLocales` +
`AppLocalesMetadataHolderService` in the manifest) covers every supported level, persists the
choice itself, and integrates with the Android 13 per-app language settings page through
`android:localeConfig`. Writing that machinery by hand would be a locale-reloading reimplementation
(`dev.md` §4).

Applying the theme through the same delegate is then the smaller change, not a new mechanism: one
dependency, one backport, one place that owns "what appearance is the app applying". It also means
the night mode is a real configuration change (`uiMode`), so `isSystemInDarkTheme()` — which
`ServoraTheme` already uses — reports it, and the window, the Compose colour scheme and the
`-night` resources all follow the same value with no parallel state.

Rejected alternatives:

* **Compose-side only** (a `CompositionLocal` with the chosen colour scheme): cannot change the
  language resources at all, and would leave the window and the `-night` resources behind the
  Compose colours.
* **`LocaleManager` (API 33+)**: does not cover `minSdk` 26.
* **`AppCompatDelegate` for the theme only, locale left to the system settings page**: the design
  puts both controls on the sign-in screen, and `BR-028` expects the choice to be usable in the app.

### D2 — The theme control offers light and dark, and deliberately not "system"

The approved design's third option (`Monitor`, "follow the device") is **not** implemented. The
product owner restricted the control to light and dark for this slice, and `BR-042` forbids
inventing the missing behaviour, so `AppTheme` has exactly two values and stores no "follow the
system" state. A stored value the app does not know still resolves to light rather than failing, so
a build that once wrote another value keeps working (`AppTheme.fromStoredValue`).

`AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM` is therefore unused. Adding the third option later is
a value in the enum, a `Monitor`-style icon and one `when` branch; nothing in this decision
prevents it.

### D3 — The platform owns the stored language; the app owns the stored theme

The two choices are stored in the place that is already authoritative for them.

* **Language** — AppCompat's own store. `AppLocalesMetadataHolderService` is declared with
  `autoStoreLocales=true`, so `AppCompatDelegate.setApplicationLocales(...)` persists the choice and
  the app reads it back through the delegate. `AppearancePreferences` deliberately keeps no second
  copy: two stores for one value can disagree, and the platform is the one that applies it.
* **Theme** — one app-private `SharedPreferences` file (`servora.appearance`), holding the chosen
  `AppTheme` name, read in `ServoraApplication.onCreate` and applied with
  `AppCompatDelegate.setDefaultNightMode(...)` **before** the first activity is created, so the
  first frame the user sees is already in the chosen appearance and no flash occurs.

`SharedPreferences` rather than DataStore: the value is a single device-local enum, and it must be
read synchronously in `Application.onCreate` on the launch path. Adding a Flow-based store for one
value would be machinery without a caller (`dev.md` §4, KISS).

`AppTheme.fromStoredValue` resolves anything unknown — including no stored value at all — to light,
so a missing or unreadable preference degrades to a working app rather than a crash.

### D4 — The current appearance is derived from the platform and provided once

`MainActivity` composes the current appearance from platform state — the applied locales
(`LocalConfiguration.current.locales`) and the applied night mode (`isSystemInDarkTheme()`) — plus
the two store callbacks, and provides it to the UI as `AppAppearance` through
`LocalAppAppearance`.

Deriving rather than holding a second copy of the value means the controls stay correct when the
appearance changes from outside the app, such as the Android 13 per-app language settings page,
without any additional plumbing. `LocalAppAppearance` has a working default (English, light, no-op
callbacks), so previews and tests render the controls without a platform.

Screens stay presentation-only: they call the two callbacks and render labels, and never touch the
delegate or `SharedPreferences` (`dev.md` §1).

### D5 — These preferences stay device-local and never enter the domain or the wire

Neither choice is business data (`BR-001`). They are not in the API contract, not in the offline
working set, and never synchronised — there is nothing to reconcile, so `BR-031`/`BR-032` do not
apply. The language changes the app's own chrome only; user-entered content stays in the language it
was entered in and no localized text is ever used as a domain value (`dev.md` §9).

Consequences to state plainly: the same user on two devices may see different appearances, and a
language chosen on Android does not change the Angular console, which handles its own language. An
appearance that follows the *account* would need a user-profile preference, which does not exist
yet (`BR-039`). See "Deferred" below.

### D6 — The segmented control is a radio group, and selection does not rest on colour

Each control is a `Row` with `Modifier.selectableGroup()`; each segment uses
`Modifier.selectable(role = Role.RadioButton)`, so TalkBack announces each option as a radio button
with its selected state and reports the pair as a set.

Labels are spoken, not decorative: the language segments announce the language's **own** name
("English" / "Français"), so a user can find their language even while the UI is showing the other
one, and the visible two-letter abbreviation is cleared with `clearAndSetSemantics {}` so TalkBack
does not spell out letters. Theme segments announce the theme's name in the current language
("Light theme" / "Dark theme", "Thème clair" / "Thème sombre").

The selected segment is lifted 1 dp above the track as well as recoloured, so the selected state is
not communicated by colour alone (`docs/design/android-design-system.md`). Both segments keep a
48 dp touch target through the segment's 40 dp height plus the track's 4 dp padding.

## Consequences

* `MainActivity` is an `AppCompatActivity` and the app depends on `androidx.appcompat`
  (`libs.versions.toml`, `appcompat = "1.8.0"`).
* `Theme.Servora` descends from `Theme.AppCompat.DayNight.NoActionBar` in both `values/` and
  `values-night/`; the dark variant still owns the true-black window background.
* Changing the language recreates the activity, because the platform reloads resources. This is
  platform behaviour, and it is acceptable here: the control sits on the sign-in screen, before any
  work is in progress.
* `ServoraTheme`'s `isSystemInDarkTheme()` default reports the night mode the delegate applies, so
  Compose colours and `-night` resources follow one value. Because the platform's default system bar
  content follows the *device* setting and this theme sets no `windowLightStatusBar`, `ServoraTheme`
  also paints the status/navigation bar content from the same resolved appearance
  (`ui/theme/SystemBarAppearance.kt`, 2026-09-13), so the icons contrast with the screen.
* Unit tests `AppThemeTest` and `AppLanguageTest` pin the two mappings (choice → night mode, stored
  value → choice, locale tag → language).

## Deferred (not implemented here)

* The design's **follow-the-device** appearance (`Monitor`): see D2.
* An appearance that follows the **user account** across devices: needs a profile preference
  (`BR-039`, still open).
* Offering the same control in the Angular console: Angular owns its own language handling.

