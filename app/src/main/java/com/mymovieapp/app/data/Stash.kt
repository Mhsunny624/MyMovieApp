package com.mymovieapp.app.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/** One scene of the user's Stash library. A video of the app is linked to it by file name + size. */
@Entity(tableName = "stash_scenes", indices = [Index("nameKey")])
data class StashScene(
    @PrimaryKey val id: Int,
    val title: String?,
    val date: String?,
    val rating: Int?,              // 0-100, as Stash keeps it
    val studio: String?,
    val tags: String,              // ", "-separated
    val fileName: String,
    val nameKey: String,           // fileName in lower case
    val size: Long,
    val sizeUnique: Boolean        // no other scene has this size: a renamed file can still be recognised
)

@Entity(tableName = "stash_performers")
data class StashPerformer(@PrimaryKey val id: Int, val name: String, val hasPhoto: Boolean, val gender: String? = null)   // gender: M, F, T (trans) or null

@Entity(tableName = "stash_links", primaryKeys = ["sceneId", "performerId"], indices = [Index("performerId")])
data class StashLink(val sceneId: Int, val performerId: Int)

/** A video of the app that matches a Stash scene, once per performer of that scene (performerId 0 = the scene has none). */
data class StashRow(
    val sceneId: Int,
    val performerId: Int,
    val videoId: Long,
    val uri: String,
    val folderUri: String?,
    val fileName: String,
    val durationMs: Long,
    val title: String?,
    val studio: String?,
    val date: String?,
    val rating: Int?,
    val tags: String
)

data class PerformerEntry(val id: Int, val name: String, val hasPhoto: Boolean, val count: Int, val gender: String? = null)

/**
 * "Import from Stash": reads the folder made by tools\stash_export.ps1 (stash.json + performers\p<id>.jpg),
 * replaces the Stash data kept in the app and copies the performer photos into the app's own storage.
 */
object StashImport {
    class Result(val scenes: Int, val performers: Int, val photos: Int)

    private interface Src {
        fun json(): String
        fun photos(): Map<String, () -> InputStream?>
        fun covers(): Map<String, () -> InputStream?>
    }

    /** Where `adb push` / a file manager can put the export so the app finds it without a picker. */
    fun defaultDir(ctx: Context): File = File(ctx.getExternalFilesDir(null), "stash_export")

    fun coverFile(ctx: Context, sceneId: Int): File = File(ctx.filesDir, "stash/scenes/s$sceneId.jpg")

    fun photoFile(ctx: Context, performerId: Int): File = File(ctx.filesDir, "stash/performers/p$performerId.jpg")

    suspend fun fromDir(ctx: Context, db: AppDb, dir: File): Result = run(ctx, db, false, object : Src {
        override fun json() = File(dir, "stash.json").readText(Charsets.UTF_8)
        override fun photos(): Map<String, () -> InputStream?> =
            File(dir, "performers").listFiles().orEmpty().associate { f -> f.name to { runCatching { f.inputStream() }.getOrNull() } }
        override fun covers(): Map<String, () -> InputStream?> =
            File(dir, "scenes").listFiles().orEmpty().associate { f -> f.name to { runCatching { f.inputStream() }.getOrNull() } }
    })

    suspend fun fromTree(ctx: Context, db: AppDb, tree: Uri): Result {
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: error("Can't open that folder")
        return fromDoc(ctx, db, root, false)
    }

