package com.servora.android.domain.model

/**
 * The Canadian province/territory codes a Property address is stored with (`BR-049`).
 *
 * The code is the stable, machine-readable value the API exchanges and the approved design uses; a
 * display label is resolved separately per language and is never the stored or exchanged value
 * (`BR-028`, `BR-041`, `Project.md` §10).
 */
enum class PropertyProvince(val code: String) {
    AB("AB"),
    BC("BC"),
    MB("MB"),
    NB("NB"),
    NL("NL"),
    NS("NS"),
    NT("NT"),
    NU("NU"),
    ON("ON"),
    PE("PE"),
    QC("QC"),
    SK("SK"),
    YT("YT"),
    ;

    companion object {
        /** The province a code names, or `null` when this build does not know it. */
        fun fromCode(code: String): PropertyProvince? =
            entries.firstOrNull { it.code == code }
    }
}
