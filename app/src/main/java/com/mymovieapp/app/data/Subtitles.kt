package com.mymovieapp.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

// ---------------------------------------------------------------------------------------------
// Models + the provider interface. To add a new subtitle source: implement SubtitleProvider and
// append it to SubtitleProviders.all below. Its key field then appears in Settings -> Playback
// and its results are merged into the player's search automatically.
// ---------------------------------------------------------------------------------------------

data class SubtitleQuery(
    val title: String, val year: Int?, val tmdbId: Int?, val kind: String,   // "film" | "series"
    val season: Int?, val episode: Int?, val language: String, val fileName: String
)

data class SubtitleResult(
    val provider: String, val id: String, val name: String, val language: String,
    val downloadUrl: String, val note: String? = null
)

data class SubSearch(val results: List<SubtitleResult>, val message: String?)

/** One configured source the user added. `provider` in SubtitleResult holds this id. */
data class SubtitleSource(
    val id: String, val type: String, val label: String,
    val key: String = "", val user: String = "", val pass: String = "", val url: String = "",
    val enabled: Boolean = true
)

data class SourceType(
    val type: String, val label: String, val hint: String,
    val key: Boolean = true, val login: Boolean = false, val url: Boolean = false
)

interface SubtitleProvider {
    suspend fun search(q: SubtitleQuery, s: SubtitleSource): List<SubtitleResult>
    /** Returns the raw downloaded bytes (a .zip or a plain subtitle file). */
    suspend fun download(r: SubtitleResult, s: SubtitleSource): ByteArray
}

object SubtitleProviders {
    val types = listOf(
        SourceType("subdl", "SubDL", "Free key: subdl.com → your profile → API"),
        SourceType("opensubtitles", "OpenSubtitles", "Free API key at opensubtitles.com/consumers. Username and password are optional but give a bigger daily quota.", login = true),
        SourceType("wyzie", "Wyzie", "Needs a TMDB-matched title. The key is optional.", key = true),
        SourceType("subsource", "SubSource (beta)", "Key from your SubSource account. Beta: its API is new and may need tweaks."),
        SourceType(
            "custom", "Custom (advanced)",
            "A search URL that returns JSON. Placeholders: {title} {year} {tmdb} {season} {episode} {lang} {key}. " +
                    "The reply can be a list, or an object holding a list under results / subtitles / data. " +
                    "Each item needs a download link (url / download / link / file) and optionally a name and language.",
            key = true, url = true
        )
    )

    fun forType(t: String): SubtitleProvider? = when (t) {
        "subdl" -> SubDl
        "opensubtitles" -> OpenSubtitles
        "wyzie" -> Wyzie
        "subsource" -> SubSourceNet
        "custom" -> CustomSource
        else -> null
    }
}

object SubHttp {
    private val client = OkHttpClient()

    private fun run(req: Request): ByteArray {
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) {
                throw IOException(
                    when (r.code) {
                        401, 403 -> "The key or login was rejected. Check it in Settings → Subtitles."
                        429 -> "Daily limit reached for this source. Try again later."
                        else -> "Server error (HTTP ${r.code})"
                    }
                )
            }
            return r.body?.bytes() ?: ByteArray(0)
        }
    }

    fun get(url: HttpUrl, headers: Map<String, String> = emptyMap()): ByteArray {
        val b = Request.Builder().url(url).header("User-Agent", "MyMovieApp v1.0")
        headers.forEach { (k, v) -> b.header(k, v) }
        return run(b.build())
    }

    fun postJson(url: HttpUrl, json: String, headers: Map<String, String> = emptyMap()): ByteArray {
        val b = Request.Builder().url(url).header("User-Agent", "MyMovieApp v1.0")
            .post(json.toRequestBody("application/json".toMediaType()))
        headers.forEach { (k, v) -> b.header(k, v) }
        return run(b.build())
    }
}

private fun JSONObject.first(vararg keys: String): String =
    keys.map { optString(it) }.firstOrNull { it.isNotBlank() && it != "null" }.orEmpty()

