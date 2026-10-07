package com.basira.app.data.vision.gemini

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// DTOs for `POST v1beta/interactions` following the official Interactions API reference
// (https://ai.google.dev/api/interactions-api), new `steps` schema (Api-Revision 2026-05-20).
// They never leave the data layer.

/**
 * Request body of `interactions.create` for a model interaction.
 *
 * Properties without defaults are always serialized (the app's JSON does not encode defaults).
 *
 * @property model model id.
 * @property input prompt text and inline image, in one turn.
 * @property systemInstruction safety and behavior instruction.
 * @property responseFormat structured JSON output configuration.
 * @property generationConfig generation parameters.
 * @property store `false` opts out of server-side storage of the interaction (and the image).
 */
@Serializable
data class GeminiInteractionRequest(
    @SerialName("model") val model: String,
    @SerialName("input") val input: List<GeminiInputContent>,
    @SerialName("system_instruction") val systemInstruction: String,
    @SerialName("response_format") val responseFormat: GeminiResponseFormat,
    @SerialName("generation_config") val generationConfig: GeminiGenerationConfig,
    @SerialName("store") val store: Boolean,
)

/**
 * One input content block: `{"type":"text","text":…}` or
 * `{"type":"image","data":<base64>,"mime_type":"image/jpeg"}`.
 *
 * @property type content type.
 * @property text text for `text` blocks.
 * @property data base64 bytes for `image` blocks.
 * @property mimeType MIME type for `image` blocks.
 */
@Serializable
data class GeminiInputContent(
    @SerialName("type") val type: String,
    @SerialName("text") val text: String? = null,
    @SerialName("data") val data: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
) {
    /** Never prints the image data. */
    override fun toString(): String = "GeminiInputContent(type=$type, chars=${(text ?: data)?.length ?: 0})"
}

/**
 * `TextResponseFormat`: JSON output constrained by [schema].
 *
 * @property type always `text`.
 * @property mimeType `application/json`.
 * @property schema JSON schema of the answer.
 */
@Serializable
data class GeminiResponseFormat(
    @SerialName("type") val type: String,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("schema") val schema: JsonObject,
)

/**
 * `GenerationConfig` subset.
 *
 * @property thinkingLevel `low`, `medium`, or `high` (`minimal` is rejected by Gemini 3.8 Flash).
 * @property maxOutputTokens output token budget; thinking also consumes output tokens.
 */
@Serializable
data class GeminiGenerationConfig(
    @SerialName("thinking_level") val thinkingLevel: String,
    @SerialName("max_output_tokens") val maxOutputTokens: Int,
)

/**
 * Response `Interaction` resource. Unknown fields are ignored; every field is optional so that a
 * missing field becomes a validation failure instead of a crash.
 *
 * @property id interaction id.
 * @property status `completed`, `incomplete`, `failed`, …
 * @property steps output steps (`model_output`, `thought`, …).
 * @property errors diagnostic faults recorded on the interaction.
 * @property error application-level error, if a 2xx body carries one.
 */
@Serializable
data class GeminiInteractionResponse(
    @SerialName("id") val id: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("steps") val steps: List<GeminiStep>? = null,
    @SerialName("errors") val errors: List<GeminiApiError>? = null,
    @SerialName("error") val error: GeminiApiError? = null,
)

/**
 * One step of the interaction timeline.
 *
 * @property type step type, `model_output` for the answer.
 * @property content content blocks.
 */
@Serializable
data class GeminiStep(
    @SerialName("type") val type: String? = null,
    @SerialName("content") val content: List<GeminiOutputContent>? = null,
)

/**
 * Output content block.
 *
 * @property type block type; only `text` is used.
 * @property text generated text (the structured JSON answer).
 */
@Serializable
data class GeminiOutputContent(
    @SerialName("type") val type: String? = null,
    @SerialName("text") val text: String? = null,
) {
    /** Never prints generated text. */
    override fun toString(): String = "GeminiOutputContent(type=$type, chars=${text?.length ?: 0})"
}

/**
 * Error body `{"error": {...}}` returned with non-2xx statuses.
 *
 * @property error error details.
 */
@Serializable
data class GeminiErrorEnvelope(
    @SerialName("error") val error: GeminiApiError? = null,
)

/**
 * Error details. Interactions errors use a `snake_case` string [code]; older Google API errors use a
 * numeric code plus an upper-case [status], so [code] is kept as raw JSON.
 *
 * @property code machine-readable code.
 * @property message developer-facing message; never shown or spoken.
 * @property status legacy canonical status, for example `RESOURCE_EXHAUSTED`.
 */
@Serializable
data class GeminiApiError(
    @SerialName("code") val code: JsonElement? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("status") val status: String? = null,
) {
    /** Lower-case machine code, from [code] when it is a string, else from [status]. */
    val normalizedCode: String?
        get() = (code as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase()
            ?: status?.lowercase()

    /** Never prints the message, which may echo request content. */
    override fun toString(): String = "GeminiApiError(code=$normalizedCode)"
}

/**
 * The structured answer requested from the model (the text of the `model_output` step).
 *
 * @property description Arabic description for speech.
 * @property confidence HIGH, MEDIUM, or LOW.
 * @property warnings short Arabic warnings.
 */
@Serializable
data class GeminiStructuredAnswer(
    @SerialName("description") val description: String,
    @SerialName("confidence") val confidence: String,
    @SerialName("warnings") val warnings: List<String> = emptyList(),
) {
    /** Never prints the description. */
    override fun toString(): String = "GeminiStructuredAnswer(confidence=$confidence, chars=${description.length})"
}
