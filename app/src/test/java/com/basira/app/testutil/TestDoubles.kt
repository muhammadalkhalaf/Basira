package com.basira.app.testutil

import com.basira.app.data.glasses.SampleImageSource
import com.basira.core.logging.AppLogger
import com.basira.domain.model.CapturedImage

/** Logger that records messages so tests can assert that secrets never reach logs. */
class RecordingLogger : AppLogger {
    val messages = mutableListOf<String>()
    override fun debug(tag: String, message: String) { messages += message }
    override fun info(tag: String, message: String) { messages += message }
    override fun warn(tag: String, message: String, throwable: Throwable?) { messages += message + (throwable?.message ?: "") }
    override fun error(tag: String, message: String, throwable: Throwable?) { messages += message + (throwable?.message ?: "") }
}

/**
 * Loads the standard sample images from test resources: text sign, stairs obstacle, indoor room, and
 * an empty dark frame (in that order).
 */
class ResourceSampleImageSource : SampleImageSource {
    private val names = listOf("01_text_sign.jpg", "02_obstacle_stairs.jpg", "03_indoor_room.jpg", "04_empty_dark.jpg")

    override fun load(index: Int): CapturedImage? {
        val name = names[index % names.size]
        val bytes = javaClass.classLoader?.getResourceAsStream("sample_images/$name")?.use { it.readBytes() } ?: return null
        return CapturedImage(bytes, 960, 720)
    }

    /** File name of the sample returned for [index]. */
    fun nameAt(index: Int): String = names[index % names.size]
}
