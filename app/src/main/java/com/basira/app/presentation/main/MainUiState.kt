package com.basira.app.presentation.main

import androidx.annotation.StringRes
import com.basira.app.R
import com.basira.app.localization.PhaseStrings
import com.basira.app.localization.PhaseText
import com.basira.app.localization.SetupAction
import com.basira.domain.assistant.AssistantPhase
import com.basira.domain.assistant.AssistantState
import com.basira.domain.model.AudioRoute
import com.basira.domain.model.Confidence
import com.basira.domain.model.Verbosity

/** Non-color status category; rendered as an icon plus text, never as color alone. */
enum class StatusTone {
    /** Informational. */
    NEUTRAL,

    /** Work in progress. */
    PROGRESS,

    /** Success. */
    SUCCESS,

    /** Needs user action. */
    WARNING,

    /** Failure. */
    ERROR,
}

/**
 * Immutable state of the main screen.
 *
 * @property phase assistant phase.
 * @property statusText localized status text.
 * @property tone status category.
 * @property progressStep 1-based step of capture/upload/analyze, or `null`.
 * @property isBusy whether an operation is running.
 * @property canDescribe whether the main action is enabled.
 * @property canCancel whether cancel is offered.
 * @property canRetry whether retry is offered.
 * @property canRepeat whether repeat is offered.
 * @property isSpeaking whether speech is playing.
 * @property setupAction recovery action for the current phase.
 * @property lastDescription text of the last successful description.
 * @property lastConfidence label of its confidence.
 * @property audioRouteLabel label of the audio route.
 * @property detailed whether detailed descriptions are on.
 * @property findObjectText text typed into the object field.
 * @property isSimulatedGlasses whether glasses are simulated.
 * @property isFakeVision whether descriptions are samples.
 * @property sessionActive whether the glasses session is open.
 * @property phoneRinging whether the phone-locator sound plays.
 * @property statusLiveRegion whether the status card is a polite live region (TalkBack on).
 */
data class MainUiState(
    val phase: AssistantPhase = AssistantPhase.Initializing,
    val statusText: PhaseText = PhaseStrings.of(AssistantPhase.Initializing),
    val tone: StatusTone = StatusTone.PROGRESS,
    val progressStep: Int? = null,
    val isBusy: Boolean = false,
    val canDescribe: Boolean = false,
    val canCancel: Boolean = false,
    val canRetry: Boolean = false,
    val canRepeat: Boolean = false,
    val isSpeaking: Boolean = false,
    val setupAction: SetupAction? = null,
    val lastDescription: String? = null,
    @param:StringRes val lastConfidence: Int? = null,
    @param:StringRes val audioRouteLabel: Int = R.string.route_phone,
    val detailed: Boolean = false,
    val findObjectText: String = "",
    val isSimulatedGlasses: Boolean = false,
    val isFakeVision: Boolean = false,
    val sessionActive: Boolean = false,
    val phoneRinging: Boolean = false,
    val statusLiveRegion: Boolean = false,
)

/** User intents on the main screen. */
sealed interface MainUiAction {
    /** Describe the scene. */
    data object Describe : MainUiAction

    /** Read text. */
    data object ReadText : MainUiAction

    /** Identify a banknote. */
    data object Currency : MainUiAction

    /** Look for the typed object. */
    data object Find : MainUiAction

    /**
     * The object field changed.
     *
     * @property text new text.
     */
    data class FindTextChanged(val text: String) : MainUiAction

    /** Cancel. */
    data object Cancel : MainUiAction

    /** Retry. */
    data object Retry : MainUiAction

    /** Repeat the last description. */
    data object Repeat : MainUiAction

    /** Stop speaking. */
    data object StopSpeaking : MainUiAction

    /** Listen for a voice command. */
    data object VoiceCommand : MainUiAction

    /** Toggle detailed descriptions. */
    data object ToggleDetailed : MainUiAction

    /** Start or stop the phone-locator sound. */
    data object FindPhone : MainUiAction

    /** Speak the status. */
    data object AnnounceStatus : MainUiAction

