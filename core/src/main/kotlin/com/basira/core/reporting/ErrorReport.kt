package com.basira.core.reporting

/**
 * One failure, described the same way whoever reports it.
 *
 * Everything a report carries is decided here and nowhere else: which subsystem failed, what it was
 * doing, how much it cost the user, what the app did about it, and whatever else is worth knowing
 * about that one call. The reporter only hands it to Crashlytics.
 *
 * ```
 * errorReporter.report(
 *     ErrorReport(
 *         domain = ErrorDomain.STORAGE,
 *         operation = "imageArchive.save",
 *         severity = ErrorSeverity.WARNING,
 *         outcome = "image_not_saved",
 *         throwable = e,
 *     ),
 * )
 * ```
 *
 * @property domain the subsystem that failed.
 * @property operation what it was doing, named the same way every time it is reported, for example
 *   `gemini POST /v1beta/interactions` or `dat.createSession`. It is what the same failure is
 *   recognised by across sessions and what Crashlytics groups the issue by, so it must not carry
 *   anything that changes per call.
 * @property severity how much it cost the user.
 * @property outcome what the app did about it, in a few snake_case words, for example
 *   `error_announced`, `default_used`, or `image_not_saved`.
 * @property message what went wrong in the app's own words; falls back to the throwable's message.
 * @property throwable what was caught, if anything.
 * @property reason a stable code that separates two failures of the same operation, for example a DAT
 *   error name or a TTS status. Two reasons are two issues in Crashlytics.
 * @property networkFailureKind why a call failed, when the caller already knows.
 * @property attributes other values worth knowing about this one failure (at most [MAX_ATTRIBUTES]).
 */
data class ErrorReport(
    val domain: ErrorDomain,
    val operation: String,
    val severity: ErrorSeverity,
    val outcome: String,
    val message: String? = null,
    val throwable: Throwable? = null,
    val reason: String? = null,
    val networkFailureKind: NetworkFailureKind? = null,
    val attributes: Map<String, String> = emptyMap(),
) {

    /**
     * Names this failure in a way that stays the same every time it happens.
     *
     * It is what the reporter throttles on, so a call that fails in a loop is reported once with a
     * count instead of a hundred times, and it is written to the report so the same signature can be
     * searched for in the dashboard.
     */
    val signature: String
        get() = buildString {
            append(domain.key).append('/').append(operation)
            networkFailureKind?.let { append('/').append(it.key) }
            reason?.let { append('/').append(it) }
            throwable?.let { append('/').append(it.rootCause()::class.java.simpleName) }
        }

    /** The one line written to the Crashlytics breadcrumb log and to Logcat. */
    fun describe(): String = buildString {
        append(severity.key).append(' ').append(domain.key).append(' ').append(operation)
        networkFailureKind?.let { append(" [").append(it.key).append(']') }
        reason?.let { append(" (").append(it).append(')') }
        append(" -> ").append(outcome)
        resolveMessage().takeIf { it.isNotEmpty() }?.let { append(": ").append(it) }
    }

    /** The message of the report, falling back to what the deepest cause of the throwable said. */
    fun resolveMessage(): String {
        if (!message.isNullOrBlank()) return message
        val root = throwable?.rootCause() ?: return ""
        return root.message?.takeIf { it.isNotBlank() } ?: root::class.java.simpleName
    }

    /** Limits. */
    companion object {
        /**
         * The most attributes one report carries. Crashlytics keeps 64 keys per report and the reporter
         * needs room for the ones it adds itself.
         */
        const val MAX_ATTRIBUTES: Int = 24
    }
}

/** How deep a cause chain is followed, so a self-referencing cause can never loop. */
private const val MAX_CAUSE_DEPTH = 10

/** Returns the deepest cause of this throwable, which is the one worth naming in a report. */
fun Throwable.rootCause(): Throwable {
    var current = this
    repeat(MAX_CAUSE_DEPTH) {
        val next = current.cause
        if (next == null || next === current) return current
        current = next
    }
    return current
}
