package com.mymovieapp.app.data

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * Last resort for a video still: decode one frame with the device's SOFTWARE video decoder.
 * Some TV boxes can't start their hardware decoder for 4K files, which is why the normal method fails there.
 */
internal object SoftFrame {
    fun grab(ctx: Context, videoUri: String, atUs: Long): android.graphics.Bitmap? {
        val ex = MediaExtractor()
        var src: MediaDataSource? = null
        try {
            when {
                videoUri.startsWith("smb://", true) -> { src = SmbMediaSource(Uri.parse(videoUri)); ex.setDataSource(src) }
                videoUri.startsWith("ftp://", true) -> { src = FtpMediaSource(Uri.parse(videoUri)); ex.setDataSource(src) }
                else -> ex.setDataSource(ctx, Uri.parse(videoUri), null)
            }
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) return null
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val names = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
                .map { it.name }
                .filter { it.startsWith("c2.android.") || it.startsWith("OMX.google.") }
            for (name in names) {
                ex.selectTrack(track)
                ex.seekTo(atUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val bmp = try { decodeFirst(ex, fmt, name) } catch (_: Throwable) { null }
                if (bmp != null) return bmp
                ex.unselectTrack(track)
            }
            return null
        } finally {
            try { ex.release() } catch (_: Throwable) {}
            try { src?.close() } catch (_: Throwable) {}
        }
    }

    private fun decodeFirst(ex: MediaExtractor, fmt: MediaFormat, codecName: String): android.graphics.Bitmap? {
        val codec = MediaCodec.createByCodecName(codecName)
        try {
            codec.configure(fmt, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            val deadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    var bmp: android.graphics.Bitmap? = null
                    if (info.size > 0) {
                        val img = codec.getOutputImage(o)
                        if (img != null) { bmp = toBitmap(img); img.close() }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (bmp != null) return bmp
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return null
                }
            }
            return null
        } finally {
            try { codec.stop() } catch (_: Throwable) {}
            try { codec.release() } catch (_: Throwable) {}
        }
    }

    /** YUV frame -> small bitmap (about 480 px wide), sampling pixels so a 4K frame never needs a big copy. */
    private fun toBitmap(img: Image): android.graphics.Bitmap? {
        var crop = img.cropRect
        if (crop == null || crop.isEmpty) crop = Rect(0, 0, img.width, img.height)
        val cw = crop.width(); val ch = crop.height()
        val step = (cw / 480).coerceAtLeast(1)
        val ow = (cw / step) and 1.inv()
        val oh = (ch / step) and 1.inv()
        if (ow < 2 || oh < 2) return null
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yb = yP.buffer; val ub = uP.buffer; val vb = vP.buffer
        val nv = ByteArray(ow * oh * 3 / 2)
        var p = 0
        for (oy in 0 until oh) {
            val row = (crop.top + oy * step) * yP.rowStride
            for (ox in 0 until ow) nv[p++] = yb.get(row + (crop.left + ox * step) * yP.pixelStride)
        }
        var oy = 0
        while (oy < oh) {
            val cy = (crop.top + oy * step) / 2
            var ox = 0
            while (ox < ow) {
                val cx = (crop.left + ox * step) / 2
                nv[p++] = vb.get(cy * vP.rowStride + cx * vP.pixelStride)
                nv[p++] = ub.get(cy * uP.rowStride + cx * uP.pixelStride)
                ox += 2
            }
            oy += 2
        }
        val jpg = ByteArrayOutputStream()
        YuvImage(nv, ImageFormat.NV21, ow, oh, null).compressToJpeg(Rect(0, 0, ow, oh), 88, jpg)
        val bytes = jpg.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}