    /**
     * Run a setup action.
     *
     * @property action the action.
     */
    data class Setup(val action: SetupAction) : MainUiAction
}

/** Pure mapping from [AssistantState] to [MainUiState]; unit-tested without Android. */
object MainUiStateMapper {

    /**
     * @param state engine state.
     * @param findObjectText current object field text.
     * @param screenReaderActive whether TalkBack is on.
     * @param simulatedGlasses whether glasses are simulated in this build.
     * @param fakeVision whether descriptions are samples in this build.
     * @return the screen state.
     */
    fun map(
        state: AssistantState,
        findObjectText: String,
        screenReaderActive: Boolean,
        simulatedGlasses: Boolean,
        fakeVision: Boolean,
    ): MainUiState {
        val phase = state.phase
        val busy = phase.isBusy
        return MainUiState(
            phase = phase,
            statusText = PhaseStrings.of(phase),
            tone = toneOf(phase),
            progressStep = when (phase) {
                is AssistantPhase.Capturing -> 1
                is AssistantPhase.Uploading -> 2
                is AssistantPhase.Analyzing -> 3
                else -> null
            },
            isBusy = busy,
            canDescribe = !busy && phase != AssistantPhase.Initializing,
            canCancel = busy,
            canRetry = !busy && isRetryable(phase),
            canRepeat = !busy && state.lastDescription != null,
            isSpeaking = state.isSpeaking,
            setupAction = PhaseStrings.setupActionFor(phase),
            lastDescription = state.lastDescription?.text,
            lastConfidence = state.lastDescription?.confidence?.let(::confidenceLabel),
            audioRouteLabel = routeLabel(state.audioRoute),
            detailed = state.settings.verbosity == Verbosity.DETAILED,
            findObjectText = findObjectText,
            isSimulatedGlasses = simulatedGlasses,
            isFakeVision = fakeVision,
            sessionActive = state.sessionActive,
            phoneRinging = state.phoneLocatorRinging,
            statusLiveRegion = screenReaderActive,
        )
    }

    private fun isRetryable(phase: AssistantPhase): Boolean =
        phase is AssistantPhase.Timeout || phase is AssistantPhase.RateLimited || phase is AssistantPhase.AuthenticationExpired ||
            phase is AssistantPhase.InvalidImage || phase is AssistantPhase.InvalidServerResponse ||
            phase is AssistantPhase.RecoverableError || phase is AssistantPhase.EmptyResult

    private fun toneOf(phase: AssistantPhase): StatusTone = when {
        phase.isBusy || phase == AssistantPhase.Initializing || phase == AssistantPhase.Registering ||
            phase == AssistantPhase.Speaking -> StatusTone.PROGRESS
        phase == AssistantPhase.Ready || phase == AssistantPhase.Loaded -> StatusTone.SUCCESS
        phase is AssistantPhase.FatalError || phase is AssistantPhase.RecoverableError || phase == AssistantPhase.Timeout ||
            phase == AssistantPhase.InvalidServerResponse || phase == AssistantPhase.InvalidImage -> StatusTone.ERROR
        phase.isEnvironmental || phase is AssistantPhase.RateLimited || phase == AssistantPhase.AuthenticationExpired -> StatusTone.WARNING
        else -> StatusTone.NEUTRAL
    }

    @StringRes
    private fun confidenceLabel(confidence: Confidence): Int = when (confidence) {
        Confidence.HIGH -> R.string.confidence_high
        Confidence.MEDIUM -> R.string.confidence_medium
        Confidence.LOW -> R.string.confidence_low
        Confidence.UNKNOWN -> R.string.confidence_unknown
    }

    @StringRes
    private fun routeLabel(route: AudioRoute): Int = when (route) {
        AudioRoute.GLASSES -> R.string.route_glasses
        AudioRoute.PHONE_SPEAKER -> R.string.route_phone
        AudioRoute.OTHER_BLUETOOTH -> R.string.route_other_bluetooth
        AudioRoute.WIRED_HEADSET -> R.string.route_wired
    }
}
