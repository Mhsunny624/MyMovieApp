package com.mymovieapp.app.data

import android.content.Context
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.mymovieapp.app.ui.parseStreamRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/** Tells the Cast library to use Google's standard receiver, so any Chromecast / Cast-enabled TV works without this app on it. */
class GCastOptions : com.google.android.gms.cast.framework.OptionsProvider {
    override fun getCastOptions(c: Context): com.google.android.gms.cast.framework.CastOptions =
        com.google.android.gms.cast.framework.CastOptions.Builder()
            .setReceiverApplicationId(com.google.android.gms.cast.CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()
    override fun getAdditionalSessionProviders(c: Context): List<com.google.android.gms.cast.framework.SessionProvider>? = null
}

/**
 * Casting to a TV that does not have this app (Google Cast). It is shaped like the app-to-app cast so the same
 * remote and handoff code drives it: [play], [command] and [status] speak the same little language.
 */
object GCast {
    val devices = MutableStateFlow<List<CastTv>>(emptyList())
    private var cast: CastContext? = null
    private var router: MediaRouter? = null
    private var selector: MediaRouteSelector? = null
    private var scanning = false

    private class Last(val uri: String, val title: String, val url: String, val mime: String, val live: Boolean)
    private var last: Last? = null
    /** Subtitle size on the TV, from the app's subtitle size setting (0 = small … 3 = largest). */
    @Volatile var subSize = 1
    private val phoneSubs = mutableListOf<MediaTrack>()
    private var nextTrackId = 1000L

    private val cb = object : MediaRouter.Callback() {
        override fun onRouteAdded(r: MediaRouter, i: MediaRouter.RouteInfo) { refresh() }
        override fun onRouteRemoved(r: MediaRouter, i: MediaRouter.RouteInfo) { refresh() }
        override fun onRouteChanged(r: MediaRouter, i: MediaRouter.RouteInfo) { refresh() }
    }

    private fun refresh() {
        val r = router ?: return
        val s = selector ?: return
        devices.value = r.routes.filter { !it.isDefault && it.matchesSelector(s) }.map { CastTv(it.name, it.id, 0, "gcast") }
    }

