package com.basira.app.data.vision.gemini

/**
 * Injectable Gemini configuration, populated from `BuildConfig` (which is generated from the
 * untracked `local.properties`).
 *
 * The key is compiled into this experimental build and can be extracted from the APK; nothing in
 * this class makes it secret. [toString] never reveals it.
 *
 * @property apiKey Gemini API key; blank when not configured.
 * @property model Gemini model id, for example `gemini-3.5-flash-lite`.
 * @property baseUrl API base URL, ending with a slash.
 */
data class GeminiConfig(
    val apiKey: String,
    val model: String,
    val baseUrl: String = DEFAULT_BASE_URL,
) {
    /** `true` when a request may be sent. A blank key or model prevents any network call. */
    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && model.isNotBlank()

    /** Log-safe representation that never contains the key. */
    override fun toString(): String =
        "GeminiConfig(model=$model, baseUrl=$baseUrl, apiKey=${if (apiKey.isBlank()) "<missing>" else "<redacted>"})"

    /** Defaults. */
    companion object {
        /** Official Gemini API host. */
        const val DEFAULT_BASE_URL: String = "https://generativelanguage.googleapis.com/"

        /**
         * Default model: a stable multimodal Flash-Lite model, chosen because it answered image
         * requests in under 2 s on 2026-10-07 while larger Flash models were slow or overloaded.
         */
        const val DEFAULT_MODEL: String = "gemini-3.5-flash-lite"
    }
}
