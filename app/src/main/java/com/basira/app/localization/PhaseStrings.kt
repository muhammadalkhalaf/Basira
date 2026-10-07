package com.basira.app.localization

import androidx.annotation.StringRes
import com.basira.app.R
import com.basira.core.error.AppError
import com.basira.core.error.DeviceHealthReason
import com.basira.core.error.UpdateTarget
import com.basira.domain.assistant.AssistantPhase
import com.basira.domain.usecase.NoResultReason

/**
 * Localizable text for one phase.
 *
 * @property title short title resource.
 * @property detail recovery instruction resource (a string, or a plurals resource when
 * [detailIsPlural]), if any.
 * @property detailArgument integer format argument for [detail], if any.
 * @property detailIsPlural whether [detail] is a plurals resource selected by [detailArgument].
 */
data class PhaseText(
    @param:StringRes val title: Int,
    val detail: Int?,
    val detailArgument: Int? = null,
    val detailIsPlural: Boolean = false,
)

/** Recovery action offered for a phase. */
enum class SetupAction(@param:StringRes val label: Int) {
    /** Open the Meta AI store listing. */
    INSTALL_META_AI(R.string.setup_install_meta_ai),

    /** Open Meta AI, for example to finish registration. */
    OPEN_META_AI(R.string.setup_open_meta_ai),

    /** Request the Android nearby-devices (Bluetooth) permission. */
    ALLOW_BLUETOOTH(R.string.setup_allow_bluetooth),

    /** Start DAT registration. */
    CONNECT_META_AI(R.string.setup_connect_meta_ai),

    /** Request the DAT camera permission through Meta AI. */
    ALLOW_CAMERA(R.string.setup_allow_camera),

    /** Open the glasses firmware update in Meta AI. */
    UPDATE_FIRMWARE(R.string.setup_update_glasses),

    /** Open the on-glasses DAT app update in Meta AI. */
    UPDATE_GLASSES_APP(R.string.setup_update_glasses_app),

    /** Ask the TTS engine to install Arabic voice data. */
    INSTALL_ARABIC_VOICE(R.string.setup_install_arabic_voice),

    /** Open system text-to-speech settings. */
    OPEN_TTS_SETTINGS(R.string.setup_open_tts_settings),
}

/** Maps assistant phases to texts and recovery actions. Shared by the UI and spoken announcements. */
object PhaseStrings {

