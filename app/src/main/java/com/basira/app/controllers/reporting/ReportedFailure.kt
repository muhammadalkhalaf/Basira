package com.basira.app.controllers.reporting

import com.basira.core.reporting.ErrorReport
import com.basira.core.reporting.rootCause

/**
 * The throwable every report is recorded as.
 *
 * Crashlytics groups non-fatals by the exception type and the frame it blames, and neither says
 * anything useful about a failed call: a 503 and a 404 from Gemini are both answered inside OkHttp,
 * and a timeout is an `IOException` thrown somewhere in the same place, so they would all land in one
 * issue nobody can act on.
 *
 * This puts one synthesised frame on top of the real stack, naming the domain, the operation, and
 * what went wrong. That frame is what Crashlytics blames, so every operation gets its own issue and a
 * 503 never hides behind a timeout. The frame reads as
 * `com.basira.app.network.gemini_POST_v1beta_interactions.http_server_error`, and it has no source
 * file because it is a label rather than a place in the code.
 *
 * Everything the failure knows is kept: the real frames follow the synthesised one, and the caught
 * throwable stays attached as the cause, so the dashboard still shows its type and where it was thrown.
 */
class ReportedFailure(report: ErrorReport) : RuntimeException(report.describe(), report.throwable) {

    init {
        stackTrace = buildStackTrace(report)
    }

    /** Puts the frame that names this failure on top of the frames that say where it happened. */
    private fun buildStackTrace(report: ErrorReport): Array<StackTraceElement> {
        val realFrames = report.throwable?.stackTrace ?: trimPlumbing(stackTrace)
        return arrayOf(namingFrame(report)) + realFrames
    }

    private companion object {
        /**
         * The synthesised frame is written under the app package, so Crashlytics treats it as app code
         * and blames it instead of looking further down for something familiar.
         */
        const val FRAME_PACKAGE = "com.basira.app."

        /** What a failure is discriminated by when it has no kind, reason, or throwable. */
        const val DEFAULT_DISCRIMINATOR = "failure"

        /** Keeps a long operation from turning into an unreadable frame. */
        const val MAX_FRAME_SEGMENT_LENGTH = 120

        /**
         * The classes whose frames say nothing about where the failure happened. Only the plumbing is
         * dropped: a caller in this package, such as [ErrorReportingInterceptor], keeps its frames.
         */
        val PLUMBING_CLASSES = setOf(CrashlyticsErrorReporter::class.java.name, ReportedFailure::class.java.name)

        /**
         * Builds the frame Crashlytics groups this failure by. The class names the operation, so one
         * operation is one issue; the method names what went wrong with it, so the same endpoint timing
         * out and answering 500 stay apart.
         */
        fun namingFrame(report: ErrorReport): StackTraceElement = StackTraceElement(
            FRAME_PACKAGE + report.domain.key + "." + toFrameSegment(report.operation),
            toFrameSegment(discriminator(report)),
            null,
            -1,
        )

        /** Prefers the network kind, then the caller's reason, then the type of the deepest cause. */
        fun discriminator(report: ErrorReport): String =
            report.networkFailureKind?.key
                ?: report.reason
                ?: report.throwable?.rootCause()?.let { it::class.java.simpleName }
                ?: DEFAULT_DISCRIMINATOR

        /**
         * Rewrites an operation so it can be read as one frame segment:
         * `gemini POST /v1beta/interactions` becomes `gemini_POST_v1beta_interactions`.
         */
        fun toFrameSegment(value: String): String {
            val segment = StringBuilder(value.length)
            var afterSeparator = true
            for (character in value) {
                if (segment.length >= MAX_FRAME_SEGMENT_LENGTH) break
                if (character.isLetterOrDigit()) {
                    segment.append(character)
                    afterSeparator = false
                } else if (!afterSeparator) {
                    segment.append('_')
                    afterSeparator = true
                }
            }
            return segment.toString().trimEnd('_').ifEmpty { "unnamed" }
        }

        /**
         * Drops the reporting plumbing from the top of the captured stack. Only used when nothing was
         * thrown, so the frames under the synthesised one start at the caller that noticed the failure.
         */
        fun trimPlumbing(frames: Array<StackTraceElement>): Array<StackTraceElement> =
            frames.dropWhile { it.className in PLUMBING_CLASSES }.toTypedArray()
    }
}
