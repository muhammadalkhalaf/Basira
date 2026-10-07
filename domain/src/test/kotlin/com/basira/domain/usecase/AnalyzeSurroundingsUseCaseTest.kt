package com.basira.domain.usecase

import com.basira.core.error.AppError
import com.basira.core.result.AppResult
import com.basira.domain.fakes.FakeAppLanguageRepository
import com.basira.domain.fakes.FakeConnectivityObserver
import com.basira.domain.fakes.FakeGlassesRepositoryForTest
import com.basira.domain.fakes.FakeImageArchive
import com.basira.domain.fakes.FakeVisionRepository
import com.basira.domain.model.AnalysisMode
import com.basira.domain.model.Confidence
import com.basira.domain.model.ConnectivityStatus
import com.basira.domain.model.LanguagePreference
import com.basira.domain.model.Verbosity
import com.basira.domain.model.VisionAnalysisResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyzeSurroundingsUseCaseTest {

    private val glasses = FakeGlassesRepositoryForTest()
    private val vision = FakeVisionRepository()
    private val connectivity = FakeConnectivityObserver()
    private val archive = FakeImageArchive()
    private val appLanguage = FakeAppLanguageRepository()
    private var nextId = 0
    private val useCase = AnalyzeSurroundingsUseCase(
        glasses = glasses,
        vision = vision,
        connectivity = connectivity,
        archive = archive,
        requestIds = { "req-${++nextId}" },
        clock = { 1_000L },
        validator = DescriptionValidator(),
        appLanguage = appLanguage,
    )

    @Test
    fun `successful request emits capture upload analyze completed`() = runTest {
        val events = useCase(AnalysisMode.SCENE_DESCRIPTION, null, Verbosity.SHORT).toList()

        assertEquals(AnalysisProgress.Capturing, events[0])
        assertEquals(AnalysisProgress.Uploading, events[1])
        assertEquals(AnalysisProgress.Analyzing, events[2])
        val completed = events[3] as AnalysisProgress.Completed
        assertEquals("req-1", completed.description.requestId)
        assertEquals("ar", vision.requests.single().language)
        assertEquals(1, archive.saveCalls)
    }

    @Test
    fun `descriptions are requested in the app language`() = runTest {
        appLanguage.setPreference(LanguagePreference.ENGLISH)

        useCase(AnalysisMode.SCENE_DESCRIPTION, null, Verbosity.SHORT).toList()

        assertEquals("en", vision.requests.single().language)
    }

    @Test
    fun `offline fails before capturing`() = runTest {
        connectivity.status.value = ConnectivityStatus.OFFLINE

        val events = useCase(AnalysisMode.SCENE_DESCRIPTION, null, Verbosity.SHORT).toList()

        assertEquals(listOf(AnalysisProgress.Failed(AppError.Offline)), events)
        assertEquals(0, glasses.captureCount)
    }

    @Test
    fun `glasses disconnection during capture is reported and nothing is uploaded`() = runTest {
        glasses.nextCapture = AppResult.Failure(AppError.GlassesDisconnected)

        val events = useCase(AnalysisMode.READ_TEXT, null, Verbosity.SHORT).toList()

        assertEquals(AnalysisProgress.Failed(AppError.GlassesDisconnected), events.last())
        assertTrue(vision.requests.isEmpty())
    }

    @Test
    fun `find object without target fails without capture`() = runTest {
        val events = useCase(AnalysisMode.FIND_OBJECT, "   ", Verbosity.SHORT).toList()

        assertEquals(listOf(AnalysisProgress.Failed(AppError.MissingTargetObject)), events)
        assertEquals(0, glasses.captureCount)
    }

    @Test
    fun `find object forwards trimmed target`() = runTest {
        useCase(AnalysisMode.FIND_OBJECT, "  المفاتيح ", Verbosity.SHORT).toList()

        assertEquals("المفاتيح", vision.requests.single().targetObject)
    }

    @Test
    fun `empty description becomes NoResult`() = runTest {
        vision.responder = { VisionAnalysisResult.Success(it.requestId, "  ", Confidence.HIGH, emptyList(), null) }

        val last = useCase(AnalysisMode.SCENE_DESCRIPTION, null, Verbosity.SHORT).toList().last()

        assertEquals(AnalysisProgress.NoResult(NoResultReason.NOTHING_RECOGNIZED), last)
    }

    @Test
    fun `currency below high confidence is never spoken as a denomination`() = runTest {
        vision.responder = { VisionAnalysisResult.Success(it.requestId, "خمسة آلاف ليرة", Confidence.MEDIUM, emptyList(), null) }

        val last = useCase(AnalysisMode.CURRENCY, null, Verbosity.SHORT).toList().last()

        assertEquals(AnalysisProgress.NoResult(NoResultReason.CURRENCY_NOT_CONFIDENT), last)
    }

    @Test
    fun `low confidence scene description is still delivered with its confidence`() = runTest {
        vision.responder = { VisionAnalysisResult.Success(it.requestId, "ربما يوجد كرسي", Confidence.LOW, listOf("الصورة معتمة"), null) }

        val last = useCase(AnalysisMode.SCENE_DESCRIPTION, null, Verbosity.SHORT).toList().last() as AnalysisProgress.Completed

        assertEquals(Confidence.LOW, last.description.confidence)
        assertEquals(listOf("الصورة معتمة"), last.description.warnings)
    }

    @Test
    fun `mismatched request id is an invalid server response`() = runTest {
        vision.responder = { VisionAnalysisResult.Success("other", "نص", Confidence.HIGH, emptyList(), null) }

        val last = useCase(AnalysisMode.SCENE_DESCRIPTION, null, Verbosity.SHORT).toList().last()

        assertEquals(AnalysisProgress.Failed(AppError.InvalidServerResponse), last)
    }

    @Test
    fun `provider failure is propagated`() = runTest {
        vision.responder = { VisionAnalysisResult.Failure(AppError.RateLimited(5_000)) }

        val last = useCase(AnalysisMode.SCENE_DESCRIPTION, null, Verbosity.DETAILED).toList().last()

        assertEquals(AnalysisProgress.Failed(AppError.RateLimited(5_000)), last)
        assertEquals(Verbosity.DETAILED, vision.requests.single().verbosity)
    }
}
