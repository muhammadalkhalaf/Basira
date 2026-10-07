package com.basira.app.integration

import com.basira.core.reporting.NoOpErrorReporter
import com.basira.app.data.glasses.FakeGlassesRepository
import com.basira.app.data.vision.gemini.answerJson
import com.basira.app.data.vision.gemini.geminiRepository
import com.basira.app.data.vision.gemini.interactionBody
import com.basira.app.testutil.ResourceSampleImageSource
import com.basira.core.error.AppError
import com.basira.domain.assistant.AnalysisRequestSpec
import com.basira.domain.assistant.AnnouncementTextProvider
import com.basira.domain.assistant.AssistantAction
import com.basira.domain.assistant.AssistantEngine
import com.basira.domain.assistant.AssistantPhase
import com.basira.domain.assistant.Announcement
import com.basira.domain.assistant.SpeakingAnnouncer
import com.basira.domain.fakes.FakeAppLanguageRepository
import com.basira.domain.fakes.FakeConnectivityObserver
import com.basira.domain.fakes.FakeHistoryRepository
import com.basira.domain.fakes.FakeImageArchive
import com.basira.domain.fakes.FakePhoneLocator
import com.basira.domain.fakes.FakeSettingsRepository
import com.basira.domain.fakes.FakeSpeechOutput
import com.basira.domain.fakes.FakeVoiceRecognizer
import com.basira.domain.fakes.RecordingFeedbackPlayer
import com.basira.domain.model.AnalysisMode
import com.basira.domain.model.ConnectivityStatus
import com.basira.domain.usecase.AnalyzeSurroundingsUseCase
import com.basira.domain.usecase.DescribeSceneUseCase
import com.basira.domain.usecase.DescriptionValidator
import com.basira.domain.usecase.FindObjectUseCase
import com.basira.domain.usecase.IdentifyCurrencyUseCase
import com.basira.domain.usecase.NoResultReason
import com.basira.domain.usecase.ReadTextUseCase
import com.basira.domain.voice.VoiceCommandParser
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end flow on the JVM: assistant engine + real use cases + [FakeGlassesRepository] with the
 * standard sample images + real Gemini repository (Interactions API format) against [MockWebServer] +
 * [FakeSpeechOutput] + [FakeConnectivityObserver].
 */
class AssistantFlowIntegrationTest {

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var engine: AssistantEngine
    private val samples = ResourceSampleImageSource()
    private val glasses = FakeGlassesRepository(samples, captureDelayMillis = 150)
    private val speech = FakeSpeechOutput()
    private val connectivity = FakeConnectivityObserver()
    private val feedback = RecordingFeedbackPlayer()
    /** Scripted Gemini: one queued status code per request. */
    private val scriptedCodes = ArrayDeque<Int>()
    private val descriptions = ArrayDeque<String>()
    private val uploadedImageSizes = mutableListOf<Int>()
    private val requestCounter = AtomicInteger()

    private val texts = AnnouncementTextProvider { announcement ->
        when (announcement) {
            is Announcement.Description -> announcement.description.text
            else -> announcement::class.simpleName
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = respond(request)
        }
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        engine = buildEngine()
        engine.start()
    }

    @After
    fun tearDown() {
        engine.shutdown()
        scope.cancel()
        server.close()
    }

    private fun respond(request: RecordedRequest): MockResponse {
        requestCounter.incrementAndGet()
        val body = Json.parseToJsonElement(request.body?.utf8().orEmpty()).jsonObject
        val image = body["input"]!!.jsonArray.map { it.jsonObject }.first { it["type"]!!.jsonPrimitive.content == "image" }
        synchronized(uploadedImageSizes) { uploadedImageSizes += image["data"]!!.jsonPrimitive.content.length }
        val code = synchronized(scriptedCodes) { scriptedCodes.removeFirstOrNull() } ?: 200
        if (code != 200) {
            val errorCode = if (code == 403) "permission_denied" else "service_unavailable"
            return MockResponse.Builder().code(code).addHeader("Content-Type", "application/json")
                .body("""{"error":{"code":"$errorCode","message":"x"}}""").build()
        }
        val text = synchronized(descriptions) { descriptions.removeFirstOrNull() } ?: "وصف"
        return MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
            .body(interactionBody(answerJson(description = text)))
            .build()
    }

    private fun buildEngine(): AssistantEngine {
        val vision = geminiRepository(server)
        val analyze = AnalyzeSurroundingsUseCase(
            glasses = glasses,
            vision = vision,
            connectivity = connectivity,
            archive = FakeImageArchive(),
            requestIds = { UUID.randomUUID().toString() },
            clock = { System.currentTimeMillis() },
            validator = DescriptionValidator(),
            appLanguage = FakeAppLanguageRepository(),
        )
        return AssistantEngine(
            glasses = glasses,
            speech = speech,
            announcer = SpeakingAnnouncer(texts, speech),
            feedback = feedback,
            connectivity = connectivity,
            settingsRepository = FakeSettingsRepository(),
            history = FakeHistoryRepository(),
            voiceRecognizer = FakeVoiceRecognizer(),
            voiceParser = VoiceCommandParser(),
            phoneLocator = FakePhoneLocator(),
            describeScene = DescribeSceneUseCase(analyze),
            readText = ReadTextUseCase(analyze),
            findObject = FindObjectUseCase(analyze),
            identifyCurrency = IdentifyCurrencyUseCase(analyze),
            scope = scope,
            errorReporter = NoOpErrorReporter,
        )
    }

