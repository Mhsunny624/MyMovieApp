package com.mymovieapp.app.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.protocol.commons.EnumWithValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTPReply
import java.io.IOException

object Sources {
    /** (mediaStoreVolumeName, label): internal storage plus any USB drives the system has indexed. */
    fun mediaVolumes(ctx: Context): List<Pair<String, String>> {
        if (Build.VERSION.SDK_INT < 29) return listOf("external" to "Device storage")
        val sm = ctx.getSystemService(StorageManager::class.java)
        return MediaStore.getExternalVolumeNames(ctx).sorted().map { name ->
            val label = sm.storageVolumes.firstOrNull { it.mediaStoreVolumeName == name }?.getDescription(ctx)
            name to (label ?: if (name == "external_primary") "Internal storage" else name)
        }
    }
}

class Scanner(private val ctx: Context, private val dao: LibraryDao) {
    companion object {
        val VIDEO_EXT = setOf("mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "wmv", "flv", "mpg", "mpeg", "3gp")
        val SUB_EXT = setOf("srt", "vtt")
    }

    private class Entry(val uri: String, val name: String, val size: Long, val modified: Long, val folders: List<String>)

    private fun subKey(path: List<String>, base: String) = path.joinToString("/") + "/" + base.lowercase()

    // ---- system folder picker (content://) ----
    private fun walkSaf(tree: Uri, rootName: String, videos: MutableList<Entry>, subs: MutableMap<String, String>) {
        fun rec(docId: String, path: List<String>) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )
            ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!name.startsWith(".")) rec(id, listOf(name) + path)
                    } else {
                        val ext = name.substringAfterLast('.', "").lowercase()
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
                        if (ext in VIDEO_EXT) videos += Entry(uri, name, c.getLong(3), c.getLong(4), path)
                        else if (ext in SUB_EXT) subs[subKey(path, name.substringBeforeLast('.'))] = uri
                    }
                }
            }
        }
        rec(DocumentsContract.getTreeDocumentId(tree), listOf(rootName))
    }

    // ---- device storage / USB via MediaStore (media://<volume>) ----
    private fun walkMedia(volume: String, videos: MutableList<Entry>) {
        val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(volume)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val pathCol = if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.RELATIVE_PATH else MediaStore.Video.Media.DATA
        val cols = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED, pathCol
        )
        ctx.contentResolver.query(collection, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val raw = c.getString(4).orEmpty()
                val dir = if (Build.VERSION.SDK_INT >= 29) raw else raw.substringBeforeLast('/', "")
                val folders = dir.trim('/').split('/').filter { it.isNotBlank() }.reversed()
                videos += Entry(
                    ContentUris.withAppendedId(collection, c.getLong(0)).toString(),
                    name, c.getLong(2), c.getLong(3) * 1000, folders
                )
            }
        }
    }

    // ---- network share (smb://host/share/path) ----
    private fun walkSmb(root: Uri, rootName: String, videos: MutableList<Entry>, subs: MutableMap<String, String>) {
        val (host, shareName, baseRel) = Smb.split(root)
        val share = Smb.share(host, shareName)
        fun rec(rel: String, path: List<String>) {
            for (info in share.list(rel)) {
                val name = info.fileName
                if (name == "." || name == "..") continue
                val childRel = if (rel.isEmpty()) name else "$rel\\$name"
                if (EnumWithValue.EnumUtils.isSet(info.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY)) {
                    if (!name.startsWith(".") && !name.startsWith("$")) rec(childRel, listOf(name) + path)
                } else {
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val uri = Smb.uri(host, shareName, childRel).toString()
                    if (ext in VIDEO_EXT) {
                        videos += Entry(uri, name, info.endOfFile, info.lastWriteTime.toEpochMillis(), path)
                    } else if (ext in SUB_EXT) {
                        subs[subKey(path, name.substringBeforeLast('.'))] = uri
                    }
                }
            }
        }
        rec(baseRel, listOf(rootName))
    }
    // ---- FTP server (ftp://host:port/path) ----
    private fun walkFtp(root: Uri, rootName: String, videos: MutableList<Entry>, subs: MutableMap<String, String>) {
        val (host, port, base) = Ftp.split(root)
        val f = Ftp.connect(host, port)
        try {
            fun rec(dir: String, path: List<String>) {
                val items = f.listFiles(dir)
                if (!FTPReply.isPositiveCompletion(f.replyCode)) throw IOException("Couldn't list $dir")
                for (e in items.orEmpty()) {
                    val name = e.name ?: continue
                    if (name == "." || name == "..") continue
                    val child = (if (dir.endsWith("/")) dir else "$dir/") + name
                    if (e.isDirectory) {
                        if (!name.startsWith(".")) rec(child, listOf(name) + path)
                    } else if (e.isFile) {
                        val ext = name.substringAfterLast('.', "").lowercase()
                        val uri = Ftp.uri(host, port, child).toString()
                        if (ext in VIDEO_EXT) videos += Entry(uri, name, e.size, e.timestamp?.timeInMillis ?: 0L, path)
                        else if (ext in SUB_EXT) subs[subKey(path, name.substringBeforeLast('.'))] = uri
                    }
                }
            }
            rec(base, listOf(rootName))
        } finally { Ftp.quit(f) }
    }

    private suspend fun ensureTitle(p: NameParser.Parsed, addedAt: Long): Title {
        dao.titleByKey(p.key)?.let { return it }
        val t = Title(titleKey = p.key, kind = p.kind, name = p.name, year = p.year, addedAt = addedAt)
        val id = dao.insertTitle(t)
        return t.copy(id = id)
    }

    /** If listing fails, an exception is thrown BEFORE anything is deleted, so an offline NAS never empties the library. */
    suspend fun scan(folder: LibraryFolder) = withContext(Dispatchers.IO) {
        val videos = ArrayList<Entry>()
        val subs = HashMap<String, String>()
        val u = Uri.parse(folder.uri)
        when (u.scheme) {
            "media" -> walkMedia(folder.uri.removePrefix("media://"), videos)
            "smb" -> walkSmb(u, folder.name, videos, subs)
            "ftp" -> walkFtp(u, folder.name, videos, subs)
            else -> walkSaf(u, folder.name, videos, subs)
        }

        val existing = dao.videoSubsInFolder(folder.uri).associateBy { it.uri }
        val seen = HashSet<String>()
        val now = System.currentTimeMillis()
        for (e in videos) {
            seen += e.uri
            // the folder inside the source: the walk lists nearest folder first and (except for device storage) the source itself last
            val rel = (if (u.scheme == "media") e.folders.reversed() else e.folders.dropLast(1).reversed()).joinToString("/").ifEmpty { null }
            val old = existing[e.uri]
            if (old != null) { if (old.subPath != rel) dao.setSubPath(old.id, rel); continue }
            val p = NameParser.parse(e.name, e.folders)
            val added = if (e.modified > 0) e.modified else now
            val title = ensureTitle(p, added)
            val k = subKey(e.folders, e.name.substringBeforeLast('.'))
            val sub = subs[k] ?: subs.entries.firstOrNull { it.key.startsWith("$k.") }?.value
            dao.insertVideo(
                Video(
                    uri = e.uri, folderUri = folder.uri, titleId = title.id, fileName = e.name,
                    season = p.season, episode = p.episode, subtitleUri = sub,
                    sizeBytes = e.size, addedAt = added, subPath = rel
                )
            )
        }
        val gone = existing.filterKeys { it !in seen }.values.map { it.id }
        if (gone.isNotEmpty()) dao.deleteVideos(gone)
        dao.deleteOrphanTitles()
    }

    suspend fun addFiles(uris: List<Uri>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        for (u in uris) {
            var name: String? = null
            var size = 0L
            ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) { name = c.getString(0); size = c.getLong(1) }
            }
            val n = name ?: continue
            if (n.substringAfterLast('.', "").lowercase() !in VIDEO_EXT) continue
            val p = NameParser.parse(n, emptyList())
            val title = ensureTitle(p, now)
            dao.insertVideo(
                Video(
                    uri = u.toString(), folderUri = null, titleId = title.id, fileName = n,
                    season = p.season, episode = p.episode, subtitleUri = null, sizeBytes = size, addedAt = now
                )
            )
        }
    }
}
