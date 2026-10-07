package com.basira.app.presentation.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.basira.app.core.BuildModes
import com.basira.app.data.accessibility.AccessibilityStateProvider
import com.basira.app.presentation.setup.ActivityEffect
import com.basira.app.presentation.setup.ActivityEffectBus
import com.basira.app.presentation.setup.PermissionStatusProvider
import com.basira.domain.assistant.AnalysisRequestSpec
import com.basira.domain.assistant.AssistantAction
import com.basira.domain.assistant.AssistantEngine
import com.basira.domain.model.AnalysisMode
import com.basira.domain.model.Verbosity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * ViewModel of the main screen with unidirectional data flow: [onAction] in, [uiState] out.
 *
 * It holds no operation state of its own; the app-scoped [AssistantEngine] owns the single in-flight
 * operation, so leaving the screen (or the ViewModel being cleared) neither duplicates nor orphans a
 * capture. Activity-bound work is requested through [ActivityEffectBus].
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val engine: AssistantEngine,
    private val effects: ActivityEffectBus,
    private val permissions: PermissionStatusProvider,
    accessibility: AccessibilityStateProvider,
) : ViewModel() {

    private val findText = MutableStateFlow("")

    /** Screen state. */
    val uiState: StateFlow<MainUiState> = combine(engine.state, findText, accessibility.screenReaderActive) { state, text, reader ->
        MainUiStateMapper.map(state, text, reader, BuildModes.isSimulatedGlasses, BuildModes.isFakeVision)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), MainUiState())

    /** Opens the glasses session when the screen is shown, if it is not open yet. */
    fun onScreenShown() {
        if (!engine.state.value.sessionActive) engine.dispatch(AssistantAction.StartSession)
    }

    /**
     * Handles a user intent.
     *
     * @param action the intent.
     */
    fun onAction(action: MainUiAction) {
        when (action) {
            MainUiAction.Describe -> analyze(AnalysisMode.SCENE_DESCRIPTION)
            MainUiAction.ReadText -> analyze(AnalysisMode.READ_TEXT)
            MainUiAction.Currency -> analyze(AnalysisMode.CURRENCY)
            MainUiAction.Find -> analyze(AnalysisMode.FIND_OBJECT, findText.value)
            is MainUiAction.FindTextChanged -> findText.value = action.text.take(MAX_FIND_TEXT)
            MainUiAction.Cancel -> engine.dispatch(AssistantAction.Cancel)
            MainUiAction.Retry -> engine.dispatch(AssistantAction.Retry)
            MainUiAction.Repeat -> engine.dispatch(AssistantAction.Repeat)
            MainUiAction.StopSpeaking -> engine.dispatch(AssistantAction.StopSpeaking)
            MainUiAction.VoiceCommand -> if (permissions.hasMicrophone()) {
                engine.dispatch(AssistantAction.ListenForCommand)
            } else {
                effects.send(ActivityEffect.RequestMicrophoneThenListen)
            }
            MainUiAction.ToggleDetailed -> engine.dispatch(
                AssistantAction.SetVerbosity(if (uiState.value.detailed) Verbosity.SHORT else Verbosity.DETAILED),
            )
            MainUiAction.FindPhone -> engine.dispatch(AssistantAction.ToggleFindPhone)
            MainUiAction.AnnounceStatus -> engine.dispatch(AssistantAction.AnnounceStatus)
            is MainUiAction.Setup -> effects.send(ActivityEffect.Setup(action.action))
        }
    }

    private fun analyze(mode: AnalysisMode, target: String? = null) {
        engine.dispatch(AssistantAction.Analyze(AnalysisRequestSpec(mode, target)))
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val MAX_FIND_TEXT = 80
    }
}
