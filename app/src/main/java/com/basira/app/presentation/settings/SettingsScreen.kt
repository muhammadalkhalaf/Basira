package com.basira.app.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.basira.app.R
import com.basira.app.presentation.components.LabeledSwitch
import com.basira.app.presentation.components.SecondaryActionButton
import com.basira.app.presentation.components.SectionHeading
import com.basira.app.presentation.theme.Dimens
import com.basira.domain.model.Verbosity
import kotlin.math.roundToInt

/**
 * Callbacks of [SettingsScreen].
 *
 * @property onBack navigate back.
 * @property onDetailed toggle detailed descriptions.
 * @property onSpeechRate change speech rate.
 * @property onSaveHistory toggle history.
 * @property onSaveImages toggle photo storage.
 * @property onConfirmSaveImages confirm photo storage.
 * @property onMediaButton toggle media button.
 * @property onRequestDelete ask to delete local data.
 * @property onConfirmDelete confirm deletion.
 * @property onDismissDialog dismiss dialog.
 * @property onToggleSession start or end session.
 * @property onRerunSetup run onboarding again.
 */
data class SettingsCallbacks(
    val onBack: () -> Unit,
    val onDetailed: (Boolean) -> Unit,
    val onSpeechRate: (Float) -> Unit,
    val onSaveHistory: (Boolean) -> Unit,
    val onSaveImages: (Boolean) -> Unit,
    val onConfirmSaveImages: () -> Unit,
    val onMediaButton: (Boolean) -> Unit,
    val onRequestDelete: () -> Unit,
    val onConfirmDelete: () -> Unit,
    val onDismissDialog: () -> Unit,
    val onToggleSession: () -> Unit,
    val onRerunSetup: () -> Unit,
)

/**
 * Settings, privacy controls, and build information.
 *
 * @param state screen state.
 * @param callbacks user actions.
 */
@Composable
fun SettingsScreen(state: SettingsUiState, callbacks: SettingsCallbacks) {
    val on = stringResource(R.string.basira_state_on)
    val off = stringResource(R.string.basira_state_off)
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(Dimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = callbacks.onBack, modifier = Modifier.size(Dimens.ActionHeight)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_back))
                }
                Text(
                    stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.semantics { heading() },
                )
            }

            SectionHeading(stringResource(R.string.settings_descriptions_heading))
            LabeledSwitch(
                label = stringResource(R.string.action_detailed_toggle),
                checked = state.settings.verbosity == Verbosity.DETAILED,
                onCheckedChange = callbacks.onDetailed,
                onText = on,
                offText = off,
            )
            SpeechRate(state.settings.speechRate, callbacks.onSpeechRate)

            SectionHeading(stringResource(R.string.settings_privacy_heading))
            Text(stringResource(R.string.settings_privacy_notice), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
            LabeledSwitch(
                label = stringResource(R.string.settings_save_history),
                summary = stringResource(R.string.settings_save_history_summary),
                checked = state.settings.saveHistory,
                onCheckedChange = callbacks.onSaveHistory,
                onText = on,
                offText = off,
            )
            LabeledSwitch(
                label = stringResource(R.string.settings_save_images),
                summary = stringResource(R.string.settings_save_images_summary),
                checked = state.settings.saveImages,
                onCheckedChange = callbacks.onSaveImages,
                onText = on,
                offText = off,
            )
            SecondaryActionButton(stringResource(R.string.settings_delete_local), callbacks.onRequestDelete)
            if (state.deletedMessageVisible) {
                Text(
                    stringResource(R.string.settings_deleted),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            SectionHeading(stringResource(R.string.settings_controls_heading))
            LabeledSwitch(
                label = stringResource(R.string.settings_media_button),
                summary = stringResource(R.string.settings_media_button_summary),
                checked = state.settings.mediaButtonEnabled,
                onCheckedChange = callbacks.onMediaButton,
                onText = on,
                offText = off,
            )

            SectionHeading(stringResource(R.string.settings_glasses_heading))
            SecondaryActionButton(
                stringResource(if (state.sessionActive) R.string.setup_end_session else R.string.setup_start_session),
                callbacks.onToggleSession,
            )
            SecondaryActionButton(stringResource(R.string.settings_rerun_setup), callbacks.onRerunSetup)

            SectionHeading(stringResource(R.string.settings_about_heading))
            Text(
                stringResource(
                    when (state.glassesMode) {
                        "real" -> R.string.settings_mode_real
                        "mockdevicekit" -> R.string.settings_mode_mockdevicekit
                        else -> R.string.settings_mode_fake
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                stringResource(if (state.fakeVision) R.string.settings_vision_fake else R.string.settings_vision_remote),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
    Dialogs(state, callbacks)
}

@Composable
private fun SpeechRate(rate: Float, onChange: (Float) -> Unit) {
    val percent = (rate * 100).roundToInt()
    val valueText = stringResource(R.string.settings_speech_rate_value, percent)
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.settings_speech_rate), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
        Slider(
            value = rate,
            onValueChange = onChange,
            valueRange = 0.5f..2.0f,
            steps = 5,
            modifier = Modifier.fillMaxWidth().semantics { stateDescription = valueText },
        )
        Text(valueText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Dialogs(state: SettingsUiState, callbacks: SettingsCallbacks) {
    when (state.dialog) {
        SettingsDialog.CONFIRM_SAVE_IMAGES -> AlertDialog(
            onDismissRequest = callbacks.onDismissDialog,
            title = { Text(stringResource(R.string.settings_save_images_confirm_title)) },
            text = { Text(stringResource(R.string.settings_save_images_confirm_text)) },
            confirmButton = { TextButton(callbacks.onConfirmSaveImages) { Text(stringResource(R.string.settings_save_images_confirm)) } },
            dismissButton = { TextButton(callbacks.onDismissDialog) { Text(stringResource(R.string.action_cancel)) } },
        )
        SettingsDialog.CONFIRM_DELETE -> AlertDialog(
            onDismissRequest = callbacks.onDismissDialog,
            title = { Text(stringResource(R.string.settings_delete_confirm_title)) },
            text = { Text(stringResource(R.string.settings_delete_confirm_text, state.historyCount, state.imageCount)) },
            confirmButton = { TextButton(callbacks.onConfirmDelete) { Text(stringResource(R.string.settings_delete_confirm)) } },
            dismissButton = { TextButton(callbacks.onDismissDialog) { Text(stringResource(R.string.action_cancel)) } },
        )
        null -> Unit
    }
}
