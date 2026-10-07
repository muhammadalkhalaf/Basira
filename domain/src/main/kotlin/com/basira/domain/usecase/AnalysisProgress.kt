package com.basira.domain.usecase

import com.basira.core.error.AppError
import com.basira.domain.model.SceneDescription

/** Step-by-step progress of one capture-and-analyze request. */
sealed interface AnalysisProgress {

    /** A photo is being captured on the glasses. */
    data object Capturing : AnalysisProgress

    /** The image is being uploaded to the vision provider. */
    data object Uploading : AnalysisProgress

    /** The image was uploaded and the vision provider is analyzing it. */
    data object Analyzing : AnalysisProgress

    /**
     * A validated description is ready.
     *
     * @property description the description to speak.
     */
    data class Completed(val description: SceneDescription) : AnalysisProgress

    /**
     * The vision provider answered but there is nothing that may be spoken as a result.
     *
     * @property reason why there is no speakable result.
     */
    data class NoResult(val reason: NoResultReason) : AnalysisProgress

    /**
     * The request failed.
     *
     * @property error typed failure.
     */
    data class Failed(val error: AppError) : AnalysisProgress
}

/** Why a structurally valid response produced nothing speakable. */
enum class NoResultReason {
    /** The model recognized nothing useful in the image. */
    NOTHING_RECOGNIZED,

    /** Currency mode was requested but the model was not confident enough to name a denomination. */
    CURRENCY_NOT_CONFIDENT,
}
