package com.basira.app.data.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.basira.core.coroutines.DispatcherProvider
import com.basira.core.error.AppError
import com.basira.core.logging.AppLogger
import com.basira.core.reporting.ErrorDomain
import com.basira.core.reporting.ErrorReport
import com.basira.core.reporting.ErrorReporter
import com.basira.core.reporting.ErrorSeverity
import com.basira.core.result.AppResult
import com.basira.domain.repository.VoiceCommandRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * One-shot [VoiceCommandRecognizer] using Android [SpeechRecognizer].
 *
 * It listens only after an explicit user action and stops after one utterance. The default input is
 * the phone microphone: the app never starts Bluetooth SCO, so the glasses' HFP microphone is not
 * opened and the DAT camera session is not disturbed.
 */
class AndroidVoiceCommandRecognizer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val logger: AppLogger,
    private val errorReporter: ErrorReporter,
) : VoiceCommandRecognizer {

    override suspend fun listenOnce(): AppResult<String> = withContext<AppResult<String>>(dispatchers.main) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return@withContext AppResult.Failure(AppError.MicrophonePermissionRequired)
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return@withContext AppResult.Failure(AppError.SpeechRecognitionUnavailable)
        }
        suspendCancellableCoroutine { continuation ->
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            /** Releases the recognizer and resumes exactly once. */
            fun finish(result: AppResult<String>) {
                recognizer.destroy()
                if (continuation.isActive) continuation.resume(result)
            }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    finish(if (best.isNullOrBlank()) AppResult.Failure(AppError.VoiceCommandNotUnderstood) else AppResult.Success(best))
                }

                override fun onError(error: Int) {
                    logger.info(TAG, "Recognition error $error")
                    val mapped = mapError(error)
                    // No match and no network are the user's and the connection's; a recognizer that
                    // cannot run at all is the one worth reporting.
                    if (mapped == AppError.SpeechRecognitionUnavailable) {
                        errorReporter.report(
                            ErrorReport(
                                domain = ErrorDomain.AUDIO,
                                operation = "voice.recognize",
                                severity = ErrorSeverity.ERROR,
                                outcome = "error_announced",
                                reason = "error_$error",
                            ),
                        )
                    }
                    finish(AppResult.Failure(mapped))
                }

                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            recognizer.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false),
            )
            continuation.invokeOnCancellation {
                recognizer.cancel()
                recognizer.destroy()
            }
        }
    }

    private fun mapError(error: Int): AppError = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> AppError.MicrophonePermissionRequired
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> AppError.VoiceCommandNotUnderstood
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> AppError.Offline
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        -> AppError.SpeechRecognitionUnavailable
        else -> AppError.VoiceCommandNotUnderstood
    }

    private companion object {
        const val TAG = "Voice"
        const val LANGUAGE = "ar"
    }
}
