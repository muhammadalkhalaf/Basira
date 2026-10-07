package com.basira.core.reporting

/**
 * Names the part of the app a failure came from.
 *
 * The domain is the first thing a report is filtered by in Crashlytics, so it answers "which
 * subsystem is broken" before anyone opens a stack trace. Every domain maps to a component that can
 * fail on its own and be fixed on its own.
 *
 * It also says whether the failures of that component reach it over the network, which is what lets
 * the reporter leave out the ones that are only the connection between the phone and the backend.
 * Without it an `IOException` from a Gemini call and an `IOException` from the image archive would
 * be read as the same thing and the wrong one would be dropped.
 *
 * @property key value written to the `error.domain` Crashlytics key.
 * @property backendFacing `true` when the failures of this subsystem come from talking to a backend.
 *   Only those are held against the connection before they are reported; a failure of a component
 *   that never leaves the phone is reported as it is.
 */
enum class ErrorDomain(val key: String, val backendFacing: Boolean) {

    /** Every HTTP call, whichever client made it. */
    NETWORK("network", true),

    /** The Gemini answer: its contract, its error envelope, and the time budget of one analysis. */
    VISION("vision", true),

    /** The Meta Wearables Device Access Toolkit: initialization, registration, sessions, capture. */
    GLASSES("glasses", false),

    /** Text to speech, voice commands, feedback tones, and the "where is my phone" ringtone. */
    AUDIO("audio", false),

    /** Decoding, orienting, and compressing the captured photo. */
    IMAGE("image", false),

    /** DataStore settings and history, the image archive, and bundled assets. */
    STORAGE("storage", false),

    /** The foreground service and its notification. */
    SERVICE("service", false),

    /** The assistant flow that ties capture, analysis, and speech together. */
    ASSISTANT("assistant", false),

    /** A screen, or an activity a screen tried to open. */
    UI("ui", false),

    /** Nothing above fits, which is itself worth seeing in the dashboard. */
    UNKNOWN("unknown", false),
}