/** Accepts either a JSON array or an object holding one under a common key. */
private fun jsonList(text: String): List<JSONObject> {
    val t = text.trim()
    val arr = if (t.startsWith("[")) JSONArray(t) else {
        val o = JSONObject(t)
        listOf("data", "results", "subtitles", "items").firstNotNullOfOrNull { o.optJSONArray(it) } ?: JSONArray()
    }
    return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

object SubDl : SubtitleProvider {
    override suspend fun search(q: SubtitleQuery, s: SubtitleSource): List<SubtitleResult> = withContext(Dispatchers.IO) {
        val b = HttpUrl.Builder().scheme("https").host("api.subdl.com").addPathSegments("api/v1/subtitles")
            .addQueryParameter("api_key", s.key)
            .addQueryParameter("languages", q.language.uppercase())
            .addQueryParameter("subs_per_page", "30")
            .addQueryParameter("type", if (q.kind == "series") "tv" else "movie")
        if (q.tmdbId != null) {
            b.addQueryParameter("tmdb_id", q.tmdbId.toString())
        } else {
            b.addQueryParameter("film_name", q.title)
            q.year?.let { b.addQueryParameter("year", it.toString()) }
        }
        if (q.kind == "series") {
            q.season?.let { b.addQueryParameter("season_number", it.toString()) }
            q.episode?.let { b.addQueryParameter("episode_number", it.toString()) }
        }
        val json = JSONObject(String(SubHttp.get(b.build())))
        if (!json.optBoolean("status", true)) {
            throw IOException(json.optString("error").ifBlank { "SubDL returned no results" })
        }
        val arr = json.optJSONArray("subtitles") ?: return@withContext emptyList()
        (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull { o ->
            val url = o.optString("url").ifBlank { o.optString("download_link") }
            if (url.isBlank()) return@mapNotNull null
            val name = o.optString("release_name").ifBlank { o.optString("name") }.ifBlank { "Subtitle" }
            val lang = o.optString("lang").ifBlank { o.optString("language") }.ifBlank { q.language }
            val note = listOfNotNull(
                if (o.optBoolean("full_season", false)) "Season pack" else null,
                o.optString("author").takeIf { it.isNotBlank() }
            ).joinToString(" · ").ifBlank { null }
            SubtitleResult(s.id, url, name, lang, url, note)
        }
    }

    override suspend fun download(r: SubtitleResult, s: SubtitleSource): ByteArray = withContext(Dispatchers.IO) {
        val full = if (r.downloadUrl.startsWith("http")) r.downloadUrl
        else "https://dl.subdl.com" + (if (r.downloadUrl.startsWith("/")) "" else "/") + r.downloadUrl
        val url = full.toHttpUrlOrNull() ?: throw IOException("Bad download link")
        SubHttp.get(url)
    }
}

object OpenSubtitles : SubtitleProvider {
    private const val BASE = "https://api.opensubtitles.com/api/v1"
    private val tokens = HashMap<String, String>()

    private fun hdr(s: SubtitleSource, auth: String? = null): Map<String, String> = buildMap {
        put("Api-Key", s.key); put("Accept", "application/json")
        if (auth != null) put("Authorization", "Bearer $auth")
    }

    private fun login(s: SubtitleSource): String? {
        if (s.user.isBlank() || s.pass.isBlank()) return null
        tokens[s.user]?.let { return it }
        val body = JSONObject().put("username", s.user).put("password", s.pass).toString()
        val t = JSONObject(String(SubHttp.postJson("$BASE/login".toHttpUrl(), body, hdr(s)))).optString("token")
        if (t.isBlank()) throw IOException("OpenSubtitles login failed")
        tokens[s.user] = t
        return t
    }

    override suspend fun search(q: SubtitleQuery, s: SubtitleSource): List<SubtitleResult> = withContext(Dispatchers.IO) {
        val b = "$BASE/subtitles".toHttpUrl().newBuilder().addQueryParameter("languages", q.language.lowercase())
        if (q.tmdbId != null) {
            b.addQueryParameter(if (q.kind == "series") "parent_tmdb_id" else "tmdb_id", q.tmdbId.toString())
        } else {
            b.addQueryParameter("query", q.title)
            q.year?.let { b.addQueryParameter("year", it.toString()) }
        }
        if (q.kind == "series") {
            q.season?.let { b.addQueryParameter("season_number", it.toString()) }
            q.episode?.let { b.addQueryParameter("episode_number", it.toString()) }
        }
        jsonList(String(SubHttp.get(b.build(), hdr(s)))).mapNotNull { o ->
            val a = o.optJSONObject("attributes") ?: return@mapNotNull null
            val f = a.optJSONArray("files")?.optJSONObject(0) ?: return@mapNotNull null
            val fileId = f.optLong("file_id", -1)
            if (fileId < 0) return@mapNotNull null
            val name = a.first("release").ifBlank { f.first("file_name") }.ifBlank { "Subtitle" }
            val note = a.optInt("download_count", 0).takeIf { it > 0 }?.let { "$it downloads" }
            SubtitleResult(s.id, fileId.toString(), name, a.first("language").ifBlank { q.language }, fileId.toString(), note)
        }
    }

    override suspend fun download(r: SubtitleResult, s: SubtitleSource): ByteArray = withContext(Dispatchers.IO) {
        val auth = login(s)
        val body = JSONObject().put("file_id", r.downloadUrl.toLong()).toString()
        val resp = JSONObject(String(SubHttp.postJson("$BASE/download".toHttpUrl(), body, hdr(s, auth))))
        val link = resp.optString("link")
        if (link.isBlank()) throw IOException(resp.optString("message").ifBlank { "OpenSubtitles download quota reached" })
        SubHttp.get(link.toHttpUrl())
    }
}

object Wyzie : SubtitleProvider {
    override suspend fun search(q: SubtitleQuery, s: SubtitleSource): List<SubtitleResult> = withContext(Dispatchers.IO) {
        val id = q.tmdbId ?: throw IOException("Wyzie needs a TMDB match. Match this title first.")
        val b = "https://sub.wyzie.ru/search".toHttpUrl().newBuilder()
            .addQueryParameter("id", id.toString())
            .addQueryParameter("language", q.language.lowercase())
        if (q.kind == "series") {
            q.season?.let { b.addQueryParameter("season", it.toString()) }
            q.episode?.let { b.addQueryParameter("episode", it.toString()) }
        }
        if (s.key.isNotBlank()) b.addQueryParameter("key", s.key)
        jsonList(String(SubHttp.get(b.build()))).mapNotNull { o ->
            val url = o.first("url")
            if (url.isBlank()) return@mapNotNull null
            val name = o.first("release", "fileName", "display").ifBlank { "Subtitle" }
            SubtitleResult(s.id, url, name, o.first("language").ifBlank { q.language }, url, o.first("format").ifBlank { null })
        }
    }

    override suspend fun download(r: SubtitleResult, s: SubtitleSource): ByteArray = withContext(Dispatchers.IO) {
        SubHttp.get(r.downloadUrl.toHttpUrlOrNull() ?: throw IOException("Bad download link"))
    }
}

/** SubSource.net. Built from its public docs and loosely parsed; treat as beta. */
object SubSourceNet : SubtitleProvider {
    private const val BASE = "https://api.subsource.net/api/v1"
    private fun hdr(s: SubtitleSource) = mapOf("X-API-Key" to s.key, "Accept" to "application/json")

    override suspend fun search(q: SubtitleQuery, s: SubtitleSource): List<SubtitleResult> = withContext(Dispatchers.IO) {
        val find = "$BASE/movies/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", q.title).addQueryParameter("searchType", "text").build()
        val movie = jsonList(String(SubHttp.get(find, hdr(s)))).firstOrNull() ?: return@withContext emptyList()
        val movieId = movie.first("movieId", "id")
        if (movieId.isBlank()) return@withContext emptyList()
        val langName = Locale(q.language.lowercase()).getDisplayLanguage(Locale.ENGLISH).lowercase()
        val list = "$BASE/subtitles".toHttpUrl().newBuilder()
            .addQueryParameter("movieId", movieId).addQueryParameter("language", langName).build()
        jsonList(String(SubHttp.get(list, hdr(s)))).mapNotNull { o ->
            val id = o.first("subtitleId", "id")
            if (id.isBlank()) return@mapNotNull null
            SubtitleResult(s.id, id, o.first("releaseInfo", "release_info", "name").ifBlank { "Subtitle" }, q.language, id)
        }
    }

    override suspend fun download(r: SubtitleResult, s: SubtitleSource): ByteArray = withContext(Dispatchers.IO) {
        SubHttp.get("$BASE/subtitles/${r.downloadUrl}/download".toHttpUrl(), hdr(s))
    }
}

object CustomSource : SubtitleProvider {
    override suspend fun search(q: SubtitleQuery, s: SubtitleSource): List<SubtitleResult> = withContext(Dispatchers.IO) {
        val u = s.url
            .replace("{title}", enc(q.title)).replace("{year}", q.year?.toString().orEmpty())
            .replace("{tmdb}", q.tmdbId?.toString().orEmpty()).replace("{season}", q.season?.toString().orEmpty())
            .replace("{episode}", q.episode?.toString().orEmpty()).replace("{lang}", q.language.lowercase())
            .replace("{key}", enc(s.key))
        val url = u.toHttpUrlOrNull() ?: throw IOException("The source URL isn't valid")
        jsonList(String(SubHttp.get(url))).mapNotNull { o ->
            val link = o.first("url", "download", "link", "file", "download_url")
            if (link.isBlank()) return@mapNotNull null
            SubtitleResult(s.id, link, o.first("release", "release_name", "name", "display", "title", "filename").ifBlank { "Subtitle" },
                o.first("lang", "language").ifBlank { q.language }, link)
        }
    }

    override suspend fun download(r: SubtitleResult, s: SubtitleSource): ByteArray = withContext(Dispatchers.IO) {
        SubHttp.get(r.downloadUrl.toHttpUrlOrNull() ?: throw IOException("Bad download link"))
    }
}

// ---------------------------------------------------------------------------------------------
// Search / download orchestration across all enabled sources.
// ---------------------------------------------------------------------------------------------

object SubtitleService {
    private fun usable(s: SubtitleSource) = s.enabled && SubtitleProviders.forType(s.type) != null &&
            when (s.type) {
                "custom" -> s.url.isNotBlank()
                "wyzie" -> true
                else -> s.key.isNotBlank()
            }

    suspend fun search(sources: List<SubtitleSource>, q: SubtitleQuery): SubSearch = coroutineScope {
        val active = sources.filter { usable(it) }
        if (active.isEmpty()) {
            return@coroutineScope SubSearch(emptyList(), "Add a subtitle source in Settings → Subtitles first.")
        }
        val outs = active.map { s ->
            async {
                try { Result.success(SubtitleProviders.forType(s.type)!!.search(q, s)) }
                catch (e: Exception) { Result.failure<List<SubtitleResult>>(e) }
            }
        }.awaitAll()
        val results = outs.flatMap { it.getOrNull().orEmpty() }
    .sortedByDescending { SubRank.score(q.fileName, it.name) }
        val errors = outs.mapIndexedNotNull { i, r -> r.exceptionOrNull()?.let { "${active[i].label}: ${it.message ?: "failed"}" } }
        SubSearch(
            results,
            when {
                results.isNotEmpty() -> errors.firstOrNull()
                errors.isNotEmpty() -> errors.joinToString("\n")
                else -> "No subtitles found for this language."
            }
        )
    }

    /** Returns null on success, or an error message. */
    suspend fun download(ctx: Context, sources: List<SubtitleSource>, videoUri: String, r: SubtitleResult, q: SubtitleQuery): String? =
        withContext(Dispatchers.IO) {
            try {
                val s = sources.firstOrNull { it.id == r.provider } ?: return@withContext "That source was removed"
                val p = SubtitleProviders.forType(s.type) ?: return@withContext "Unknown source"
                val raw = p.download(r, s)
                val (ext, bytes) = SubArchive.extract(raw, q) ?: return@withContext "That download had no subtitle file in it."
                val lang = r.language.ifBlank { q.language }
                SubStore.save(ctx, videoUri, lang, r.name, ext, SubText.decode(bytes, lang))
                null
            } catch (e: Exception) {
                e.message ?: "Download failed"
            }
        }
}

// ---------------------------------------------------------------------------------------------
// Search / download orchestration across all providers that have a key.
// ---------------------------------------------------------------------------------------------


object SubArchive {
    private val EXT = setOf("srt", "vtt", "ass", "ssa")

    fun extract(raw: ByteArray, q: SubtitleQuery): Pair<String, ByteArray>? {
        if (raw.size > 4 && raw[0] == 'P'.code.toByte() && raw[1] == 'K'.code.toByte()) {
            val entries = ArrayList<Pair<String, ByteArray>>()
            ZipInputStream(ByteArrayInputStream(raw)).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val ext = e.name.substringAfterLast('.', "").lowercase()
                    if (!e.isDirectory && ext in EXT) entries += e.name to z.readBytes()
                    e = z.nextEntry
                }
            }
            if (entries.isEmpty()) return null
            val pick = pickEpisode(entries, q) ?: entries.first()
            return pick.first.substringAfterLast('.').lowercase() to pick.second
        }
        val head = String(raw, 0, minOf(raw.size, 200), Charsets.ISO_8859_1)
        val ext = when {
            head.trimStart('\uFEFF', ' ', '\n', '\r').startsWith("WEBVTT") -> "vtt"
            head.contains("[Script Info]") -> "ass"
            else -> "srt"
        }
        return ext to raw
    }

    /** Season packs hold one file per episode; choose the one matching the video. */
    private fun pickEpisode(entries: List<Pair<String, ByteArray>>, q: SubtitleQuery): Pair<String, ByteArray>? {
        val s = q.season
        val e = q.episode
        if (s == null || e == null) return null
        val a = Regex("(?i)s0*$s[\\s._-]*e0*$e(?!\\d)")
        val b = Regex("(?i)(?<!\\d)0*${s}x0*$e(?!\\d)")
        return entries.firstOrNull { a.containsMatchIn(it.first) || b.containsMatchIn(it.first) }
    }
}

