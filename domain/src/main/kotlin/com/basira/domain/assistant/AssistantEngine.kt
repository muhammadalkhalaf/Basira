package com.basira.domain.assistant

import com.basira.core.coroutines.ApplicationScope
import com.basira.core.error.AppError
import com.basira.core.reporting.ErrorDomain
import com.basira.core.reporting.ErrorReport
import com.basira.core.reporting.ErrorReporter
import com.basira.core.reporting.ErrorSeverity
import com.basira.core.result.AppResult
import com.basira.domain.model.AnalysisMode
import com.basira.domain.model.AudioRoute
import com.basira.domain.model.ConnectivityStatus
import com.basira.domain.model.FeedbackCue
import com.basira.domain.model.GlassesStatus
import com.basira.domain.model.SceneDescription
import com.basira.domain.model.SpeechAvailability
import com.basira.domain.model.SpeechCompletion
import com.basira.domain.model.SpeechOutputState
import com.basira.domain.model.SpeechQueueMode
import com.basira.domain.model.UserSettings
import com.basira.domain.model.Verbosity
import com.basira.domain.repository.ConnectivityObserver
import com.basira.domain.repository.DescriptionHistoryRepository
import com.basira.domain.repository.FeedbackPlayer
import com.basira.domain.repository.GlassesRepository
import com.basira.domain.repository.PhoneLocator
import com.basira.domain.repository.SettingsRepository
import com.basira.domain.repository.SpeechOutput
import com.basira.domain.repository.VoiceCommandRecognizer
import com.basira.domain.usecase.AnalysisProgress
import com.basira.domain.usecase.DescribeSceneUseCase
import com.basira.domain.usecase.FindObjectUseCase
import com.basira.domain.usecase.IdentifyCurrencyUseCase
import com.basira.domain.usecase.ReadTextUseCase
import com.basira.domain.voice.VoiceCommand
import com.basira.domain.voice.VoiceCommandParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Parameters of a capture-and-analyze request, kept so the user can retry it.
 *
 * @property mode requested mode.
 * @property targetObject object name for object-finding mode.
 * @property verbosityOverride verbosity requested for this request only.
 */
data class AnalysisRequestSpec(
    val mode: AnalysisMode,
    val targetObject: String? = null,
    val verbosityOverride: Verbosity? = null,
)

/** User intents handled by [AssistantEngine]. */
sealed interface AssistantAction {
    /**
     * Capture and analyze one photo.
     *
     * @property spec request parameters.
     */
    data class Analyze(val spec: AnalysisRequestSpec) : AssistantAction

    /** Cancel the running operation. */
    data object Cancel : AssistantAction

    /** Repeat the last request with a new photo. */
    data object Retry : AssistantAction

    /** Speak the last successful description again. */
    data object Repeat : AssistantAction

    /** Stop speaking immediately. */
    data object StopSpeaking : AssistantAction

    /** Listen for a single voice command using the phone microphone. */
    data object ListenForCommand : AssistantAction

    /** Speak the current status. */
    data object AnnounceStatus : AssistantAction

    /**
     * Change the preferred verbosity.
     *
     * @property verbosity new verbosity.
     */
    data class SetVerbosity(val verbosity: Verbosity) : AssistantAction

    /** Open the glasses session. */
    data object StartSession : AssistantAction

    /** Close the glasses session. */
    data object EndSession : AssistantAction

    /** Start or stop the "where is my phone" sound. */
    data object ToggleFindPhone : AssistantAction
}

/**
 * Immutable snapshot of everything the presentation layer renders.
 *
 * @property phase current phase.
 * @property lastDescription last successful description, preserved across later failures.
 * @property lastRequest last requested analysis, used by retry.
 * @property audioRoute current speech output route.
 * @property isSpeaking whether speech is playing.
 * @property glasses aggregated glasses state.
 * @property connectivity phone connectivity.
 * @property settings current user settings.
 * @property sessionActive whether the user keeps a glasses session open.
 * @property phoneLocatorRinging whether the "where is my phone" sound is playing.
 */
