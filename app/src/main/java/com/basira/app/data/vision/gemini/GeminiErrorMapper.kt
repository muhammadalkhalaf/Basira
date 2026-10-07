package com.basira.app.data.vision.gemini

import com.basira.core.error.AppError
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.net.ssl.SSLException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Classifies transport failures, HTTP statuses, and Gemini error codes
 * (https://ai.google.dev/gemini-api/docs/api-errors) into [AppError] values.
 *
 * Provider messages are never surfaced; only the typed error leaves this class.
 *
 * @property json parser for the error envelope.
 * @property nowProvider clock used to resolve HTTP-date `Retry-After` values.
 */
class GeminiErrorMapper(
    private val json: Json,
    private val nowProvider: () -> ZonedDateTime = { ZonedDateTime.now() },
) {

    /**
     * Maps a non-2xx response.
     *
     * @param httpCode HTTP status code.
     * @param errorBody raw body (JSON error envelope, HTML, or empty).
     * @param retryAfterHeader `Retry-After` header value, if any.
     * @return the typed error.
     */
    fun fromHttp(httpCode: Int, errorBody: String?, retryAfterHeader: String?): AppError {
        val error = parseEnvelope(errorBody)
        val code = error?.normalizedCode
        val message = error?.message.orEmpty()
        if (code in BLOCKED_CODES) return AppError.ContentBlocked
        return when (httpCode) {
            400 -> when {
                code == "authentication" || message.contains("api key", ignoreCase = true) -> AppError.ApiKeyRejected
                code == "failed_precondition" -> AppError.ServiceNotConfigured
                message.contains("image", ignoreCase = true) || message.contains("mime", ignoreCase = true) ->
                    AppError.InvalidImage
                else -> AppError.Unexpected(fatal = false)
            }
            401, 403 -> AppError.ApiKeyRejected
            402 -> AppError.QuotaExceeded
            404 -> AppError.ServiceNotConfigured
            408 -> AppError.Timeout
            413, 415, 422 -> AppError.InvalidImage
            429 -> if (code == "quota_exceeded") {
                AppError.QuotaExceeded
            } else {
                AppError.RateLimited(parseRetryAfterMillis(retryAfterHeader))
            }
            in 500..599 -> AppError.ServerError(httpCode)
            else -> AppError.Unexpected(fatal = false)
        }
    }

    /**
     * Maps an application-level error carried by a 2xx body or recorded on the interaction.
     *
     * @param error the error.
     * @return the typed error.
     */
    fun fromApiError(error: GeminiApiError): AppError = when (error.normalizedCode) {
        in BLOCKED_CODES -> AppError.ContentBlocked
        "authentication", "permission_denied", "unauthenticated" -> AppError.ApiKeyRejected
        "quota_exceeded", "payment_required" -> AppError.QuotaExceeded
        "rate_limit_exceeded", "too_many_requests", "resource_exhausted" -> AppError.RateLimited(null)
        "deadline_exceeded" -> AppError.Timeout
        "service_unavailable", "unavailable" -> AppError.ServerError(503)
        "model_not_found", "failed_precondition" -> AppError.ServiceNotConfigured
        else -> AppError.InvalidServerResponse
    }

    /**
     * Maps an exception thrown while executing the call.
     *
     * Connect timeouts and DNS or connection failures are transient (retryable); read and call
     * timeouts are [AppError.Timeout] and are not retried because Gemini may still be processing.
     *
     * @param throwable the failure.
     * @return the typed error.
     */
    fun fromThrowable(throwable: Throwable): AppError = when (throwable) {
        is SerializationException, is IllegalArgumentException -> AppError.InvalidServerResponse
        is SocketTimeoutException ->
            if (throwable.message?.contains("connect", ignoreCase = true) == true) AppError.Transport else AppError.Timeout
        is InterruptedIOException -> AppError.Timeout
        is UnknownHostException, is ConnectException, is NoRouteToHostException, is SSLException -> AppError.Transport
        is IOException -> AppError.Transport
        else -> AppError.Unexpected(fatal = false)
    }

    /**
     * Parses `Retry-After` given in seconds or as an HTTP date.
     *
     * @param header raw header value.
     * @return delay in milliseconds, or `null` when absent or unparseable.
     */
    fun parseRetryAfterMillis(header: String?): Long? {
        val value = header?.trim().orEmpty()
        if (value.isEmpty()) return null
        value.toLongOrNull()?.let { return it.coerceAtLeast(0) * 1_000 }
        return try {
            val date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
            Duration.between(nowProvider(), date).toMillis().coerceAtLeast(0)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun parseEnvelope(body: String?): GeminiApiError? {
        if (body.isNullOrBlank()) return null
        return try {
            json.decodeFromString(GeminiErrorEnvelope.serializer(), body).error
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Constants. */
    companion object {
        /** "Generation blocked" codes from the official API errors reference. */
        val BLOCKED_CODES: Set<String> = setOf(
            "safety", "recitation", "language", "prohibited_content", "spii", "blocklist",
            "image_safety", "image_prohibited_content", "image_recitation", "image_other", "content_blocked",
        )
    }
}
