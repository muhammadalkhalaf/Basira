package com.basira.app.data.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.basira.core.coroutines.BasiraConstants
import com.basira.core.coroutines.DispatcherProvider
import com.basira.core.logging.NoOpLogger
import com.basira.core.result.AppResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageProcessorTest {

    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
    }
    private val processor = ImageProcessor(dispatchers, NoOpLogger)

    private fun jpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.DKGRAY) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    /** Writes EXIF orientation and a GPS location into [bytes]. */
    private fun withExif(bytes: ByteArray, orientation: Int): ByteArray {
        val file = File.createTempFile("exif", ".jpg").apply { writeBytes(bytes) }
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            setLatLong(33.5138, 36.2765)
            saveAttributes()
        }
        return file.readBytes().also { file.delete() }
    }

    @Test
    fun `large photos are downscaled within the upload limits`() = runBlocking {
        val result = processor.process(RawPhoto.Encoded(jpeg(3000, 2000))) as AppResult.Success
        val image = result.value
        assertTrue(maxOf(image.widthPx, image.heightPx) <= BasiraConstants.MAX_IMAGE_EDGE_PX)
        assertTrue(image.jpegBytes.size <= BasiraConstants.MAX_UPLOAD_BYTES)
        assertEquals(0xFF.toByte(), image.jpegBytes[0])
        assertEquals(0xD8.toByte(), image.jpegBytes[1])
    }

    @Test
    fun `EXIF orientation is applied once and location metadata is removed`() = runBlocking {
        val input = withExif(jpeg(800, 400), ExifInterface.ORIENTATION_ROTATE_90)
        assertTrue(ExifInterface(ByteArrayInputStream(input)).latLong != null)

        val image = (processor.process(RawPhoto.Encoded(input)) as AppResult.Success).value

        assertEquals(400, image.widthPx)
        assertEquals(800, image.heightPx)
        val exif = ExifInterface(ByteArrayInputStream(image.jpegBytes))
        assertNull(exif.latLong)
        assertEquals(ExifInterface.ORIENTATION_UNDEFINED, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED))
        val decoded = BitmapFactory.decodeByteArray(image.jpegBytes, 0, image.jpegBytes.size)
        assertEquals(400, decoded.width)
    }

    @Test
    fun `undecodable bytes are an invalid image`() = runBlocking {
        assertEquals(
            AppResult.Failure(com.basira.core.error.AppError.InvalidImage),
            processor.process(RawPhoto.Encoded(byteArrayOf(1, 2, 3))),
        )
    }
}
