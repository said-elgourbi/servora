package com.servora.android.ui.signin

/**
 * The release counterpart of the development sign-in accounts: none.
 *
 * A release build carries no account and therefore renders no dev sign-in section at all
 * (`docs/tracker/049-android-dev-sign-in-buttons.md`). The type exists so the shared sign-in screen
 * compiles against one definition per build type, and so "no development shortcut in a release
 * build" is a compile-time property of the source set rather than a flag checked at runtime.
 */
object DevSignInAccounts {

    /** Always empty: a release build offers no development account. */
    val available: List<DevSignInAccount> = emptyList()
}