    private suspend fun awaitPhase(predicate: (AssistantPhase) -> Boolean): AssistantPhase =
        withTimeout(10_000) { engine.state.first { predicate(it.phase) }.phase }

    private suspend fun describeAndAwait(mode: AnalysisMode = AnalysisMode.SCENE_DESCRIPTION): AssistantPhase {
        awaitPhase { it == AssistantPhase.Ready || it == AssistantPhase.Loaded || it is AssistantPhase.EmptyResult || !it.isBusy && it != AssistantPhase.Initializing }
        engine.dispatch(AssistantAction.Analyze(AnalysisRequestSpec(mode, if (mode == AnalysisMode.FIND_OBJECT) "باب" else null)))
        awaitPhase { it.isBusy }
        return awaitPhase { !it.isBusy && it != AssistantPhase.Speaking }
    }

    @Test
    fun `each standard sample image is uploaded and its description spoken`() = runBlocking {
        descriptions += listOf("لافتة مخرج", "درج نازل أمامك", "غرفة فيها طاولة", "")

        assertEquals(AssistantPhase.Loaded, describeAndAwait(AnalysisMode.READ_TEXT))
        assertEquals(AssistantPhase.Loaded, describeAndAwait())
        assertEquals(AssistantPhase.Loaded, describeAndAwait(AnalysisMode.FIND_OBJECT))
        assertEquals(AssistantPhase.EmptyResult(NoResultReason.NOTHING_RECOGNIZED), describeAndAwait())

        assertTrue(speech.spoken.containsAll(listOf("لافتة مخرج", "درج نازل أمامك", "غرفة فيها طاولة")))
        assertEquals("غرفة فيها طاولة", engine.state.value.lastDescription?.text)
        assertEquals(4, uploadedImageSizes.size)
        uploadedImageSizes.forEach { assertTrue("base64 image too small: $it", it > 10_000) }
    }

    @Test
    fun `offline to online never resends the previous capture`() = runBlocking {
        awaitPhase { it == AssistantPhase.Ready }
        connectivity.status.value = ConnectivityStatus.OFFLINE
        awaitPhase { it == AssistantPhase.Offline }

        engine.dispatch(AssistantAction.Analyze(AnalysisRequestSpec(AnalysisMode.SCENE_DESCRIPTION)))
        delay(300)
        connectivity.status.value = ConnectivityStatus.ONLINE
        awaitPhase { it == AssistantPhase.Ready }
        delay(500)

        assertEquals(0, requestCounter.get())
        assertEquals(0, glasses.captureCount)
    }

    @Test
    fun `transient 503 is retried and the result delivered`() = runBlocking {
        scriptedCodes += 503
        descriptions += "باب مفتوح"

        assertEquals(AssistantPhase.Loaded, describeAndAwait())
        assertEquals(2, requestCounter.get())
        assertEquals(1, glasses.captureCount)
    }

    @Test
    fun `rejected API key is fatal, spoken, and not retried`() = runBlocking {
        scriptedCodes += 403

        assertEquals(AssistantPhase.FatalError(AppError.ApiKeyRejected), describeAndAwait())
        assertEquals(1, requestCounter.get())
        assertEquals(1, glasses.captureCount)
    }

    @Test
    fun `glasses disconnecting during capture is reported without uploading`() = runBlocking {
        awaitPhase { it == AssistantPhase.Ready }
        engine.dispatch(AssistantAction.Analyze(AnalysisRequestSpec(AnalysisMode.SCENE_DESCRIPTION)))
        awaitPhase { it is AssistantPhase.Capturing }
        glasses.simulateConnection(false)

        assertEquals(AssistantPhase.GlassesDisconnected, awaitPhase { !it.isBusy })
        assertEquals(0, requestCounter.get())
    }

    @Test
    fun `cancel during analysis keeps the previous description`() = runBlocking {
        descriptions += "طاولة"
        assertEquals(AssistantPhase.Loaded, describeAndAwait())
        val previous = engine.state.value.lastDescription

        engine.dispatch(AssistantAction.Analyze(AnalysisRequestSpec(AnalysisMode.SCENE_DESCRIPTION)))
        awaitPhase { it.isBusy }
        engine.dispatch(AssistantAction.Cancel)

        assertEquals(AssistantPhase.Ready, awaitPhase { !it.isBusy })
        assertEquals(previous, engine.state.value.lastDescription)
    }
}
