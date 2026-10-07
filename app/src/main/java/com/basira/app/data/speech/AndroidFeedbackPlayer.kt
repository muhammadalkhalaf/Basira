package com.basira.app.data.speech

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.basira.core.logging.AppLogger
import com.basira.domain.model.FeedbackCue
import com.basira.domain.repository.FeedbackPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [FeedbackPlayer] that pairs a short tone with a distinct vibration pattern for every cue, so each
 * event can be told apart by sound alone or by touch alone.
 */
@Singleton
class AndroidFeedbackPlayer @Inject constructor(
    @param:ApplicationContext context: Context,
    private val logger: AppLogger,
) : FeedbackPlayer {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    private val toneGenerator: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME)
    } catch (e: RuntimeException) {
        logger.warn(TAG, "Tone generator unavailable", e)
        null
    }

    override fun play(cue: FeedbackCue) {
        val (tone, durationMillis) = TONES.getValue(cue)
        try {
            toneGenerator?.startTone(tone, durationMillis)
        } catch (e: RuntimeException) {
            logger.warn(TAG, "Tone failed", e)
        }
        vibrate(PATTERNS.getValue(cue))
    }

    private fun vibrate(pattern: LongArray) {
        val device = vibrator ?: return
        if (!device.hasVibrator()) return
        device.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private companion object {
        const val TAG = "Feedback"
        const val TONE_VOLUME = 70

        val TONES: Map<FeedbackCue, Pair<Int, Int>> = mapOf(
            FeedbackCue.CAPTURE to (ToneGenerator.TONE_PROP_BEEP to 120),
            FeedbackCue.PROCESSING to (ToneGenerator.TONE_PROP_ACK to 150),
            FeedbackCue.SUCCESS to (ToneGenerator.TONE_PROP_BEEP2 to 200),
            FeedbackCue.CANCELLED to (ToneGenerator.TONE_PROP_NACK to 200),
            FeedbackCue.OFFLINE to (ToneGenerator.TONE_SUP_CONGESTION_ABBREV to 400),
            FeedbackCue.ERROR to (ToneGenerator.TONE_SUP_ERROR to 300),
            FeedbackCue.BUSY to (ToneGenerator.TONE_PROP_PROMPT to 120),
            FeedbackCue.LISTENING to (ToneGenerator.TONE_CDMA_PIP to 150),
        )

        // Waveforms: off/on durations in milliseconds.
        val PATTERNS: Map<FeedbackCue, LongArray> = mapOf(
            FeedbackCue.CAPTURE to longArrayOf(0, 40),
            FeedbackCue.PROCESSING to longArrayOf(0, 20, 80, 20),
            FeedbackCue.SUCCESS to longArrayOf(0, 60, 60, 60),
            FeedbackCue.CANCELLED to longArrayOf(0, 120),
            FeedbackCue.OFFLINE to longArrayOf(0, 200, 100, 200),
            FeedbackCue.ERROR to longArrayOf(0, 300),
            FeedbackCue.BUSY to longArrayOf(0, 20, 40, 20, 40, 20),
            FeedbackCue.LISTENING to longArrayOf(0, 30, 30, 30),
        )
    }
}
