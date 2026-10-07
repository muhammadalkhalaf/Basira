package com.basira.core.coroutines

/** Application-wide, non-secret constants that are independent of the Android framework. */
object BasiraConstants {
    /** Language code sent to the vision provider; descriptions are always requested in Arabic. */
    const val RESPONSE_LANGUAGE: String = "ar"

    /** Maximum JPEG payload sent for analysis, in bytes (well below Gemini's 20 MB inline limit). */
    const val MAX_UPLOAD_BYTES: Int = 1_500_000

    /** Longest edge of the uploaded image, in pixels. */
    const val MAX_IMAGE_EDGE_PX: Int = 1280

    /** First JPEG quality tried when encoding; lower qualities are used only if the payload is too big. */
    const val JPEG_INITIAL_QUALITY: Int = 82

    /** Longest description, in characters, that the client is willing to speak. */
    const val MAX_DESCRIPTION_CHARS: Int = 4_000

    /** Longest object name accepted for object-finding mode, in characters. */
    const val MAX_TARGET_OBJECT_CHARS: Int = 80
}
