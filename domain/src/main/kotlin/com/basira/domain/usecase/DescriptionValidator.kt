package com.basira.domain.usecase

import com.basira.core.coroutines.BasiraConstants
import com.basira.domain.model.AnalysisMode
import com.basira.domain.model.Confidence
import com.basira.domain.model.SceneDescription
import com.basira.domain.model.VisionAnalysisResult
import javax.inject.Inject

/**
 * Applies semantic rules to a structurally valid provider response before anything is spoken.
 *
 * Structural validation (JSON shape, enum values) happens in the data layer; this class enforces
 * product rules that are independent of the transport.
 */
class DescriptionValidator @Inject constructor() {

    /** Result of [validate]. */
    sealed interface Outcome {
        /**
         * The text may be spoken.
         *
         * @property description sanitized description.
         */
        data class Valid(val description: SceneDescription) : Outcome

        /**
         * Nothing may be spoken as a result.
         *
         * @property reason why.
         */
        data class NoResult(val reason: NoResultReason) : Outcome

        /** The response violates the contract, for example an absurdly long description. */
        data object Invalid : Outcome
    }

    /**
     * Validates [result] for the request that produced it.
     *
     * @param result structurally valid provider result.
     * @param mode mode of the request.
     * @param targetObject object name for object-finding mode.
     * @param nowMillis timestamp recorded on the description.
     * @return the validation outcome.
     */
    fun validate(
        result: VisionAnalysisResult.Success,
        mode: AnalysisMode,
        targetObject: String?,
        nowMillis: Long,
    ): Outcome {
        val text = sanitize(result.description)
        if (text.length > BasiraConstants.MAX_DESCRIPTION_CHARS) return Outcome.Invalid
        if (mode == AnalysisMode.CURRENCY && result.confidence != Confidence.HIGH) {
            return Outcome.NoResult(NoResultReason.CURRENCY_NOT_CONFIDENT)
        }
        if (text.isEmpty()) return Outcome.NoResult(NoResultReason.NOTHING_RECOGNIZED)
        return Outcome.Valid(
            SceneDescription(
                requestId = result.requestId,
                text = text,
                confidence = result.confidence,
                warnings = result.warnings.map(::sanitize).filter { it.isNotEmpty() }.take(MAX_WARNINGS),
                processingTimeMillis = result.processingTimeMillis,
                mode = mode,
                targetObject = targetObject,
                createdAtMillis = nowMillis,
            ),
        )
    }

    /** Collapses whitespace and strips control characters that could confuse speech engines. */
    private fun sanitize(raw: String): String =
        raw.replace(controlCharacters, " ").replace(whitespace, " ").trim()

    private companion object {
        const val MAX_WARNINGS = 3
        val controlCharacters = Regex("[\\p{Cntrl}&&[^\\n]]")
        val whitespace = Regex("\\s+")
    }
}
