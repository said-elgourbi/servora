package com.servora.android.ui.components

/**
 * The initials an avatar shows for a name.
 *
 * An avatar exists as the fallback for a missing photo (`BR-020`), so it is derived from the name
 * rather than left blank: the first letters of the first two words, uppercased, and `?` when the name
 * this build received is empty. It lives here because more than one feature draws one — a customer
 * and a technician are both presented with their initials.
 */
internal fun initials(name: String): String =
    name.split(" ")
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercaseChar().toString() }
        .ifBlank { "?" }
