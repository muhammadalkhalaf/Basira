package com.basira.domain.fakes

import com.basira.core.error.AppError
import com.basira.core.result.AppResult
import com.basira.domain.assistant.Announcement
import com.basira.domain.assistant.Announcer
import com.basira.domain.model.AudioRoute
import com.basira.domain.model.CameraPermissionStatus
import com.basira.domain.model.CapturedImage
import com.basira.domain.model.CompatibilityStatus
import com.basira.domain.model.ConnectivityStatus
import com.basira.domain.model.FeedbackCue
import com.basira.domain.model.GlassesDevice
import com.basira.domain.model.GlassesStatus
import com.basira.domain.model.LinkStatus
import com.basira.domain.model.RegistrationStatus
import com.basira.domain.model.SceneDescription
import com.basira.domain.model.SdkState
import com.basira.domain.model.SessionStatus
import com.basira.domain.model.SpeechAvailability
import com.basira.domain.model.SpeechCompletion
import com.basira.domain.model.SpeechOutputState
import com.basira.domain.model.SpeechQueueMode
import com.basira.domain.model.UserSettings
import com.basira.domain.model.VisionAnalysisRequest
import com.basira.domain.model.VisionAnalysisResult
import com.basira.domain.model.WornStatus
import com.basira.domain.repository.CapturedImageArchive
import com.basira.domain.repository.ConnectivityObserver
import com.basira.domain.repository.DescriptionHistoryRepository
import com.basira.domain.repository.FeedbackPlayer
import com.basira.domain.repository.GlassesRepository
import com.basira.domain.repository.PhoneLocator
import com.basira.domain.repository.SettingsRepository
import com.basira.domain.repository.SpeechOutput
import com.basira.domain.repository.VisionAnalysisRepository
import com.basira.domain.repository.VoiceCommandRecognizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Glasses status that satisfies every readiness check. */
fun readyGlassesStatus(): GlassesStatus = GlassesStatus(
    sdk = SdkState.READY,
    metaAiInstalled = true,
    registration = RegistrationStatus.REGISTERED,
    device = GlassesDevice(
        name = "Ray-Ban Meta",
        link = LinkStatus.CONNECTED,
        worn = WornStatus.WORN,
        hingeOpen = true,
        batteryPercent = 80,
        charging = false,
        compatibility = CompatibilityStatus.COMPATIBLE,
        thermalWarning = false,
    ),
    session = SessionStatus.ACTIVE,
    cameraPermission = CameraPermissionStatus.GRANTED,
    isSimulated = true,
)

/** Small valid image used by tests. */
fun testImage(): CapturedImage = CapturedImage(ByteArray(16) { it.toByte() }, 4, 4)

/** Test double for [GlassesRepository] whose capture result and timing are scriptable. */
class FakeGlassesRepositoryForTest(initial: GlassesStatus = readyGlassesStatus()) : GlassesRepository {
    override val status = MutableStateFlow(initial)
    var captureCount = 0
        private set
    var startSessionCalls = 0
        private set
    var stopSessionCalls = 0
        private set
    var nextCapture: AppResult<CapturedImage> = AppResult.Success(testImage())

    /** When set, captures suspend until this deferred completes. */
    var captureGate: CompletableDeferred<Unit>? = null

    override fun startSession() {
        startSessionCalls++
    }

    override fun stopSession() {
        stopSessionCalls++
    }

    override suspend fun refreshCameraPermission(): CameraPermissionStatus = status.value.cameraPermission

    override fun onCameraPermissionResult(granted: Boolean) {
        status.update {
            it.copy(cameraPermission = if (granted) CameraPermissionStatus.GRANTED else CameraPermissionStatus.DENIED_BY_USER)
        }
    }

    override suspend fun captureImage(): AppResult<CapturedImage> {
        captureCount++
        captureGate?.await()
        return nextCapture
    }
}

/** Test double for [VisionAnalysisRepository]. */
class FakeVisionRepository : VisionAnalysisRepository {
    val requests = mutableListOf<VisionAnalysisRequest>()
    var responder: (VisionAnalysisRequest) -> VisionAnalysisResult = { request ->
        VisionAnalysisResult.Success(request.requestId, "أمامك باب مفتوح على بعد مترين.", com.basira.domain.model.Confidence.HIGH, emptyList(), 900)
    }
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun analyze(request: VisionAnalysisRequest, onUploadComplete: () -> Unit): VisionAnalysisResult {
        requests += request
        onUploadComplete()
        gate?.await()
        return responder(request)
    }
}

