package com.basira.domain.assistant

import com.basira.core.error.AppError
import com.basira.core.error.UpdateTarget
import com.basira.core.logging.NoOpLogger
import com.basira.core.result.AppResult
import com.basira.domain.fakes.FakeConnectivityObserver
import com.basira.domain.fakes.FakeGlassesRepositoryForTest
import com.basira.domain.fakes.FakeHistoryRepository
import com.basira.domain.fakes.FakeImageArchive
import com.basira.domain.fakes.FakePhoneLocator
import com.basira.domain.fakes.FakeSettingsRepository
import com.basira.domain.fakes.FakeSpeechOutput
import com.basira.domain.fakes.FakeVisionRepository
import com.basira.domain.fakes.FakeVoiceRecognizer
import com.basira.domain.fakes.RecordingAnnouncer
import com.basira.domain.fakes.RecordingFeedbackPlayer
import com.basira.domain.fakes.readyGlassesStatus
import com.basira.domain.model.AnalysisMode
import com.basira.domain.model.AudioRoute
import com.basira.domain.model.CameraPermissionStatus
import com.basira.domain.model.CompatibilityStatus
import com.basira.domain.model.Confidence
import com.basira.domain.model.ConnectivityStatus
import com.basira.domain.model.FeedbackCue
import com.basira.domain.model.LinkStatus
import com.basira.domain.model.RegistrationStatus
import com.basira.domain.model.SpeechAvailability
import com.basira.domain.model.SpeechCompletion
import com.basira.domain.model.Verbosity
import com.basira.domain.model.VisionAnalysisResult
import com.basira.domain.usecase.AnalyzeSurroundingsUseCase
import com.basira.domain.usecase.DescribeSceneUseCase
import com.basira.domain.usecase.DescriptionValidator
import com.basira.domain.usecase.FindObjectUseCase
import com.basira.domain.usecase.IdentifyCurrencyUseCase
import com.basira.domain.usecase.NoResultReason
import com.basira.domain.usecase.ReadTextUseCase
import com.basira.domain.voice.VoiceCommandParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantEngineTest {

    private val glasses = FakeGlassesRepositoryForTest()
    private val vision = FakeVisionRepository()
    private val speech = FakeSpeechOutput()
    private val announcer = RecordingAnnouncer(speech)
    private val feedback = RecordingFeedbackPlayer()
    private val connectivity = FakeConnectivityObserver()
    private val settings = FakeSettingsRepository()
    private val history = FakeHistoryRepository()
    private val voice = FakeVoiceRecognizer()
    private val phoneLocator = FakePhoneLocator()
    private var requestCounter = 0

    private var engineScope: CoroutineScope? = null

    @After
    fun tearDown() {
        engineScope?.cancel()
    }

    /**
     * Creates the engine on a scope driven by the test scheduler. A dedicated scope (instead of
     * `backgroundScope`) is used so `advanceUntilIdle()` also runs the engine's coroutines.
     */
    private fun TestScope.createEngine(): AssistantEngine {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        engineScope = scope
        val analyze = AnalyzeSurroundingsUseCase(
            glasses = glasses,
            vision = vision,
            connectivity = connectivity,
            archive = FakeImageArchive(),
            requestIds = { "req-${++requestCounter}" },
            clock = { 42L },
            validator = DescriptionValidator(),
        )
        return AssistantEngine(
            glasses = glasses,
            speech = speech,
            announcer = announcer,
            feedback = feedback,
            connectivity = connectivity,
            settingsRepository = settings,
            history = history,
            voiceRecognizer = voice,
            voiceParser = VoiceCommandParser(),
            phoneLocator = phoneLocator,
            describeScene = DescribeSceneUseCase(analyze),
            readText = ReadTextUseCase(analyze),
            findObject = FindObjectUseCase(analyze),
            identifyCurrency = IdentifyCurrencyUseCase(analyze),
            scope = scope,
            logger = NoOpLogger,
        ).also { it.start() }
    }

    private fun AssistantEngine.analyze(mode: AnalysisMode = AnalysisMode.SCENE_DESCRIPTION, target: String? = null) =
        dispatch(AssistantAction.Analyze(AnalysisRequestSpec(mode, target)))

    @Test
    fun `initial status is announced once dependencies are ready`() = runTest {
        val engine = createEngine()
        advanceUntilIdle()

        assertEquals(AssistantPhase.Ready, engine.state.value.phase)
        assertEquals(Announcement.Status(AssistantPhase.Ready), announcer.announcements.single())
    }

    @Test
    fun `initial status mentions the phone speaker when glasses audio is not connected`() = runTest {
        speech.state.update { it.copy(route = AudioRoute.PHONE_SPEAKER) }
        createEngine()
        advanceUntilIdle()

        assertEquals(
            Announcement.Status(AssistantPhase.Ready, route = AudioRoute.PHONE_SPEAKER),
            announcer.announcements.single(),
        )
    }

    @Test
    fun `successful request walks through every phase and keeps the description`() = runTest {
        val engine = createEngine()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        vision.gate = gate

        engine.analyze()
        runCurrent()
        assertEquals(AssistantPhase.Analyzing(AnalysisMode.SCENE_DESCRIPTION), engine.state.value.phase)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(AssistantPhase.Loaded, engine.state.value.phase)
        assertEquals("req-1", engine.state.value.lastDescription?.requestId)
        assertTrue(announcer.announcements.any { it is Announcement.Description && !it.repeated })
        assertEquals(listOf(FeedbackCue.CAPTURE, FeedbackCue.PROCESSING, FeedbackCue.SUCCESS), feedback.cues)
    }

    @Test
    fun `duplicate requests do not start a second capture`() = runTest {
        val engine = createEngine()
        advanceUntilIdle()
        glasses.captureGate = CompletableDeferred()

        engine.analyze()
        runCurrent()
        engine.analyze()
        engine.analyze(AnalysisMode.READ_TEXT)
        runCurrent()

        assertEquals(1, glasses.captureCount)
        assertEquals(2, feedback.cues.count { it == FeedbackCue.BUSY })
        assertEquals(AssistantPhase.Capturing(AnalysisMode.SCENE_DESCRIPTION), engine.state.value.phase)
    }

    @Test
    fun `cancel stops the operation, keeps the last description, and never uploads`() = runTest {
        val engine = createEngine()
        advanceUntilIdle()
        engine.analyze()
        advanceUntilIdle()
        val previous = engine.state.value.lastDescription
        val gate = CompletableDeferred<Unit>()
        glasses.captureGate = gate

        engine.analyze()
        runCurrent()
        engine.dispatch(AssistantAction.Cancel)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(AssistantPhase.Ready, engine.state.value.phase)
        assertEquals(previous, engine.state.value.lastDescription)
        assertEquals(1, vision.requests.size)
        assertTrue(Announcement.Cancelled in announcer.announcements)
        assertTrue(FeedbackCue.CANCELLED in feedback.cues)
    }

    @Test
    fun `failure after success preserves the last description`() = runTest {
        val engine = createEngine()
        advanceUntilIdle()
        engine.analyze()
        advanceUntilIdle()
        val previous = engine.state.value.lastDescription
        vision.responder = { VisionAnalysisResult.Failure(AppError.Timeout) }

        engine.analyze()
        advanceUntilIdle()

        assertEquals(AssistantPhase.Timeout, engine.state.value.phase)
        assertEquals(previous, engine.state.value.lastDescription)
        assertEquals(Announcement.Status(AssistantPhase.Timeout), announcer.announcements.last())
    }

    @Test
    fun `http style failures map to dedicated phases`() = runTest {
        val engine = createEngine()
        advanceUntilIdle()
        val cases = mapOf(
            AppError.AuthenticationExpired to AssistantPhase.AuthenticationExpired,
            AppError.RateLimited(3_000) to AssistantPhase.RateLimited(3_000),
            AppError.InvalidServerResponse to AssistantPhase.InvalidServerResponse,
            AppError.InvalidImage to AssistantPhase.InvalidImage,
            AppError.ServerError(500) to AssistantPhase.RecoverableError(AppError.ServerError(500)),
            AppError.Forbidden to AssistantPhase.FatalError(AppError.Forbidden),
            AppError.ApiKeyRejected to AssistantPhase.FatalError(AppError.ApiKeyRejected),
            AppError.ServiceNotConfigured to AssistantPhase.FatalError(AppError.ServiceNotConfigured),
            AppError.QuotaExceeded to AssistantPhase.RecoverableError(AppError.QuotaExceeded),
            AppError.ContentBlocked to AssistantPhase.RecoverableError(AppError.ContentBlocked),
        )
        for ((error, phase) in cases) {
            vision.responder = { VisionAnalysisResult.Failure(error) }
            engine.analyze()
            advanceUntilIdle()
            assertEquals(phase, engine.state.value.phase)
        }
    }

    @Test
    fun `offline blocks capture and recovery asks the user instead of resending`() = runTest {
        val engine = createEngine()
        advanceUntilIdle()

        connectivity.status.value = ConnectivityStatus.OFFLINE
        advanceUntilIdle()
        assertEquals(AssistantPhase.Offline, engine.state.value.phase)
        assertTrue(FeedbackCue.OFFLINE in feedback.cues)

        engine.analyze()
        advanceUntilIdle()
        assertEquals(0, glasses.captureCount)

        connectivity.status.value = ConnectivityStatus.ONLINE
        advanceUntilIdle()

        assertEquals(AssistantPhase.Ready, engine.state.value.phase)
        assertEquals(Announcement.Status(AssistantPhase.Ready, previous = AssistantPhase.Offline), announcer.announcements.last())
        assertEquals(0, glasses.captureCount)
        assertTrue(vision.requests.isEmpty())
    }

    @Test
    fun `offline failure during request is cleared when connectivity returns`() = runTest {
        val engine = createEngine()
        advanceUntilIdle()
        vision.responder = { VisionAnalysisResult.Failure(AppError.Offline) }
        engine.analyze()
        advanceUntilIdle()
        assertEquals(AssistantPhase.Offline, engine.state.value.phase)

        connectivity.status.value = ConnectivityStatus.OFFLINE
        advanceUntilIdle()
        connectivity.status.value = ConnectivityStatus.ONLINE
        advanceUntilIdle()

        assertEquals(AssistantPhase.Ready, engine.state.value.phase)
        assertEquals(1, vision.requests.size)
    }

    @Test
    fun `permission denial is shown and never retried automatically`() = runTest {
        glasses.status.update { it.copy(cameraPermission = CameraPermissionStatus.DENIED_BY_USER) }
        val engine = createEngine()
        advanceUntilIdle()

        engine.analyze()
        advanceUntilIdle()

        assertEquals(AssistantPhase.PermissionDenied, engine.state.value.phase)
        assertEquals(0, glasses.captureCount)
    }

    @Test
    fun `permission not yet granted asks for permission`() = runTest {
        glasses.status.update { it.copy(cameraPermission = CameraPermissionStatus.NOT_GRANTED) }
        val engine = createEngine()
        advanceUntilIdle()

        assertEquals(AssistantPhase.PermissionRequired, engine.state.value.phase)
    }

    @Test
    fun `registration and companion app states`() = runTest {
        glasses.status.update { it.copy(metaAiInstalled = false) }
        val engine = createEngine()
        advanceUntilIdle()
        assertEquals(AssistantPhase.MetaAiMissing, engine.state.value.phase)

        glasses.status.update { it.copy(metaAiInstalled = true, registration = RegistrationStatus.UNREGISTERED) }
        advanceUntilIdle()
        assertEquals(AssistantPhase.RegistrationRequired, engine.state.value.phase)

        glasses.status.update { it.copy(registration = RegistrationStatus.REGISTERING) }
        advanceUntilIdle()
        assertEquals(AssistantPhase.Registering, engine.state.value.phase)
    }

    @Test
    fun `glasses unavailable, disconnected and incompatible states`() = runTest {
        val ready = readyGlassesStatus()
        glasses.status.value = ready.copy(device = null)
        val engine = createEngine()
        advanceUntilIdle()
        assertEquals(AssistantPhase.GlassesUnavailable, engine.state.value.phase)

        glasses.status.value = ready.copy(device = ready.device?.copy(link = LinkStatus.DISCONNECTED))
        advanceUntilIdle()
        assertEquals(AssistantPhase.GlassesDisconnected, engine.state.value.phase)

        glasses.status.value = ready.copy(device = ready.device?.copy(compatibility = CompatibilityStatus.FIRMWARE_UPDATE_REQUIRED))
        advanceUntilIdle()
        assertEquals(AssistantPhase.UnsupportedVersion(UpdateTarget.GLASSES_FIRMWARE), engine.state.value.phase)
    }

    @Test
    fun `glasses disconnection during capture is reported`() = runTest {
        val engine = createEngine()
        advanceUntilIdle()
        glasses.nextCapture = AppResult.Failure(AppError.GlassesDisconnected)

        engine.analyze()
        advanceUntilIdle()

        assertEquals(AssistantPhase.GlassesDisconnected, engine.state.value.phase)
        assertTrue(vision.requests.isEmpty())
    }

    @Test
    fun `speech engine and Arabic voice availability`() = runTest {
        speech.state.update { it.copy(availability = SpeechAvailability.ENGINE_UNAVAILABLE) }
        val engine = createEngine()
        advanceUntilIdle()
        assertEquals(AssistantPhase.SpeechUnavailable(arabicVoiceMissing = false), engine.state.value.phase)

        speech.state.update { it.copy(availability = SpeechAvailability.ARABIC_MISSING) }
        advanceUntilIdle()
        assertEquals(AssistantPhase.SpeechUnavailable(arabicVoiceMissing = true), engine.state.value.phase)

        engine.analyze()
        advanceUntilIdle()
        assertEquals(0, glasses.captureCount)
    }

    @Test
    fun `bluetooth route loss during speech is announced`() = runTest {
        val engine = createEngine()
        advanceUntilIdle()
        speech.nextCompletion = SpeechCompletion.ROUTE_LOST

        engine.analyze()
        advanceUntilIdle()

        assertEquals(Announcement.GlassesAudioLost, announcer.announcements.last())
        assertEquals(AssistantPhase.Loaded, engine.state.value.phase)
    }

    @Test
    fun `route changes are announced after start-up`() = runTest {
        createEngine()
        advanceUntilIdle()

        speech.state.update { it.copy(route = AudioRoute.PHONE_SPEAKER) }
        advanceUntilIdle()

        assertEquals(Announcement.RouteChanged(AudioRoute.PHONE_SPEAKER), announcer.announcements.last())
    }

    @Test
    fun `repeat speaks the last description or explains there is none`() = runTest {
        val engine = createEngine()
        advanceUntilIdle()

        engine.dispatch(AssistantAction.Repeat)
        advanceUntilIdle()
        assertEquals(Announcement.NothingToRepeat, announcer.announcements.last())

        engine.analyze()
        advanceUntilIdle()
        engine.dispatch(AssistantAction.Repeat)
        advanceUntilIdle()

        val last = announcer.announcements.last() as Announcement.Description
        assertTrue(last.repeated)
        assertEquals(1, glasses.captureCount)
    }

    @Test
    fun `stop speaking interrupts the description and keeps it loaded`() = runTest {
        val engine = createEngine()
        advanceUntilIdle()
        speech.blockWhen = { it.startsWith("Description") }
        engine.analyze()
        advanceUntilIdle()
        assertEquals(AssistantPhase.Speaking, engine.state.value.phase)

        engine.dispatch(AssistantAction.StopSpeaking)
        advanceUntilIdle()

        assertEquals(AssistantPhase.Loaded, engine.state.value.phase)
        assertNotNull(engine.state.value.lastDescription)
    }

    @Test
    fun `empty and currency-uncertain results`() = runTest {
        val engine = createEngine()
        advanceUntilIdle()
        vision.responder = { VisionAnalysisResult.Success(it.requestId, "", Confidence.HIGH, emptyList(), null) }
        engine.analyze()
        advanceUntilIdle()
        assertEquals(AssistantPhase.EmptyResult(NoResultReason.NOTHING_RECOGNIZED), engine.state.value.phase)

        vision.responder = { VisionAnalysisResult.Success(it.requestId, "ألف ليرة", Confidence.LOW, emptyList(), null) }
        engine.analyze(AnalysisMode.CURRENCY)
        advanceUntilIdle()
        assertEquals(AssistantPhase.EmptyResult(NoResultReason.CURRENCY_NOT_CONFIDENT), engine.state.value.phase)
        assertEquals(null, engine.state.value.lastDescription)
    }

    @Test
    fun `retry repeats the last request with a new capture`() = runTest {
        val engine = createEngine()
        advanceUntilIdle()
        engine.analyze(AnalysisMode.FIND_OBJECT, "المفاتيح")
        advanceUntilIdle()

        engine.dispatch(AssistantAction.Retry)
        advanceUntilIdle()

        assertEquals(2, glasses.captureCount)
        assertEquals(listOf("المفاتيح", "المفاتيح"), vision.requests.map { it.targetObject })
    }

    @Test
    fun `voice command triggers object search`() = runTest {
        val engine = createEngine()
        advanceUntilIdle()
        voice.next = AppResult.Success("ابحث عن الهاتف")

        engine.dispatch(AssistantAction.ListenForCommand)
        advanceUntilIdle()

        assertEquals(AnalysisMode.FIND_OBJECT, vision.requests.single().mode)
        assertEquals("الهاتف", vision.requests.single().targetObject)
        assertTrue(FeedbackCue.LISTENING in feedback.cues)
    }

    @Test
    fun `voice failures are announced`() = runTest {
        val engine = createEngine()
        advanceUntilIdle()

        voice.next = AppResult.Failure(AppError.MicrophonePermissionRequired)
        engine.dispatch(AssistantAction.ListenForCommand)
        advanceUntilIdle()
        assertEquals(Announcement.MicrophonePermissionNeeded, announcer.announcements.last())

        voice.next = AppResult.Success("كيف الطقس")
        engine.dispatch(AssistantAction.ListenForCommand)
        advanceUntilIdle()
        assertEquals(Announcement.VoiceNotUnderstood, announcer.announcements.last())
        assertEquals(AssistantPhase.Ready, engine.state.value.phase)
    }

    @Test
    fun `verbosity preference is persisted and used for the next request`() = runTest {
        val engine = createEngine()
        advanceUntilIdle()

        engine.dispatch(AssistantAction.SetVerbosity(Verbosity.DETAILED))
        advanceUntilIdle()
        engine.analyze()
        advanceUntilIdle()

        assertEquals(Verbosity.DETAILED, settings.current.verbosity)
        assertEquals(Verbosity.DETAILED, vision.requests.single().verbosity)
    }

    @Test
    fun `session start and end are forwarded to the glasses`() = runTest {
        val engine = createEngine()
        advanceUntilIdle()

        engine.dispatch(AssistantAction.StartSession)
        advanceUntilIdle()
        assertTrue(engine.state.value.sessionActive)

        engine.dispatch(AssistantAction.EndSession)
        advanceUntilIdle()
        assertFalse(engine.state.value.sessionActive)
        assertEquals(1, glasses.startSessionCalls)
        assertEquals(1, glasses.stopSessionCalls)
    }

    @Test
    fun `find phone toggles the locator`() = runTest {
        val engine = createEngine()
        advanceUntilIdle()

        engine.dispatch(AssistantAction.ToggleFindPhone)
        advanceUntilIdle()
        assertTrue(engine.state.value.phoneLocatorRinging)

        engine.dispatch(AssistantAction.ToggleFindPhone)
        advanceUntilIdle()
        assertFalse(engine.state.value.phoneLocatorRinging)
    }
}
