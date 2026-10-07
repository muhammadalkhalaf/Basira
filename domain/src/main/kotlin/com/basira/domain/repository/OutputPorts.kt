package com.basira.domain.repository

import com.basira.core.result.AppResult
import com.basira.domain.model.ConnectivityStatus
import com.basira.domain.model.FeedbackCue
import com.basira.domain.model.SpeechCompletion
import com.basira.domain.model.SpeechOutputState
import com.basira.domain.model.SpeechQueueMode
import kotlinx.coroutines.flow.StateFlow

/**
 * Spoken output, preferably routed to the glasses speakers.
 *
 * Implementations never switch silently to a non-Arabic voice: when Arabic is unavailable,
 * [state] reports it and [speak] returns [SpeechCompletion.FAILED].
 */
interface SpeechOutput {

    /** Availability, playback, and audio-route state. */
    val state: StateFlow<SpeechOutputState>

    /**
     * Speaks [text] and suspends until playback ends.
     *
     * Cancelling the calling coroutine stops playback.
     *
     * @param text Arabic text to speak.
     * @param queueMode whether to interrupt or queue after the current utterance.
     * @return how playback ended.
     */
    suspend fun speak(text: String, queueMode: SpeechQueueMode = SpeechQueueMode.FLUSH): SpeechCompletion

    /** Stops any current or queued utterance immediately and releases audio focus. */
    fun stop()

    /** Re-checks the Text-to-Speech engine, for example after the user installed Arabic voice data. */
    fun refreshAvailability()
}

/** Short audio and haptic cues that complement speech. */
interface FeedbackPlayer {
    /**
     * Plays [cue] without blocking.
     *
     * @param cue the cue to play.
     */
    fun play(cue: FeedbackCue)
}

/** Observes whether the phone has a validated internet connection. */
interface ConnectivityObserver {
    /** Current connectivity; starts as [ConnectivityStatus.UNKNOWN] until first evaluated. */
    val status: StateFlow<ConnectivityStatus>
}

/**
 * One-shot, user-initiated voice command capture.
 *
 * Implementations must not listen continuously; they record only during an active request and
 * prefer the phone microphone so the glasses camera session is not disturbed.
 */
interface VoiceCommandRecognizer {

    /**
     * Listens for a single utterance.
     *
     * Cancelling the calling coroutine stops listening.
     *
     * @return the best transcript, or a typed failure such as missing microphone permission.
     */
    suspend fun listenOnce(): AppResult<String>
}

/** Plays a loud, stoppable sound on the phone so the user can find it. */
interface PhoneLocator {
    /** Whether the locator sound is currently playing. */
    val isRinging: StateFlow<Boolean>

    /** Starts the sound; it stops automatically after a bounded duration. */
    fun start()

    /** Stops the sound immediately. */
    fun stop()
}

/** Source of unique request identifiers. */
fun interface RequestIdGenerator {
    /** Returns a new unique identifier. */
    fun next(): String
}

/** Source of the current wall-clock time, injectable for tests. */
fun interface Clock {
    /** Returns the current time in milliseconds since the epoch. */
    fun nowMillis(): Long
}
