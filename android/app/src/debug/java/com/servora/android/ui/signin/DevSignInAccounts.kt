package com.servora.android.ui.signin

/**
 * The local development accounts a **debug** build offers on the sign-in screen.
 *
 * This is the build-type half of the temporary dev sign-in shortcut recorded in
 * `docs/tracker/049-android-dev-sign-in-buttons.md`. The `release` source set provides the same
 * type with an empty list, so the buttons are absent from a release build **by construction**
 * rather than by a runtime flag somebody could forget to reset.
 *
 * The addresses are the reserved `.test` accounts `make seed` creates, and the passwords are the
 * local values from the git-ignored `.env` (`docs/development/setup.md` §3). They authenticate
 * nothing but a local database. If `SEED_*_PASSWORD` was left empty, `make seed` printed generated
 * values once — paste those here instead.
 */
object DevSignInAccounts {

    /** The accounts this build can sign in as, in the order the screen shows them. */
    val available: List<DevSignInAccount> = listOf(
        DevSignInAccount(
            role = DevSignInRole.MANAGER,
            email = "manager@servora.test",
            password = "Servora!Manager2026",
        ),
        DevSignInAccount(
            role = DevSignInRole.TECHNICIAN,
            email = "technician@servora.test",
            password = "Servora!Tech2026",
        ),
    )
}
