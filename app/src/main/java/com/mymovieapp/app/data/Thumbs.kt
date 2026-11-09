package com.mymovieapp.app.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap

/** Makes a poster-sized still from a video's own frames and caches it, for titles with no TMDB poster. */
object Thumbs {
    private val gate = Semaphore(1)   // one at a time: a TV's video chip often can't open two 4K decoders at once
    private val failed = ConcurrentHashMap.newKeySet<String>()
    /** Number of players open. Thumbnail work waits while a video is playing, so it can't steal the decoder or the network. */
    val playersOpen = java.util.concurrent.atomic.AtomicInteger(0)
    /** Emits a video's uri whenever its still has just been made, so cards on screen can pick it up. */
    private val created = MutableSharedFlow<String>(extraBufferCapacity = 128)

    /** The still if it already exists. Never makes one, so it is safe to call while scrolling. */
    fun cached(ctx: Context, uri: String): File? = fileFor(ctx, uri).takeIf { it.exists() && it.length() > 0 }
    fun hasFailed(uri: String) = uri in failed
    /** Puts a ready-made still (a Stash cover) where the app would otherwise make its own, replacing one it made. */
    fun seed(ctx: Context, uri: String, src: File) {
        val out = fileFor(ctx, uri)
        out.parentFile?.mkdirs()
        src.copyTo(out, overwrite = true)
        failed -= uri
        created.tryEmit(uri)
    }
    private val reasons = ConcurrentHashMap<String, String>()
    fun reason(uri: String): String? = reasons[uri]
    /** Lets files that couldn't be read before be tried again. */
    fun forgetFailures() { failed.clear() }
    suspend fun awaitCreated(uri: String) { created.first { it == uri } }

    private fun fileFor(ctx: Context, uri: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
        return File(ctx.cacheDir, "thumbs/$h.jpg")
    }
    fun cacheBytes(ctx: Context): Long =
        File(ctx.cacheDir, "thumbs").listFiles().orEmpty().sumOf { it.length() }

    fun clearCache(ctx: Context) {
        File(ctx.cacheDir, "thumbs").listFiles()?.forEach { it.delete() }
        failed.clear()          // also lets videos that failed before be tried again
    }

    suspend fun get(ctx: Context, videoUri: String): File? = withContext(Dispatchers.IO) {
        val out = fileFor(ctx, videoUri)
        if (out.exists() && out.length() > 0) return@withContext out
        if (videoUri in failed) return@withContext null
        gate.withPermit {
            while (playersOpen.get() > 0) kotlinx.coroutines.delay(1000)
            if (out.exists() && out.length() > 0) return@withPermit out
            val r = MediaMetadataRetriever()
            var src: MediaDataSource? = null
            try {
                if (videoUri.startsWith("smb://")) {
                    src = SmbMediaSource(Uri.parse(videoUri)).also { r.setDataSource(it) }
                } else if (videoUri.startsWith("ftp://")) {
                    src = FtpMediaSource(Uri.parse(videoUri)).also { r.setDataSource(it) }
                } else {
                    r.setDataSource(ctx, Uri.parse(videoUri))
                }
                val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                // Aim well into the film (15%, at most 5 min in) so we skip logos and black intro frames.
                val atUs = (if (durMs > 0) minOf(durMs * 15 / 100, 300_000L) else 60_000L) * 1000
                val vw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val vh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                // Ask for a small picture first: a full 4K bitmap is too big for some TV boxes.
                fun grab(t: Long): Bitmap? {
                    val small = if (android.os.Build.VERSION.SDK_INT >= 27 && vw > 480 && vh > 0)
                        r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, (480f * vh / vw).toInt().coerceAtLeast(1))
                    else null
                    return small ?: r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
                // Try the chosen spot first, then nearer the start only if that fails.
                var frame: Bitmap? = null
                for (t in listOf(atUs, atUs / 2, 30_000_000L, 5_000_000L, 0L)) {
                    frame = grab(t)
                    if (frame != null) break
                }
                if (frame == null) frame = r.getFrameAtTime(-1)
                // The TV's hardware decoder can't open some 4K files: decode one frame in software instead.
                if (frame == null) frame = try { SoftFrame.grab(ctx, videoUri, atUs) } catch (_: Throwable) { null }
                if (frame == null) { failed += videoUri; reasons[videoUri] = "no picture could be decoded"; return@withPermit null }
                val w = 480
                val h = (frame.height * (w.toFloat() / frame.width)).toInt().coerceAtLeast(1)
                val scaled = if (frame.width > w) Bitmap.createScaledBitmap(frame, w, h, true) else frame
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                created.tryEmit(videoUri)
                out
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failed += videoUri
                reasons[videoUri] = e.javaClass.simpleName + (e.message?.let { ": " + it.take(80) } ?: "")
                null
            } finally {
                try { r.release() } catch (_: Exception) {}
                src?.close()
            }
        }
    }
}

/** Lets MediaMetadataRetriever read a video straight from an SMB share. */
internal class SmbMediaSource(uri: Uri) : MediaDataSource() {
    private val file: com.hierynomus.smbj.share.File
    private val total: Long
    private var blockStart = -1L
    private var block = ByteArray(0)

    init {
        val (h, s, rel) = Smb.split(uri)
        file = Smb.share(h, s).openFile(
            rel, EnumSet.of(AccessMask.GENERIC_READ), null,
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null
        )
        total = file.fileInformation.standardInformation.endOfFile
    }

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= total) return -1
        val n = minOf(size.toLong(), total - position).toInt()
        var done = 0
        while (done < n) {
            val p = position + done
            if (blockStart < 0 || p < blockStart || p >= blockStart + block.size) {
                val len = minOf(512 * 1024L, total - p).toInt()
                val b = ByteArray(len)
                var got = 0
                while (got < len) {
                    val r = file.read(b, p + got, got, len - got)
                    if (r <= 0) break
                    got += r
                }
                if (got == 0) return if (done == 0) -1 else done
                block = b.copyOf(got)
                blockStart = p
            }
            val c = minOf(n - done, (blockStart + block.size - p).toInt())
            System.arraycopy(block, (p - blockStart).toInt(), buffer, offset + done, c)
            done += c
        }
        return done
    }

    override fun getSize(): Long = total

    override fun close() { try { file.close() } catch (_: Exception) {} }
}