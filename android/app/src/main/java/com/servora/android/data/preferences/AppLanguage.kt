package com.servora.android.data.preferences

/**
 * A language the Android app ships for its own interface (`BR-028`).
 *
 * [languageTag] is the stable identifier handed to the platform; the resources the app draws
 * come from `values/` and `values-fr/`. Display text lives in `strings.xml`, and adding a
 * language stays a translation task rather than a code change (`Project.md` §10).
 */
enum class AppLanguage(val languageTag: String) {
    ENGLISH("en"),
    FRENCH("fr");

    companion object {
        /**
         * Resolves the language the platform is currently applying.
         *
         * The platform reports a bare language code (`en`, `fr`), so anything the app does not
         * ship resolves to [ENGLISH], which is also the resource fallback.
         */
        fun fromLanguageTag(tag: String?): AppLanguage =
            entries.firstOrNull { it.languageTag.equals(tag, ignoreCase = true) } ?: ENGLISH
    }
}
