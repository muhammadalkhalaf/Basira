package com.basira.app.controllers.reporting

import com.basira.core.reporting.ErrorDomain
import com.basira.core.reporting.ErrorReport
import com.basira.core.reporting.ErrorReporter
import com.basira.core.reporting.ErrorSeverity
import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Reports every call that did not deliver an answer, for whichever client it is installed on.
 *
 * This is what makes the network reporting consistent: a call is reported the same way whichever
 * repository made it, and a new endpoint is covered the day it is added without anybody remembering to
 * report it. Callers stay responsible for what the user hears, not for what the dashboard receives.
 *
 * Install it as the first application interceptor, so it sees the result of the call after OkHttp
 * finished and reports one failure per call rather than one per network attempt:
 * ```
 * builder.addInterceptor(ErrorReportingInterceptor("gemini", errorReporter))
 * ```
 *
 * Whether a failure is actually sent is decided by the reporter: a failure of the connection rather
 * than of the backend only leaves a breadcrumb.
 *
 * @param clientName the backend this client talks to, for example `gemini`. It becomes the first part
 *   of the operation, so two backends answering the same path stay apart in the dashboard.
 * @property reporter where the failures go.
 */
class ErrorReportingInterceptor(
    clientName: String,
    private val reporter: ErrorReporter,
) : Interceptor {

    private val clientName = clientName.ifBlank { "http" }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startedAtNanos = System.nanoTime()
        val response = try {
            chain.proceed(request)
        } catch (failure: IOException) {
            // The call still fails for the caller: this only records why before handing it on.
            reportFailedCall(request, failure, elapsedMillis(startedAtNanos))
            throw failure
        } catch (failure: RuntimeException) {
            reportFailedCall(request, failure, elapsedMillis(startedAtNanos))
            throw failure
        }
        if (!response.isSuccessful) reportUnsuccessfulResponse(request, response, elapsedMillis(startedAtNanos))
        return response
    }

    /**
     * Reports an answer that arrived with a status code the app cannot use. The status code decides
     * the kind and the severity, and the start of the error body is the message, because that is
     * where Gemini says what it refused and why.
     */
    private fun reportUnsuccessfulResponse(request: Request, response: Response, durationMillis: Long) {
        val kind = NetworkErrorClassifier.classifyHttpStatus(response.code)
        reporter.report(
            ErrorReport(
                domain = ErrorDomain.NETWORK,
                operation = operationOf(request),
                severity = kind.severity,
                outcome = OUTCOME,
                message = peekErrorBody(response) ?: "HTTP ${response.code}",
                networkFailureKind = kind,
                attributes = commonAttributes(request, durationMillis) + mapOf(
                    "http.status" to response.code.toString(),
                    "http.protocol" to response.protocol.toString(),
                ),
            ),
        )
    }

    /** Reports a call that never got an answer; the reporter classifies the throwable. */
    private fun reportFailedCall(request: Request, failure: Exception, durationMillis: Long) {
        reporter.report(
            ErrorReport(
                domain = ErrorDomain.NETWORK,
                operation = operationOf(request),
                severity = ErrorSeverity.ERROR,
                outcome = OUTCOME,
                throwable = failure,
                attributes = commonAttributes(request, durationMillis),
            ),
        )
    }

    private fun commonAttributes(request: Request, durationMillis: Long): Map<String, String> = mapOf(
        "http.method" to request.method,
        "http.host" to request.url.host,
        "http.url" to request.url.toString(),
        "http.duration_ms" to durationMillis.toString(),
    )

    /**
     * Reads the start of the error body without consuming it, so the caller can still parse it.
     * Returns `null` when it cannot be read; reporting must never fail the call.
     */
    private fun peekErrorBody(response: Response): String? = try {
        response.peekBody(MAX_ERROR_BODY_BYTES).string().trim().ifEmpty { null }
    } catch (_: IOException) {
        null
    }

    /**
     * Names the call in a way that stays the same across sessions. This is the key the report is
     * grouped by, so one endpoint is one issue in Crashlytics. The query is left out because it is
     * not part of which endpoint was called.
     */
    private fun operationOf(request: Request): String = "$clientName ${request.method} ${request.url.encodedPath}"

    private fun elapsedMillis(startedAtNanos: Long): Long = (System.nanoTime() - startedAtNanos) / 1_000_000L

    private companion object {
        /** The caller maps the failure to a typed error and the user hears it. */
        const val OUTCOME = "call_failed"

        /** Crashlytics keeps 1 KB per value, which is more than the first line of any error body. */
        const val MAX_ERROR_BODY_BYTES = 1_024L
    }
}
