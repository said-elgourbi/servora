package com.servora.android.di

import javax.inject.Qualifier

/**
 * The application-lifetime coroutine scope.
 *
 * Offline replay runs for as long as the process does — it is triggered by connectivity, session and
 * startup events rather than by a screen — so it needs a scope that outlives every ViewModel
 * (`docs/architecture/offline-first-architecture.md` §6).
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope
