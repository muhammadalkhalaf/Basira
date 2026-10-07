package com.basira.app.data.vision.gemini

import com.basira.core.coroutines.BasiraConstants
import com.basira.core.error.AppError
import com.basira.core.result.AppResult
import com.basira.domain.model.Confidence
import javax.inject.Inject
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Validated answer extracted from a Gemini interaction.
 *
 * @property description Arabic description; may be empty (handled as "nothing recognized").
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
 * confidence values, and oversized fields.
 *
 * @property errorMapper maps provider error codes.
 */
class GeminiResponseParser @Inject constructor(private val errorMapper: GeminiErrorMapper) {

    private val answerJson = Json { ignoreUnknownKeys = true }

    /**
     * @param response decoded `Interaction`.
     * @return the validated answer or a typed failure.
     */
    fun parse(response: GeminiInteractionResponse): AppResult<GeminiAnswer> {
        response.error?.let { return AppResult.Failure(errorMapper.fromApiError(it)) }
        val recorded = response.errors.orEmpty()
        if (recorded.any { it.normalizedCode in GeminiErrorMapper.BLOCKED_CODES }) {
            return AppResult.Failure(AppError.ContentBlocked)
        }
        when (response.status) {
            null, STATUS_COMPLETED -> Unit
            STATUS_FAILED -> return AppResult.Failure(recorded.firstOrNull()?.let(errorMapper::fromApiError) ?: AppError.ServerError(500))
            else -> return AppResult.Failure(AppError.InvalidServerResponse)
        }
        val text = outputText(response) ?: return AppResult.Failure(AppError.InvalidServerResponse)
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
        if (!text.startsWith("{") || !text.endsWith("}")) return AppResult.Failure(AppError.InvalidServerResponse)
        val answer = try {
            answerJson.decodeFromString(GeminiStructuredAnswer.serializer(), text)
        } catch (_: SerializationException) {
            return AppResult.Failure(AppError.InvalidServerResponse)
        } catch (_: IllegalArgumentException) {
            return AppResult.Failure(AppError.InvalidServerResponse)
        }
        val confidence = when (answer.confidence.trim().uppercase()) {
            "HIGH" -> Confidence.HIGH
            "MEDIUM" -> Confidence.MEDIUM
            "LOW" -> Confidence.LOW
            else -> return AppResult.Failure(AppError.InvalidServerResponse)
        }
        val valid = answer.description.length <= BasiraConstants.MAX_DESCRIPTION_CHARS &&
            answer.warnings.size <= MAX_WARNINGS &&
            answer.warnings.all { it.length <= MAX_WARNING_CHARS }
        if (!valid) return AppResult.Failure(AppError.InvalidServerResponse)
        return AppResult.Success(GeminiAnswer(answer.description.trim(), confidence, answer.warnings))
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
