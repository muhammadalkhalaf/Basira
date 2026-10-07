package com.basira.core.reporting

/**
 * The one way a non-fatal failure reaches the crash dashboard.
 *
 * Every component reports through this instead of calling Crashlytics itself, which is what makes the
 * dashboard usable: the same keys are always present, the same failure is always named the same way,
 * and one broken call in a retry loop cannot bury everything else. The Android implementation lives
 * in `com.basira.app.controllers.reporting`; this interface keeps the domain layer free of Firebase.
 *
 * Reporting never changes what the app does: implementations swallow their own failures.
 */
interface ErrorReporter {

    /**
     * Reports a failure. Never throws.
     *
     * @param report what failed, how badly, and what the app did about it.
     */
    fun report(report: ErrorReport)

    /**
     * Leaves a line in the report log without reporting anything. The last lines are attached to
     * whatever is reported next, which is how a report says what the app was doing before it failed.
     *
     * @param message the line.
     */
    fun breadcrumb(message: String)
}

/** Reporter that discards everything; used by tests and as a safe default. */
object NoOpErrorReporter : ErrorReporter {
    override fun report(report: ErrorReport) = Unit
    override fun breadcrumb(message: String) = Unit
}
