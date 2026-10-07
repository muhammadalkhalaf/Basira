package com.basira.app.data.speech

import android.content.Context
import android.content.res.Configuration
import com.basira.app.R
import com.basira.app.core.BuildModes
import com.basira.app.data.accessibility.AccessibilityStateProvider
import com.basira.app.localization.PhaseStrings
import com.basira.domain.assistant.Announcement
import com.basira.domain.assistant.AnnouncementTextProvider
import com.basira.domain.assistant.AssistantPhase
import com.basira.domain.model.AnalysisMode
import com.basira.domain.model.AudioRoute
import com.basira.domain.model.Confidence
import com.basira.domain.model.Verbosity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject

/**
 * Resolves spoken announcements from Arabic resources, independent of the UI language, because the
 * speech engine is configured for Arabic and the product never speaks in a mismatched language.
 *
 * Status announcements are skipped while TalkBack is active and the app is visible, because the
 * status card is a polite live region that TalkBack already reads; this avoids duplicate speech.
 */
class ResourceAnnouncementTextProvider @Inject constructor(
    @param:ApplicationContext context: Context,
    private val accessibility: AccessibilityStateProvider,
) : AnnouncementTextProvider {

    private val arabic: Context = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag("ar")) },
    )

    override fun textFor(announcement: Announcement): String? = when (announcement) {
        is Announcement.Status ->
            if (accessibility.shouldDeferStatusToScreenReader()) null else statusText(announcement)
        is Announcement.CaptureStarted ->
            if (accessibility.shouldDeferStatusToScreenReader()) null else captureText(announcement.mode, announcement.targetObject)
        is Announcement.Description -> descriptionText(announcement)
        Announcement.Cancelled -> s(R.string.announce_cancelled)
        Announcement.Busy -> s(R.string.announce_busy)
        Announcement.NothingToRepeat -> s(R.string.announce_nothing_to_repeat)
        is Announcement.RouteChanged -> routeText(announcement.route)
        Announcement.GlassesAudioLost -> s(R.string.announce_glasses_audio_lost)
        Announcement.VoiceNotUnderstood -> s(R.string.announce_voice_not_understood)
        Announcement.MicrophonePermissionNeeded -> s(R.string.announce_microphone_needed)
        Announcement.VoiceUnavailable -> s(R.string.announce_voice_unavailable)
        Announcement.TargetObjectMissing -> s(R.string.status_error_missing_target)
        is Announcement.VerbosityChanged -> s(
            if (announcement.verbosity == Verbosity.DETAILED) R.string.announce_verbosity_detailed else R.string.announce_verbosity_short,
        )
        Announcement.SessionEnded -> s(R.string.announce_session_ended)
    }

    private fun statusText(status: Announcement.Status): String {
        val text = PhaseStrings.of(status.phase, status.previous)
        val parts = mutableListOf(s(text.title) + ".")
        text.detail?.let { detail ->
            val argument = text.detailArgument
            parts += when {
                text.detailIsPlural && argument != null -> arabic.resources.getQuantityString(detail, argument, argument)
                argument != null -> arabic.getString(detail, argument)
                else -> s(detail)
            }
        }
        if (status.route == AudioRoute.PHONE_SPEAKER) parts += s(R.string.announce_route_suffix_phone)
        if (status.phase == AssistantPhase.Ready && status.previous == null && BuildModes.isSimulatedGlasses) {
            parts += s(R.string.announce_simulation)
        }
        return parts.joinToString(" ")
    }

    private fun captureText(mode: AnalysisMode, targetObject: String?): String = when (mode) {
        AnalysisMode.SCENE_DESCRIPTION -> s(R.string.announce_capture_scene)
        AnalysisMode.READ_TEXT -> s(R.string.announce_capture_text)
        AnalysisMode.FIND_OBJECT -> targetObject?.takeIf { it.isNotBlank() }
            ?.let { arabic.getString(R.string.announce_capture_find, it) } ?: s(R.string.announce_capture_scene)
        AnalysisMode.CURRENCY -> s(R.string.announce_capture_currency)
    }

    private fun descriptionText(announcement: Announcement.Description): String {
        val description = announcement.description
        val parts = mutableListOf<String>()
        if (announcement.repeated) parts += s(R.string.announce_repeat_prefix)
        if (description.confidence == Confidence.LOW) parts += s(R.string.announce_low_confidence_prefix)
        parts += description.text
        description.warnings.forEach { parts += arabic.getString(R.string.announce_warning, it) }
        return parts.joinToString(" ")
    }

    private fun routeText(route: AudioRoute): String = when (route) {
        AudioRoute.GLASSES -> s(R.string.announce_route_glasses)
        AudioRoute.PHONE_SPEAKER -> s(R.string.announce_route_phone)
        AudioRoute.OTHER_BLUETOOTH -> s(R.string.announce_route_other_bluetooth)
        AudioRoute.WIRED_HEADSET -> s(R.string.announce_route_wired)
    }

    private fun s(resource: Int): String = arabic.getString(resource)
}
