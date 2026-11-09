package com.mymovieapp.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("library", Context.MODE_PRIVATE)

    val tmdbKey = MutableStateFlow(sp.getString("tmdb", "") ?: "")
    val autoplayNext = MutableStateFlow(sp.getBoolean("autoplay", true))
    val subSize = MutableStateFlow(sp.getInt("subSize", 1))      // 0 small, 1 medium, 2 large
    val subColor = MutableStateFlow(sp.getInt("subColor", 0))    // 0 white, 1 yellow
    val subBg = MutableStateFlow(sp.getBoolean("subBg", false))
    val subPos = MutableStateFlow(sp.getInt("subPos", 0))           // 0 low, 1 medium, 2 high
    val subLang = MutableStateFlow(sp.getString("subLang", "EN") ?: "EN")
    val livePlayer = MutableStateFlow(sp.getInt("livePlayer", 0))     // 0 = auto, 1 = ExoPlayer, 2 = VLC
    // Crash guard: the channel VLC was playing when the app last died is remembered and then played with ExoPlayer.
    val vlcCrashed = MutableStateFlow(run {
        val set = (sp.getStringSet("vlcCrashed", emptySet()).orEmpty()).mapNotNull { it.toLongOrNull() }.toMutableSet()
        val armed = sp.getLong("vlcArmed", -1L)
        if (armed >= 0) { set += armed; sp.edit().putStringSet("vlcCrashed", set.map { it.toString() }.toSet()).remove("vlcArmed").commit() }
        set.toSet()
    })
    fun vlcArm(id: Long) { sp.edit().putLong("vlcArmed", id).commit() }
    fun vlcDisarm(id: Long) { if (sp.getLong("vlcArmed", -1L) == id) sp.edit().remove("vlcArmed").commit() }
    fun clearVlcCrashed() { vlcCrashed.value = emptySet(); sp.edit().remove("vlcCrashed").apply() }
    fun setLivePlayer(v: Int) { livePlayer.value = v; sp.edit().putInt("livePlayer", v).apply() }
    val videoResize = MutableStateFlow(sp.getInt("videoResize", 0))   // 0 = fit, 3 = stretch, 4 = zoom
    val shuffle = MutableStateFlow(sp.getBoolean("shuffle", false))   // player: false = continuous (in order), true = shuffle
    val autoThumbs = MutableStateFlow(sp.getBoolean("autoThumbs", true))   // make video stills in the background after a scan
    val hwDecoder = MutableStateFlow(sp.getBoolean("hwDecoder", false))   // thumbnails: use the device's own video chip
    val subSources = MutableStateFlow(loadSources())

    private fun loadSources(): List<SubtitleSource> {
        val raw = sp.getString("subsources", null)
        // First run after the update: carry over the SubDL key you already entered.
        if (raw == null) return listOf(SubtitleSource("subdl", "subdl", "SubDL", key = sp.getString("subkey_subdl", "") ?: ""))
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                SubtitleSource(
                    o.getString("id"), o.getString("type"), o.getString("label"), o.optString("key"),
                    o.optString("user"), o.optString("pass"), o.optString("url"), o.optBoolean("enabled", true)
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveSources(list: List<SubtitleSource>) {
        subSources.value = list
        val a = JSONArray()
        list.forEach { s ->
            a.put(JSONObject().put("id", s.id).put("type", s.type).put("label", s.label).put("key", s.key)
                .put("user", s.user).put("pass", s.pass).put("url", s.url).put("enabled", s.enabled))
        }
        sp.edit().putString("subsources", a.toString()).apply()
    }

    fun upsertSource(s: SubtitleSource) {
        val cur = subSources.value
        saveSources(if (cur.any { it.id == s.id }) cur.map { if (it.id == s.id) s else it } else cur + s)
    }

    fun removeSource(id: String) = saveSources(subSources.value.filter { it.id != id })

    fun setTmdbKey(v: String) { tmdbKey.value = v; sp.edit().putString("tmdb", v.trim()).apply() }
    fun setHwDecoder(v: Boolean) { hwDecoder.value = v; sp.edit().putBoolean("hwDecoder", v).apply() }
    fun setAutoThumbs(v: Boolean) { autoThumbs.value = v; sp.edit().putBoolean("autoThumbs", v).apply() }
    fun setShuffle(v: Boolean) { shuffle.value = v; sp.edit().putBoolean("shuffle", v).apply() }
    fun setAutoplayNext(v: Boolean) { autoplayNext.value = v; sp.edit().putBoolean("autoplay", v).apply() }
    fun setSubSize(v: Int) { subSize.value = v; sp.edit().putInt("subSize", v).apply() }
    fun setSubColor(v: Int) { subColor.value = v; sp.edit().putInt("subColor", v).apply() }
    fun setSubBg(v: Boolean) { subBg.value = v; sp.edit().putBoolean("subBg", v).apply() }
    fun setSubPos(v: Int) { subPos.value = v; sp.edit().putInt("subPos", v).apply() }
    fun setSubLang(v: String) { subLang.value = v; sp.edit().putString("subLang", v).apply() }
    val liveMode get() = sp.getInt("liveMode", 0)
    val liveGroup get() = sp.getString("liveGroup", "") ?: ""
    fun setLive(mode: Int, group: String) { sp.edit().putInt("liveMode", mode).putString("liveGroup", group).apply() }
    fun setVideoResize(v: Int) { videoResize.value = v; sp.edit().putInt("videoResize", v).apply() }
}