/** Subtitle files are often not UTF-8; convert to UTF-8 so every language displays correctly. */
object SubText {
    private fun strict(bytes: ByteArray, cs: Charset): String? = try {
        cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        null
    }

    fun decode(bytes: ByteArray, lang: String): String {
        var b = bytes
        if (b.size >= 2 && ((b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) || (b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()))) {
            return String(b, Charsets.UTF_16)
        }
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) b = b.copyOfRange(3, b.size)
        strict(b, Charsets.UTF_8)?.let { return it }
        val name = when (lang.uppercase().take(2)) {
            "AR", "FA" -> "windows-1256"
            "RU", "UK", "BG", "SR" -> "windows-1251"
            "TR" -> "windows-1254"
            "EL" -> "windows-1253"
            "HE" -> "windows-1255"
            "PL", "CS", "HU", "RO", "SK", "HR", "SL" -> "windows-1250"
            "ZH" -> "GBK"
            "JA" -> "Shift_JIS"
            "KO" -> "EUC-KR"
            else -> "windows-1252"
        }
        return String(b, runCatching { Charset.forName(name) }.getOrDefault(Charsets.ISO_8859_1))
    }
}

/** Downloaded subtitles live in app storage, one folder per video, named "<LANG>__<label>.<ext>". */
object SubStore {
    class Local(val file: File, val lang: String, val label: String)