    /** Main thread. Starts looking for Cast devices; false when this phone has no Google Play services. */
    fun startDiscovery(app: Context): Boolean {
        LanStream.init(app)
        try {
            if (cast == null) {
                @Suppress("DEPRECATION")
                cast = CastContext.getSharedInstance(app.applicationContext)
                router = MediaRouter.getInstance(app.applicationContext)
                selector = MediaRouteSelector.Builder()
                    .addControlCategory(com.google.android.gms.cast.CastMediaControlIntent.categoryForCast(com.google.android.gms.cast.CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
                    .build()
            }
            if (!scanning) {
                router!!.addCallback(selector!!, cb, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
                scanning = true
            }
            refresh()
            return true
        } catch (_: Throwable) { return false }
    }

    fun stopDiscovery() {
        try { if (scanning) router?.removeCallback(cb) } catch (_: Throwable) {}
        scanning = false
    }

    private fun session(): CastSession? = try { cast?.sessionManager?.currentCastSession?.takeIf { it.isConnected } } catch (_: Throwable) { null }
    private fun client(): RemoteMediaClient? = session()?.remoteMediaClient

    /** Connects to [tv] (starts a Cast session on it). -> (ok, message). */
    suspend fun connect(tv: CastTv): Pair<Boolean, String> = withContext(Dispatchers.Main) {
        val c = cast ?: return@withContext false to "Casting isn't available on this phone"
        val cur = session()
        if (cur != null && cur.castDevice?.friendlyName == tv.name) return@withContext true to tv.name
        val route = router?.routes?.firstOrNull { it.id == tv.host } ?: return@withContext false to "${tv.name} isn't visible any more"
        val done = CompletableDeferred<Pair<Boolean, String>>()
        val l = object : SessionManagerListener<CastSession> {
            override fun onSessionStarting(s: CastSession) {}
            override fun onSessionStarted(s: CastSession, id: String) { done.complete(true to tv.name) }
            override fun onSessionStartFailed(s: CastSession, e: Int) { done.complete(false to "Couldn't connect to ${tv.name}") }
            override fun onSessionEnding(s: CastSession) {}
            override fun onSessionEnded(s: CastSession, e: Int) {}
            override fun onSessionResuming(s: CastSession, id: String) {}
            override fun onSessionResumed(s: CastSession, was: Boolean) { done.complete(true to tv.name) }
            override fun onSessionResumeFailed(s: CastSession, e: Int) { done.complete(false to "Couldn't connect to ${tv.name}") }
            override fun onSessionSuspended(s: CastSession, r: Int) {}
        }
        c.sessionManager.addSessionManagerListener(l, CastSession::class.java)
        try {
            router!!.selectRoute(route)
            withTimeoutOrNull(20_000) { done.await() } ?: (false to "${tv.name} didn't answer")
        } finally { c.sessionManager.removeSessionManagerListener(l, CastSession::class.java) }
    }

    private suspend fun awaitOk(pr: com.google.android.gms.common.api.PendingResult<RemoteMediaClient.MediaChannelResult>): Boolean =
        withTimeoutOrNull(20_000) { suspendCancellableCoroutine<Boolean> { k -> pr.setResultCallback { r -> if (k.isActive) k.resume(r.status.isSuccess) } } } ?: false

    /** The TV's own default is white text on a solid black box; use clear text with an outline instead. */
    private fun clearStyle(): com.google.android.gms.cast.TextTrackStyle {
        val st = com.google.android.gms.cast.TextTrackStyle()
        st.setForegroundColor(0xFFFFFFFF.toInt())
        st.setBackgroundColor(0x00000000)
        st.setWindowType(com.google.android.gms.cast.TextTrackStyle.WINDOW_TYPE_NONE)
        st.setWindowColor(0x00000000)
        st.setEdgeType(com.google.android.gms.cast.TextTrackStyle.EDGE_TYPE_OUTLINE)
        st.setEdgeColor(0xFF000000.toInt())
        st.setFontGenericFamily(com.google.android.gms.cast.TextTrackStyle.FONT_FAMILY_SANS_SERIF)
        st.setFontScale(when (subSize) { 0 -> 0.8f; 1 -> 1.0f; 2 -> 1.3f; else -> 1.6f })
        return st
    }

    private fun buildInfo(l: Last): MediaInfo {
        val md = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply { putString(MediaMetadata.KEY_TITLE, l.title) }
        val b = MediaInfo.Builder(l.url)
            .setStreamType(if (l.live) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(l.mime)
            .setMetadata(md)
        if (phoneSubs.isNotEmpty()) b.setMediaTracks(phoneSubs.toList())
        b.setTextTrackStyle(clearStyle())
        return b.build()
    }

    private suspend fun load(l: Last, posMs: Long, activeTracks: LongArray): Pair<Boolean, String> {
        val rmc = client() ?: return false to "Not connected to a Cast device"
        val rq = MediaLoadRequestData.Builder().setMediaInfo(buildInfo(l)).setAutoplay(true).setCurrentTime(posMs).setActiveTrackIds(activeTracks).build()
        if (!awaitOk(rmc.load(rq))) return false to "The TV couldn't play this (maybe an unsupported format)"
        // Sent again on its own: the TV (and the phone's own caption settings) can override the style that came with the video.
        try { rmc.setTextTrackStyle(clearStyle()) } catch (_: Throwable) {}
        return true to ""
    }

    /** Sends a video to the connected Cast device. [body] is the same JSON the app-to-app cast uses (uri, title, mode, pos, live). */
    suspend fun play(body: JSONObject): Pair<Boolean, String> = withContext(Dispatchers.Main) {
        val uri = body.optString("uri"); val title = body.optString("title"); val mode = body.optString("mode")
        val live = body.optBoolean("live")
        val pos = body.optLong("pos")
        val name = if (title.isNotBlank() && title.contains('.')) title else (android.net.Uri.parse(uri).lastPathSegment ?: title)
        val url: String
        val mime: String
        if (uri.startsWith("http", true) && mode != "file") {
            url = parseStreamRequest(uri).first
            val ext = android.net.Uri.parse(url).lastPathSegment.orEmpty()
            mime = if (ext.contains('.')) LanStream.mimeFor(ext) else if (live) "video/mp2t" else "video/mp4"
        } else {
            url = LanStream.urlForMedia(uri, name) ?: return@withContext false to "This phone isn't on Wi-Fi"
            mime = LanStream.mimeFor(name)
        }
        if (last?.uri != uri) phoneSubs.clear()
        val l = Last(uri, title, url, mime, live)
        last = l
        load(l, pos, phoneSubs.lastOrNull()?.let { longArrayOf(it.id) } ?: LongArray(0))
    }

    /** What is playing on the Cast device, in the same shape as the app TV's /status; null when the device can't be reached. */
    suspend fun status(): JSONObject? = withContext(Dispatchers.Main) {
        val rmc = client() ?: return@withContext null
        val ms = rmc.mediaStatus
        val o = JSONObject()
        if (ms == null) return@withContext o.put("active", false)
        val st = ms.playerState
        val active = st == MediaStatus.PLAYER_STATE_PLAYING || st == MediaStatus.PLAYER_STATE_PAUSED ||
            st == MediaStatus.PLAYER_STATE_BUFFERING || st == MediaStatus.PLAYER_STATE_LOADING
        val len = rmc.streamDuration.takeIf { it > 0 } ?: 0L
        o.put("active", active).put("pos", rmc.approximateStreamPosition).put("len", len)
            .put("playing", st == MediaStatus.PLAYER_STATE_PLAYING || st == MediaStatus.PLAYER_STATE_BUFFERING)
            .put("title", ms.mediaInfo?.metadata?.getString(MediaMetadata.KEY_TITLE).orEmpty())
            .put("ended", st == MediaStatus.PLAYER_STATE_IDLE && ms.idleReason == MediaStatus.IDLE_REASON_FINISHED)
            .put("error", st == MediaStatus.PLAYER_STATE_IDLE && ms.idleReason == MediaStatus.IDLE_REASON_ERROR)
            .put("speed", ms.playbackRate)
        val act = ms.activeTrackIds?.toSet().orEmpty()
        val audio = JSONArray(); val spu = JSONArray()
        ms.mediaInfo?.mediaTracks?.forEach { t ->
            val j = JSONObject().put("id", t.id.toInt()).put("name", t.name ?: t.language ?: "Track ${t.id}").put("sel", t.id in act)
            if (t.type == MediaTrack.TYPE_AUDIO) audio.put(j) else if (t.type == MediaTrack.TYPE_TEXT) spu.put(j)
        }
        o.put("audio", audio).put("spu", spu)
    }

    /** Remote-control commands; the same action names as the app-to-app cast. */
    suspend fun command(action: String): Pair<Boolean, String> = withContext(Dispatchers.Main) {
        val s = session() ?: return@withContext false to "Not connected"
        val rmc = s.remoteMediaClient ?: return@withContext false to "Not connected"
        try {
            when {
                action == "playpause" -> rmc.togglePlayback()
                action == "back10" || action == "fwd10" -> {
                    val d = if (action == "back10") -10_000L else 10_000L
                    rmc.seek(MediaSeekOptions.Builder().setPosition((rmc.approximateStreamPosition + d).coerceAtLeast(0)).build())
                }
                action.startsWith("seek:") -> rmc.seek(MediaSeekOptions.Builder().setPosition(action.removePrefix("seek:").toLong()).build())
                action == "volup" -> s.volume = (s.volume + 0.05).coerceIn(0.0, 1.0)
                action == "voldown" -> s.volume = (s.volume - 0.05).coerceIn(0.0, 1.0)
                action == "stop" || action == "release" -> {
                    try { rmc.stop() } catch (_: Throwable) {}
                    cast?.sessionManager?.endCurrentSession(true)
                }
                action.startsWith("speed:") -> rmc.setPlaybackRate(action.removePrefix("speed:").toDouble())
                action.startsWith("audio:") || action.startsWith("spu:") -> {
                    val isAudio = action.startsWith("audio:")
                    val id = action.substringAfter(':').toLong()
                    val tracks = rmc.mediaInfo?.mediaTracks.orEmpty()
                    val sameType = tracks.filter { (it.type == MediaTrack.TYPE_AUDIO) == isAudio && (isAudio || it.type == MediaTrack.TYPE_TEXT) }.map { it.id }.toSet()
                    val keep = (rmc.mediaStatus?.activeTrackIds?.toList().orEmpty()).filter { it !in sameType }
                    rmc.setActiveMediaTracks((keep + (if (id >= 0) listOf(id) else emptyList())).toLongArray())
                }
                else -> return@withContext false to "Not available on this TV"
            }
            true to ""
        } catch (e: Throwable) { false to "The TV didn't take that" }
    }

    /** Subtitles from this phone for the video now on the TV: (.srt / .vtt / .ass text) -> WebVTT, served over Wi-Fi, TV reloads at the same spot. */
    suspend fun addSubtitle(raw: ByteArray, fileName: String): Pair<Boolean, String> = withContext(Dispatchers.Main) {
        val l = last ?: return@withContext false to "Nothing is playing on the TV"
        val rmc = client() ?: return@withContext false to "Not connected to a Cast device"
        val vtt = toVtt(SubText.decode(raw, ""), fileName.substringAfterLast('.', "srt").lowercase())
            ?: return@withContext false to "Couldn't read that subtitle file"
        val url = LanStream.urlForText(vtt, "text/vtt", "sub.vtt") ?: return@withContext false to "This phone isn't on Wi-Fi"
        val id = nextTrackId++
        phoneSubs.add(MediaTrack.Builder(id, MediaTrack.TYPE_TEXT).setContentId(url).setContentType("text/vtt")
            .setSubtype(MediaTrack.SUBTYPE_SUBTITLES).setName(fileName.substringBeforeLast('.').take(40).ifBlank { "Phone subtitle" }).setLanguage("und").build())
        val pos = rmc.approximateStreamPosition
        val r = load(l, pos, longArrayOf(id))
        if (r.first) true to "Subtitles added" else r
    }

    private fun toVtt(text: String, ext: String): String? {
        val t = text.replace("\r\n", "\n").replace('\r', '\n').trimStart('﻿')
        if (t.startsWith("WEBVTT")) return t
        if (ext == "ass" || ext == "ssa") {
            var ts = -1; var te = -1; var tx = -1
            val sb = StringBuilder("WEBVTT\n\n")
            var n = 0
            for (line in t.lines()) {
                if (line.startsWith("Format:")) {
                    val f = line.removePrefix("Format:").split(',').map { it.trim().lowercase() }
                    ts = f.indexOf("start"); te = f.indexOf("end"); tx = f.indexOf("text")
                } else if (line.startsWith("Dialogue:") && ts >= 0 && te >= 0 && tx >= 0) {
                    val p = line.removePrefix("Dialogue:").split(',', limit = tx + 1)
                    if (p.size <= tx) continue
                    fun fix(s: String): String { val x = s.trim(); val h = x.substringBefore(':').padStart(2, '0'); return "$h:${x.substringAfter(':')}0" }
                    val body = p[tx].replace(Regex("\\{[^}]*\\}"), "").replace("\\N", "\n").replace("\\n", "\n").trim()
                    if (body.isEmpty()) continue
                    sb.append(++n).append('\n').append(fix(p[ts])).append(" --> ").append(fix(p[te])).append('\n').append(body).append("\n\n")
                }
            }
            return if (n > 0) sb.toString() else null
        }
        if (!Regex("\\d+:\\d+:\\d+[,.]\\d+\\s*-->").containsMatchIn(t)) return null
        return "WEBVTT\n\n" + Regex("(\\d+:\\d+:\\d+),(\\d+)").replace(t, "$1.$2")
    }

    fun isConnected(): Boolean = session() != null
}
