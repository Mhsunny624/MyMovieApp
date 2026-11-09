package com.mymovieapp.app.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.media.MediaDataSource

/** Reads each video's length once (2 files at a time) and saves it in the library. */
object Durations {
    private val gate = Semaphore(2)
    private val lock = Mutex()   // runs one pass at a time; extra calls simply wait their turn

    /** folder = null reads everything still missing; otherwise only that library folder. */
    suspend fun fill(ctx: Context, dao: LibraryDao, folder: String? = null) = withContext(Dispatchers.IO) {
        lock.withLock {
            val tried = HashSet<Long>()
            while (true) {
                val all = if (folder == null) dao.videosWithoutDuration() else dao.videosWithoutDurationIn(folder)
                val todo = all.filter { it.id !in tried }
                if (todo.isEmpty()) break
                coroutineScope {
                    todo.map { v ->
                        async {
                            gate.withPermit {
                                val d = read(ctx, v.uri)
                                if (d != 0L) dao.setDuration(v.id, d)   // 0 = share offline etc., try again later
                            }
                        }
                    }.awaitAll()
                }
                tried += todo.map { it.id }
            }
        }
    }

    /** > 0 = length in ms, -1 = file has no readable length, 0 = couldn't open it right now. */
    private fun read(ctx: Context, uri: String): Long {
        val r = MediaMetadataRetriever()
        var src: MediaDataSource? = null
        return try {
            if (uri.startsWith("smb://")) src = SmbMediaSource(Uri.parse(uri)).also { r.setDataSource(it) }
            else if (uri.startsWith("ftp://")) src = FtpMediaSource(Uri.parse(uri)).also { r.setDataSource(it) }
            else r.setDataSource(ctx, Uri.parse(uri))
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 } ?: -1L
        } catch (e: Exception) {
            0L
        } finally {
            try { r.release() } catch (_: Exception) {}
            src?.close()
        }
    }
}