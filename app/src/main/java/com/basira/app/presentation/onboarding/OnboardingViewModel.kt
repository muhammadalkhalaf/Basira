package com.basira.app.presentation.onboarding

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.basira.app.R
import com.basira.app.localization.SetupAction
import com.basira.app.localization.withLanguage
import com.basira.app.presentation.setup.ActivityEffect
import com.basira.app.presentation.setup.ActivityEffectBus
import com.basira.app.presentation.setup.PermissionStatusProvider
import com.basira.domain.assistant.AssistantEngine
import com.basira.domain.assistant.AssistantState
import com.basira.domain.model.CameraPermissionStatus
import com.basira.domain.model.LinkStatus
import com.basira.domain.model.RegistrationStatus
import com.basira.domain.model.SdkState
import com.basira.domain.model.SpeechAvailability
import com.basira.domain.repository.AppLanguageRepository
import com.basira.domain.repository.SettingsRepository
import com.basira.domain.repository.SpeechOutput
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** State of one setup check. */
enum class CheckState(@param:StringRes val label: Int) {
    /** Completed. */
    DONE(R.string.check_state_done),

    /** Needs user action. */
    NEEDED(R.string.check_state_needed),

    /** Still being determined. */
    CHECKING(R.string.check_state_checking),
}

/**
 * One row of the setup checklist.
 *
 * @property label row label.
 * @property state current state.
 * @property action action that resolves the row, if any.
 * @property requestsNotifications whether the action is the notification permission request.
 */
data class SetupCheck(
    @param:StringRes val label: Int,
    val state: CheckState,
    val action: SetupAction? = null,
    val requestsNotifications: Boolean = false,
)

/**
 * Immutable onboarding state.
 *
 * @property step current zero-based step.
 * @property totalSteps number of steps.
 * @property checks setup checklist.
 * @property voiceCheck voice row for the app language.
 */
data class OnboardingUiState(
    val step: Int = 0,
    val totalSteps: Int = TOTAL_STEPS,
    val checks: List<SetupCheck> = emptyList(),
    val voiceCheck: SetupCheck = SetupCheck(R.string.check_voice, CheckState.CHECKING),
) {
    companion object {
        /** Number of onboarding steps: welcome, consent, glasses, voice, done. */
        const val TOTAL_STEPS: Int = 5

        /** Index of the consent step. */
        const val CONSENT_STEP: Int = 1
    }
}

/**
 * Guided first-run setup that can be completed with TalkBack: welcome, privacy and safety consent
 * (required), glasses and permission checklist, voice check, and completion.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    engine: AssistantEngine,
    private val settings: SettingsRepository,
    private val effects: ActivityEffectBus,
    private val permissions: PermissionStatusProvider,
    private val speech: SpeechOutput,
    private val appLanguage: AppLanguageRepository,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    private val step = MutableStateFlow(0)
    private val permissionTick = MutableStateFlow(0)

    /** Screen state. */
    val uiState: StateFlow<OnboardingUiState> = combine(step, engine.state, permissionTick) { current, state, _ ->
        OnboardingUiState(step = current, checks = checksFor(state), voiceCheck = voiceCheckFor(state))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnboardingUiState())

    /** Moves to the next step. The consent step only advances through [acceptConsent]. */
    fun next() {
        if (step.value == OnboardingUiState.CONSENT_STEP) return
        step.value = (step.value + 1).coerceAtMost(OnboardingUiState.TOTAL_STEPS - 1)
    }

    /** Moves to the previous step. */
    fun back() {
        step.value = (step.value - 1).coerceAtLeast(0)
    }

    /** Records consent and continues. */
    fun acceptConsent() {
        viewModelScope.launch {
            settings.update { it.copy(consentAccepted = true) }
            step.value = OnboardingUiState.CONSENT_STEP + 1
        }
    }

    /** Declining consent closes the app; nothing is captured without consent. */
    fun declineConsent() = effects.send(ActivityEffect.Finish)

    /**
     * Runs a setup action.
     *
     * @param check the checklist row.
     */
    fun resolve(check: SetupCheck) {
        when {
            check.requestsNotifications -> effects.send(ActivityEffect.RequestNotifications)
            check.action != null -> effects.send(ActivityEffect.Setup(check.action))
        }
    }

    /** Re-reads Android permissions, for example after returning from a dialog. */
    fun refreshPermissions() {
        permissionTick.value += 1
    }

    /** Speaks a test sentence in the app language. */
    fun testVoice() {
        val texts = context.withLanguage(appLanguage.language.value)
        viewModelScope.launch { speech.speak(texts.getString(R.string.voice_test_sentence)) }
    }

    /**
     * Marks onboarding as finished.
     *
     * @param onDone invoked after the flag was persisted.
     */
    fun finish(onDone: () -> Unit) {
        viewModelScope.launch {
            settings.update { it.copy(onboardingCompleted = true) }
            onDone()
        }
    }

    private fun checksFor(state: AssistantState): List<SetupCheck> {
        val glasses = state.glasses
        fun row(label: Int, done: Boolean, action: SetupAction?, checking: Boolean = false, notifications: Boolean = false) =
            SetupCheck(
                label = label,
                state = when {
                    done -> CheckState.DONE
                    checking -> CheckState.CHECKING
                    else -> CheckState.NEEDED
                },
                action = action.takeIf { !done },
                requestsNotifications = notifications && !done,
            )
        return listOf(
            row(R.string.check_bluetooth, permissions.hasBluetooth() || glasses.isSimulated, SetupAction.ALLOW_BLUETOOTH),
            row(R.string.check_notifications, permissions.hasNotifications(), null, notifications = true),
            row(R.string.check_meta_ai, glasses.metaAiInstalled, SetupAction.INSTALL_META_AI),
            row(
                R.string.check_registration,
                glasses.registration == RegistrationStatus.REGISTERED,
                SetupAction.CONNECT_META_AI,
                checking = glasses.registration == RegistrationStatus.REGISTERING || glasses.sdk == SdkState.NOT_INITIALIZED,
            ),
            row(R.string.check_glasses, glasses.device?.link == LinkStatus.CONNECTED, SetupAction.OPEN_META_AI),
            row(
                R.string.check_camera,
                glasses.cameraPermission == CameraPermissionStatus.GRANTED,
                SetupAction.ALLOW_CAMERA,
                checking = glasses.cameraPermission == CameraPermissionStatus.UNKNOWN,
            ),
        )
    }

    private fun voiceCheckFor(@Suppress("UNUSED_PARAMETER") state: AssistantState): SetupCheck = when (speech.state.value.availability) {
        SpeechAvailability.READY -> SetupCheck(R.string.check_voice, CheckState.DONE)
        SpeechAvailability.INITIALIZING -> SetupCheck(R.string.check_voice, CheckState.CHECKING)
        SpeechAvailability.VOICE_MISSING -> SetupCheck(R.string.check_voice, CheckState.NEEDED, SetupAction.INSTALL_VOICE)
        SpeechAvailability.ENGINE_UNAVAILABLE -> SetupCheck(R.string.check_voice, CheckState.NEEDED, SetupAction.OPEN_TTS_SETTINGS)
    }
}
