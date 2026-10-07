package com.basira.domain.model

/** Kind of visual assistance requested from the vision provider. */
enum class AnalysisMode {
    /** General description of the scene with safety-critical information first. */
    SCENE_DESCRIPTION,

    /** Faithful transcription of visible text. */
    READ_TEXT,

    /** Whether a named object is visible and roughly where. */
    FIND_OBJECT,

    /** Banknote denomination, reported only when the model is confident. */
    CURRENCY,
}

/** Preferred length of spoken descriptions. */
enum class Verbosity {
    /** One to three short sentences. */
    SHORT,

    /** A fuller description, still ordered by safety relevance. */
    DETAILED,
}

/** Confidence reported by the vision provider for a description. */
enum class Confidence {
    /** The model is confident in the description. */
    HIGH,

    /** The description is plausible but parts may be wrong. */
    MEDIUM,

    /** The description is a guess and must be presented as uncertain. */
    LOW,

    /** The vision provider did not provide a recognized confidence value. */
    UNKNOWN,
}

/**
 * Upload-ready still image captured from the glasses.
 *
 * The bytes are a re-encoded JPEG without EXIF metadata, already oriented and resized by the data
 * layer. This is deliberately not a data class so the bytes never appear in `toString()` output.
 *
 * @property jpegBytes encoded JPEG payload.
 * @property widthPx width of the encoded image in pixels.
 * @property heightPx height of the encoded image in pixels.
 */
class CapturedImage(
    val jpegBytes: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
) {
    /** Returns a log-safe summary that never includes image content. */
    override fun toString(): String = "CapturedImage(${widthPx}x$heightPx, ${jpegBytes.size} bytes)"
}

/**
 * Provider-independent analysis request sent to the vision provider.
 *
 * @property image upload-ready image.
 * @property mode requested assistance mode.
 * @property targetObject object name for [AnalysisMode.FIND_OBJECT]; `null` otherwise.
 * @property language BCP-47 language of the expected response; the app language.
 * @property verbosity preferred response length.
 * @property requestId unique identifier used for correlation and idempotency on the server.
 */
data class VisionAnalysisRequest(
    val image: CapturedImage,
    val mode: AnalysisMode,
    val targetObject: String?,
    val language: String,
    val verbosity: Verbosity,
    val requestId: String,
)

/**
 * Validated description ready to be spoken.
 *
 * @property requestId identifier of the request that produced this description.
 * @property text text in the app language, suitable for speech.
 * @property confidence provider-reported confidence.
 * @property warnings short safety or quality warnings reported by the vision provider.
 * @property processingTimeMillis server processing time, if reported.
 * @property mode mode that produced this description.
 * @property targetObject searched object for [AnalysisMode.FIND_OBJECT].
 * @property createdAtMillis wall-clock time when the description was received.
 */
data class SceneDescription(
    val requestId: String,
    val text: String,
    val confidence: Confidence,
    val warnings: List<String>,
    val processingTimeMillis: Long?,
    val mode: AnalysisMode,
    val targetObject: String?,
    val createdAtMillis: Long,
) {
    /** Returns a log-safe summary that never includes the description text. */
    override fun toString(): String =
        "SceneDescription(requestId=$requestId, mode=$mode, confidence=$confidence, chars=${text.length})"
}

/** Outcome of [com.basira.domain.repository.VisionAnalysisRepository.analyze]. */
sealed interface VisionAnalysisResult {

    /**
     * The vision provider returned a structurally valid response.
     *
     * @property requestId echo of the request identifier.
     * @property description description in the requested language; may be blank when nothing was recognized.
     * @property confidence reported confidence.
     * @property warnings reported warnings.
     * @property processingTimeMillis reported processing time.
     */
    data class Success(
        val requestId: String,
        val description: String,
        val confidence: Confidence,
        val warnings: List<String>,
        val processingTimeMillis: Long?,
    ) : VisionAnalysisResult {
        /** Returns a log-safe summary that never includes the description text. */
        override fun toString(): String =
            "Success(requestId=$requestId, confidence=$confidence, chars=${description.length})"
    }

    /**
     * The request failed.
     *
     * @property error typed failure.
     */
    data class Failure(val error: com.basira.core.error.AppError) : VisionAnalysisResult
}
