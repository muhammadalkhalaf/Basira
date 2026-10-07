package com.basira.app.presentation.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.basira.app.R
import com.basira.app.presentation.components.OutlinedCard
import com.basira.app.presentation.components.PrimaryActionButton
import com.basira.app.presentation.components.SecondaryActionButton
import com.basira.app.presentation.theme.Dimens

/** Test tags for onboarding. */
object OnboardingTestTags {
    /** Consent accept button. */
    const val ACCEPT = "onboarding_accept"

    /** Next button. */
    const val NEXT = "onboarding_next"
}

/**
 * Callbacks of [OnboardingScreen].
 *
 * @property onNext next step.
 * @property onBack previous step.
 * @property onAcceptConsent accept consent.
 * @property onDeclineConsent decline consent.
 * @property onResolve run a checklist action.
 * @property onTestVoice speak a test sentence.
 * @property onFinish finish onboarding.
 */
data class OnboardingCallbacks(
    val onNext: () -> Unit,
    val onBack: () -> Unit,
    val onAcceptConsent: () -> Unit,
    val onDeclineConsent: () -> Unit,
    val onResolve: (SetupCheck) -> Unit,
    val onTestVoice: () -> Unit,
    val onFinish: () -> Unit,
)

/**
 * First-run setup. Each step starts with a heading announcing "Step N of M" and its title, followed
 * by content and large navigation buttons, so it can be completed with TalkBack swipe navigation.
 *
 * @param state onboarding state.
 * @param callbacks user actions.
 */
@Composable
fun OnboardingScreen(state: OnboardingUiState, callbacks: OnboardingCallbacks) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(Dimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
        ) {
            Text(
                stringResource(R.string.onboarding_step, state.step + 1, state.totalSteps),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (state.step) {
                0 -> TextStep(R.string.onboarding_welcome_title, R.string.onboarding_welcome_body)
                1 -> ConsentStep(callbacks)
                2 -> SetupStep(state, callbacks)
                3 -> VoiceStep(state, callbacks)
                else -> TextStep(R.string.onboarding_done_title, R.string.onboarding_done_body)
            }
            Navigation(state, callbacks)
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
}

@Composable
private fun TextStep(title: Int, body: Int) {
    Title(stringResource(title))
    Body(stringResource(body))
}

@Composable
private fun ConsentStep(callbacks: OnboardingCallbacks) {
    Title(stringResource(R.string.onboarding_consent_title))
    listOf(
        R.string.consent_point_capture,
        R.string.consent_point_people,
        R.string.consent_point_provider,
        R.string.consent_point_storage,
        R.string.consent_point_accuracy,
        R.string.consent_point_not_replacement,
        R.string.consent_point_safety,
    ).forEach { point -> OutlinedCard { Body(stringResource(point)) } }
    PrimaryActionButton(
        label = stringResource(R.string.consent_accept),
        onClick = callbacks.onAcceptConsent,
        minHeight = Dimens.ActionHeight,
        modifier = Modifier.testTag(OnboardingTestTags.ACCEPT),
    )
    SecondaryActionButton(stringResource(R.string.consent_decline), callbacks.onDeclineConsent)
}

@Composable
private fun SetupStep(state: OnboardingUiState, callbacks: OnboardingCallbacks) {
    Title(stringResource(R.string.onboarding_setup_title))
    Body(stringResource(R.string.onboarding_setup_body))
    state.checks.forEach { check -> CheckRow(check, callbacks.onResolve) }
}

@Composable
private fun VoiceStep(state: OnboardingUiState, callbacks: OnboardingCallbacks) {
    Title(stringResource(R.string.onboarding_voice_title))
    Body(stringResource(R.string.onboarding_voice_body))
    CheckRow(state.voiceCheck, callbacks.onResolve)
    SecondaryActionButton(stringResource(R.string.onboarding_voice_test), callbacks.onTestVoice)
}

@Composable
private fun CheckRow(check: SetupCheck, onResolve: (SetupCheck) -> Unit) {
    OutlinedCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
            Icon(
                if (check.state == CheckState.DONE) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.check_row, stringResource(check.label), stringResource(check.state.label)),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        val actionLabel = when {
            check.requestsNotifications -> R.string.setup_allow_notifications
            else -> check.action?.label
        }
        if (actionLabel != null) SecondaryActionButton(stringResource(actionLabel), { onResolve(check) })
    }
}

@Composable
private fun Navigation(state: OnboardingUiState, callbacks: OnboardingCallbacks) {
    val last = state.step == state.totalSteps - 1
    when {
        last -> PrimaryActionButton(stringResource(R.string.onboarding_start), callbacks.onFinish, minHeight = Dimens.ActionHeight)
        state.step == OnboardingUiState.CONSENT_STEP -> Unit
        state.step == 2 -> {
            PrimaryActionButton(
                stringResource(R.string.onboarding_next),
                callbacks.onNext,
                minHeight = Dimens.ActionHeight,
                modifier = Modifier.testTag(OnboardingTestTags.NEXT),
            )
            Text(stringResource(R.string.onboarding_skip_setup), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> PrimaryActionButton(
            stringResource(R.string.onboarding_next),
            callbacks.onNext,
            minHeight = Dimens.ActionHeight,
            modifier = Modifier.testTag(OnboardingTestTags.NEXT),
        )
    }
    if (state.step > 0) SecondaryActionButton(stringResource(R.string.onboarding_back), callbacks.onBack)
}
