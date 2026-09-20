package com.servora.android.ui.signin

/**
 * The default foundation role a local development account signs in as (`BR-003`).
 *
 * It is a stable code rather than a display name: the label a screen shows is resolved from a
 * string resource in the language the app is running in (`BR-028`, `Project.md` §10), so the same
 * account reads correctly in English and French.
 */
enum class DevSignInRole {
    MANAGER,
    TECHNICIAN,
}

/**
 * One local development account the sign-in screen can offer as a one-tap shortcut.
 *
 * This is development tooling, not product behaviour (`BR-042`). The account it describes is a
 * seeded `.test` account in a local database (`docs/development/setup.md` §3), and the button it
 * drives submits those real credentials to the real sign-in endpoint — a shortcut, never an
 * authentication bypass (`BR-007`, `BR-018`).
 */
data class DevSignInAccount(
    val role: DevSignInRole,
    val email: String,
    val password: String,
)
