package com.basira.app.data.vision

import com.basira.domain.model.AnalysisMode
import com.basira.domain.model.AppLanguage
import com.basira.domain.model.Confidence
import com.basira.domain.model.VisionAnalysisRequest
import com.basira.domain.model.VisionAnalysisResult
import com.basira.domain.repository.VisionAnalysisRepository
import kotlinx.coroutines.delay

/**
 * Offline stand-in for Gemini, used by development builds without a `GEMINI_API_KEY`. It never makes
 * a network call.
 *
 * Answers are in the requested language. Every answer starts with "وصف تجريبي" or "Simulated
 * description" so a tester can never mistake it for a real analysis of the surroundings. Never used in
 * release builds.
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
        val target = request.targetObject.orEmpty()
        val text = if (AppLanguage.fromTag(request.language) == AppLanguage.ARABIC) {
            when (request.mode) {
                AnalysisMode.SCENE_DESCRIPTION -> SCENES_AR[index % SCENES_AR.size]
                AnalysisMode.READ_TEXT -> "$PREFIX_AR النص المكتوب: «مخرج الطوارئ». السطر الثاني غير مقروء."
                AnalysisMode.FIND_OBJECT -> "$PREFIX_AR لا أرى $target بوضوح. قد يكون على الطاولة عند الساعة الثانية."
                AnalysisMode.CURRENCY -> "$PREFIX_AR ورقة نقدية تبدو من فئة ألف ليرة سورية."
            }
        } else {
            when (request.mode) {
                AnalysisMode.SCENE_DESCRIPTION -> SCENES_EN[index % SCENES_EN.size]
                AnalysisMode.READ_TEXT -> "$PREFIX_EN The text reads: «Emergency exit». The second line is unreadable."
                AnalysisMode.FIND_OBJECT -> "$PREFIX_EN I cannot see $target clearly. It may be on the table at two o'clock."
                AnalysisMode.CURRENCY -> "$PREFIX_EN A banknote that looks like one thousand Syrian pounds."
            }
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
        const val PREFIX_AR = "وصف تجريبي:"
        const val PREFIX_EN = "Simulated description:"
        val SCENES_AR = listOf(
            "$PREFIX_AR درج نازل أمامك مباشرة على بعد مترين تقريباً. باب مفتوح عند الساعة الواحدة.",
            "$PREFIX_AR غرفة جلوس. كرسي في طريقك على بعد متر عند الساعة الثانية عشرة. لا أشخاص ظاهرون.",
            "$PREFIX_AR لا أرى شيئاً واضحاً، الصورة شبه مظلمة.",
        )
        val SCENES_EN = listOf(
            "$PREFIX_EN Stairs going down directly ahead, about two meters away. An open door at one o'clock.",
            "$PREFIX_EN A living room. A chair in your path about one meter away at twelve o'clock. No people visible.",
            "$PREFIX_EN I cannot see anything clearly; the image is almost dark.",
        )
    }
}
