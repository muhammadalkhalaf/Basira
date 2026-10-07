package com.basira.domain.usecase

import com.basira.domain.model.AnalysisMode
import com.basira.domain.model.Verbosity
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * Describes the scene in front of the user, putting safety-critical information first.
 *
 * @property analyze shared capture-and-analyze pipeline.
 */
class DescribeSceneUseCase @Inject constructor(private val analyze: AnalyzeSurroundingsUseCase) {
    /**
     * @param verbosity preferred answer length.
     * @return progress of the single request.
     */
    operator fun invoke(verbosity: Verbosity): Flow<AnalysisProgress> =
        analyze(AnalysisMode.SCENE_DESCRIPTION, targetObject = null, verbosity = verbosity)
}

/**
 * Reads visible text aloud, marking unreadable sections.
 *
 * @property analyze shared capture-and-analyze pipeline.
 */
class ReadTextUseCase @Inject constructor(private val analyze: AnalyzeSurroundingsUseCase) {
    /**
     * @param verbosity preferred answer length.
     * @return progress of the single request.
     */
    operator fun invoke(verbosity: Verbosity): Flow<AnalysisProgress> =
        analyze(AnalysisMode.READ_TEXT, targetObject = null, verbosity = verbosity)
}

/**
 * Reports whether a named object is visible and its approximate direction.
 *
 * @property analyze shared capture-and-analyze pipeline.
 */
class FindObjectUseCase @Inject constructor(private val analyze: AnalyzeSurroundingsUseCase) {
    /**
     * @param targetObject the object to look for; a blank name fails with
     * [com.basira.core.error.AppError.MissingTargetObject] before any capture.
     * @param verbosity preferred answer length.
     * @return progress of the single request.
     */
    operator fun invoke(targetObject: String, verbosity: Verbosity): Flow<AnalysisProgress> =
        analyze(AnalysisMode.FIND_OBJECT, targetObject = targetObject, verbosity = verbosity)
}

/**
 * Identifies a banknote denomination; anything below high confidence is reported as uncertain
 * instead of guessing.
 *
 * @property analyze shared capture-and-analyze pipeline.
 */
class IdentifyCurrencyUseCase @Inject constructor(private val analyze: AnalyzeSurroundingsUseCase) {
    /**
     * @param verbosity preferred answer length.
     * @return progress of the single request.
     */
    operator fun invoke(verbosity: Verbosity): Flow<AnalysisProgress> =
        analyze(AnalysisMode.CURRENCY, targetObject = null, verbosity = verbosity)
}
