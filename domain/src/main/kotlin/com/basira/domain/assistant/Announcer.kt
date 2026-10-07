package com.basira.domain.assistant

import com.basira.domain.model.AnalysisMode
import com.basira.domain.model.AudioRoute
import com.basira.domain.model.SceneDescription
import com.basira.domain.model.SpeechCompletion
import com.basira.domain.model.SpeechQueueMode
import com.basira.domain.model.Verbosity
import com.basira.domain.repository.SpeechOutput
import javax.inject.Inject

/** Something the assistant tells the user. */
sealed interface Announcement {

    /**
     * The current status, spoken at start-up, on request, and after environmental changes.
     *
     * @property phase the phase to explain.
     * @property previous the previous environmental phase, used for messages such as
     * "connection restored".
     * @property route current audio route, mentioned when output does not come from the glasses.
     */
    data class Status(
        val phase: AssistantPhase,
        val previous: AssistantPhase? = null,
        val route: AudioRoute? = null,
    ) : Announcement

    /**
     * A photo is being captured.
     *
     * @property mode requested mode.
     * @property targetObject object name for object-finding mode.
     */
    data class CaptureStarted(val mode: AnalysisMode, val targetObject: String? = null) : Announcement

    /**
     * A description, possibly repeated.
     *
     * @property description what to speak.
     * @property repeated `true` when the user asked to hear the last description again.
     */
    data class Description(val description: SceneDescription, val repeated: Boolean) : Announcement

    /** The running operation was cancelled. */
    data object Cancelled : Announcement

    /** A request was ignored because another one is running. */
    data object Busy : Announcement

    /** "Repeat" was requested before any description exists. */
    data object NothingToRepeat : Announcement

    /**
     * The audio output changed.
     *
     * @property route new route.
     */
    data class RouteChanged(val route: AudioRoute) : Announcement

    /** The glasses audio disconnected during playback. */
    data object GlassesAudioLost : Announcement

    /** The voice command was not understood. */
    data object VoiceNotUnderstood : Announcement

    /** Voice commands need the microphone permission. */
    data object MicrophonePermissionNeeded : Announcement

    /** Speech recognition is not available on this phone. */
    data object VoiceUnavailable : Announcement

    /** Object-finding was requested without an object name. */
    data object TargetObjectMissing : Announcement

    /**
     * The verbosity preference changed.
     *
     * @property verbosity new verbosity.
     */
    data class VerbosityChanged(val verbosity: Verbosity) : Announcement

    /** The glasses session was ended by the user. */
    data object SessionEnded : Announcement
}

/** Converts [Announcement] values to text in the app language. Implemented with app resources. */
fun interface AnnouncementTextProvider {
    /**
     * @param announcement what to say.
     * @return the text to speak, or `null` when nothing should be spoken.
     */
    fun textFor(announcement: Announcement): String?
}

/** Speaks [Announcement] values. */
interface Announcer {
    /**
     * Speaks [announcement] and suspends until playback ends.
     *
     * @param announcement what to say.
     * @param queueMode whether to interrupt current speech.
     * @return how playback ended.
     */
    suspend fun announce(
        announcement: Announcement,
        queueMode: SpeechQueueMode = SpeechQueueMode.FLUSH,
    ): SpeechCompletion
}

/**
 * Default [Announcer] that resolves text through [AnnouncementTextProvider] and speaks it through
 * [SpeechOutput].
 */
class SpeakingAnnouncer @Inject constructor(
    private val texts: AnnouncementTextProvider,
    private val speech: SpeechOutput,
) : Announcer {
    override suspend fun announce(announcement: Announcement, queueMode: SpeechQueueMode): SpeechCompletion {
        val text = texts.textFor(announcement) ?: return SpeechCompletion.COMPLETED
        return speech.speak(text, queueMode)
    }
}
