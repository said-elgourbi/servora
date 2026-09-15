package com.servora.android.data.offline

/**
 * Where a read was served from (`docs/architecture/offline-first-architecture.md` §2).
 *
 * Values from the working set are the last the backend reported, never a current answer: a screen
 * says so rather than presenting a local copy as up to date (`§7`, `§10`).
 */
enum class ReadSource {
    /** The backend answered this read. */
    BACKEND,

    /** The API could not be reached, so the last answer it reported is being shown. */
    WORKING_SET,
}