/** Test double for [SpeechOutput] that records utterances and completes immediately by default. */
class FakeSpeechOutput(
    availability: SpeechAvailability = SpeechAvailability.READY,
    route: AudioRoute = AudioRoute.GLASSES,
) : SpeechOutput {
    override val state = MutableStateFlow(SpeechOutputState(availability = availability, route = route))
    val spoken = mutableListOf<String>()
    var stopCount = 0
        private set
    var nextCompletion: SpeechCompletion = SpeechCompletion.COMPLETED

    /** Utterances matching this predicate block until [stop] is called. */
    var blockWhen: ((String) -> Boolean)? = null
    private var currentGate: CompletableDeferred<SpeechCompletion>? = null

    override suspend fun speak(text: String, queueMode: SpeechQueueMode): SpeechCompletion {
        if (state.value.availability != SpeechAvailability.READY) return SpeechCompletion.FAILED
        spoken += text
        state.update { it.copy(isSpeaking = true) }
        val completion = if (blockWhen?.invoke(text) == true) {
            CompletableDeferred<SpeechCompletion>().also { currentGate = it }.await()
        } else {
            nextCompletion
        }
        state.update { it.copy(isSpeaking = false) }
        return completion
    }

    override fun stop() {
        stopCount++
        currentGate?.complete(SpeechCompletion.INTERRUPTED)
    }

    override fun refreshAvailability() = Unit
}

/** [Announcer] that records announcements and delegates to an optional [SpeechOutput]. */
class RecordingAnnouncer(private val speech: FakeSpeechOutput? = null) : Announcer {
    val announcements = mutableListOf<Announcement>()

    override suspend fun announce(announcement: Announcement, queueMode: SpeechQueueMode): SpeechCompletion {
        announcements += announcement
        return speech?.speak(announcement.toString(), queueMode) ?: SpeechCompletion.COMPLETED
    }
}

/** Records feedback cues. */
class RecordingFeedbackPlayer : FeedbackPlayer {
    val cues = mutableListOf<FeedbackCue>()
    override fun play(cue: FeedbackCue) {
        cues += cue
    }
}

/** Scriptable connectivity. */
class FakeConnectivityObserver(initial: ConnectivityStatus = ConnectivityStatus.ONLINE) : ConnectivityObserver {
    override val status = MutableStateFlow(initial)
}

/** In-memory settings. */
class FakeSettingsRepository(initial: UserSettings = UserSettings(consentAccepted = true, onboardingCompleted = true)) :
    SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<UserSettings> = state
    val current: UserSettings get() = state.value
    override suspend fun update(transform: (UserSettings) -> UserSettings) {
        state.update(transform)
    }
}

/** In-memory history. */
class FakeHistoryRepository : DescriptionHistoryRepository {
    private val state = MutableStateFlow<List<SceneDescription>>(emptyList())
    override val history: Flow<List<SceneDescription>> = state
    override suspend fun add(description: SceneDescription) {
        state.update { listOf(description) + it }
    }
    override suspend fun clear() {
        state.value = emptyList()
    }
}

/** Archive that only counts calls. */
class FakeImageArchive : CapturedImageArchive {
    var saveCalls = 0
        private set
    override suspend fun saveIfEnabled(image: CapturedImage, requestId: String) {
        saveCalls++
    }
    override suspend fun clear() = Unit
    override suspend fun count(): Int = 0
}

/** Scriptable recognizer. */
class FakeVoiceRecognizer : VoiceCommandRecognizer {
    var next: AppResult<String> = AppResult.Failure(AppError.VoiceCommandNotUnderstood)
    override suspend fun listenOnce(): AppResult<String> = next
}

/** Phone locator that only flips a flag. */
class FakePhoneLocator : PhoneLocator {
    override val isRinging: StateFlow<Boolean> get() = ringing
    private val ringing = MutableStateFlow(false)
    override fun start() {
        ringing.value = true
    }
    override fun stop() {
        ringing.value = false
    }
}
