package com.basira.app.data.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import com.basira.core.coroutines.BasiraConstants
import com.basira.core.coroutines.DispatcherProvider
import com.basira.core.error.AppError
import com.basira.core.reporting.ErrorDomain
import com.basira.core.reporting.ErrorReport
import com.basira.core.reporting.ErrorReporter
import com.basira.core.reporting.ErrorSeverity
import com.basira.core.result.AppResult
import com.basira.domain.model.CapturedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.withContext

/** Raw photo as delivered by the glasses SDK. */
sealed interface RawPhoto {
    /**
     * An already decoded bitmap; orientation is assumed to be applied by the SDK.
     *
     * @property bitmap decoded image owned by the SDK; it is not recycled by the processor.
     */
    class Decoded(val bitmap: Bitmap) : RawPhoto

    /**
     * Encoded bytes (HEIC or JPEG) that may carry an EXIF orientation tag.
     *
     * @property bytes encoded image.
     */
    class Encoded(val bytes: ByteArray) : RawPhoto
}

/**
 * Turns a raw glasses photo into an upload-ready JPEG.
 *
 * Steps: decode with bounded memory, apply the EXIF orientation exactly once, downscale so the longest
 * edge is at most [BasiraConstants.MAX_IMAGE_EDGE_PX], and re-encode as JPEG with decreasing quality
 * until the payload fits [BasiraConstants.MAX_UPLOAD_BYTES]. Re-encoding through [Bitmap.compress]
 * writes no EXIF block, so GPS location, timestamps, and device identifiers are removed.
 */
class ImageProcessor @Inject constructor(
    private val dispatchers: DispatcherProvider,
    private val errorReporter: ErrorReporter,
) {

    /**
     * Processes [photo] on the default dispatcher.
     *
     * @param photo raw SDK output.
     * @return the processed image or [AppError.InvalidImage] when it cannot be decoded.
     */
    suspend fun process(photo: RawPhoto): AppResult<CapturedImage> = withContext(dispatchers.default) {
        try {
            val oriented = when (photo) {
                is RawPhoto.Decoded -> photo.bitmap
                is RawPhoto.Encoded -> decodeOriented(photo.bytes)
            } ?: return@withContext fail(
                "image.decode",
                attributes = mapOf("image.bytes" to ((photo as? RawPhoto.Encoded)?.bytes?.size ?: 0).toString()),
            )
            val scaled = scaleDown(oriented)
            val ownsOriented = photo is RawPhoto.Encoded
            try {
                encode(scaled)
            } finally {
                if (scaled !== oriented) scaled.recycle()
                if (ownsOriented) oriented.recycle()
            }
        } catch (oom: OutOfMemoryError) {
            fail("image.process", throwable = oom)
        }
    }

    /** Reports a photo that could not be prepared and returns the failure the user hears. */
    private fun fail(
        operation: String,
        throwable: Throwable? = null,
        attributes: Map<String, String> = emptyMap(),
    ): AppResult.Failure {
        errorReporter.report(
            ErrorReport(
                domain = ErrorDomain.IMAGE,
                operation = operation,
                severity = ErrorSeverity.ERROR,
                outcome = "error_announced",
                throwable = throwable,
                attributes = attributes,
            ),
        )
        return AppResult.Failure(AppError.InvalidImage)
    }

    private fun decodeOriented(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val matrix = orientationMatrix(readOrientation(bytes))
        if (matrix.isIdentity) return decoded
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
            if (it !== decoded) decoded.recycle()
        }
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while (max(width, height) / (sample * 2) >= BasiraConstants.MAX_IMAGE_EDGE_PX) sample *= 2
        return sample
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= BasiraConstants.MAX_IMAGE_EDGE_PX) return bitmap
        val ratio = BasiraConstants.MAX_IMAGE_EDGE_PX.toFloat() / longest
        return bitmap.scale(
            (bitmap.width * ratio).roundToInt().coerceAtLeast(1),
            (bitmap.height * ratio).roundToInt().coerceAtLeast(1),
        )
    }

    private fun encode(bitmap: Bitmap): AppResult<CapturedImage> {
        for (quality in JPEG_QUALITIES) {
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) break
            val bytes = output.toByteArray()
            if (bytes.size <= BasiraConstants.MAX_UPLOAD_BYTES) {
                return AppResult.Success(CapturedImage(bytes, bitmap.width, bitmap.height))
            }
        }
        return fail("image.encode", attributes = mapOf("image.size" to "${bitmap.width}x${bitmap.height}"))
    }

    private fun readOrientation(bytes: ByteArray): Int = try {
        ByteArrayInputStream(bytes).use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    } catch (e: IOException) {
        errorReporter.report(
            ErrorReport(
                domain = ErrorDomain.IMAGE,
                operation = "image.exifOrientation",
                severity = ErrorSeverity.WARNING,
                outcome = "orientation_assumed_normal",
                throwable = e,
            ),
        )
        ExifInterface.ORIENTATION_NORMAL
    }

    private companion object {
        val JPEG_QUALITIES = listOf(BasiraConstants.JPEG_INITIAL_QUALITY, 72, 60, 48)

        /** Same EXIF orientation handling as the official CameraAccess sample. */
        fun orientationMatrix(orientation: Int): Matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    postRotate(90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    postRotate(270f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            }
        }
    }
}
