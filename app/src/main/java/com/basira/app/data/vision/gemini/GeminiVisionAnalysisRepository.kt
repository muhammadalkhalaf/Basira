package com.basira.app.data.vision.gemini

import com.basira.core.coroutines.BasiraConstants
import com.basira.core.coroutines.DispatcherProvider
import com.basira.core.error.AppError
import com.basira.core.error.RetryClassifier
import com.basira.core.logging.AppLogger
import com.basira.core.result.AppResult
import com.basira.core.retry.ExponentialBackoff
import com.basira.domain.model.VisionAnalysisRequest
import com.basira.domain.model.VisionAnalysisResult
import com.basira.domain.repository.VisionAnalysisRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.Response

/**
 * [VisionAnalysisRepository] that sends the captured image directly from the phone to the Gemini
 * Interactions API. No application backend is involved.
 *
 * - The image (already oriented, resized, EXIF-free JPEG from the DAT capture path) is validated and
 *   base64-encoded off the main thread, then sent with the mode-specific prompt in one interaction
 *   with `store=false`.
 * - At most one request is in flight; callers queue on a mutex.
 * - Cancelling the calling coroutine cancels the OkHttp call.
 * - At most [ExponentialBackoff.maxAttempts] (two) automatic retries, only for transient connection
 *   failures, HTTP 502/503/504, and HTTP 429 with a short `Retry-After`. Nothing else is retried.
 * - The whole operation, including retries, is bounded by [totalBudgetMillis]; when Gemini is
 *   overloaded and answers 503 only after a long wait, the user hears "timed out" instead of waiting
 *   for several minutes.
 * - A missing key returns [AppError.ServiceNotConfigured] without any network call.
 * - The key, image data, prompts, and answers are never logged.
 *
 * @property api Retrofit service.
 * @property config key, model, and base URL.
 * @property prompts prompt and schema builder.
 * @property parser response validator.
 * @property errorMapper error classifier.
 * @property backoff retry policy.
 * @property base64Encoder encodes JPEG bytes (Android `Base64.NO_WRAP` in production).
 * @property dispatchers dispatchers for CPU-bound encoding.
 * @property logger log-safe logger.
 * @property totalBudgetMillis upper bound for one analysis including all retries.
 */
