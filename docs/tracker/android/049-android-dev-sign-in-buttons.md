# Tracker 049 — Temporary dev sign-in buttons (Android)

**Status: IMPLEMENTED** (two debug-only buttons on the Android sign-in screen; JVM tests run, device-test
sources compile, **Android physical-device QA is the product owner's**, `qa.md` §7)

Date: 2026-09-19

Type: **development tooling** — not a product feature. No business rule, API contract, database schema,
shared contract or product behaviour changes.

Business rules touched: `BR-001`, `BR-007`, `BR-018`, `BR-028`, `BR-042` (all respected, none changed)

## Why

Switching between the Manager and the Technician audience during device QA meant typing a seeded credential
by hand every time, on a phone keyboard. The request was for two temporary buttons that do it in one tap.

## What it adds

The Android sign-in screen shows a small **Development account** section with one button per seeded account
(`make seed`, `docs/development/setup.md` §3) when — and only when — the build carries accounts:

| Button                | Account                                       |
| --------------------- | --------------------------------------------- |
| Sign in as Manager    | `manager@servora.test` (default Manager role) |
| Sign in as Technician | `technician@servora.test` (default Tech role) |

Each button prefills the form and calls the ordinary submit path, so the attempt is an ordinary sign-in:
real credentials, the real `POST /auth/sign-in`, the same validation, failure handling and session
establishment (`BR-018`, `BR-001`). It is a shortcut, **never an authentication bypass** (`BR-007`).
The password is never rendered; only the address is shown, to tell the accounts apart.

## The design decision: how "debug only" is guaranteed

| Candidate | Why it was or was not used |
| --------- | -------------------------- |
| A credential-bearing file in `src/main` plus a `BuildConfig.DEBUG` check at render time | One source set holds the credentials, so the release APK contains them and only a runtime flag keeps them off the screen. One forgotten `if` is the whole guarantee. **Not used.** |
| **`src/debug` holds the accounts; `src/release` declares the same type with an empty list** | The sign-in screen renders the section only when the list is non-empty, so a release build has nothing to render. The guarantee is a property of the build type, not of a flag. **Used.** |
| Reading the credentials from `local.properties` through `buildConfigField` | Keeps the passwords out of git, but adds build-script surface for a temporary helper. Rejected by the product owner in favour of the simpler source-set variant above. |

## The recorded exception: the passwords are in git

`dev.md` §5 and `Project.md` §16 forbid committing credentials, and this change commits two. They are the
local seed passwords the git-ignored `.env` already holds, they authenticate nothing but a local database,
and the accounts use the reserved `.test` suffix (RFC 2606), so they can never reach a real inbox or a real
deployment. The product owner chose this knowingly for a tool that is faster to use than to configure.

Scope of the exception: `android/app/src/debug/java/com/servora/android/ui/signin/DevSignInAccounts.kt`
**only**. No other file may carry a credential, and a real credential must never be put there.

## Removal

The helper is temporary. Removing it means deleting the pieces below and nothing else; no other code depends
on the accounts once the section is gone.

| # | File | What to remove |
| - | ---- | -------------- |
| 1 | `src/debug/java/…/ui/signin/DevSignInAccounts.kt` | the file |
| 2 | `src/release/java/…/ui/signin/DevSignInAccounts.kt` | the file |
| 3 | `src/main/java/…/ui/signin/DevSignInAccount.kt` | the file (`DevSignInRole`, `DevSignInAccount`) |
| 4 | `SignInScreen.kt`, `SignInViewModel.kt` | the `devAccounts`/`onDevSignIn` parameters, the `DevSignInSection` call, `DevSignInSection` and `DevSignInRole.label`; `onDevSignIn` |
| 5 | tests, strings, docs | `src/test/…/DevSignInAccountsTest.kt`, `src/androidTest/…/DevSignInButtonsTest.kt`, the two dev tests in `SignInViewModelTest.kt`, the three `dev_sign_in_*` strings in both `strings.xml` files, the `docs/development/setup.md` §3 subsection, and this entry |

## Files

| File | Change |
| ---- | ------ |
| `android/app/src/main/java/com/servora/android/ui/signin/DevSignInAccount.kt` | new — `DevSignInRole`, `DevSignInAccount` (the shared types; no credentials) |
| `android/app/src/debug/java/com/servora/android/ui/signin/DevSignInAccounts.kt` | new — the two seeded accounts (debug builds only) |
| `android/app/src/release/java/com/servora/android/ui/signin/DevSignInAccounts.kt` | new — the same type with an empty list |
| `android/app/src/main/java/com/servora/android/ui/signin/SignInViewModel.kt` | `onDevSignIn(email, password)` — prefills, then the ordinary submit path |
| `android/app/src/main/java/com/servora/android/ui/signin/SignInScreen.kt` | `devAccounts` + `onDevSignIn` parameters, and the section that renders only when accounts exist |
| `android/app/src/main/res/values/strings.xml`, `values-fr/strings.xml` | three dev labels, English and French (`BR-028`) |
| `android/app/src/test/java/com/servora/android/ui/signin/DevSignInAccountsTest.kt` | new — what the debug source set offers |
| `android/app/src/test/java/com/servora/android/ui/signin/SignInViewModelTest.kt` | one-step dev sign-in; a dev tap during an attempt changes nothing |
| `android/app/src/androidTest/java/com/servora/android/ui/auth/DevSignInButtonsTest.kt` | new — the section's rendering rules (device-only; the product owner runs it) |
| `docs/development/setup.md` | §3 subsection: what the buttons are, and that they are local tooling |

`AuthFlowScreen` needed no change: it calls the stateful `SignInScreen` overload, which wires
`onDevSignIn` to the ViewModel itself.

## Verification

| Check | Result |
| ----- | ------ |
| `./gradlew compileDebugKotlin` and `./gradlew compileReleaseKotlin` | **PASS** — both variants compile, so the debug/release source-set split breaks neither build |
| `./gradlew assembleDebug` | **PASS** — `app/build/outputs/apk/debug/app-debug.apk` (18 MB) |
| `./gradlew testDebugUnitTest --tests 'com.servora.android.ui.signin.*'` | **PASS** — `DevSignInAccountsTest` 3 tests, `SignInViewModelTest` 14 tests, 0 failures, 0 errors |
| `./gradlew assembleDebugAndroidTest` | **PASS** — the device-test sources compile, `DevSignInButtonsTest` included (`qa.md` §7.3) |
| `./gradlew lintDebug` | **DEFERRED — the product owner's machine cannot run Android lint.** `qa.md` §3.1 defers the full lint of an affected application to feature completion; run `make android-lint` with `make android-test` when the machine allows |
| `DevSignInButtonsTest` on a device | **NOT RUN — device QA is the product owner's.** `./gradlew connectedDebugAndroidTest` from `android/` |
