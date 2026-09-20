package com.servora.android.ui.signin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the temporary development sign-in shortcut offers in a build that carries accounts
 * (`docs/tracker/049-android-dev-sign-in-buttons.md`).
 *
 * These assertions run against the `debug` source set, which is the one `testDebugUnitTest`
 * compiles. The `release` counterpart declares the same type with an empty list, so "no development
 * account in a release build" is a property of the build type rather than of this test
 * (`DevSignInScreen.kt` — the screen renders the section only when the list is non-empty).
 */
class DevSignInAccountsTest {

    @Test
    fun `offers one account per default foundation role`() {
        assertEquals(
            listOf(DevSignInRole.MANAGER, DevSignInRole.TECHNICIAN),
            DevSignInAccounts.available.map { it.role },
        )
    }

    @Test
    fun `offers the seeded development addresses`() {
        // The addresses `make seed` creates (`docs/development/setup.md` §3). They are not secrets:
        // the password is the part held outside this assertion.
        assertEquals(
            listOf("manager@servora.test", "technician@servora.test"),
            DevSignInAccounts.available.map { it.email },
        )
    }

    @Test
    fun `offers a usable credential for every account it shows`() {
        // A shortcut with a blank field would be a button that cannot sign in, so the build only
        // offers accounts it can actually use.
        DevSignInAccounts.available.forEach { account ->
            assertTrue("${account.role} needs an address", account.email.isNotBlank())
            assertTrue("${account.role} needs a password", account.password.isNotBlank())
        }
    }
}
