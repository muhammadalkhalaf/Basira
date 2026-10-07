package com.basira.domain.model

/** Availability of spoken output. */
enum class SpeechAvailability {
    /** The Text-to-Speech engine is still initializing. */
    INITIALIZING,

    /** Arabic speech is available. */
    READY,

    /** No Text-to-Speech engine could be initialized. */
    ENGINE_UNAVAILABLE,

    /** The engine works but Arabic voice data is missing. */
    ARABIC_MISSING,
}

/** Physical output used for spoken feedback. */
enum class AudioRoute {
    /** The Ray-Ban Meta glasses speakers over Bluetooth. */
    GLASSES,

    /** Another Bluetooth audio device. */
    OTHER_BLUETOOTH,

    /** A wired or USB headset. */
    WIRED_HEADSET,

    /** The phone's built-in speaker. */
    PHONE_SPEAKER,
}

/**
 * Snapshot of the speech output subsystem.
 *
 * @property availability whether Arabic speech can be produced.
 * @property isSpeaking whether an utterance is currently playing.
 * @property route current output route.
 */
data class SpeechOutputState(
    val availability: SpeechAvailability = SpeechAvailability.INITIALIZING,
    val isSpeaking: Boolean = false,
    val route: AudioRoute = AudioRoute.PHONE_SPEAKER,
)

/** How a new utterance interacts with the one currently playing. */
enum class SpeechQueueMode {
    /** Interrupt the current utterance. */
    FLUSH,

    /** Play after the current utterance. */
    ADD,
}

/** Final outcome of a single utterance. */
enum class SpeechCompletion {
    /** The utterance played to the end. */
    COMPLETED,

    /** The utterance was interrupted by the user or a newer utterance. */
    INTERRUPTED,

    /** The audio route (for example the glasses) disconnected during playback. */
    ROUTE_LOST,

    /** The engine failed or Arabic is unavailable. */
    FAILED,
}

/** Connectivity of the phone. */
enum class ConnectivityStatus {
    /** A validated internet connection is available. */
    ONLINE,

    /** No validated internet connection. */
    OFFLINE,

    /** Not determined yet. */
    UNKNOWN,
}

/** Short non-speech feedback played with audio and haptics. */
enum class FeedbackCue {
    /** A photo is about to be captured. */
    CAPTURE,

    /** The image was uploaded and is being analyzed. */
    PROCESSING,

    /** A description is ready. */
    SUCCESS,

    /** The operation was cancelled. */
    CANCELLED,

    /** The phone is offline. */
    OFFLINE,

    /** The operation failed. */
    ERROR,

    /** A request was ignored because another one is running. */
    BUSY,

    /** The app started listening for a voice command. */
    LISTENING,
}

/**
 * Persisted, non-sensitive user preferences.
 *
 * @property verbosity preferred description length.
 * @property consentAccepted whether the privacy and safety consent was accepted.
 * @property onboardingCompleted whether the guided first-run setup was finished.
 * @property saveHistory whether description text is kept on the device.
 * @property saveImages whether captured images are kept on the device (explicit opt-in).
 * @property mediaButtonEnabled whether a supported headset media button triggers a description.
 * @property speechRate Text-to-Speech rate multiplier.
 */
data class UserSettings(
    val verbosity: Verbosity = Verbosity.SHORT,
    val consentAccepted: Boolean = false,
    val onboardingCompleted: Boolean = false,
    val saveHistory: Boolean = false,
    val saveImages: Boolean = false,
    val mediaButtonEnabled: Boolean = false,
    val speechRate: Float = 1.0f,
)
