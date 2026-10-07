package com.basira.app.data.speech

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.basira.core.coroutines.ApplicationScope
import com.basira.core.reporting.ErrorDomain
import com.basira.core.reporting.ErrorReport
import com.basira.core.reporting.ErrorReporter
import com.basira.core.reporting.ErrorSeverity
import com.basira.domain.model.SpeechAvailability
import com.basira.domain.model.SpeechCompletion
import com.basira.domain.model.SpeechOutputState
import com.basira.domain.model.SpeechQueueMode
import com.basira.domain.repository.SettingsRepository
import com.basira.domain.repository.SpeechOutput
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [SpeechOutput] backed by Android [TextToSpeech].
 *
 * - Arabic support is verified with [TextToSpeech.isLanguageAvailable]; when data is missing the state
 *   becomes [SpeechAvailability.ARABIC_MISSING] and nothing is spoken in another language.
 * - Speech uses [AudioRouteManager.speechAttributes], so it follows a connected Bluetooth output such
 *   as the glasses, and holds ducking audio focus only while speaking.
 * - When the Bluetooth route disappears mid-utterance, playback stops and the utterance completes with
 *   [SpeechCompletion.ROUTE_LOST] instead of continuing on the phone speaker.
 * - Cancelling a [speak] call stops only that utterance.
 */
@Singleton
class AndroidSpeechOutput @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val routes: AudioRouteManager,
    private val focus: AudioFocusController,
    settings: SettingsRepository,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val errorReporter: ErrorReporter,
) : SpeechOutput {

    private val _state = MutableStateFlow(SpeechOutputState())
    override val state: StateFlow<SpeechOutputState> = _state.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<String, CancellableContinuation<SpeechCompletion>>()
    private var tts: TextToSpeech? = null
    private var initTimeout: Job? = null

    @Volatile
    private var currentUtteranceId: String? = null

    @Volatile
    private var speechRate = 1.0f

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            currentUtteranceId = utteranceId
            _state.update { it.copy(isSpeaking = true) }
        }

        override fun onDone(utteranceId: String) = complete(utteranceId, SpeechCompletion.COMPLETED)

        override fun onStop(utteranceId: String, interrupted: Boolean) =
            complete(utteranceId, SpeechCompletion.INTERRUPTED)

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) = onError(utteranceId, TextToSpeech.ERROR)

        override fun onError(utteranceId: String, errorCode: Int) {
            reportTts("tts.speak", ErrorSeverity.WARNING, "utterance_not_spoken", reason = "error_$errorCode")
            complete(utteranceId, SpeechCompletion.FAILED)
        }
    }

    init {
        routes.start()
        mainHandler.post(::initialize)
        scope.launch { routes.route.collect { route -> _state.update { it.copy(route = route) } } }
        scope.launch { routes.routeLost.collect { onRouteLost() } }
        scope.launch {
            settings.settings.map { it.speechRate }.distinctUntilChanged().collect { rate ->
                speechRate = rate
                tts?.setSpeechRate(rate)
            }
        }
    }

    override suspend fun speak(text: String, queueMode: SpeechQueueMode): SpeechCompletion {
        val engine = tts
        if (engine == null || _state.value.availability != SpeechAvailability.READY || text.isBlank()) {
            return SpeechCompletion.FAILED
        }
        return suspendCancellableCoroutine { continuation ->
            val id = UUID.randomUUID().toString()
            pending[id] = continuation
            focus.acquire()
            val mode = if (queueMode == SpeechQueueMode.FLUSH) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (engine.speak(text, mode, Bundle(), id) != TextToSpeech.SUCCESS) {
                reportTts("tts.speak", ErrorSeverity.WARNING, "utterance_not_spoken", reason = "rejected")
                complete(id, SpeechCompletion.FAILED)
            }
            continuation.invokeOnCancellation {
                pending.remove(id)
                if (currentUtteranceId == id) engine.stop()
                releaseIfIdle()
            }
        }
    }

    override fun stop() {
        tts?.stop()
        completeAll(SpeechCompletion.INTERRUPTED)
    }

    override fun refreshAvailability() {
        mainHandler.post(::initialize)
    }

    private fun initialize() {
        initTimeout?.cancel()
        completeAll(SpeechCompletion.FAILED)
        tts?.shutdown()
        _state.update { it.copy(availability = SpeechAvailability.INITIALIZING, isSpeaking = false) }
        initTimeout = scope.launch {
            delay(INIT_TIMEOUT_MILLIS)
            if (_state.value.availability == SpeechAvailability.INITIALIZING) {
                reportTts("tts.initialize", ErrorSeverity.CRITICAL, "speech_unavailable", reason = "timeout")
                _state.update { it.copy(availability = SpeechAvailability.ENGINE_UNAVAILABLE) }
            }
        }
        tts = TextToSpeech(context) { status -> mainHandler.post { onInitialized(status) } }
    }

    private fun onInitialized(status: Int) {
        initTimeout?.cancel()
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            reportTts("tts.initialize", ErrorSeverity.CRITICAL, "speech_unavailable", reason = "status_$status")
            _state.update { it.copy(availability = SpeechAvailability.ENGINE_UNAVAILABLE) }
            return
        }
        val availability = engine.isLanguageAvailable(ARABIC)
        if (availability == TextToSpeech.LANG_MISSING_DATA || availability == TextToSpeech.LANG_NOT_SUPPORTED ||
            engine.setLanguage(ARABIC) < TextToSpeech.LANG_AVAILABLE
        ) {
            reportTts("tts.arabicVoice", ErrorSeverity.ERROR, "voice_install_offered", reason = "availability_$availability")
            _state.update { it.copy(availability = SpeechAvailability.ARABIC_MISSING) }
            return
        }
        engine.setAudioAttributes(routes.speechAttributes)
        engine.setSpeechRate(speechRate)
        engine.setOnUtteranceProgressListener(listener)
        _state.update { it.copy(availability = SpeechAvailability.READY) }
    }

    /** Reports a TTS failure together with the engine it happened on, which is what decides the fix. */
    private fun reportTts(operation: String, severity: ErrorSeverity, outcome: String, reason: String) {
        errorReporter.report(
            ErrorReport(
                domain = ErrorDomain.AUDIO,
                operation = operation,
                severity = severity,
                outcome = outcome,
                reason = reason,
                attributes = mapOf("tts.engine" to (tts?.defaultEngine ?: "none")),
            ),
        )
    }

    private fun onRouteLost() {
        if (pending.isEmpty()) return
        tts?.stop()
        completeAll(SpeechCompletion.ROUTE_LOST)
    }

    private fun complete(id: String, completion: SpeechCompletion) {
        pending.remove(id)?.let { if (it.isActive) it.resume(completion) }
        if (currentUtteranceId == id) currentUtteranceId = null
        releaseIfIdle()
    }

    private fun completeAll(completion: SpeechCompletion) {
        pending.keys.toList().forEach { complete(it, completion) }
        currentUtteranceId = null
        releaseIfIdle()
    }

    private fun releaseIfIdle() {
        if (pending.isEmpty()) {
            _state.update { it.copy(isSpeaking = false) }
            focus.release()
        }
    }

    private companion object {
        const val INIT_TIMEOUT_MILLIS = 8_000L
        val ARABIC: Locale = Locale.forLanguageTag("ar")
    }
}