    private fun dir(ctx: Context, videoUri: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(videoUri.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        return File(ctx.filesDir, "subs/$h").apply { mkdirs() }
    }

    fun list(ctx: Context, videoUri: String): List<Local> =
        dir(ctx, videoUri).listFiles().orEmpty()
            .filter { it.extension.lowercase() in setOf("srt", "vtt", "ass", "ssa") && it.name.contains("__") }
            .sortedByDescending { it.lastModified() }
            .map { Local(it, it.name.substringBefore("__"), it.name.substringAfter("__").substringBeforeLast('.')) }

    fun save(ctx: Context, videoUri: String, lang: String, label: String, ext: String, text: String): File {
        val safeLang = lang.replace(Regex("[^A-Za-z_]"), "").ifBlank { "XX" }
        val safeLabel = label.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().take(80).ifBlank { "subtitle" }
        val f = File(dir(ctx, videoUri), "${safeLang}__$safeLabel.$ext")
        f.writeText(text, Charsets.UTF_8)
        f.setLastModified(System.currentTimeMillis())
        return f
    }

    fun delete(f: File) { f.delete() }
    /** Keeps downloaded subtitles attached to a video whose address changed. */
    fun move(ctx: Context, oldUri: String, newUri: String) {
        val from = dir(ctx, oldUri)
        val to = dir(ctx, newUri)
        if (from == to) return
        from.listFiles().orEmpty().forEach { it.renameTo(File(to, it.name)) }
        from.delete()
    }
}

/** Shifts every cue time in an .srt/.vtt file (positive = later). */
object SubShift {
    private val TS = Regex("""(?:(\d{1,2}):)?(\d{2}):(\d{2})([,.])(\d{3})""")

