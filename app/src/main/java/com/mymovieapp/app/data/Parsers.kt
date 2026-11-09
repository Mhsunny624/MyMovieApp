package com.mymovieapp.app.data

/** Turns messy file/folder names into a clean title, year and season/episode. */
object NameParser {
    data class Parsed(val kind: String, val name: String, val year: Int?, val season: Int?, val episode: Int?) {
        val key: String
            get() = if (kind == "series") "series:${name.lowercase()}" else "film:${name.lowercase()}:${year ?: 0}"
    }

    private val SE = Regex("""(?i)\bs(\d{1,2})[\s._-]*e(\d{1,3})""")
    private val SE_X = Regex("""(?i)\b(\d{1,2})x(\d{2,3})\b""")
    private val EP = Regex("""(?i)\b(?:episode|ep|e)[\s._-]*(\d{1,3})\b""")
    private val NUM = Regex("""\d{1,3}""")
    private val YEAR = Regex("""\b(?:19|20)\d{2}\b""")
    private val SEASON_DIR = Regex("""(?i)^(?:season|series|s)[\s._-]*(\d{1,2})\b.*""")
    private val JUNK = Regex(
        """(?i)\b(?:480p|576p|720p|1080p|2160p|4k|uhd|bluray|blu-ray|brrip|bdrip|web-?dl|webrip|hdrip|dvdrip|hdtv|x264|x265|h264|h265|hevc|avc|aac|ac3|dts|remux|proper|repack|extended|unrated|multi|dual)\b"""
    )
    private val BRACKETS = Regex("""[\[\(\{][^\]\)\}]*[\]\)\}]""")
    private val SPACES = Regex("""\s+""")

    private fun tidy(s: String): String {
        var t = s.replace('.', ' ').replace('_', ' ')
        t = BRACKETS.replace(t, " ")
        JUNK.find(t)?.let { t = t.substring(0, it.range.first) }
        return SPACES.replace(t, " ").trim(' ', '-', '(', '[', '{', ',', ':')
    }

    private fun lastYear(s: String): MatchResult? = YEAR.findAll(s).filter { it.range.first > 0 }.lastOrNull()

    /** [folders] = parent folder names, nearest first. */
    fun parse(fileName: String, folders: List<String>): Parsed {
        val base = fileName.substringBeforeLast('.').replace('_', ' ')
        val seasonDir = folders.firstOrNull { SEASON_DIR.matches(it) }
        val showFolder = folders.firstOrNull { !SEASON_DIR.matches(it) }
        val showName = showFolder?.let { tidy(it) }?.ifBlank { null }

        val se = SE.find(base) ?: SE_X.find(base)
        if (se != null) {
            val name = tidy(base.substring(0, se.range.first)).ifBlank { showName ?: "Unknown" }
            return Parsed("series", name, null, se.groupValues[1].toInt(), se.groupValues[2].toInt())
        }
        if (seasonDir != null) {
            val season = SEASON_DIR.matchEntire(seasonDir)!!.groupValues[1].toInt()
            val ep = EP.find(base)?.groupValues?.get(1)?.toInt() ?: NUM.find(base)?.value?.toInt()
            return Parsed("series", showName ?: "Unknown", null, season, ep)
        }

        lastYear(base)?.let { y ->
            val name = tidy(base.substring(0, y.range.first)).ifBlank { tidy(base) }
            return Parsed("film", name, y.value.toInt(), null, null)
        }
        folders.firstOrNull()?.let { f ->
            lastYear(f)?.let { y ->
                val n = tidy(f.substring(0, y.range.first))
                if (n.isNotBlank()) return Parsed("film", n, y.value.toInt(), null, null)
            }
        }
        return Parsed("film", tidy(base).ifBlank { base }, null, null, null)
    }
}

object M3u {
    // Built once: a big playlist has tens of thousands of lines and building these per line floods memory.
    private val reGroup = Regex("group-title=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val reLogo = Regex("tvg-logo=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val reId = Regex("tvg-id=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private fun attr(line: String, re: Regex): String? = re.find(line)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    fun parse(text: String): List<Channel> = parseLines(text.removePrefix("\uFEFF").lineSequence())

    /** Reads line by line, so a very large playlist never has to sit in memory as one huge string. */
    fun parseLines(lines: Sequence<String>): List<Channel> {
        val out = ArrayList<Channel>()
        var name: String? = null
        var grp = "Undefined"
        var logo: String? = null
        var tvgId: String? = null
        val opts = LinkedHashMap<String, String>()
        val now = System.currentTimeMillis()
        var first = true
        for (raw0 in lines) {
            val raw = if (first) raw0.removePrefix("\uFEFF") else raw0
            first = false
            val line = raw.trim()
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                name = line.substringAfterLast(',', "").trim().ifBlank { null }
                grp = (attr(line, reGroup) ?: "Undefined").substringBefore(';').trim().ifBlank { "Undefined" }
                logo = attr(line, reLogo)
                tvgId = attr(line, reId)
            } else if (line.startsWith("#EXTVLCOPT:", ignoreCase = true)) {
                val kv = line.substringAfter(':')
                val v = kv.substringAfter('=', "").trim()
                when (kv.substringBefore('=').trim().lowercase()) {
                    "http-user-agent" -> opts["User-Agent"] = v
                    "http-referrer", "http-referer" -> opts["Referer"] = v
                }
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                val fallback = line.substringBefore('|').substringAfterLast('/').substringBefore('?').ifBlank { line }
                var url = line
                if (opts.isNotEmpty() && '|' !in url) url += "|" + opts.entries.joinToString("&") { "${it.key}=${it.value}" }
                out += Channel(name = name ?: fallback, url = url, grp = grp, logo = logo, addedAt = now, number = out.size + 1, tvgId = tvgId)
                name = null; grp = "Undefined"; logo = null; tvgId = null; opts.clear()
            }
        }
        return out
    }
}
