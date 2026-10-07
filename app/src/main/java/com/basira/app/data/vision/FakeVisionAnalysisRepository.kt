package com.basira.app.data.vision

import com.basira.domain.model.AnalysisMode
import com.basira.domain.model.Confidence
import com.basira.domain.model.VisionAnalysisRequest
import com.basira.domain.model.VisionAnalysisResult
import com.basira.domain.repository.VisionAnalysisRepository
import kotlinx.coroutines.delay

/**
 * Offline stand-in for Gemini, used by development builds without a `GEMINI_API_KEY`. It never makes
 * a network call.
 *
 * Every answer starts with "وصف تجريبي" (simulated description) so a tester can never mistake it for
 * a real analysis of the surroundings. Never used in release builds.
 *
 * @property latencyMillis simulated processing time.
 */
class FakeVisionAnalysisRepository(private val latencyMillis: Long = 1_200L) : VisionAnalysisRepository {

    private var counter = 0

    override suspend fun analyze(request: VisionAnalysisRequest, onUploadComplete: () -> Unit): VisionAnalysisResult {
        delay(latencyMillis / 3)
        onUploadComplete()
        delay(latencyMillis)
        val index = counter++
        val text = when (request.mode) {
            AnalysisMode.SCENE_DESCRIPTION -> SCENES[index % SCENES.size]
            AnalysisMode.READ_TEXT -> "$PREFIX النص المكتوب: «مخرج الطوارئ». السطر الثاني غير مقروء."
            AnalysisMode.FIND_OBJECT ->
                "$PREFIX لا أرى ${request.targetObject.orEmpty()} بوضوح. قد يكون على الطاولة عند الساعة الثانية."
            AnalysisMode.CURRENCY -> "$PREFIX ورقة نقدية تبدو من فئة ألف ليرة سورية."
        }
        return VisionAnalysisResult.Success(
            requestId = request.requestId,
            description = text,
            confidence = if (request.mode == AnalysisMode.FIND_OBJECT) Confidence.MEDIUM else Confidence.HIGH,
            warnings = emptyList(),
            processingTimeMillis = latencyMillis,
        )
    }

    private companion object {
        const val PREFIX = "وصف تجريبي:"
        val SCENES = listOf(
            "$PREFIX درج نازل أمامك مباشرة على بعد مترين تقريباً. باب مفتوح عند الساعة الواحدة.",
            "$PREFIX غرفة جلوس. كرسي في طريقك على بعد متر عند الساعة الثانية عشرة. لا أشخاص ظاهرون.",
            "$PREFIX لا أرى شيئاً واضحاً، الصورة شبه مظلمة.",
        )
    }
}
