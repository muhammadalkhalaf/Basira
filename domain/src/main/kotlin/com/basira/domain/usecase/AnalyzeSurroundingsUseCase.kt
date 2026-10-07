package com.basira.domain.usecase

import com.basira.core.coroutines.BasiraConstants
import com.basira.core.error.AppError
import com.basira.core.result.AppResult
import com.basira.domain.model.AnalysisMode
import com.basira.domain.model.ConnectivityStatus
import com.basira.domain.model.VisionAnalysisRequest
import com.basira.domain.model.VisionAnalysisResult
import com.basira.domain.model.Verbosity
import com.basira.domain.repository.AppLanguageRepository
import com.basira.domain.repository.CapturedImageArchive
import com.basira.domain.repository.Clock
import com.basira.domain.repository.ConnectivityObserver
import com.basira.domain.repository.GlassesRepository
import com.basira.domain.repository.RequestIdGenerator
import com.basira.domain.repository.VisionAnalysisRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/**
 * Shared pipeline behind every assistance mode: check connectivity, capture one photo, optionally
 * archive it (explicit opt-in only), upload it, and validate the answer. The answer is requested in
 * the app language, which is also the language it is spoken in.
 *
 * The returned flow is cold: each collection performs exactly one capture. Cancelling the collector
 * cancels the capture or the HTTP call that is in progress. Nothing is retried after connectivity
 * returns; the user must ask again because the surroundings may have changed.
 */
class AnalyzeSurroundingsUseCase @Inject constructor(
    private val glasses: GlassesRepository,
    private val vision: VisionAnalysisRepository,
    private val connectivity: ConnectivityObserver,
    private val archive: CapturedImageArchive,
    private val requestIds: RequestIdGenerator,
    private val clock: Clock,
    private val validator: DescriptionValidator,
    private val appLanguage: AppLanguageRepository,
) {

    /**
     * Runs one request.
     *
     * @param mode assistance mode.
     * @param targetObject object to look for; required for [AnalysisMode.FIND_OBJECT].
     * @param verbosity preferred answer length.
     * @return progress events ending with [AnalysisProgress.Completed], [AnalysisProgress.NoResult],
     * or [AnalysisProgress.Failed].
     */
    operator fun invoke(
        mode: AnalysisMode,
        targetObject: String?,
        verbosity: Verbosity,
    ): Flow<AnalysisProgress> = channelFlow {
        val target = targetObject?.trim()?.take(BasiraConstants.MAX_TARGET_OBJECT_CHARS)
        if (mode == AnalysisMode.FIND_OBJECT && target.isNullOrEmpty()) {
            send(AnalysisProgress.Failed(AppError.MissingTargetObject))
            return@channelFlow
        }
        if (isOffline()) {
            send(AnalysisProgress.Failed(AppError.Offline))
            return@channelFlow
        }
        send(AnalysisProgress.Capturing)
        val image = when (val captured = glasses.captureImage()) {
            is AppResult.Failure -> {
                send(AnalysisProgress.Failed(captured.error))
                return@channelFlow
            }
            is AppResult.Success -> captured.value
        }
        val requestId = requestIds.next()
        archive.saveIfEnabled(image, requestId)
        if (isOffline()) {
            send(AnalysisProgress.Failed(AppError.Offline))
            return@channelFlow
        }
        send(AnalysisProgress.Uploading)
        val request = VisionAnalysisRequest(
            image = image,
            mode = mode,
            targetObject = target.takeIf { mode == AnalysisMode.FIND_OBJECT },
            language = appLanguage.language.value.tag,
            verbosity = verbosity,
            requestId = requestId,
        )
        val result = vision.analyze(request) { trySend(AnalysisProgress.Analyzing) }
        send(toProgress(result, request))
    }

    private fun isOffline(): Boolean = connectivity.status.value == ConnectivityStatus.OFFLINE

    private fun toProgress(
        result: VisionAnalysisResult,
        request: VisionAnalysisRequest,
    ): AnalysisProgress = when (result) {
        is VisionAnalysisResult.Failure -> AnalysisProgress.Failed(result.error)
        is VisionAnalysisResult.Success -> {
            if (result.requestId != request.requestId) {
                AnalysisProgress.Failed(AppError.InvalidServerResponse)
            } else {
                when (val outcome = validator.validate(result, request.mode, request.targetObject, clock.nowMillis())) {
                    is DescriptionValidator.Outcome.Valid -> AnalysisProgress.Completed(outcome.description)
                    is DescriptionValidator.Outcome.NoResult -> AnalysisProgress.NoResult(outcome.reason)
                    DescriptionValidator.Outcome.Invalid -> AnalysisProgress.Failed(AppError.InvalidServerResponse)
                }
            }
        }
    }
}
