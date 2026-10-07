package com.basira.app.service

import android.content.Context
import android.content.Intent
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import com.basira.core.logging.AppLogger
import com.basira.domain.assistant.AnalysisRequestSpec
import com.basira.domain.assistant.AssistantAction
import com.basira.domain.assistant.AssistantEngine
import com.basira.domain.model.AnalysisMode

/**
 * Optional headset media-button activation through a [MediaSessionCompat].
 *
 * Android delivers media-button events to the most recently active media session, so delivery is not
 * guaranteed (another media app may own the buttons). A single press describes the scene, or stops
 * speech while speaking. Whether the Ray-Ban Meta touchpad is forwarded as a media button is not
 * documented and has not been verified on hardware. Enabled only when the user turns it on.
 *
 * @property context service context.
 * @property engine assistant engine.
 * @property logger logger.
 */
class MediaButtonController(
    private val context: Context,
    private val engine: AssistantEngine,
    private val logger: AppLogger,
) {
    private var session: MediaSessionCompat? = null

    /** Activates the media session. Idempotent. */
    fun enable() {
        if (session != null) return
        session = MediaSessionCompat(context, TAG).apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                    val event = keyEventOf(mediaButtonEvent) ?: return false
                    if (event.action != KeyEvent.ACTION_UP) return true
                    return handleKey(event.keyCode)
                }
            })
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_PLAY)
                    .setState(PlaybackStateCompat.STATE_PAUSED, 0, 1f)
                    .build(),
            )
            isActive = true
        }
        logger.info(TAG, "Media button session enabled")
    }

    /** Releases the media session. Idempotent. */
    fun disable() {
        session?.run {
            isActive = false
            release()
        }
        session = null
    }

    private fun handleKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> {
            val action = if (engine.state.value.isSpeaking) {
                AssistantAction.StopSpeaking
            } else {
                AssistantAction.Analyze(AnalysisRequestSpec(AnalysisMode.SCENE_DESCRIPTION))
            }
            engine.dispatch(action)
            true
        }
        else -> false
    }

    @Suppress("DEPRECATION")
    private fun keyEventOf(intent: Intent): KeyEvent? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }

    private companion object {
        const val TAG = "MediaButton"
    }
}
