package com.basira.app.data.speech

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Requests transient, ducking audio focus while the assistant speaks so music or podcasts lower
 * their volume, and releases it as soon as speech ends.
 */
@Singleton
class AudioFocusController @Inject constructor(
    @param:ApplicationContext context: Context,
    routes: AudioRouteManager,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(routes.speechAttributes)
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener { }
        .build()
    private var held = false

    /** Requests focus if not already held. */
    @Synchronized
    fun acquire() {
        if (held) return
        held = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /** Releases focus if held. */
    @Synchronized
    fun release() {
        if (!held) return
        audioManager.abandonAudioFocusRequest(request)
        held = false
    }
}
