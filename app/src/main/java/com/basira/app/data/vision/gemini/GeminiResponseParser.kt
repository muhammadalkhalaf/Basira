package com.basira.app.data.vision.gemini

import com.basira.core.coroutines.BasiraConstants
import com.basira.core.error.AppError
import com.basira.core.reporting.ErrorDomain
import com.basira.core.reporting.ErrorReport
import com.basira.core.reporting.ErrorReporter
import com.basira.core.reporting.ErrorSeverity
import com.basira.core.result.AppResult
import com.basira.domain.model.Confidence
import javax.inject.Inject
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Validated answer extracted from a Gemini interaction.
 *
 * @property description description in the requested language; may be empty (handled as "nothing recognized").
 * @property confidence HIGH, MEDIUM, or LOW; never [Confidence.UNKNOWN].
 * @property warnings short warnings.
 */
data class GeminiAnswer(
    val description: String,
    val confidence: Confidence,
    val warnings: List<String>,
) {
    /** Never prints the description. */
    override fun toString(): String = "GeminiAnswer(confidence=$confidence, chars=${description.length})"
}

/**
 * Validates a 2xx Gemini `Interaction` and extracts the structured answer.
 *
 * Rejected (never spoken): application-level errors, safety blocks, `incomplete` (truncated) or other
 * non-completed statuses, missing `model_output` text, non-JSON or schema-violating text, unknown
 * confidence values, and oversized fields. Every rejection is reported with the reason it was rejected
 * for, because the HTTP call itself succeeded and nothing else would ever see it.
 *
 * @property errorMapper maps provider error codes.
 * @property errorReporter where rejected answers are reported.
 */
class GeminiResponseParser @Inject constructor(
    private val errorMapper: GeminiErrorMapper,
    private val errorReporter: ErrorReporter,
) {

    private val answerJson = Json { ignoreUnknownKeys = true }

    /**
     * @param response decoded `Interaction`.
     * @return the validated answer or a typed failure.
     */
    fun parse(response: GeminiInteractionResponse): AppResult<GeminiAnswer> {
        response.error?.let { return reject(errorMapper.fromApiError(it), "api_error_${it.normalizedCode}", it.message) }
        val recorded = response.errors.orEmpty()
        recorded.firstOrNull { it.normalizedCode in GeminiErrorMapper.BLOCKED_CODES }?.let {
            return reject(AppError.ContentBlocked, "blocked_${it.normalizedCode}", it.message)
        }
        when (response.status) {
            null, STATUS_COMPLETED -> Unit
            STATUS_FAILED -> {
                val first = recorded.firstOrNull()
                return reject(first?.let(errorMapper::fromApiError) ?: AppError.ServerError(500), "status_failed", first?.message)
            }
            else -> return reject(AppError.InvalidServerResponse, "status_${response.status}")
        }
        val text = outputText(response) ?: return reject(AppError.InvalidServerResponse, "no_output_text")
        return parseAnswer(text)
    }

    /**
     * Joins the text blocks of the last `model_output` step, mirroring the SDK `output_text` rule.
     *
     * @return the text, or `null` when there is none.
     */
    private fun outputText(response: GeminiInteractionResponse): String? {
        val step = response.steps.orEmpty().lastOrNull { it.type == STEP_MODEL_OUTPUT } ?: return null
        val text = step.content.orEmpty().filter { it.type == CONTENT_TEXT }.mapNotNull { it.text }.joinToString("")
        return text.takeIf { it.isNotBlank() }
    }

    /**
     * Strictly parses the JSON answer. A Markdown code fence is tolerated; anything else around the
     * object is rejected.
     */
    internal fun parseAnswer(raw: String): AppResult<GeminiAnswer> {
        val text = raw.trim().removeSurrounding("```json", "```").removeSurrounding("```", "```").trim()
        if (!text.startsWith("{") || !text.endsWith("}")) return reject(AppError.InvalidServerResponse, "not_a_json_object")
        val answer = try {
            answerJson.decodeFromString(GeminiStructuredAnswer.serializer(), text)
        } catch (e: SerializationException) {
            return reject(AppError.InvalidServerResponse, "schema_violation", throwable = e)
        } catch (e: IllegalArgumentException) {
            return reject(AppError.InvalidServerResponse, "schema_violation", throwable = e)
        }
        val confidence = when (answer.confidence.trim().uppercase()) {
            "HIGH" -> Confidence.HIGH
            "MEDIUM" -> Confidence.MEDIUM
            "LOW" -> Confidence.LOW
            else -> return reject(AppError.InvalidServerResponse, "unknown_confidence", "confidence=${answer.confidence}")
        }
        val valid = answer.description.length <= BasiraConstants.MAX_DESCRIPTION_CHARS &&
            answer.warnings.size <= MAX_WARNINGS &&
            answer.warnings.all { it.length <= MAX_WARNING_CHARS }
        if (!valid) {
            return reject(
                AppError.InvalidServerResponse,
                "oversized",
                "description=${answer.description.length} chars, warnings=${answer.warnings.size}",
            )
        }
        return AppResult.Success(GeminiAnswer(answer.description.trim(), confidence, answer.warnings))
    }

    /** Reports why an answer was rejected and returns the failure the user hears. */
    private fun reject(
        error: AppError,
        reason: String,
        message: String? = null,
        throwable: Throwable? = null,
    ): AppResult.Failure {
        errorReporter.report(
            ErrorReport(
                domain = ErrorDomain.VISION,
                operation = "gemini.answer",
                severity = if (error == AppError.ContentBlocked) ErrorSeverity.WARNING else ErrorSeverity.ERROR,
                outcome = "error_announced",
                message = message,
                throwable = throwable,
                reason = reason,
                attributes = mapOf("app.error" to error.toString()),
            ),
        )
        return AppResult.Failure(error)
    }

    private companion object {
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
        const val STEP_MODEL_OUTPUT = "model_output"
        const val CONTENT_TEXT = "text"
        const val MAX_WARNINGS = 10
        const val MAX_WARNING_CHARS = 300
    }
}