data class AssistantState(
    val phase: AssistantPhase = AssistantPhase.Initializing,
    val lastDescription: SceneDescription? = null,
    val lastRequest: AnalysisRequestSpec? = null,
    val audioRoute: AudioRoute = AudioRoute.PHONE_SPEAKER,
    val isSpeaking: Boolean = false,
    val glasses: GlassesStatus = GlassesStatus(),
    val connectivity: ConnectivityStatus = ConnectivityStatus.UNKNOWN,
    val settings: UserSettings = UserSettings(),
    val sessionActive: Boolean = false,
    val phoneLocatorRinging: Boolean = false,
)

/**
 * Application-scoped state machine behind the voice-first flow.
 *
 * It is independent of screens so the in-app button, the foreground notification, the optional
 * media button, and voice commands all drive the same state. All public methods are cheap and
 * non-blocking; long work runs in [scope]. Exactly one operation (listening, capturing, uploading,
 * analyzing) can run at a time; duplicate requests produce a "busy" cue instead of a second
 * capture. The last successful description survives every later failure.
 *
 * Lifecycle: call [start] once. The engine lives as long as [scope]; [shutdown] exists for tests.
 */
@Singleton
class AssistantEngine @Inject constructor(
    private val glasses: GlassesRepository,
    private val speech: SpeechOutput,
    private val announcer: Announcer,
    private val feedback: FeedbackPlayer,
    private val connectivity: ConnectivityObserver,
    private val settingsRepository: SettingsRepository,
    private val history: DescriptionHistoryRepository,
    private val voiceRecognizer: VoiceCommandRecognizer,
    private val voiceParser: VoiceCommandParser,
    private val phoneLocator: PhoneLocator,
    private val describeScene: DescribeSceneUseCase,
    private val readText: ReadTextUseCase,
    private val findObject: FindObjectUseCase,
    private val identifyCurrency: IdentifyCurrencyUseCase,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val errorReporter: ErrorReporter,
) {

    /** Engine-owned mutable facts; everything else is derived from observed inputs. */
    private data class Control(
        val started: Boolean = false,
        val operation: AssistantPhase? = null,
        val operationId: Long = 0,
        val outcome: AssistantPhase? = null,
        val lastDescription: SceneDescription? = null,
        val lastRequest: AnalysisRequestSpec? = null,
        val sessionActive: Boolean = false,
    )

    private data class Inputs(
        val control: Control,
        val glasses: GlassesStatus,
        val speech: SpeechOutputState,
        val connectivity: ConnectivityStatus,
        val settings: UserSettings,
        val ringing: Boolean,
    )

    private val control = MutableStateFlow(Control())
    private val _state = MutableStateFlow(AssistantState())

    /** Current assistant state. */
    val state: StateFlow<AssistantState> = _state.asStateFlow()

    private var observeJob: Job? = null
    private var routeJob: Job? = null
    private var operationJob: Job? = null
    private var pendingStatusJob: Job? = null
    private var readiness: AssistantPhase? = null
    private var lastAnnouncedPhase: AssistantPhase? = null
    private var initialStatusAnnounced = false

    /** Starts observing inputs and announces the initial status once it is known. Idempotent. */
    fun start() {
        if (observeJob != null) return
        control.update { it.copy(started = true) }
        observeJob = scope.launch { observeInputs().collect(::onInputs) }
        routeJob = scope.launch { observeRouteChanges() }
    }

    /** Cancels all engine work. Intended for tests and process teardown. */
    fun shutdown() {
        operationJob?.cancel()
        pendingStatusJob?.cancel()
        observeJob?.cancel()
        routeJob?.cancel()
        observeJob = null
        routeJob = null
    }

    /**
     * Handles a user intent.
     *
     * @param action the intent.
     */
    fun dispatch(action: AssistantAction) {
        when (action) {
            is AssistantAction.Analyze -> analyze(action.spec)
            AssistantAction.Cancel -> cancel()
            AssistantAction.Retry -> analyze(control.value.lastRequest ?: AnalysisRequestSpec(AnalysisMode.SCENE_DESCRIPTION))
            AssistantAction.Repeat -> repeatLast()
            AssistantAction.StopSpeaking -> stopSpeaking()
            AssistantAction.ListenForCommand -> listenForCommand()
            AssistantAction.AnnounceStatus -> announceStatus()
            is AssistantAction.SetVerbosity -> setVerbosity(action.verbosity)
            AssistantAction.StartSession -> startSession()
            AssistantAction.EndSession -> endSession()
            AssistantAction.ToggleFindPhone -> toggleFindPhone()
        }
    }

    // region Observation

    private fun observeInputs(): Flow<Inputs> {
        val platform = combine(control, glasses.status, speech.state) { c, g, s -> Triple(c, g, s) }
        val environment = combine(connectivity.status, settingsRepository.settings, phoneLocator.isRinging) { n, u, r ->
            Triple(n, u, r)
        }
        return combine(platform, environment) { p, e ->
            Inputs(p.first, p.second, p.third, e.first, e.second, e.third)
        }
    }

    private fun onInputs(inputs: Inputs) {
        val newReadiness = resolveReadiness(inputs)
        val previous = readiness
        if (newReadiness != previous) {
            readiness = newReadiness
            if (control.value.outcome?.isEnvironmental == true) {
                control.update { it.copy(outcome = null) }
            }
            scheduleReadinessAnnouncement(newReadiness, previous, inputs.speech.route)
        }
        val current = control.value
        _state.value = AssistantState(
            phase = resolvePhase(current, newReadiness),
            lastDescription = current.lastDescription,
            lastRequest = current.lastRequest,
            audioRoute = inputs.speech.route,
            isSpeaking = inputs.speech.isSpeaking,
            glasses = inputs.glasses,
            connectivity = inputs.connectivity,
            settings = inputs.settings,
            sessionActive = current.sessionActive,
            phoneLocatorRinging = inputs.ringing,
        )
    }

    private fun resolveReadiness(inputs: Inputs): AssistantPhase {
        val availability = inputs.speech.availability
        return when {
            availability == SpeechAvailability.INITIALIZING || inputs.glasses.isResolving -> AssistantPhase.Initializing
            availability == SpeechAvailability.ENGINE_UNAVAILABLE -> AssistantPhase.SpeechUnavailable(false)
            availability == SpeechAvailability.VOICE_MISSING -> AssistantPhase.SpeechUnavailable(true)
            inputs.glasses.isRegistering -> AssistantPhase.Registering
            else -> inputs.glasses.blockingIssue()?.let(AssistantPhase::fromError)
                ?: if (inputs.connectivity == ConnectivityStatus.OFFLINE) AssistantPhase.Offline else AssistantPhase.Ready
        }
    }

    private fun resolvePhase(current: Control, readiness: AssistantPhase): AssistantPhase = when {
        !current.started -> AssistantPhase.Initializing
        current.operation != null -> current.operation
        readiness != AssistantPhase.Ready -> readiness
        else -> current.outcome ?: AssistantPhase.Ready
    }

    private fun scheduleReadinessAnnouncement(
        newReadiness: AssistantPhase,
        previous: AssistantPhase?,
        route: AudioRoute,
    ) {
        pendingStatusJob?.cancel()
        if (newReadiness == AssistantPhase.Initializing) return
        pendingStatusJob = scope.launch {
            // Debounce so flapping links or start-up races produce a single announcement.
            delay(STATUS_DEBOUNCE_MILLIS)
            val busy = control.value.operation
            if (busy?.isBusy == true) return@launch
            val isFirst = !initialStatusAnnounced
            if (!isFirst && newReadiness == lastAnnouncedPhase) return@launch
            initialStatusAnnounced = true
            lastAnnouncedPhase = newReadiness
            if (newReadiness == AssistantPhase.Offline) feedback.play(FeedbackCue.OFFLINE)
            val announcement = Announcement.Status(
                phase = newReadiness,
                previous = previous?.takeIf { !isFirst && it.isEnvironmental },
                route = route.takeIf { it != AudioRoute.GLASSES && newReadiness == AssistantPhase.Ready },
            )
            announcer.announce(announcement, if (busy != null) SpeechQueueMode.ADD else SpeechQueueMode.FLUSH)
        }
    }

    private suspend fun observeRouteChanges() {
        speech.state.map { it.route }.distinctUntilChanged().drop(1).collect { route ->
            if (!initialStatusAnnounced) return@collect
            // A route lost during playback is reported by the speaking operation itself.
            if (control.value.operation == AssistantPhase.Speaking) return@collect
            announcer.announce(Announcement.RouteChanged(route), SpeechQueueMode.ADD)
        }
    }

    // endregion

    // region Operations

    /**
     * Atomically claims the single operation slot.
     *
     * A [AssistantPhase.Speaking] operation may be pre-empted; busy phases may not.
     *
     * @return the new operation id, or `null` when another busy operation is running.
     */
    private fun tryAcquire(phase: AssistantPhase): Long? {
        var acquiredId: Long? = null
        control.update { current ->
            if (current.operation?.isBusy == true) {
                acquiredId = null
                current
            } else {
                val id = current.operationId + 1
                acquiredId = id
                current.copy(operation = phase, operationId = id, outcome = null)
            }
        }
        return acquiredId
    }

    private fun setOperation(id: Long, phase: AssistantPhase) {
        control.update { if (it.operationId == id) it.copy(operation = phase) else it }
    }

    private fun finishOperation(id: Long, outcome: AssistantPhase?) {
        control.update { if (it.operationId == id) it.copy(operation = null, outcome = outcome) else it }
    }

    private fun rejectAsBusy() {
        feedback.play(FeedbackCue.BUSY)
        scope.launch { announcer.announce(Announcement.Busy, SpeechQueueMode.ADD) }
    }

    private fun analyze(spec: AnalysisRequestSpec) {
        if (control.value.operation?.isBusy == true) return rejectAsBusy()
        val ready = readiness ?: AssistantPhase.Initializing
        if (ready != AssistantPhase.Ready) {
            feedback.play(FeedbackCue.ERROR)
            lastAnnouncedPhase = ready
            scope.launch { announcer.announce(Announcement.Status(ready)) }
            return
        }
        val id = tryAcquire(AssistantPhase.Capturing(spec.mode)) ?: return rejectAsBusy()
        control.update { it.copy(lastRequest = spec) }
        phoneLocator.stop()
        operationJob?.cancel()
        operationJob = scope.launch { runAnalysis(id, spec) }
    }

    private suspend fun runAnalysis(id: Long, spec: AnalysisRequestSpec) {
        speech.stop()
        feedback.play(FeedbackCue.CAPTURE)
        val captureAnnouncement = scope.launch { announcer.announce(Announcement.CaptureStarted(spec.mode, spec.targetObject)) }
        try {
            val verbosity = spec.verbosityOverride ?: _state.value.settings.verbosity
            progressFor(spec, verbosity).collect { progress ->
                handleProgress(id, spec, progress, captureAnnouncement)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unexpected: Exception) {
            errorReporter.report(
                ErrorReport(
                    domain = ErrorDomain.ASSISTANT,
                    operation = "assistant.analysis",
                    severity = ErrorSeverity.ERROR,
                    outcome = "error_announced",
                    throwable = unexpected,
                    attributes = mapOf("analysis.mode" to spec.mode.name),
                ),
            )
            reportFailure(id, AppError.Unexpected())
        } finally {
            captureAnnouncement.cancel()
            control.update {
                if (it.operationId == id && it.operation?.isBusy == true) it.copy(operation = null) else it
            }
        }
    }

    private fun progressFor(spec: AnalysisRequestSpec, verbosity: Verbosity): Flow<AnalysisProgress> = when (spec.mode) {
        AnalysisMode.SCENE_DESCRIPTION -> describeScene(verbosity)
        AnalysisMode.READ_TEXT -> readText(verbosity)
        AnalysisMode.FIND_OBJECT -> findObject(spec.targetObject.orEmpty(), verbosity)
        AnalysisMode.CURRENCY -> identifyCurrency(verbosity)
    }

    private suspend fun handleProgress(
        id: Long,
        spec: AnalysisRequestSpec,
        progress: AnalysisProgress,
        captureAnnouncement: Job,
    ) {
        when (progress) {
            AnalysisProgress.Capturing -> setOperation(id, AssistantPhase.Capturing(spec.mode))
            AnalysisProgress.Uploading -> setOperation(id, AssistantPhase.Uploading(spec.mode))
            AnalysisProgress.Analyzing -> {
                setOperation(id, AssistantPhase.Analyzing(spec.mode))
                feedback.play(FeedbackCue.PROCESSING)
            }
            is AnalysisProgress.Completed -> {
                captureAnnouncement.cancel()
                deliverDescription(id, progress.description)
            }
            is AnalysisProgress.NoResult -> {
                captureAnnouncement.cancel()
                val phase = AssistantPhase.EmptyResult(progress.reason)
                finishOperation(id, phase)
                lastAnnouncedPhase = phase
                feedback.play(FeedbackCue.ERROR)
                announcer.announce(Announcement.Status(phase))
            }
            is AnalysisProgress.Failed -> {
                captureAnnouncement.cancel()
                reportFailure(id, progress.error)
            }
        }
    }

    private suspend fun deliverDescription(id: Long, description: SceneDescription) {
        control.update { if (it.operationId == id) it.copy(lastDescription = description) else it }
        try {
            history.add(description)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (storageFailure: Exception) {
            errorReporter.report(
                ErrorReport(
                    domain = ErrorDomain.STORAGE,
                    operation = "history.add",
                    severity = ErrorSeverity.WARNING,
                    outcome = "description_spoken_not_saved",
                    throwable = storageFailure,
                ),
            )
        }
        feedback.play(FeedbackCue.SUCCESS)
        speakDescription(id, description, repeated = false)
    }

    private suspend fun speakDescription(id: Long, description: SceneDescription, repeated: Boolean) {
        setOperation(id, AssistantPhase.Speaking)
        lastAnnouncedPhase = AssistantPhase.Loaded
        val completion = try {
            announcer.announce(Announcement.Description(description, repeated))
        } finally {
            finishOperation(id, AssistantPhase.Loaded)
        }
        if (completion == SpeechCompletion.ROUTE_LOST) {
            announcer.announce(Announcement.GlassesAudioLost)
        }
    }

    private suspend fun reportFailure(id: Long, error: AppError) {
        val phase = if (error == AppError.MissingTargetObject) {
            AssistantPhase.RecoverableError(error)
        } else {
            AssistantPhase.fromError(error)
        }
        finishOperation(id, phase)
        lastAnnouncedPhase = phase
        feedback.play(if (error == AppError.Offline) FeedbackCue.OFFLINE else FeedbackCue.ERROR)
        val announcement = if (error == AppError.MissingTargetObject) {
            Announcement.TargetObjectMissing
        } else {
            Announcement.Status(phase)
        }
        announcer.announce(announcement)
    }

    private fun cancel() {
        val current = control.value
        val job = operationJob
        if (current.operation == null || current.operation == AssistantPhase.Speaking || job == null || !job.isActive) {
            stopSpeaking()
            return
        }
        job.cancel()
        speech.stop()
        control.update { it.copy(operation = null, outcome = null, operationId = it.operationId + 1) }
        feedback.play(FeedbackCue.CANCELLED)
        scope.launch { announcer.announce(Announcement.Cancelled) }
    }

    private fun repeatLast() {
        val description = control.value.lastDescription
        if (description == null) {
            scope.launch { announcer.announce(Announcement.NothingToRepeat) }
            return
        }
        val id = tryAcquire(AssistantPhase.Speaking) ?: return rejectAsBusy()
        operationJob?.cancel()
        operationJob = scope.launch { speakDescription(id, description, repeated = true) }
    }

    private fun stopSpeaking() {
        speech.stop()
        phoneLocator.stop()
    }

    private fun listenForCommand() {
        val id = tryAcquire(AssistantPhase.Listening) ?: return rejectAsBusy()
        operationJob?.cancel()
        operationJob = scope.launch {
            speech.stop()
            feedback.play(FeedbackCue.LISTENING)
            val result = try {
                voiceRecognizer.listenOnce()
            } finally {
                finishOperation(id, null)
            }
            when (result) {
                is AppResult.Success -> voiceParser.parse(result.value)?.let(::execute)
                    ?: announcer.announce(Announcement.VoiceNotUnderstood)
                is AppResult.Failure -> announcer.announce(voiceFailureAnnouncement(result.error))
            }
        }
    }

    private fun voiceFailureAnnouncement(error: AppError): Announcement = when (error) {
        AppError.MicrophonePermissionRequired -> Announcement.MicrophonePermissionNeeded
        AppError.SpeechRecognitionUnavailable -> Announcement.VoiceUnavailable
        else -> Announcement.VoiceNotUnderstood
    }

    private fun execute(command: VoiceCommand) {
        when (command) {
            is VoiceCommand.Describe -> analyze(AnalysisRequestSpec(AnalysisMode.SCENE_DESCRIPTION, verbosityOverride = command.verbosityOverride))
            VoiceCommand.ReadText -> analyze(AnalysisRequestSpec(AnalysisMode.READ_TEXT))
            is VoiceCommand.FindObject -> analyze(AnalysisRequestSpec(AnalysisMode.FIND_OBJECT, targetObject = command.target))
            VoiceCommand.Currency -> analyze(AnalysisRequestSpec(AnalysisMode.CURRENCY))
            VoiceCommand.Repeat -> repeatLast()
            VoiceCommand.StopSpeaking -> stopSpeaking()
            VoiceCommand.Cancel -> cancel()
            VoiceCommand.Status -> announceStatus()
        }
    }

    private fun announceStatus() {
        val snapshot = _state.value
        lastAnnouncedPhase = snapshot.phase
        scope.launch {
            announcer.announce(Announcement.Status(snapshot.phase, route = snapshot.audioRoute.takeIf { it != AudioRoute.GLASSES }))
        }
    }

    private fun setVerbosity(verbosity: Verbosity) {
        scope.launch {
            settingsRepository.update { it.copy(verbosity = verbosity) }
            announcer.announce(Announcement.VerbosityChanged(verbosity))
        }
    }

    private fun startSession() {
        control.update { it.copy(sessionActive = true) }
        glasses.startSession()
    }

    private fun endSession() {
        cancel()
        glasses.stopSession()
        control.update { it.copy(sessionActive = false) }
        scope.launch { announcer.announce(Announcement.SessionEnded, SpeechQueueMode.ADD) }
    }

    private fun toggleFindPhone() {
        if (phoneLocator.isRinging.value) {
            phoneLocator.stop()
        } else {
            speech.stop()
            phoneLocator.start()
        }
    }

    // endregion

    private companion object {
        const val STATUS_DEBOUNCE_MILLIS = 1_200L
    }
}