class GeminiVisionAnalysisRepository(
    private val api: GeminiApiService,
    private val config: GeminiConfig,
    private val prompts: GeminiPromptBuilder,
    private val parser: GeminiResponseParser,
    private val errorMapper: GeminiErrorMapper,
    private val backoff: ExponentialBackoff,
    private val base64Encoder: (ByteArray) -> String,
    private val dispatchers: DispatcherProvider,
    private val logger: AppLogger,
    private val totalBudgetMillis: Long = DEFAULT_TOTAL_BUDGET_MILLIS,
) : VisionAnalysisRepository {

    private val inFlight = Mutex()

    override suspend fun analyze(request: VisionAnalysisRequest, onUploadComplete: () -> Unit): VisionAnalysisResult =
        inFlight.withLock {
            withTimeoutOrNull(totalBudgetMillis) { analyzeWithRetry(request, onUploadComplete) }
                ?: VisionAnalysisResult.Failure(AppError.Timeout)
        }

    private suspend fun analyzeWithRetry(request: VisionAnalysisRequest, onUploadComplete: () -> Unit): VisionAnalysisResult {
        if (!config.isConfigured) return VisionAnalysisResult.Failure(AppError.ServiceNotConfigured)
        if (!isAcceptableJpeg(request.image.jpegBytes)) return VisionAnalysisResult.Failure(AppError.InvalidImage)
        val body = withContext(dispatchers.default) { buildRequest(request) }
        val uploadNotified = AtomicBoolean(false)
        val listener = UploadCompletionListener { if (uploadNotified.compareAndSet(false, true)) onUploadComplete() }
        var attempt = 0
        while (true) {
            val outcome = attemptOnce(body, listener)
            val error = (outcome.result as? AppResult.Failure)?.error
                ?: return toDomain(outcome.result, request)
            val retryDelay = retryDelayFor(error, outcome.retryAfterMillis, attempt + 1)
                ?: return VisionAnalysisResult.Failure(error)
            attempt += 1
            logger.info(TAG, "Retrying after ${error::class.simpleName}, attempt $attempt")
            delay(retryDelay)
        }
    }

    /** Returns the delay before the next attempt, or `null` when the error must not be retried. */
    private fun retryDelayFor(error: AppError, retryAfterMillis: Long?, nextAttempt: Int): Long? {
        val backoffDelay = backoff.delayForAttempt(nextAttempt) ?: return null
        val shortHint = retryAfterMillis?.takeIf { it <= MAX_HONORED_RETRY_AFTER_MILLIS }
        return when {
            error is AppError.RateLimited -> shortHint
            RetryClassifier.isAutomaticallyRetryable(error) -> shortHint ?: backoffDelay
            else -> null
        }
    }

    private fun buildRequest(request: VisionAnalysisRequest): GeminiInteractionRequest = GeminiInteractionRequest(
        model = config.model,
        input = listOf(
            GeminiInputContent(type = "text", text = prompts.userPrompt(request.mode, request.verbosity, request.targetObject)),
            GeminiInputContent(type = "image", data = base64Encoder(request.image.jpegBytes), mimeType = JPEG_MIME_TYPE),
        ),
        systemInstruction = prompts.systemInstruction,
        responseFormat = GeminiResponseFormat(type = "text", mimeType = "application/json", schema = prompts.responseSchema),
        generationConfig = GeminiGenerationConfig(thinkingLevel = THINKING_LEVEL, maxOutputTokens = MAX_OUTPUT_TOKENS),
        store = false,
    )

    private class AttemptOutcome(val result: AppResult<GeminiAnswer>, val retryAfterMillis: Long? = null)

    private suspend fun attemptOnce(body: GeminiInteractionRequest, listener: UploadCompletionListener): AttemptOutcome =
        try {
            handle(api.createInteraction(config.apiKey, body, listener))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            logger.warn(TAG, "Gemini call failed", failure)
            AttemptOutcome(AppResult.Failure(errorMapper.fromThrowable(failure)))
        }

    private fun handle(response: Response<GeminiInteractionResponse>): AttemptOutcome {
        if (response.isSuccessful) {
            val interaction = response.body()
                ?: return AttemptOutcome(AppResult.Failure(AppError.InvalidServerResponse))
            return AttemptOutcome(parser.parse(interaction))
        }
        val retryAfter = response.headers()["Retry-After"]
        val error = errorMapper.fromHttp(response.code(), response.errorBody()?.string(), retryAfter)
        logger.warn(TAG, "Gemini answered HTTP ${response.code()} (${error::class.simpleName})")
        return AttemptOutcome(AppResult.Failure(error), errorMapper.parseRetryAfterMillis(retryAfter))
    }

    private fun toDomain(result: AppResult<GeminiAnswer>, request: VisionAnalysisRequest): VisionAnalysisResult =
        when (result) {
            is AppResult.Failure -> VisionAnalysisResult.Failure(result.error)
            is AppResult.Success -> VisionAnalysisResult.Success(
                requestId = request.requestId,
                description = result.value.description,
                confidence = result.value.confidence,
                warnings = result.value.warnings,
                processingTimeMillis = null,
            )
        }

    private fun isAcceptableJpeg(bytes: ByteArray): Boolean =
        bytes.size in MIN_JPEG_BYTES..BasiraConstants.MAX_UPLOAD_BYTES &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()

    private companion object {
        const val TAG = "GeminiVision"
        const val JPEG_MIME_TYPE = "image/jpeg"
        const val THINKING_LEVEL = "low"
        const val MAX_OUTPUT_TOKENS = 2_048
        const val MIN_JPEG_BYTES = 4
        const val MAX_HONORED_RETRY_AFTER_MILLIS = 10_000L

        /** Upper bound for one analysis including retries (the per-attempt call timeout is 60 s). */
        const val DEFAULT_TOTAL_BUDGET_MILLIS = 75_000L
    }
}

/** Repository used when the Gemini key or model is not configured; fails every request explicitly. */
class MisconfiguredVisionAnalysisRepository : VisionAnalysisRepository {
    override suspend fun analyze(request: VisionAnalysisRequest, onUploadComplete: () -> Unit): VisionAnalysisResult =
        VisionAnalysisResult.Failure(AppError.ServiceNotConfigured)
}
