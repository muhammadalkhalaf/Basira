package com.basira.app.presentation.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.basira.app.R
import com.basira.app.presentation.components.LabeledSwitch
import com.basira.app.presentation.components.OutlinedCard
import com.basira.app.presentation.components.PrimaryActionButton
import com.basira.app.presentation.components.SecondaryActionButton
import com.basira.app.presentation.components.SectionHeading
import com.basira.app.presentation.theme.Dimens

/** Test tags used by Compose accessibility tests. */
object MainTestTags {
    /** Status card. */
    const val STATUS = "main_status"

    /** Primary describe action. */
    const val DESCRIBE = "main_describe"

    /** Cancel action. */
    const val CANCEL = "main_cancel"

    /** Retry action. */
    const val RETRY = "main_retry"

    /** Setup action. */
    const val SETUP = "main_setup"
}

/**
 * Main voice-first screen.
 *
 * Focus order follows reading order: title, mode banners, status, recovery action, the large describe
 * button, operation controls (cancel, retry, stop, repeat), more options, last description, utilities.
 * The status card is a polite live region only while TalkBack is on, so status is never announced
 * twice (the app voice is silent for status in that case).
 *
 * @param state screen state.
 * @param onAction intent callback.
 * @param onOpenSettings opens settings.
 */
@Composable
fun MainScreen(state: MainUiState, onAction: (MainUiAction) -> Unit, onOpenSettings: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(Dimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
        ) {
            TopBar(onOpenSettings)
            ModeBanners(state)
            StatusCard(state)
            state.setupAction?.let { action ->
                PrimaryActionButton(
                    label = stringResource(action.label),
                    onClick = { onAction(MainUiAction.Setup(action)) },
                    minHeight = Dimens.ActionHeight,
                    modifier = Modifier.testTag(MainTestTags.SETUP),
                )
            }
            PrimaryActionButton(
                label = stringResource(R.string.action_describe),
                onClick = { onAction(MainUiAction.Describe) },
                enabled = state.canDescribe,
                stateDescription = if (state.isBusy) stringResource(R.string.state_busy) else null,
                icon = Icons.Filled.Visibility,
                modifier = Modifier.testTag(MainTestTags.DESCRIBE),
            )
            OperationControls(state, onAction)
            MoreOptions(state, onAction)
            LastDescription(state)
            Utilities(state, onAction)
        }
    }
}

@Composable
private fun TopBar(onOpenSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        IconButton(onClick = onOpenSettings, modifier = Modifier.size(Dimens.ActionHeight)) {
            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.action_settings))
        }
    }
}

@Composable
private fun ModeBanners(state: MainUiState) {
    if (state.isSimulatedGlasses) Banner(stringResource(R.string.banner_simulated_glasses))
    if (state.isFakeVision) Banner(stringResource(R.string.banner_fake_vision))
}

@Composable
private fun Banner(text: String) {
    OutlinedCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun StatusCard(state: MainUiState) {
    val title = stringResource(state.statusText.title)
    val text = state.statusText
    val detail = text.detail?.let { resource ->
        val argument = text.detailArgument
        when {
            text.detailIsPlural && argument != null -> pluralStringResource(resource, argument, argument)
            argument != null -> stringResource(resource, argument)
            else -> stringResource(resource)
        }
    }
    OutlinedCard(
        modifier = Modifier
            .testTag(MainTestTags.STATUS)
            .semantics(mergeDescendants = true) {
                heading()
                if (state.statusLiveRegion) liveRegion = LiveRegionMode.Polite
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(toneIcon(state.tone), contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        }
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        state.progressStep?.let { step ->
            Text(stringResource(R.string.progress_step, step), style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { step / 3f }, modifier = Modifier.fillMaxWidth())
        }
        Text(stringResource(state.audioRouteLabel), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OperationControls(state: MainUiState, onAction: (MainUiAction) -> Unit) {
    if (state.canCancel) {
        SecondaryActionButton(
            label = stringResource(R.string.action_cancel),
            onClick = { onAction(MainUiAction.Cancel) },
            icon = Icons.Filled.Stop,
            modifier = Modifier.testTag(MainTestTags.CANCEL),
        )
    }
    if (state.canRetry) {
        SecondaryActionButton(
            label = stringResource(R.string.action_retry),
            onClick = { onAction(MainUiAction.Retry) },
            icon = Icons.Filled.Replay,
            modifier = Modifier.testTag(MainTestTags.RETRY),
        )
    }
    if (state.isSpeaking) {
        SecondaryActionButton(stringResource(R.string.action_stop_speaking), { onAction(MainUiAction.StopSpeaking) }, icon = Icons.Filled.Stop)
    }
    SecondaryActionButton(
        label = stringResource(R.string.action_repeat),
        onClick = { onAction(MainUiAction.Repeat) },
        enabled = state.canRepeat,
        icon = Icons.Filled.Replay,
    )
    SecondaryActionButton(
        label = stringResource(R.string.action_voice_command),
        onClick = { onAction(MainUiAction.VoiceCommand) },
        enabled = !state.isBusy,
        icon = Icons.Filled.Mic,
    )
}

@Composable
private fun MoreOptions(state: MainUiState, onAction: (MainUiAction) -> Unit) {
    SectionHeading(stringResource(R.string.main_modes_heading))
    SecondaryActionButton(stringResource(R.string.action_read_text), { onAction(MainUiAction.ReadText) }, enabled = state.canDescribe)
    SecondaryActionButton(stringResource(R.string.action_currency), { onAction(MainUiAction.Currency) }, enabled = state.canDescribe)
    OutlinedTextField(
        value = state.findObjectText,
        onValueChange = { onAction(MainUiAction.FindTextChanged(it)) },
        label = { Text(stringResource(R.string.find_object_label)) },
        placeholder = { Text(stringResource(R.string.find_object_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onAction(MainUiAction.Find) }),
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodyLarge,
    )
    SecondaryActionButton(stringResource(R.string.action_find), { onAction(MainUiAction.Find) }, enabled = state.canDescribe)
    LabeledSwitch(
        label = stringResource(R.string.action_detailed_toggle),
        checked = state.detailed,
        onCheckedChange = { onAction(MainUiAction.ToggleDetailed) },
        onText = stringResource(R.string.basira_state_on),
        offText = stringResource(R.string.basira_state_off),
    )
}

@Composable
private fun LastDescription(state: MainUiState) {
    SectionHeading(stringResource(R.string.main_last_description_heading))
    OutlinedCard(modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Text(
            text = state.lastDescription ?: stringResource(R.string.main_no_description),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        state.lastConfidence?.let {
            Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Utilities(state: MainUiState, onAction: (MainUiAction) -> Unit) {
    SecondaryActionButton(stringResource(R.string.action_announce_status), { onAction(MainUiAction.AnnounceStatus) })
    SecondaryActionButton(
        label = stringResource(if (state.phoneRinging) R.string.action_stop_find_phone else R.string.action_find_phone),
        onClick = { onAction(MainUiAction.FindPhone) },
    )
}

private fun toneIcon(tone: StatusTone): ImageVector = when (tone) {
    StatusTone.NEUTRAL -> Icons.Filled.Info
    StatusTone.PROGRESS -> Icons.Filled.HourglassTop
    StatusTone.SUCCESS -> Icons.Filled.CheckCircle
    StatusTone.WARNING -> Icons.Filled.Warning
    StatusTone.ERROR -> Icons.Filled.Error
}
