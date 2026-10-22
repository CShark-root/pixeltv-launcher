package com.pixelden.launcher.system

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/**
 * Кодирует неподвижную картинку в короткий H.264-ролик (BT.709, ограниченный диапазон, как обычное видео).
 * Нужен калибровке: видеопроцессор ТВ обрабатывает только видео, а не интерфейс.
 */
object PatternVideo {
    private const val FRAMES = 10
    private const val FRAME_US = 200_000L

    fun encode(bmp: Bitmap, out: File) {
        val w = bmp.width
        val h = bmp.height
        val (y, u, v) = toYuv(bmp)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            // Высокий битрейт: в шаблонах линии в пиксель, сжатие не должно их размыть.
            setInteger(MediaFormat.KEY_BIT_RATE, 30_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 5)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val tmp = File(out.path + ".tmp")
        val muxer = MediaMuxer(tmp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var fed = 0
            var eosSent = false
            while (true) {
                if (!eosSent) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        if (fed < FRAMES) {
                            val img = codec.getInputImage(i) ?: error("кодировщик не дал кадр")
                            fill(img, y, u, v, w, h)
                            codec.queueInputBuffer(i, 0, w * h * 3 / 2, fed * FRAME_US, 0)
                            fed++
                        } else {
                            codec.queueInputBuffer(i, 0, 0, fed * FRAME_US, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosSent = true
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start() }
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && track >= 0) muxer.writeSampleData(track, buf, info)
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            runCatching { if (track >= 0) muxer.stop() }
            muxer.release()
        }
        if (!tmp.renameTo(out)) error("не удалось сохранить ${out.name}")
    }

    /** RGB → Y'CbCr BT.709 в ограниченном диапазоне (16–235 / 16–240), цвет — по среднему из квадрата 2×2. */
    private fun toYuv(bmp: Bitmap): Triple<ByteArray, ByteArray, ByteArray> {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        val y = ByteArray(w * h)
        val u = ByteArray(w / 2 * (h / 2))
        val v = ByteArray(w / 2 * (h / 2))
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            y[i] = (16 + (0.2126 * r + 0.7152 * g + 0.0722 * b) * 219 / 255 + 0.5).toInt().coerceIn(0, 255).toByte()
        }
        for (cy in 0 until h / 2) for (cx in 0 until w / 2) {
            var r = 0; var g = 0; var b = 0
            for (dy in 0..1) for (dx in 0..1) {
                val c = px[(cy * 2 + dy) * w + cx * 2 + dx]
                r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255
            }
            val rf = r / 4.0; val gf = g / 4.0; val bf = b / 4.0
            val k = cy * (w / 2) + cx
            u[k] = (128 + (-0.1146 * rf - 0.3854 * gf + 0.5 * bf) * 224 / 255 + 0.5).toInt().coerceIn(0, 255).toByte()
            v[k] = (128 + (0.5 * rf - 0.4542 * gf - 0.0458 * bf) * 224 / 255 + 0.5).toInt().coerceIn(0, 255).toByte()
        }
        return Triple(y, u, v)
    }

    /** Раскладывает плоскости по буферу кодировщика с учётом его шагов строк и пикселей. */
    private fun fill(img: android.media.Image, y: ByteArray, u: ByteArray, v: ByteArray, w: Int, h: Int) {
        val planes = img.planes
        fun put(plane: android.media.Image.Plane, src: ByteArray, pw: Int, ph: Int) {
            val buf = plane.buffer
            val rs = plane.rowStride
            val ps = plane.pixelStride
            for (row in 0 until ph) {
                val base = row * rs
                if (ps == 1) { buf.position(base); buf.put(src, row * pw, pw) }
                else for (col in 0 until pw) buf.put(base + col * ps, src[row * pw + col])
            }
        }
        put(planes[0], y, w, h)
        put(planes[1], u, w / 2, h / 2)
        put(planes[2], v, w / 2, h / 2)
    }
}
