package com.basira.app.data.glasses

import android.content.Context
import android.graphics.BitmapFactory
import com.basira.core.reporting.ErrorDomain
import com.basira.core.reporting.ErrorReport
import com.basira.core.reporting.ErrorReporter
import com.basira.core.reporting.ErrorSeverity
import com.basira.domain.model.CapturedImage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject

/**
 * Loads sample JPEGs from `assets/sample_images/` (bundled only in debug builds).
 */
class AssetSampleImageSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val errorReporter: ErrorReporter,
) : SampleImageSource {

    private val names: List<String> by lazy {
        try {
            context.assets.list(DIRECTORY).orEmpty().filter { it.endsWith(".jpg") }.sorted()
        } catch (e: IOException) {
            report("sampleImages.list", e)
            emptyList()
        }
    }

    override fun load(index: Int): CapturedImage? {
        if (names.isEmpty()) return null
        val name = names[index % names.size]
        return try {
            val bytes = context.assets.open("$DIRECTORY/$name").use { it.readBytes() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            CapturedImage(bytes, bounds.outWidth, bounds.outHeight)
        } catch (e: IOException) {
            report("sampleImages.load", e)
            null
        }
    }

    /** A bundled sample that cannot be read is a packaging defect; the simulated capture fails. */
    private fun report(operation: String, failure: IOException) = errorReporter.report(
        ErrorReport(
            domain = ErrorDomain.STORAGE,
            operation = operation,
            severity = ErrorSeverity.WARNING,
            outcome = "capture_failed",
            throwable = failure,
        ),
    )

    private companion object {
        const val DIRECTORY = "sample_images"
    }
}
