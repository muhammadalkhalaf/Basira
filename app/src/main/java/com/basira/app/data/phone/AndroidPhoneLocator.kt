package com.basira.app.data.phone

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import com.basira.core.coroutines.ApplicationScope
import com.basira.core.reporting.ErrorDomain
import com.basira.core.reporting.ErrorReport
import com.basira.core.reporting.ErrorReporter
import com.basira.core.reporting.ErrorSeverity
import com.basira.domain.repository.PhoneLocator
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * "Where is my phone?" sound: plays the default alarm tone on the phone (alarm usage, so it is heard
 * from the phone even when Bluetooth audio is connected) for at most [MAX_DURATION_MILLIS].
 */
@Singleton
class AndroidPhoneLocator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val errorReporter: ErrorReporter,
) : PhoneLocator {

    private val _isRinging = MutableStateFlow(false)
    override val isRinging: StateFlow<Boolean> = _isRinging.asStateFlow()

    private var ringtone: Ringtone? = null
    private var timeout: Job? = null

    override fun start() {
        if (_isRinging.value) return
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val tone = RingtoneManager.getRingtone(context, uri) ?: run {
            errorReporter.report(
                ErrorReport(
                    domain = ErrorDomain.AUDIO,
                    operation = "phoneLocator.ringtone",
                    severity = ErrorSeverity.ERROR,
                    outcome = "phone_not_ringing",
                    message = "No ringtone for $uri",
                ),
            )
            return
        }
        tone.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        tone.isLooping = true
        tone.play()
        ringtone = tone
        _isRinging.value = true
        timeout = scope.launch {
            delay(MAX_DURATION_MILLIS)
            stop()
        }
    }

    override fun stop() {
        timeout?.cancel()
        timeout = null
        ringtone?.stop()
        ringtone = null
        _isRinging.value = false
    }

    private companion object {
        const val MAX_DURATION_MILLIS = 30_000L
    }
}
