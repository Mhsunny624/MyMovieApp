package com.mymovieapp.app.data

/**
 * Many IPTV links need a specific User-Agent/Referer. Playlists carry these either as a
 * "|User-Agent=...&Referer=..." suffix on the URL or as #EXTVLCOPT lines (folded into that suffix when imported).
 * The player's HTTP source reads [props]/[userAgent] each time it opens a connection.
 */
object StreamHeaders {
    const val DEFAULT_UA = "VLC/3.0.20 LibVLC/3.0.20"

    @Volatile var userAgent: String = DEFAULT_UA
    @Volatile var props: Map<String, String> = emptyMap()

    /** "http://host/x.m3u8|User-Agent=Foo&Referer=Bar" -> (clean url, headers) */
    fun split(raw: String): Pair<String, Map<String, String>> {
        val i = raw.indexOf('|')
        if (i < 0) return raw to emptyMap()
        val headers = raw.substring(i + 1).split('&').mapNotNull { p ->
            val k = p.substringBefore('=', "").trim()
            if (k.isEmpty()) null else k to p.substringAfter('=', "").trim()
        }.toMap()
        return raw.substring(0, i) to headers
    }

    fun uaOf(headers: Map<String, String>): String =
        headers.entries.firstOrNull { it.key.equals("user-agent", ignoreCase = true) }?.value?.ifBlank { null } ?: DEFAULT_UA

    fun apply(headers: Map<String, String>) {
        userAgent = uaOf(headers)
        props = headers.filterKeys { !it.equals("user-agent", ignoreCase = true) }
    }
}