    fun supports(f: File) = f.extension.lowercase() in setOf("srt", "vtt")

    fun apply(f: File, deltaMs: Long) {
        val out = f.readText(Charsets.UTF_8).lineSequence().joinToString("\n") { line ->
            if ("-->" !in line) line else TS.replace(line) { m ->
                val h = m.groupValues[1].ifBlank { "0" }.toLong()
                val total = maxOf(0L, ((h * 60 + m.groupValues[2].toLong()) * 60 + m.groupValues[3].toLong()) * 1000 + m.groupValues[5].toLong() + deltaMs)
                "%02d:%02d:%02d%s%03d".format(total / 3_600_000, (total / 60_000) % 60, (total / 1000) % 60, m.groupValues[4], total % 1000)
            }
        }
        f.writeText(out, Charsets.UTF_8)
    }
}
/** Reads cue start times and text from an .srt/.vtt file, for "sync to line". */
object SubCues {
    class Cue(val startMs: Long, val text: String)

    private val TS = Regex("""(?:(\d{1,2}):)?(\d{2}):(\d{2})[,.](\d{3})""")
    private val TAGS = Regex("<[^>]+>")

    fun parse(f: File): List<Cue> {
        val lines = f.readText(Charsets.UTF_8).lines()
        val out = ArrayList<Cue>()
        var i = 0
        while (i < lines.size) {
            val m = if ("-->" in lines[i]) TS.find(lines[i]) else null
            if (m == null) { i++; continue }
            val ms = ((m.groupValues[1].ifBlank { "0" }.toLong() * 60 + m.groupValues[2].toLong()) * 60 + m.groupValues[3].toLong()) * 1000 +
                    m.groupValues[4].toLong()
            val sb = StringBuilder()
            i++
            while (i < lines.size && lines[i].isNotBlank()) { sb.append(lines[i].replace(TAGS, "").trim()).append(' '); i++ }
            val t = sb.toString().trim()
            if (t.isNotEmpty()) out += Cue(ms, t)
        }
        return out
    }
}
/** Orders download results so the one closest to your video's file name comes first. */
object SubRank {
    private val NOISE = setOf("the", "and", "mkv", "mp4", "avi", "srt")
    private val SOURCES = setOf("bluray", "bdrip", "brrip", "remux", "webdl", "webrip", "web", "hdrip", "hdtv", "dvdrip", "amzn", "nf", "dsnp")
    private val RES = setOf("2160p", "4k", "1080p", "720p", "480p")
    private val CODECS = setOf("x264", "x265", "h264", "h265", "hevc", "avc")

    private fun tokens(s: String): Set<String> =
        s.lowercase().replace("web-dl", "webdl").split(Regex("[^a-z0-9]+")).filter { it.length > 1 && it !in NOISE }.toSet()

    fun score(videoName: String, release: String): Int {
        val base = videoName.substringBeforeLast('.', videoName)
        val a = tokens(base)
        val b = tokens(release)
        if (a.isEmpty() || b.isEmpty()) return 0
        val common = a.intersect(b)
        var s = common.size * 100 / a.union(b).size
        s += common.count { it in SOURCES } * 20
        s += common.count { it in RES } * 15
        s += common.count { it in CODECS } * 5
        val group = base.substringAfterLast('-', "").lowercase()
        if (group.length in 2..12 && group in b) s += 30
        return s
    }
}