    /**
     * @param phase phase to describe.
     * @param previous previous environmental phase, used for "connection restored" style messages.
     * @return localizable text for [phase].
     */
    fun of(phase: AssistantPhase, previous: AssistantPhase? = null): PhaseText = when (phase) {
        AssistantPhase.Initializing -> PhaseText(R.string.status_initializing_title, R.string.status_initializing_detail)
        AssistantPhase.MetaAiMissing -> PhaseText(R.string.status_meta_ai_missing_title, R.string.status_meta_ai_missing_detail)
        AssistantPhase.BluetoothPermissionRequired -> PhaseText(R.string.status_bluetooth_title, R.string.status_bluetooth_detail)
        AssistantPhase.RegistrationRequired -> PhaseText(R.string.status_registration_title, R.string.status_registration_detail)
        AssistantPhase.Registering -> PhaseText(R.string.status_registering_title, R.string.status_registering_detail)
        AssistantPhase.GlassesUnavailable -> PhaseText(R.string.status_glasses_unavailable_title, R.string.status_glasses_unavailable_detail)
        AssistantPhase.GlassesDisconnected -> PhaseText(R.string.status_glasses_disconnected_title, R.string.status_glasses_disconnected_detail)
        AssistantPhase.PermissionRequired -> PhaseText(R.string.status_permission_required_title, R.string.status_permission_required_detail)
        AssistantPhase.PermissionDenied -> PhaseText(R.string.status_permission_denied_title, R.string.status_permission_denied_detail)
        is AssistantPhase.UnsupportedVersion -> when (phase.target) {
            UpdateTarget.GLASSES_FIRMWARE -> PhaseText(R.string.status_update_firmware_title, R.string.status_update_firmware_detail)
            UpdateTarget.GLASSES_DAT_APP -> PhaseText(R.string.status_update_glasses_app_title, R.string.status_update_glasses_app_detail)
            UpdateTarget.THIS_APP -> PhaseText(R.string.status_update_this_app_title, R.string.status_update_this_app_detail)
        }
        is AssistantPhase.SpeechUnavailable ->
            if (phase.arabicVoiceMissing) {
                PhaseText(R.string.status_arabic_missing_title, R.string.status_arabic_missing_detail)
            } else {
                PhaseText(R.string.status_tts_missing_title, R.string.status_tts_missing_detail)
            }
        AssistantPhase.Offline -> PhaseText(R.string.status_offline_title, R.string.status_offline_detail)
        AssistantPhase.Ready -> when (previous) {
            AssistantPhase.Offline -> PhaseText(R.string.status_connection_restored_title, R.string.status_connection_restored_detail)
            AssistantPhase.GlassesDisconnected, AssistantPhase.GlassesUnavailable ->
                PhaseText(R.string.status_glasses_connected_title, R.string.status_ready_detail)
            else -> PhaseText(R.string.status_ready_title, R.string.status_ready_detail)
        }
        AssistantPhase.Listening -> PhaseText(R.string.status_listening_title, R.string.status_listening_detail)
        is AssistantPhase.Capturing -> PhaseText(R.string.status_capturing_title, R.string.status_capturing_detail)
        is AssistantPhase.Uploading -> PhaseText(R.string.status_uploading_title, R.string.status_uploading_detail)
        is AssistantPhase.Analyzing -> PhaseText(R.string.status_analyzing_title, R.string.status_analyzing_detail)
        AssistantPhase.Speaking -> PhaseText(R.string.status_speaking_title, R.string.status_speaking_detail)
        AssistantPhase.Loaded -> PhaseText(R.string.status_loaded_title, R.string.status_loaded_detail)
        is AssistantPhase.EmptyResult -> when (phase.reason) {
            NoResultReason.NOTHING_RECOGNIZED -> PhaseText(R.string.status_empty_title, R.string.status_empty_detail)
            NoResultReason.CURRENCY_NOT_CONFIDENT ->
                PhaseText(R.string.status_currency_uncertain_title, R.string.status_currency_uncertain_detail)
        }
        AssistantPhase.Timeout -> PhaseText(R.string.status_timeout_title, R.string.status_timeout_detail)
        is AssistantPhase.RateLimited -> phase.retryAfterMillis?.let { millis ->
            PhaseText(
                R.string.status_rate_limited_title,
                R.plurals.status_rate_limited_seconds_detail,
                ((millis + 999) / 1_000).toInt(),
                detailIsPlural = true,
            )
        } ?: PhaseText(R.string.status_rate_limited_title, R.string.status_rate_limited_detail)
        AssistantPhase.AuthenticationExpired -> PhaseText(R.string.status_auth_expired_title, R.string.status_auth_expired_detail)
        AssistantPhase.InvalidImage -> PhaseText(R.string.status_invalid_image_title, R.string.status_invalid_image_detail)
        AssistantPhase.InvalidServerResponse -> PhaseText(R.string.status_invalid_response_title, R.string.status_invalid_response_detail)
        is AssistantPhase.RecoverableError -> PhaseText(R.string.status_error_title, recoverableDetail(phase.error))
        is AssistantPhase.FatalError -> PhaseText(
            R.string.status_fatal_title,
            when (phase.error) {
                AppError.Forbidden -> R.string.status_fatal_forbidden
                AppError.ServiceNotConfigured -> R.string.status_fatal_not_configured
                AppError.ApiKeyRejected -> R.string.status_fatal_key_rejected
                else -> R.string.status_fatal_detail
            },
        )
    }

    /**
     * @param phase current phase.
     * @return the recovery action for [phase], or `null` when no setup action applies.
     */
    fun setupActionFor(phase: AssistantPhase): SetupAction? = when (phase) {
        AssistantPhase.MetaAiMissing -> SetupAction.INSTALL_META_AI
        AssistantPhase.BluetoothPermissionRequired -> SetupAction.ALLOW_BLUETOOTH
        AssistantPhase.RegistrationRequired -> SetupAction.CONNECT_META_AI
        AssistantPhase.Registering, AssistantPhase.GlassesUnavailable, AssistantPhase.GlassesDisconnected ->
            SetupAction.OPEN_META_AI
        AssistantPhase.PermissionRequired, AssistantPhase.PermissionDenied -> SetupAction.ALLOW_CAMERA
        is AssistantPhase.UnsupportedVersion -> when (phase.target) {
            UpdateTarget.GLASSES_FIRMWARE -> SetupAction.UPDATE_FIRMWARE
            UpdateTarget.GLASSES_DAT_APP -> SetupAction.UPDATE_GLASSES_APP
            UpdateTarget.THIS_APP -> null
        }
        is AssistantPhase.SpeechUnavailable ->
            if (phase.arabicVoiceMissing) SetupAction.INSTALL_ARABIC_VOICE else SetupAction.OPEN_TTS_SETTINGS
        else -> null
    }

    @StringRes
    private fun recoverableDetail(error: AppError): Int = when (error) {
        AppError.CaptureFailed -> R.string.status_error_capture_failed
        AppError.CaptureBusy -> R.string.status_error_capture_busy
        is AppError.DeviceHealth -> when (error.reason) {
            DeviceHealthReason.THERMAL -> R.string.status_error_thermal
            DeviceHealthReason.BATTERY_LOW -> R.string.status_error_battery
            DeviceHealthReason.FOLDED -> R.string.status_error_folded
        }
        is AppError.ServerError -> R.string.status_error_server
        AppError.Transport -> R.string.status_error_transport
        AppError.MissingTargetObject -> R.string.status_error_missing_target
        AppError.QuotaExceeded -> R.string.status_error_quota
        AppError.ContentBlocked -> R.string.status_error_blocked
        else -> R.string.status_error_detail
    }
}
