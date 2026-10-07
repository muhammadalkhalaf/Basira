package com.basira.app.mock

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/**
 * Encodes a short H.264 MP4 on the device so MockDeviceKit has a camera feed (it refuses to start a
 * stream without `MockCameraKit.setCameraFeed`). Uses only platform MediaCodec/MediaMuxer, so no
 * binary video asset has to be committed.
 */
object MockVideoFeed {

    private const val WIDTH = 544
    private const val HEIGHT = 960
    private const val FRAME_RATE = 15
    private const val FRAME_COUNT = 45
    private const val TIMEOUT_US = 10_000L

    /**
     * Writes the video to [target].
     *
     * @param target output file.
     * @return [target].
     */
    fun create(target: File): File {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 1_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val info = MediaCodec.BufferInfo()
        var track = -1
        var framesQueued = 0
        var done = false
        try {
            while (!done) {
                if (framesQueued <= FRAME_COUNT) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val timeUs = framesQueued * 1_000_000L / FRAME_RATE
                        if (framesQueued == FRAME_COUNT) {
                            codec.queueInputBuffer(inputIndex, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            fillFrame(codec, inputIndex, framesQueued)
                            codec.queueInputBuffer(inputIndex, 0, WIDTH * HEIGHT * 3 / 2, timeUs, 0)
                        }
                        framesQueued++
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                    outputIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0 && track >= 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            muxer.writeSampleData(track, buffer, info)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            if (track >= 0) muxer.stop()
            muxer.release()
        }
        return target
    }

    /** Fills a moving gray gradient into the codec's YUV input image. */
    private fun fillFrame(codec: MediaCodec, index: Int, frame: Int) {
        val image = requireNotNull(codec.getInputImage(index)) { "encoder has no input image" }
        image.planes.forEachIndexed { planeIndex, plane ->
            val buffer = plane.buffer
            val planeWidth = if (planeIndex == 0) WIDTH else WIDTH / 2
            val planeHeight = if (planeIndex == 0) HEIGHT else HEIGHT / 2
            for (y in 0 until planeHeight) {
                for (x in 0 until planeWidth) {
                    val value = if (planeIndex == 0) ((x + y + frame * 4) and 0xFF) else 128
                    buffer.put(y * plane.rowStride + x * plane.pixelStride, value.toByte())
                }
            }
        }
    }
}
