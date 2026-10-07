package com.basira.core.coroutines

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Injectable source of coroutine dispatchers so production code never hard-codes
 * [kotlinx.coroutines.Dispatchers] and tests can substitute a test dispatcher.
 */
interface DispatcherProvider {
    /** Dispatcher bound to the UI thread. */
    val main: CoroutineDispatcher

    /** Dispatcher for blocking I/O such as disk and network access. */
    val io: CoroutineDispatcher

    /** Dispatcher for CPU-bound work such as image decoding and compression. */
    val default: CoroutineDispatcher
}

/**
 * Qualifies the application-wide [kotlinx.coroutines.CoroutineScope] that outlives screens and is
 * used by components such as the assistant engine and the foreground service.
 */
@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
