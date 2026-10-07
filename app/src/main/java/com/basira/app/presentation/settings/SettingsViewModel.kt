package com.basira.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.basira.app.core.BuildModes
import com.basira.domain.assistant.AssistantAction
import com.basira.domain.assistant.AssistantEngine
import com.basira.domain.model.LanguagePreference
import com.basira.domain.model.UserSettings
import com.basira.domain.model.Verbosity
import com.basira.domain.repository.AppLanguageRepository
import com.basira.domain.repository.CapturedImageArchive
import com.basira.domain.repository.DescriptionHistoryRepository
import com.basira.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Confirmation dialog currently shown. */
enum class SettingsDialog {
    /** Confirm enabling photo storage. */
    CONFIRM_SAVE_IMAGES,

    /** Confirm deleting local data. */
    CONFIRM_DELETE,
}

/**
 * Immutable settings state.
 *
 * @property settings persisted settings.
 * @property language the app language choice.
 * @property historyCount stored descriptions.
 * @property imageCount stored photos.
 * @property dialog visible dialog.
 * @property deletedMessageVisible whether "deleted" feedback is shown.
 * @property sessionActive whether the glasses session is open.
 * @property glassesMode build glasses mode.
 * @property fakeVision whether descriptions are samples.
 */
data class SettingsUiState(
    val settings: UserSettings = UserSettings(),
    val language: LanguagePreference = LanguagePreference.SYSTEM,
    val historyCount: Int = 0,
    val imageCount: Int = 0,
    val dialog: SettingsDialog? = null,
    val deletedMessageVisible: Boolean = false,
    val sessionActive: Boolean = false,
    val glassesMode: String = BuildModes.glassesMode,
    val fakeVision: Boolean = BuildModes.isFakeVision,
)

/** Settings and privacy controls. Photo storage requires an explicit confirmation. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val history: DescriptionHistoryRepository,
    private val archive: CapturedImageArchive,
    private val engine: AssistantEngine,
    private val appLanguage: AppLanguageRepository,
) : ViewModel() {

    private val local = MutableStateFlow(SettingsUiState())

    /** Screen state. */
    val uiState: StateFlow<SettingsUiState> =
        combine(local, settings.settings, history.history, engine.state, appLanguage.preference) { base, current, entries, assistant, language ->
            base.copy(settings = current, language = language, historyCount = entries.size, sessionActive = assistant.sessionActive)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        refreshImageCount()
    }

    /**
     * @param detailed whether detailed descriptions are preferred.
     */
    fun setDetailed(detailed: Boolean) =
        engine.dispatch(AssistantAction.SetVerbosity(if (detailed) Verbosity.DETAILED else Verbosity.SHORT))

    /**
     * Switches the language of the screens, speech, voice commands, and descriptions.
     *
     * @param preference the new choice.
     */
    fun setLanguage(preference: LanguagePreference) {
        if (preference != appLanguage.preference.value) appLanguage.setPreference(preference)
    }

    /**
     * @param rate new speech rate multiplier.
     */
    fun setSpeechRate(rate: Float) = update { it.copy(speechRate = rate) }

    /**
     * Turning history off deletes it.
     *
     * @param enabled new value.
     */
    fun setSaveHistory(enabled: Boolean) {
        update { it.copy(saveHistory = enabled) }
        if (!enabled) viewModelScope.launch { history.clear() }
    }

    /**
     * Enabling photo storage asks for confirmation first; disabling deletes saved photos.
     *
     * @param enabled new value.
     */
    fun setSaveImages(enabled: Boolean) {
        if (enabled) {
            local.update { it.copy(dialog = SettingsDialog.CONFIRM_SAVE_IMAGES) }
        } else {
            update { it.copy(saveImages = false) }
            viewModelScope.launch {
                archive.clear()
                refreshImageCount()
            }
        }
    }

    /** Confirms photo storage. */
    fun confirmSaveImages() {
        update { it.copy(saveImages = true) }
        dismissDialog()
    }

    /**
     * @param enabled whether the headset media button triggers a description.
     */
    fun setMediaButton(enabled: Boolean) = update { it.copy(mediaButtonEnabled = enabled) }

    /** Asks for confirmation before deleting local data. */
    fun requestDelete() {
        refreshImageCount()
        local.update { it.copy(dialog = SettingsDialog.CONFIRM_DELETE, deletedMessageVisible = false) }
    }

    /** Permanently deletes description history and saved photos. */
    fun confirmDelete() {
        viewModelScope.launch {
            history.clear()
            archive.clear()
            refreshImageCount()
            local.update { it.copy(dialog = null, deletedMessageVisible = true) }
        }
    }

    /** Hides the current dialog. */
    fun dismissDialog() = local.update { it.copy(dialog = null) }

    /** Starts or ends the glasses session. */
    fun toggleSession() =
        engine.dispatch(if (engine.state.value.sessionActive) AssistantAction.EndSession else AssistantAction.StartSession)

    /**
     * Marks onboarding as not completed so it runs again.
     *
     * @param onDone invoked after the flag was persisted.
     */
    fun rerunSetup(onDone: () -> Unit) {
        viewModelScope.launch {
            settings.update { it.copy(onboardingCompleted = false) }
            onDone()
        }
    }

    private fun update(transform: (UserSettings) -> UserSettings) {
        viewModelScope.launch { settings.update(transform) }
    }

    private fun refreshImageCount() {
        viewModelScope.launch {
            val count = archive.count()
            local.update { it.copy(imageCount = count) }
        }
    }

    /** Returns current settings once, for tests. */
    suspend fun currentSettings(): UserSettings = settings.settings.first()
}
