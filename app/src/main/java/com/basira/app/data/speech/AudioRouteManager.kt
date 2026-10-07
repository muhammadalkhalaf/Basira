package com.basira.app.data.speech

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.basira.core.logging.AppLogger
import com.basira.domain.model.AudioRoute
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks where spoken output is heard.
 *
 * Android routes media-like usages (our TTS uses [AudioAttributes.USAGE_ASSISTANT]) to a connected
 * Bluetooth A2DP device automatically, so "routing to the glasses" means: use that usage, detect
 * whether the active Bluetooth output is the glasses, and fall back to the phone speaker otherwise.
 * Apps cannot force A2DP routing for this usage; [AudioManager.setCommunicationDevice] only applies
 * to voice-communication audio and would disturb the DAT camera session, so it is not used.
 *
 * Bluetooth disconnection during playback is reported through [routeLost]
 * ([AudioManager.ACTION_AUDIO_BECOMING_NOISY]) so speech can stop instead of continuing loudly on the
 * phone speaker.
 */
@Singleton
class AudioRouteManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val logger: AppLogger,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _route = MutableStateFlow(AudioRoute.PHONE_SPEAKER)
    private val _routeLost = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Volatile
    private var glassesName: String? = null
    private var started = false

    /** Current output route. */
    val route: StateFlow<AudioRoute> = _route.asStateFlow()

    /** Emits when audio output is about to fall back to the phone speaker during playback. */
    val routeLost: SharedFlow<Unit> = _routeLost.asSharedFlow()

    /** Audio attributes used for speech and cues. */
    val speechAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                logger.info(TAG, "Audio becoming noisy")
                _routeLost.tryEmit(Unit)
            }
        }
    }

    /** Starts observing audio devices. Idempotent. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        ContextCompat.registerReceiver(
            context,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refresh()
    }

    /**
     * Tells the manager the Bluetooth name of the linked glasses so they can be recognized.
     *
     * @param name device name reported by the glasses SDK.
     */
    fun setGlassesName(name: String?) {
        glassesName = name
        refresh()
    }

    /** Recomputes [route]. */
    fun refresh() {
        val next = computeRoute()
        if (next != _route.value) {
            logger.info(TAG, "Audio route: $next")
            _route.value = next
        }
    }

    private fun computeRoute(): AudioRoute {
        val devices: List<AudioDeviceInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            audioManager.getAudioDevicesForAttributes(speechAttributes)
        } else {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }
        val bluetooth = devices.firstOrNull { it.type in BLUETOOTH_TYPES }
        if (bluetooth != null) return if (isGlasses(bluetooth)) AudioRoute.GLASSES else AudioRoute.OTHER_BLUETOOTH
        if (devices.any { it.type in WIRED_TYPES }) return AudioRoute.WIRED_HEADSET
        return AudioRoute.PHONE_SPEAKER
    }

    private fun isGlasses(device: AudioDeviceInfo): Boolean {
        val name = device.productName?.toString().orEmpty()
        val known = glassesName
        if (!known.isNullOrBlank() && name.equals(known, ignoreCase = true)) return true
        return GLASSES_NAME_HINTS.any { name.contains(it, ignoreCase = true) }
    }

    private companion object {
        const val TAG = "AudioRoute"
        val GLASSES_NAME_HINTS = listOf("Ray-Ban", "RayBan", "Meta", "Oakley")
        val BLUETOOTH_TYPES = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            add(AudioDeviceInfo.TYPE_HEARING_AID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
                add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
        }
        val WIRED_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
    }
}
