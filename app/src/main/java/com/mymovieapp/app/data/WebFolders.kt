package com.mymovieapp.app.data

import android.content.Context
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class WebServer(val id: String, val name: String, val url: String, val user: String, val pass: String)

data class WebEntry(val name: String, val url: String, val dir: Boolean) {
    private val ext get() = name.substringAfterLast('.', "").lowercase()
    val isVideo get() = !dir && ext in Scanner.VIDEO_EXT
    val isSub get() = !dir && ext in Scanner.SUB_EXT
}

/** Saved web folders. Passwords are encrypted. */
object WebStore {
    private const val FILE = "web_folders"

    fun load(ctx: Context): List<WebServer> {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("list", null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                WebServer(
                    o.getString("id"), o.getString("name"), o.getString("url"), o.optString("user"),
                    runCatching { Secrets.decrypt(o.optString("pass")) }.getOrDefault("")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save(ctx: Context, list: List<WebServer>) {
        val a = JSONArray()
        list.forEach { s ->
            a.put(
                JSONObject().put("id", s.id).put("name", s.name).put("url", s.url)
                    .put("user", s.user).put("pass", Secrets.encrypt(s.pass))
            )
        }
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("list", a.toString()).apply()
    }

    fun add(ctx: Context, s: WebServer): List<WebServer> = (load(ctx) + s).also { save(ctx, it) }
    fun remove(ctx: Context, id: String): List<WebServer> = load(ctx).filter { it.id != id }.also { save(ctx, it) }
}

/** Reads an "Index of …" style page and returns its sub-folders and files. */
object WebIndex {
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()
    }
    private val hrefRe = Regex("""href\s*=\s*["']([^"'#]+)["']""", RegexOption.IGNORE_CASE)

    private fun unescape(s: String): String = s.replace("&amp;", "&").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&#x27;", "'")

    fun list(url: String, user: String, pass: String): List<WebEntry> {
        val rb = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android) MyMovieApp")
        if (user.isNotBlank()) rb.header("Authorization", Credentials.basic(user, pass))
        client.newCall(rb.build()).execute().use { r ->
            if (r.code == 401 || r.code == 403) {
                throw IOException(if (user.isBlank()) "This folder needs a login." else "Wrong username or password.")
            }
            if (!r.isSuccessful) throw IOException("The server answered HTTP ${r.code}.")
            val html = r.body?.string().orEmpty()
            // A folder typed without its last "/" would make every sub-folder resolve to the parent and get dropped.
            val base0 = r.request.url
            val base = if (base0.encodedPath.endsWith("/")) base0 else (base0.newBuilder().addPathSegment("").build())
            val baseStr = base.toString()
            val seen = HashSet<String>()
            val out = ArrayList<WebEntry>()
            for (m in hrefRe.findAll(html)) {
                val link = base.resolve(unescape(m.groupValues[1].trim())) ?: continue
                if (link.query != null) continue                    // sort links like ?C=N;O=D
                val s = link.toString()
                if (!s.startsWith(baseStr) || s == baseStr || !seen.add(s)) continue   // parent / outside links
                val name = link.pathSegments.lastOrNull { it.isNotEmpty() } ?: continue
                // Some servers list folders without a trailing "/": a name with no dot is treated as a folder.
                val isDir = s.endsWith("/") || !name.contains('.')
                out += WebEntry(name, if (isDir && !s.endsWith("/")) "$s/" else s, isDir)
            }
            return out.filter { it.dir || it.isVideo || it.isSub }
                .sortedWith(compareBy({ !it.dir }, { it.name.lowercase() }))
        }
    }
}