package com.servora.android.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The language the chrome reports is resolved from the locale the platform is applying. The
 * resolution rule matters because the app ships two languages and the platform can report any
 * (`BR-028`, `docs/decisions/007-android-appearance-controls.md`).
 */
class AppLanguageTest {

    @Test
    fun `resolves the languages the app ships`() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLanguageTag("en"))
        assertEquals(AppLanguage.FRENCH, AppLanguage.fromLanguageTag("fr"))
    }

    @Test
    fun `resolves a platform tag case-insensitively`() {
        assertEquals(AppLanguage.FRENCH, AppLanguage.fromLanguageTag("FR"))
    }

    @Test
    fun `falls back to English for a language the app does not ship`() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLanguageTag("es"))
    }

    @Test
    fun `falls back to English when the platform reports no language`() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLanguageTag(null))
    }

    @Test
    fun `hands the platform the stable tags the resources are keyed by`() {
        assertEquals(listOf("en", "fr"), AppLanguage.entries.map { it.languageTag })
        assertNull(AppLanguage.entries.firstOrNull { it.languageTag.isBlank() })
    }
}
