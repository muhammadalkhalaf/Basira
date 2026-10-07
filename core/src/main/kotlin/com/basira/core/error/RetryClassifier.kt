package com.basira.core.error

/**
 * Decides whether a failed operation may be repeated automatically.
 *
 * The policy is intentionally conservative because every repeated vision request re-uploads an image
 * of the user's surroundings and costs quota:
 * - Automatic retry is allowed only for transient connection failures and HTTP 502, 503, and 504.
 * - Authentication, authorization, permission, registration, validation, rate-limit, and timeout
 *   failures are never retried automatically; the user decides whether to try again.
 */
object RetryClassifier {

    private val retryableHttpCodes = setOf(502, 503, 504)

    /**
     * Returns `true` when [error] is transient and a bounded automatic retry is safe.
     *
     * @param error the failure produced by the previous attempt.
     * @return whether the caller may schedule another attempt without user interaction.
     */
    fun isAutomaticallyRetryable(error: AppError): Boolean = when (error) {
        AppError.Transport -> true
        is AppError.ServerError -> error.httpCode in retryableHttpCodes
        else -> false
    }

    /**
     * Returns `true` when the user should be offered a manual "try again" action for [error].
     *
     * Permission denial, registration problems, and version incompatibilities need a setup action
     * instead of a plain retry, so they return `false`.
     *
     * @param error the failure to classify.
     * @return whether a plain retry button is meaningful.
     */
    fun isUserRetryable(error: AppError): Boolean = when (error) {
        AppError.CameraPermissionDenied,
        AppError.CameraPermissionRequired,
        AppError.RegistrationRequired,
        AppError.RegistrationFailed,
        AppError.MetaAiNotInstalled,
        AppError.BluetoothPermissionRequired,
        is AppError.IncompatibleVersion,
        AppError.TextToSpeechUnavailable,
        AppError.ArabicVoiceMissing,
        AppError.Forbidden,
        AppError.ServiceNotConfigured,
        AppError.ApiKeyRejected,
        -> false
        is AppError.Unexpected -> !error.fatal
        else -> true
    }
}