    /** [root] holds stash.json and performers\. merge = true adds to what is already there (one drive's data next to another's). */
    suspend fun fromDoc(ctx: Context, db: AppDb, root: DocumentFile, merge: Boolean): Result {
        return run(ctx, db, merge, object : Src {
            override fun json(): String {
                val f = root.findFile("stash.json") ?: error("stash.json not found in that folder")
                return ctx.contentResolver.openInputStream(f.uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("Can't read stash.json")
            }
            override fun photos(): Map<String, () -> InputStream?> =
                root.findFile("performers")?.listFiles().orEmpty().mapNotNull { f -> f.name?.let { n -> n to { runCatching { ctx.contentResolver.openInputStream(f.uri) }.getOrNull() } } }.toMap()
            override fun covers(): Map<String, () -> InputStream?> =
                root.findFile("scenes")?.listFiles().orEmpty().mapNotNull { f -> f.name?.let { n -> n to { runCatching { ctx.contentResolver.openInputStream(f.uri) }.getOrNull() } } }.toMap()
        })
    }

    /** [dirRel] is the MyMovieApp_stash folder inside a network share (backslashes). */
    suspend fun fromSmb(ctx: Context, db: AppDb, share: com.hierynomus.smbj.share.DiskShare, dirRel: String, merge: Boolean): Result {
        fun open(rel: String): InputStream? = runCatching {
            val f = share.openFile(
                rel, java.util.EnumSet.of(com.hierynomus.msdtyp.AccessMask.GENERIC_READ), null,
                com.hierynomus.mssmb2.SMB2ShareAccess.ALL, com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OPEN, null
            )
            object : java.io.FilterInputStream(f.inputStream) {
                override fun close() { try { super.close() } catch (_: Exception) {}; try { f.close() } catch (_: Exception) {} }
            } as InputStream
        }.getOrNull()
        fun names(rel: String): List<String> =
            runCatching { share.list(rel).map { it.fileName }.filter { it != "." && it != ".." } }.getOrDefault(emptyList())
        return run(ctx, db, merge, object : Src {
            override fun json(): String = open("$dirRel\\stash.json")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("Can't read stash.json")
            override fun photos(): Map<String, () -> InputStream?> = names("$dirRel\\performers").associate { n -> n to { open("$dirRel\\performers\\$n") } }
            override fun covers(): Map<String, () -> InputStream?> = names("$dirRel\\scenes").associate { n -> n to { open("$dirRel\\scenes\\$n") } }
        })
    }

    private suspend fun run(ctx: Context, db: AppDb, merge: Boolean, src: Src): Result = withContext(Dispatchers.IO) {
        val j = JSONObject(src.json())
        val photos = src.photos()
        val covers = src.covers()

        val sj = j.getJSONArray("scenes")
        val sizeCount = HashMap<Long, Int>()
        for (i in 0 until sj.length()) { val z = sj.getJSONObject(i).optLong("size"); sizeCount[z] = (sizeCount[z] ?: 0) + 1 }
        val scenes = ArrayList<StashScene>()
        val links = ArrayList<StashLink>()
        for (i in 0 until sj.length()) {
            val o = sj.getJSONObject(i)
            val file = o.optString("file")
            if (file.isBlank()) continue
            val id = o.getInt("id")
            val size = o.optLong("size")
            val tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            scenes += StashScene(
                id = id,
                title = o.optString("title").takeIf { it.isNotBlank() && it != "null" },
                date = o.optString("date").takeIf { it.isNotBlank() && it != "null" },
                rating = if (o.isNull("rating")) null else o.optInt("rating"),
                studio = o.optString("studio").takeIf { it.isNotBlank() && it != "null" },
                tags = tags.joinToString(", "),
                fileName = file, nameKey = file.lowercase(), size = size,
                sizeUnique = (sizeCount[size] ?: 0) == 1
            )
            o.optJSONArray("performers")?.let { a -> for (k in 0 until a.length()) links += StashLink(id, a.getInt(k)) }
        }
        val pj = j.getJSONArray("performers")
        val perfs = (0 until pj.length()).map { i ->
            val o = pj.getJSONObject(i)
            val id = o.getInt("id")
            val g = when (o.optString("gender").uppercase()) { "MALE" -> "M"; "FEMALE" -> "F"; "TRANSGENDER_MALE", "TRANSGENDER_FEMALE" -> "T"; else -> null }
            StashPerformer(id, o.optString("name"), photos.containsKey("p$id.jpg") || (merge && photoFile(ctx, id).exists()), g)
        }

        // photos first (a failure here leaves the old data untouched)
        val dest = File(ctx.filesDir, "stash/performers")
        if (!merge) dest.deleteRecursively()
        dest.mkdirs()
        var copied = 0
        for (p in perfs) {
            val open = photos["p${p.id}.jpg"] ?: continue
            runCatching { open()?.use { ins -> File(dest, "p${p.id}.jpg").outputStream().use { ins.copyTo(it) } }; copied++ }
        }

        // scene covers made by Stash
        val cdest = File(ctx.filesDir, "stash/scenes")
        if (!merge) cdest.deleteRecursively()
        cdest.mkdirs()
        for (s in scenes) {
            val open = covers["s${s.id}.jpg"] ?: continue
            runCatching { open()?.use { ins -> File(cdest, "s${s.id}.jpg").outputStream().use { ins.copyTo(it) } } }
        }

        val dao = db.dao()
        db.withTransaction {
            if (merge) scenes.map { it.id }.chunked(500).forEach { dao.deleteStashLinksOf(it) }
            else { dao.clearStashLinks(); dao.clearStashScenes(); dao.clearStashPerformers() }
            dao.insertStashScenes(scenes); dao.insertStashPerformers(perfs); dao.insertStashLinks(links)
        }
        Result(scenes.size, perfs.size, copied)
    }

    suspend fun clear(ctx: Context, db: AppDb) = withContext(Dispatchers.IO) {
        val dao = db.dao()
        db.withTransaction { dao.clearStashLinks(); dao.clearStashScenes(); dao.clearStashPerformers() }
        File(ctx.filesDir, "stash").deleteRecursively()
    }
}

/** Drives (a folder picked once, normally the root of the USB drive) the app looks at for MyMovieApp_stash\stash.json. */
class StashDrives(ctx: Context) {
    private val sp = ctx.getSharedPreferences("stash", Context.MODE_PRIVATE)
    fun trees(): Set<String> = sp.getStringSet("trees", emptySet()).orEmpty().toSet()
    fun add(uri: String) { sp.edit().putStringSet("trees", trees() + uri).apply() }
    fun remove(uri: String) { sp.edit().putStringSet("trees", trees() - uri).remove("sig:$uri").apply() }
    fun sig(uri: String): String? = sp.getString("sig:$uri", null)
    fun setSig(uri: String, s: String?) { sp.edit().apply { if (s == null) remove("sig:$uri") else putString("sig:$uri", s) }.apply() }
    fun seeded(): Set<String> = sp.getStringSet("seeded", emptySet()).orEmpty().toSet()
    fun addSeeded(uris: Collection<String>) { sp.edit().putStringSet("seeded", seeded() + uris).apply() }
    fun clearSigs() { sp.edit().apply { sp.all.keys.filter { it.startsWith("sig:") }.forEach { remove(it) } }.apply() }
}
