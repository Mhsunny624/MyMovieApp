package com.mymovieapp.app.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.navigation.NavController
import com.mymovieapp.app.LibraryViewModel
import com.mymovieapp.app.PlayRequest
import com.mymovieapp.app.data.CastState
import com.mymovieapp.app.data.CastSender
import com.mymovieapp.app.data.GCast
import com.mymovieapp.app.data.CastSession
import com.mymovieapp.app.data.ChannelBuffer
import com.mymovieapp.app.data.Ftp
import com.mymovieapp.app.data.LocalStream
import com.mymovieapp.app.data.FtpStore
import com.mymovieapp.app.data.Smb
import com.mymovieapp.app.data.SubStore
import com.mymovieapp.app.data.TrackPrefs
import com.mymovieapp.app.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import kotlin.math.abs
import kotlin.math.roundToInt

private var vlcInitError: String? = null

private class VlcTrack(val id: Int, val name: String, val selected: Boolean)

private fun castTracks(a: org.json.JSONArray?): List<VlcTrack> =
    if (a == null) emptyList() else (0 until a.length()).map { val o = a.getJSONObject(it); VlcTrack(o.getInt("id"), o.optString("name"), o.optBoolean("sel")) }

private fun tracksJson(l: List<VlcTrack>): org.json.JSONArray =
    org.json.JSONArray().also { a -> l.forEach { a.put(org.json.JSONObject().put("id", it.id).put("name", it.name).put("sel", it.selected)) } }

/** VLC takes the subtitle look as start-up options (the same choices as the ExoPlayer screen). */
private fun vlcSubtitleArgs(p: Prefs): List<String> {
    val size = when (p.subSize.value) { 0 -> 22; 1 -> 16; 2 -> 12; else -> 9 }          // VLC: video height / this number
    val color = when (p.subColor.value) { 1 -> 0xFFE066; 2 -> 0x80DEEA; else -> 0xFFFFFF }
    val margin = when (p.subPos.value) { 0 -> 0; 1 -> 60; else -> 140 }
    val out = arrayListOf("--freetype-rel-fontsize=$size", "--freetype-color=$color", "--sub-margin=$margin")
    if (p.subBg.value) { out += "--freetype-background-opacity=160"; out += "--freetype-background-color=0" }
    else out += "--freetype-background-opacity=0"
    return out
}

private fun speedLabel(s: Float): String = if (s % 1f == 0f) "${s.toInt()}×" else "${s}×"

private fun vlcTracks(list: Array<MediaPlayer.TrackDescription>?, current: Int): List<VlcTrack> =
    (list?.toList() ?: emptyList()).filter { it.id >= 0 }
        .map { VlcTrack(it.id, it.name ?: "Track ${it.id}", it.id == current) }

/** The address VLC opens. SMB / FTP get the saved login put into the address. */
private fun vlcSource(clean: String): Uri {
    val src = Uri.parse(clean)
    return when (src.scheme?.lowercase()) {
        "smb" -> {
            val c = Smb.credsFor(src.host.orEmpty())
            if (c == null || c.user.isBlank()) src
            else {
                val user = if (c.domain.isNotBlank()) "${c.domain};${c.user}" else c.user
                src.buildUpon().encodedAuthority("${Uri.encode(user, ";")}:${Uri.encode(c.pass)}@${src.host}").build()
            }
        }
        "ftp" -> {
            val (h, p, _) = Ftp.split(src)
            val c = FtpStore.get(h, p)
            if (c == null || c.user.isBlank()) src
            else src.buildUpon().encodedAuthority("${Uri.encode(c.user)}:${Uri.encode(c.pass)}@$h:$p").build()
        }
        else -> src
    }
}

/** Playback with LibVLC (the engine inside the VLC app). Same controls as the ExoPlayer screen. */
@Composable
fun VlcPlayerScreen(vm: LibraryViewModel, nav: NavController, type: String, id: Long, onFail: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val tv = LocalIsTv.current
    val fh = if (tv) Modifier.focusHighlight(CircleShape) else Modifier
    val scope = rememberCoroutineScope()
    val act = ctx as? Activity
    val onFailNow by rememberUpdatedState(onFail)
    DisposableEffect(type, id) { onDispose { if (type == "channel") vm.prefs.vlcDisarm(id) } }

    val vlc = remember {
        try {
            val base = arrayListOf("--network-caching=3000", "--file-caching=2000", "--http-reconnect")
            try { LibVLC(ctx, ArrayList(base + vlcSubtitleArgs(vm.prefs))) }
            catch (_: Throwable) { LibVLC(ctx, base) }      // if this VLC refuses a style option, still play
        } catch (t: Throwable) { vlcInitError = t.toString(); null }
    }
    if (vlc == null) {
        LaunchedEffect(Unit) {
            Toast.makeText(ctx, "VLC couldn't start: $vlcInitError", Toast.LENGTH_LONG).show()
            onFailNow()
        }
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    val mp = remember(vlc) { MediaPlayer(vlc) }

    var req by remember { mutableStateOf<PlayRequest?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var controls by remember { mutableStateOf(false) }
    var lastBump by remember { mutableLongStateOf(0L) }
    var locals by remember { mutableStateOf<List<SubStore.Local>>(emptyList()) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var vlcStatus by remember { mutableStateOf("Opening…") }
    var posMs by remember { mutableLongStateOf(0L) }
    var lenMs by remember { mutableLongStateOf(0L) }
    // Live channels: some streams (HLS with a rewind window) allow seeking back; most do not.
    var seekable by remember(id) { mutableStateOf(false) }
    val showBar = type != "channel" || (seekable && lenMs > 30_000L)
    var dragFrac by remember { mutableStateOf<Float?>(null) }
    var audioList by remember { mutableStateOf<List<VlcTrack>>(emptyList()) }
    var spuList by remember { mutableStateOf<List<VlcTrack>>(emptyList()) }
    var videoList by remember { mutableStateOf<List<VlcTrack>>(emptyList()) }
    var qualityMenu by remember { mutableStateOf(false) }
    var maxHeight by remember { mutableIntStateOf(0) }       // 0 = Auto; otherwise the tallest picture a stream may use
    var bufferSec by remember(id) { mutableIntStateOf(if (type == "channel") ChannelBuffer.get(ctx, id) else 0) }   // this channel's own extra buffer
    var bufferMenu by remember { mutableStateOf(false) }
    var audioMenu by remember { mutableStateOf(false) }
    var subMenu by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    var showSubs by remember { mutableStateOf(false) }
    var styleDirty by remember { mutableStateOf(false) }
    var wasPlayingForSubs by remember { mutableStateOf(false) }
    var spuDelayUs by remember { mutableLongStateOf(0L) }
    val applied = remember { booleanArrayOf(false, false) }     // saved audio / subtitle choice already applied for this file
    var attempt by remember { mutableIntStateOf(0) }
    var restartTick by remember { mutableIntStateOf(0) }
    var speed by remember { mutableFloatStateOf(1f) }
    var shuffle by remember { mutableStateOf(vm.prefs.shuffle.value) }
    var resizeMode by remember { mutableIntStateOf(vm.prefs.videoResize.value) }
    var swipeLabel by remember { mutableStateOf<String?>(null) }
    var castFor by remember { mutableStateOf<com.mymovieapp.app.LibraryViewModel.CastItem?>(null) }
    // Handoff: the video plays on the TV and this screen is its remote. The session outlives this screen so next / previous keep casting.
    var castTv by remember { mutableStateOf(if (tv) null else CastSession.tv) }
    var castAudio by remember { mutableStateOf<List<VlcTrack>>(emptyList()) }
    var castSpu by remember { mutableStateOf<List<VlcTrack>>(emptyList()) }
    var resumeAt by remember { mutableLongStateOf(-1L) }
    // TV remote number keys: type a channel number (its place in the list being browsed), then wait or press OK.
    var numBuf by remember { mutableStateOf("") }
    val numBufNow by rememberUpdatedState(numBuf)
    var renameOpen by remember { mutableStateOf(false) }
    var deleteAsk by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var renameBusy by remember { mutableStateOf(false) }
    var renameError by remember { mutableStateOf<String?>(null) }
    var resumeAfterRename by remember { mutableStateOf(false) }
    var extAsk by remember { mutableStateOf(false) }

    val autoplay by vm.prefs.autoplayNext.collectAsStateWithLifecycle()
    val reqNow by rememberUpdatedState(req)
    val autoplayNow by rememberUpdatedState(autoplay)
    val controlsNow by rememberUpdatedState(controls)
    val attemptNow by rememberUpdatedState(attempt)
    val speedNow by rememberUpdatedState(speed)
    val shuffleNow by rememberUpdatedState(shuffle)
    val resizeNow by rememberUpdatedState(resizeMode)
    val posNow by rememberUpdatedState(posMs)
    val lenNow by rememberUpdatedState(lenMs)
    val pfd = remember { arrayOfNulls<ParcelFileDescriptor>(1) }
    val audioMgr = remember { ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager }
    val favIds by vm.favoriteIds.collectAsStateWithLifecycle()
    val isFav = type == "channel" && id in favIds
    val playFocus = remember { FocusRequester() }
    val bump: () -> Unit = { lastBump = System.currentTimeMillis() }

    fun applyScale() {
        try {
            mp.setVideoScale(
                when (resizeNow) {
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
                    AspectRatioFrameLayout.RESIZE_MODE_FILL -> MediaPlayer.ScaleType.SURFACE_FILL
                    else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
                }
            )
        } catch (_: Throwable) {}
    }
    fun normTrack(n: String): String {
        val br = Regex("\\[(.*?)]").find(n)?.groupValues?.get(1)
        return (br ?: n.replace(Regex("^Track \\d+\\s*-?\\s*"), "")).trim().lowercase()
    }
    /** Puts back the audio / subtitle language chosen earlier for this series or folder. */
    fun applySaved() {
        val g = reqNow?.groupKey ?: return
        if (!applied[0] && audioList.size > 1) {
            TrackPrefs.get(ctx, g, "a")?.let { want ->
                audioList.firstOrNull { normTrack(it.name) == want }?.let { if (!it.selected) mp.setAudioTrack(it.id) }
            }
            applied[0] = true
        }
        if (!applied[1]) {
            val want = TrackPrefs.get(ctx, g, "s")
            if (want == null) applied[1] = true
            else if (want == "off") { if (spuList.isNotEmpty()) { mp.setSpuTrack(-1); applied[1] = true } }
            else spuList.firstOrNull { normTrack(it.name) == want }?.let { mp.setSpuTrack(it.id); applied[1] = true }
        }
    }
    fun refreshTracks() {
        try {
            audioList = vlcTracks(mp.audioTracks, mp.audioTrack)
            spuList = vlcTracks(mp.spuTracks, mp.spuTrack)
            videoList = vlcTracks(mp.videoTracks, mp.videoTrack)
            applySaved()
        } catch (_: Throwable) {}
    }
    val pickPhoneSub = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            val name = ctx.contentResolver.query(u, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME).let { i -> if (i >= 0) c.getString(i) else null } else null } ?: "subtitle.srt"
            val bytes = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openInputStream(u)?.use { it.readBytes() } }.getOrNull() }
            val tvNow = castTv
            val r = if (bytes == null) "Couldn't read that file" else if (tvNow == null) "Not casting" else CastSender.sendSubtitle(tvNow, CastSession.pin, bytes, name).second.ifBlank { "Subtitles added" }
            Toast.makeText(ctx, r, Toast.LENGTH_SHORT).show()
        }
    }
    fun castCmd(a: String) {
        val t = castTv ?: return
        val p = CastSession.pin
        scope.launch { CastSender.post(t, p, "/cmd", org.json.JSONObject().put("action", a)) }
    }
    /** The TV is gone or finished: take the video back here (from where the TV got to), or just stop casting. */
    fun endCast(msg: String?, resume: Boolean) {
        val last = posNow
        CastSession.clear(); castTv = null
        if (msg != null) Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        if (resume) { resumeAt = if (type == "channel") 0L else last; attempt = 0; restartTick++ }
    }
    fun seekTo(ms: Long) {
        val hi = if (lenNow > 0) lenNow else Long.MAX_VALUE
        val t = ms.coerceIn(0L, hi)
        if (castTv != null) castCmd("seek:$t") else mp.setTime(t)
        posMs = t
    }
    // TV: lets the phone remote show a seek bar and seek
    DisposableEffect(tv) {
        // When one player replaces another, the old screen is disposed after the new one has registered:
        // only clear what is still ours, or the phone loses the seek bar.
        val myReader: () -> Triple<Long, Long, Boolean> = { Triple(posNow, lenNow, playing) }
        val mySeeker: (Long) -> Unit = { ms -> seekTo(ms) }
        val myInfo: () -> org.json.JSONObject = {
            org.json.JSONObject().put("audio", tracksJson(audioList)).put("spu", tracksJson(spuList)).put("speed", speedNow.toDouble())
        }
        val myCmd: (String) -> Unit = { m ->
            when {
                m.startsWith("audio:") -> m.removePrefix("audio:").toIntOrNull()?.let { mp.setAudioTrack(it) }
                m.startsWith("spu:") -> m.removePrefix("spu:").toIntOrNull()?.let { mp.setSpuTrack(it) }
                m.startsWith("speed:") -> m.removePrefix("speed:").toFloatOrNull()?.let { speed = it; mp.setRate(it) }
                m.startsWith("subfile:") -> {
                    // a subtitle sent from the phone: show it now
                    applied[1] = true
                    try { mp.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(java.io.File(m.removePrefix("subfile:"))), true) } catch (_: Throwable) {}
                    scope.launch { delay(500); refreshTracks() }
                }
            }
        }
        if (tv) {
            CastState.reader = myReader
            CastState.seeker = mySeeker
            CastState.info = myInfo
            CastState.cmd = myCmd
        }
        onDispose {
            if (tv) {
                if (CastState.reader === myReader) CastState.reader = null
                if (CastState.seeker === mySeeker) CastState.seeker = null
                if (CastState.info === myInfo) CastState.info = null
                if (CastState.cmd === myCmd) CastState.cmd = null
            }
        }
    }
    LaunchedEffect(req) { if (tv) CastState.title = req?.title.orEmpty() }
    // Leave / return: the video pauses when the app goes away and stays paused; pressing play re-checks the lock and the picture / sound.
    var away by remember { mutableStateOf(false) }
    var audioChanged by remember { mutableStateOf(false) }
    var closedByLock by remember { mutableStateOf(false) }
    var layoutRef by remember { mutableStateOf<VLCVideoLayout?>(null) }
    val lockClose: () -> Unit = {
        if (!closedByLock) {
            closedByLock = true
            try { mp.pause() } catch (_: Throwable) {}
            nav.popBackStack()
        }
    }
    fun saveNow(finished: Boolean = false) {
        if (closedByLock) return
        val r = reqNow ?: return
        val key = r.progressKey ?: return
        val dur = lenNow
        if (dur <= 0) return
        vm.saveProgress(key, if (finished) dur else posNow, dur)
    }
    val goTo: (Long) -> Unit = { nid ->
        saveNow()
        keepPlayerWindow = true
        if (castTv != null) CastSession.handover = true
        nav.navigate("player/$type/$nid") { popUpTo("player/{type}/{id}") { inclusive = true } }
    }
    fun nextTarget(): Long? {
        val r = reqNow ?: return null
        return if (shuffleNow && r.pool.isNotEmpty()) r.pool.random() else r.skipNextId
    }
    val jumpToNumber: () -> Unit = {
        val n = numBuf.toIntOrNull()
        numBuf = ""
        val target = if (n != null) reqNow?.channelIds?.getOrNull(n - 1) else null
        if (target != null && target != id) goTo(target)
        else if (n != null && target == null) { swipeLabel = "No channel $n"; scope.launch { delay(1500); swipeLabel = null } }
    }
    val jumpNow by rememberUpdatedState(jumpToNumber)
    LaunchedEffect(numBuf) {
        if (numBuf.isNotEmpty()) {
            swipeLabel = "Channel $numBuf"
            delay(1500)
            jumpNow()
        }
    }
    val hasNext = req?.skipNextId != null || (shuffle && req?.pool?.isNotEmpty() == true)
    val hasPrev = req?.skipPrevId != null
    val nextOverlay = type != "channel" && hasNext && lenMs > 120_000L && (lenMs - posMs) in 1L..30_000L
    val nextOverlayNow by rememberUpdatedState(nextOverlay)
    val togglePlay: () -> Unit = {
        if (castTv != null) { castCmd("playpause"); playing = !playing }
        else if (mp.isPlaying) mp.pause()
        else if (away) {
            scope.launch {
                if (vm.sourceLocked(type, id)) { lockClose(); return@launch }
                val pos = posNow
                val reload = audioChanged
                away = false; audioChanged = false
                if (reload) { resumeAt = pos; attempt = 0; restartTick++ }
                else {
                    try { layoutRef?.let { mp.detachViews(); mp.attachViews(it, null, true, false) } } catch (_: Throwable) {}
                    mp.play()
                    delay(3000)
                    if (!closedByLock && !away && posNow == pos) {
                        if (!vm.sourceLocked(type, id)) { resumeAt = pos; attempt = 0; restartTick++ } else lockClose()
                    }
                }
            }
        }
        else mp.play()
    }
    val doPrev: () -> Unit = {
        val prev = reqNow?.skipPrevId
        if (prev == null || (type != "channel" && posNow > 3000)) seekTo(0) else goTo(prev)
    }
    val doNext: () -> Unit = { nextTarget()?.let(goTo) }
    // Handoff: follow the TV once a second. It drives position, play state and tracks; if it vanishes, play here instead.
    LaunchedEffect(castTv) {
        val ct = castTv ?: return@LaunchedEffect
        val pinNow = CastSession.pin
        var fails = 0; var idle = 0; var seen = false
        while (true) {
            val st = CastSender.statusRaw(ct, pinNow)
            if (st == null) {
                if (++fails >= 3) { endCast("TV not reachable. Continuing here.", true); break }
            } else {
                fails = 0
                if (st.optBoolean("error")) { endCast("The TV can't play this file (format not supported by Cast). Continuing here.", true); break }
                if (st.optBoolean("active")) {
                    seen = true; idle = 0
                    if (dragFrac == null) { posMs = st.optLong("pos"); playing = st.optBoolean("playing") }
                    lenMs = st.optLong("len")
                    if (lenMs > 30_000L) seekable = true
                    buffering = false
                    castAudio = castTracks(st.optJSONArray("audio")); castSpu = castTracks(st.optJSONArray("spu"))
                    val sp = st.optDouble("speed", 1.0).toFloat()
                    if (sp != speed) speed = sp
                    if (st.optBoolean("ended") && type != "channel") {
                        saveNow(true)
                        val nx = nextTarget()
                        if (autoplayNow && nx != null) goTo(nx)
                        else { castCmd("stop"); CastSession.clear(); castTv = null; playing = false; Toast.makeText(ctx, "Finished", Toast.LENGTH_SHORT).show() }
                        break
                    }
                } else {
                    idle++
                    if (idle >= (if (seen) 4 else 12)) { endCast("The TV stopped. Continuing here.", true); break }
                }
            }
            delay(1000)
        }
    }
    val reload: () -> Unit = {
        error = null
        attempt = 0
        restartTick++
        Toast.makeText(ctx, "Reconnecting…", Toast.LENGTH_SHORT).show()
    }
    val openOther: () -> Unit = {
        val r = reqNow
        if (r != null) {
            mp.pause()
            openExternally(ctx, r.uri, r.title)
        }
    }
    val toggleResizeMode: () -> Unit = {
        val (newMode, toastText) = when (resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> Pair(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, "Zoom (Crop)")
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> Pair(AspectRatioFrameLayout.RESIZE_MODE_FILL, "Stretch (Fill Screen)")
            else -> Pair(AspectRatioFrameLayout.RESIZE_MODE_FIT, "Original / Fit")
        }
        resizeMode = newMode
        vm.prefs.setVideoResize(newMode)
        Toast.makeText(ctx, toastText, Toast.LENGTH_SHORT).show()
    }
    val toggleShuffle: () -> Unit = {
        shuffle = !shuffle
        vm.prefs.setShuffle(shuffle)
        Toast.makeText(
            ctx,
            if (shuffle) "Shuffle: next plays a random file" else "Continuous: next plays the next file",
            Toast.LENGTH_SHORT
        ).show()
    }
    /** A live stream dropped or a file failed: channels retry a few times, files hand over to ExoPlayer. */
    fun problem(why: String = "playback error") {
        if (type == "channel" && attemptNow < 3) { attempt = attemptNow + 1; return }
        if (type == "channel") vm.markChannel(id, true)
        Toast.makeText(ctx, "VLC failed ($why), using ExoPlayer", Toast.LENGTH_LONG).show()
        keepPlayerWindow = true
        onFailNow()
    }

    LaunchedEffect(type, id) {
        req = vm.playRequest(type, id)
        if (req == null) error = "File not found"
    }
    LaunchedEffect(req) {
        if (type == "channel") req?.let {
            val n = it.channelIds.indexOf(id) + 1
            swipeLabel = if (n > 0) "$n · ${it.title}" else it.title
            delay(2000); swipeLabel = null
        }
    }

    // Start (or restart) playback
    LaunchedEffect(req, restartTick) {
        val r = req ?: return@LaunchedEffect
        CastState.ended = false
        val ct0 = castTv
        if (!tv && ct0 != null) {
            // handed over to the TV: send this video there instead of playing it here
            controls = true
            val (ci, err) = vm.castItem(type, id)
            var sent = false
            var castErr: String? = null
            if (ci != null) {
                val viaPhone = ci.mode == "local" && ct0.kind == "app"
                val sendUri = if (viaPhone) { com.mymovieapp.app.data.LanStream.init(ctx); com.mymovieapp.app.data.LanStream.urlForMedia(ci.uri, ci.title) } else ci.uri
                if (sendUri == null) castErr = "This phone isn't on Wi-Fi" else {
                val body = org.json.JSONObject().put("uri", sendUri).put("title", ci.title).put("mode", if (viaPhone) "url" else ci.mode)
                    .put("pos", if (type == "channel") 0L else r.resumeMs).put("handoff", true).put("live", type == "channel")
                sent = CastSender.post(ct0, CastSession.pin, "/play", body).first
                }
            }
            if (sent) return@LaunchedEffect
            Toast.makeText(ctx, castErr ?: err ?: "Couldn't send that to the TV. Playing here.", Toast.LENGTH_LONG).show()
            CastSession.clear(); castTv = null
        }
        val (clean, headers) = parseStreamRequest(r.uri)
        applied[0] = false; applied[1] = false
        val l = withContext(Dispatchers.IO) { SubStore.list(ctx, clean) }
        locals = l
        try { pfd[0]?.close() } catch (_: Throwable) {}
        pfd[0] = null
        val src = vlcSource(clean)
        val media: Media? = try {
            if (src.scheme.equals("content", true)) {
                val p = ctx.contentResolver.openFileDescriptor(src, "r")
                if (p == null) null else { pfd[0] = p; Media(vlc, p.fileDescriptor) }
            } else if (src.scheme.equals("smb", true) || src.scheme.equals("ftp", true)) {
                // VLC reads the share through a private loopback address served by the app's own SMB / FTP code
                Media(vlc, Uri.parse(withContext(Dispatchers.IO) { LocalStream.urlFor(clean) }))
            } else Media(vlc, src)
        } catch (t: Throwable) { Toast.makeText(ctx, "VLC couldn't open this: $t", Toast.LENGTH_LONG).show(); null }
        if (media == null) { keepPlayerWindow = true; onFailNow(); return@LaunchedEffect }
        if (type == "channel") vm.prefs.vlcArm(id)
        media.setHWDecoderEnabled(true, false)
        if (maxHeight > 0) media.addOption(":adaptive-maxheight=$maxHeight")
        if (type == "channel" && bufferSec > 0) {
            media.addOption(":network-caching=${bufferSec * 1000}")
            media.addOption(":live-caching=${bufferSec * 1000}")
        }
        val web = src.scheme.equals("http", true) || src.scheme.equals("https", true)
        if (web) {
            val ua = headers["User-Agent"] ?: headers["user-agent"]
                ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            media.addOption(":http-user-agent=$ua")
            (headers["Referer"] ?: headers["referer"])?.let { media.addOption(":http-referrer=$it") }
        }
        val startAt = if (resumeAt >= 0) resumeAt else r.resumeMs
        if (startAt > 5000) media.addOption(":start-time=${startAt / 1000}")
        if (type == "channel" && StyleRestart.key == "$type/$id" && StyleRestart.pos > 5000) {
            media.addOption(":start-time=${StyleRestart.pos / 1000}")
            StyleRestart.key = null
        }
        l.forEachIndexed { i, s ->
            media.addSlave(IMedia.Slave(IMedia.Slave.Type.Subtitle, if (i == 0) 4 else 2, Uri.fromFile(s.file).toString()))
        }
        r.subtitleUri?.let { media.addSlave(IMedia.Slave(IMedia.Slave.Type.Subtitle, if (l.isEmpty()) 4 else 2, it)) }
        buffering = true
        vlcStatus = "Starting…"
        posMs = 0L
        mp.media = media
        media.release()
        mp.play()
    }

    DisposableEffect(mp) {
        mp.setEventListener(MediaPlayer.EventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing -> {
                    playing = true; buffering = false
                    mp.setRate(speedNow)
                    applyScale()
                    refreshTracks()
                    if (type == "channel") { vm.markChannel(id, false); attempt = 0 }
                }
                MediaPlayer.Event.Paused -> playing = false
                MediaPlayer.Event.Stopped -> playing = false
                MediaPlayer.Event.TimeChanged -> posMs = e.timeChanged
                MediaPlayer.Event.LengthChanged -> lenMs = e.lengthChanged
                MediaPlayer.Event.SeekableChanged -> seekable = e.seekable
                MediaPlayer.Event.Buffering -> { buffering = e.buffering < 100f; vlcStatus = "Buffering ${e.buffering.toInt()}%  ·  ${LocalStream.status()}" }
                MediaPlayer.Event.Opening -> vlcStatus = "Opened, reading…  ·  ${LocalStream.status()}"
                MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> refreshTracks()
                MediaPlayer.Event.Vout -> applyScale()
                MediaPlayer.Event.EncounteredError -> problem("VLC reported an error on ${reqNow?.uri?.substringBefore(':')}")
                MediaPlayer.Event.EndReached -> {
                    playing = false
                    if (tv && CastState.phoneDriven && !CastState.phoneLive) CastState.ended = true      // the phone decides what comes next
                    else if (type == "channel") problem("stream ended")
                    else {
                        saveNow(true)
                        val next = if (shuffleNow) (reqNow?.pool?.randomOrNull() ?: reqNow?.nextId) else reqNow?.nextId
                        if (next != null && autoplayNow) {
                            keepPlayerWindow = true
                            nav.navigate("player/$type/$next") { popUpTo("player/{type}/{id}") { inclusive = true } }
                        }
                    }
                }
            }
        })
        onDispose {
            saveNow()
            if (!tv) {
                if (CastSession.handover) CastSession.handover = false
                else CastSession.tv?.let { t ->
                    // leaving the player for good: the TV keeps playing, but it is no longer driven by this phone
                    val p = CastSession.pin
                    CastSession.clear()
                    Thread { try { kotlinx.coroutines.runBlocking { CastSender.post(t, p, "/cmd", org.json.JSONObject().put("action", "release")) } } catch (_: Throwable) {} }.start()
                }
            }
            mp.setEventListener(null)
            try { mp.detachViews() } catch (_: Throwable) {}
            try { pfd[0]?.close() } catch (_: Throwable) {}
            // stopping and releasing can block for a moment (network), so do it off the main thread
            Thread {
                try { mp.stop() } catch (_: Throwable) {}
                try { mp.release() } catch (_: Throwable) {}
                try { vlc.release() } catch (_: Throwable) {}
            }.start()
        }
    }
    LaunchedEffect(attempt) {
        if (attempt in 1..3) {
            delay(2000L shl (attempt - 1))
            restartTick++
        }
    }
    LaunchedEffect(resizeMode) { applyScale() }
    LaunchedEffect(buffering) {
        while (buffering) {
            vlcStatus = "Loading…  ·  ${LocalStream.status()}"
            delay(1000)
        }
    }

    // Pause when the app goes to the background (Home button), so audio doesn't keep playing.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, mp) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) { saveNow(); away = true; try { mp.pause() } catch (_: Throwable) {} }
            if (e == androidx.lifecycle.Lifecycle.Event.ON_START && away) {
                controls = true; lastBump = System.currentTimeMillis()
                scope.launch { if (vm.sourceLocked(type, id)) lockClose() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    // Sound devices changing while away (a Bluetooth speaker) means the audio output must be opened again.
    DisposableEffect(mp) {
        val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
        val cb = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(d: Array<out android.media.AudioDeviceInfo>?) { if (away) audioChanged = true }
            override fun onAudioDevicesRemoved(d: Array<out android.media.AudioDeviceInfo>?) { if (away) audioChanged = true }
        }
        try { am?.registerAudioDeviceCallback(cb, null) } catch (_: Throwable) {}
        onDispose { try { am?.unregisterAudioDeviceCallback(cb) } catch (_: Throwable) {} }
    }
    // A locked source never plays: if its lock closes (leaving the app, Lock now), the player closes, and a cast stops with it.
    LaunchedEffect(req) {
        if (req == null) return@LaunchedEffect
        vm.vault.hidden.collect { if (vm.sourceLocked(type, id)) lockClose() }
    }
    LaunchedEffect(mp) {
        while (true) {
            delay(5000)
            if (playing) saveNow()
        }
    }

    DisposableEffect(Unit) {
        val ctrl = act?.window?.let { WindowCompat.getInsetsController(it, view) }
        act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        com.mymovieapp.app.data.Thumbs.playersOpen.incrementAndGet()
        ctrl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctrl?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            com.mymovieapp.app.data.Thumbs.playersOpen.decrementAndGet()
            if (keepPlayerWindow) {
                keepPlayerWindow = false
            } else {
                act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                ctrl?.show(WindowInsetsCompat.Type.systemBars())
                act?.window?.let { w ->
                    val lp = w.attributes
                    lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    w.attributes = lp
                }
            }
        }
    }
    LaunchedEffect(controls) {
        if (tv) return@LaunchedEffect
        val c = act?.window?.let { WindowCompat.getInsetsController(it, view) } ?: return@LaunchedEffect
        if (controls) c.show(WindowInsetsCompat.Type.navigationBars())
        else c.hide(WindowInsetsCompat.Type.navigationBars())
    }
    // auto-hide the controls after a few seconds without any touch / key
    LaunchedEffect(controls, lastBump) {
        if (controls && castTv == null) {
            delay(if (tv) 6000L else 3500L)
            if (!audioMenu && !subMenu && !qualityMenu && !speedMenu && !showSubs) controls = false
        }
    }
    // TV: put the remote's focus on play/pause when the controls appear
    LaunchedEffect(controls) {
        if (tv && controls) {
            delay(80)
            try { playFocus.requestFocus() } catch (_: Throwable) {}
        }
    }

    // TV remote. Keys are caught here (MainActivity passes them on first).
    DisposableEffect(tv, type) {
        val myHandler: (android.view.KeyEvent) -> Boolean = handler@{ e ->
            val k = e.keyCode
            if (e.action == KeyEvent.ACTION_DOWN) bump()
            // Back while the controls are showing: just hide them, keep playing.
            if (controlsNow && k == KeyEvent.KEYCODE_BACK) {
                if (e.action == KeyEvent.ACTION_UP) controls = false
                return@handler true
            }
            if (k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k == KeyEvent.KEYCODE_MEDIA_PLAY || k == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                if (e.action == KeyEvent.ACTION_DOWN) {
                    when (k) {
                        KeyEvent.KEYCODE_MEDIA_PLAY -> mp.play()
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> mp.pause()
                        else -> if (mp.isPlaying) mp.pause() else mp.play()
                    }
                }
                return@handler true
            }
            if (k == KeyEvent.KEYCODE_MEDIA_NEXT || k == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) { if (k == KeyEvent.KEYCODE_MEDIA_NEXT) doNext() else doPrev() }
                return@handler true
            }
            if (controlsNow) return@handler false
            if (nextOverlayNow && (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) doNext()
                return@handler true
            }
            if (type == "channel") {
                val digit = when (k) {
                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> k - KeyEvent.KEYCODE_0
                    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> k - KeyEvent.KEYCODE_NUMPAD_0
                    else -> -1
                }
                if (digit >= 0) {
                    if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && numBufNow.length < 4) numBuf = numBufNow + digit
                    return@handler true
                }
                if (numBufNow.isNotEmpty() && (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                    if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) jumpNow()
                    return@handler true
                }
                if (numBufNow.isNotEmpty() && k == KeyEvent.KEYCODE_BACK) {
                    if (e.action == KeyEvent.ACTION_UP) { numBuf = ""; swipeLabel = null }
                    return@handler true
                }
            }
            val back = k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_MEDIA_REWIND
            val fwd = k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            // A video cast from the phone is opened like a link (a "channel"), but it is a video: LEFT / RIGHT seek in it.
            val liveKeys = type == "channel" && !CastState.isCastedVideo(reqNow?.uri)
            when {
                (back || fwd) && liveKeys -> {
                    // live TV: LEFT / RIGHT change channel
                    if (e.action == android.view.KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                        (if (fwd) reqNow?.skipNextId else reqNow?.skipPrevId)?.let(goTo)
                    }
                    true
                }
                (back || fwd) && !liveKeys -> {
                    if (e.action == KeyEvent.ACTION_DOWN) {
                        val dur = lenNow
                        if (dur > 0) {
                            val step = (10_000L * (1 + e.repeatCount / 3)).coerceAtMost(120_000L)
                            val to = (posNow + if (fwd) step else -step).coerceIn(0L, dur)
                            seekTo(to)
                            swipeLabel = "${swipeTime(to)}  (${if (fwd) "+" else "−"}${step / 1000}s)"
                        }
                    } else if (e.action == KeyEvent.ACTION_UP) swipeLabel = null
                    true
                }
                k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER ||
                    k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_DPAD_UP ||
                    k == KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (e.action == KeyEvent.ACTION_DOWN) controls = true
                    true
                }
                else -> false
            }
        }
        if (tv) PlayerKeys.handler = myHandler
        // the previous video's screen may be removed after the next one starts: only clear our own handler
        onDispose { if (PlayerKeys.handler === myHandler) PlayerKeys.handler = null }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(mp) { detectTapGestures(onTap = { controls = !controls; bump() }) }
            .pointerInput(mp) {
                var total = 0f
                var startPos = 0L
                var target = 0L
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f; startPos = posNow; target = startPos },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        total += dx
                        val dur = lenNow
                        if (dur > 0) {
                            target = (startPos + (total / size.width * 120_000f).toLong()).coerceIn(0L, dur)
                            val diff = target - startPos
                            swipeLabel = "${swipeTime(target)}  (${if (diff >= 0) "+" else "−"}${swipeTime(abs(diff))})"
                        }
                    },
                    onDragEnd = { if (swipeLabel != null) seekTo(target); swipeLabel = null },
                    onDragCancel = { swipeLabel = null }
                )
            }
            .pointerInput(mp) {
                var left = true
                var start = 0f
                var moved = 0f
                var sentV = 0f
                detectVerticalDragGestures(
                    onDragStart = { p ->
                        left = p.x < size.width / 2
                        moved = 0f
                        sentV = 0.5f
                        start = if (left) {
                            val b = act?.window?.attributes?.screenBrightness ?: -1f
                            if (b >= 0f) b else android.provider.Settings.System.getInt(
                                ctx.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128
                            ) / 255f
                        } else {
                            audioMgr.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() /
                                audioMgr.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                        }
                    },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        moved -= dy
                        val v = (start + moved / size.height).coerceIn(0f, 1f)
                        if (left) {
                            act?.window?.let { w ->
                                val lp = w.attributes
                                lp.screenBrightness = v.coerceAtLeast(0.02f)
                                w.attributes = lp
                            }
                            swipeLabel = "Brightness ${(v * 100).roundToInt()}%"
                        } else if (castTv != null) {
                            // casting: the swipe changes the TV's volume, a step for every few percent
                            if (sentV == 0.5f) sentV = v
                            if (v - sentV > 0.05f) { castCmd("volup"); sentV = v } else if (sentV - v > 0.05f) { castCmd("voldown"); sentV = v }
                            swipeLabel = "TV volume"
                        } else {
                            val max = audioMgr.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                            audioMgr.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (v * max).roundToInt(), 0)
                            swipeLabel = "Volume ${(v * 100).roundToInt()}%"
                        }
                    },
                    onDragEnd = { swipeLabel = null },
                    onDragCancel = { swipeLabel = null }
                )
            }
            .pointerInput(mp) {
                // Hold a finger on the picture to fast-forward: 2x, then 4x, then 6x the longer you hold.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        waitForUpOrCancellation()
                        true
                    }
                    if (released == null && !controlsNow && type != "channel" && castTv == null) {
                        val steps = listOf(2f, 4f, 6f)
                        var level = 0
                        mp.setRate(steps[0])
                        swipeLabel = "2×  speed"
                        var held = true
                        while (held) {
                            val up = if (level < steps.lastIndex)
                                withTimeoutOrNull(2000L) { waitForUpOrCancellation(); true }
                            else { waitForUpOrCancellation(); true }
                            if (up != null) held = false
                            else {
                                level++
                                mp.setRate(steps[level])
                                swipeLabel = "${steps[level].toInt()}×  speed"
                            }
                        }
                        mp.setRate(speedNow)
                        swipeLabel = null
                    }
                }
            }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                VLCVideoLayout(c).also {
                    it.keepScreenOn = true
                    layoutRef = it
                    mp.attachViews(it, null, true, false)
                }
            }
        )
        castTv?.let { ct ->
            Column(
                Modifier.fillMaxSize().background(Color.Black),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Filled.Cast, null, tint = Accent, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(12.dp))
                Text("Casting to ${ct.name}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(req?.let { if (type == "channel") it.title else titleWithType(parseStreamRequest(it.uri).first, it.title) }.orEmpty(), color = Color(0xCCFFFFFF), maxLines = 4, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp))
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { pickPhoneSub.launch(arrayOf("*/*")) }) { Text("Subtitles from this phone…") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { castCmd("stop"); endCast(null, true) }) { Text("Play here") }
            }
        }
        if (buffering && error == null && castTv == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Accent)
                Spacer(Modifier.height(10.dp))
                Text(vlcStatus.substringBefore("  \u00b7  "), color = Color(0xCCFFFFFF), fontSize = 12.sp)
            }
        }
        swipeLabel?.let {
            Text(
                it, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center)
                    .background(Color(0x99000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            )
        }
        if (type == "channel" && attempt > 0 && error == null) {
            Text(
                "Reconnecting… ($attempt/3)", color = Color.White, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp)
                    .background(Color(0x99000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
        if (nextOverlay && !controls && error == null) {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 28.dp)
                    .clip(RoundedCornerShape(24.dp)).background(Color(0xCC000000))
                    .clickable { doNext() }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.SkipNext, null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text(if (tv) "Next  ·  press OK" else "Next", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
        if (controls) {
            // top bar
            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent)))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { nav.popBackStack() }, modifier = fh) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Text(
                    text = req?.let { if (type == "channel") it.title else titleWithType(parseStreamRequest(it.uri).first, it.title) }.orEmpty(), modifier = Modifier.weight(1f),
                    color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Text(
                    "VLC", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .then(if (tv) Modifier.focusHighlight(RoundedCornerShape(6.dp)) else Modifier)
                        .clickable { saveNow(); keepPlayerWindow = true; onFailNow() }
                        .background(Accent)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                val audioShown = if (castTv != null) castAudio else audioList
                if (audioShown.size > 1) {
                    Box {
                        TextButton(onClick = { audioMenu = true; bump() }, modifier = fh) {
                            Icon(Icons.Filled.Audiotrack, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                audioShown.firstOrNull { it.selected }?.name ?: "Audio",
                                color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        DropdownMenu(expanded = audioMenu, onDismissRequest = { audioMenu = false }) {
                            audioShown.forEach { o ->
                                DropdownMenuItem(
                                    text = { Text(o.name, color = if (o.selected) Accent else Color.Unspecified) },
                                    onClick = {
                                        if (castTv != null) { castCmd("audio:${o.id}"); audioMenu = false; return@DropdownMenuItem }
                                        applied[0] = true
                                        reqNow?.groupKey?.let { g -> TrackPrefs.set(ctx, g, "a", normTrack(o.name)) }
                                        mp.setAudioTrack(o.id); audioMenu = false; refreshTracks()
                                    }
                                )
                            }
                        }
                    }
                }
                if (type == "channel") {
                    IconButton(onClick = reload, modifier = fh) { Icon(Icons.Filled.Refresh, "Reload", tint = Color.White) }
                    IconButton(modifier = fh, onClick = {
                        vm.toggleFavorite(id)
                        Toast.makeText(ctx, if (isFav) "Removed from favourites" else "Added to favourites", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(
                            if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favourite",
                            tint = if (isFav) Danger else Color.White
                        )
                    }
                    IconButton(modifier = fh, onClick = { deleteAsk = true; bump() }) {
                        Icon(Icons.Filled.Delete, "Remove channel", tint = Color.White)
                    }
                }
                if (type == "file" && req?.uri?.startsWith("ftp://") != true) {
                    IconButton(modifier = fh, onClick = {
                        scope.launch {
                            renameText = vm.videoById(id)?.fileName?.substringBeforeLast('.').orEmpty()
                            renameError = null
                            resumeAfterRename = mp.isPlaying
                            mp.pause()
                            renameOpen = true
                        }
                    }) { Icon(Icons.Filled.Edit, "Rename", tint = Color.White) }
                }
                if (type != "channel") {
                    Box {
                        TextButton(onClick = { speedMenu = true; bump() }, modifier = fh, contentPadding = PaddingValues(horizontal = 10.dp)) {
                            Text(speedLabel(speed), color = if (speed == 1f) Color.White else Accent, fontWeight = FontWeight.Bold)
                        }
                        DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f, 6f, 8f).forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(speedLabel(s), color = if (speed == s) Accent else Color.Unspecified) },
                                    onClick = { speed = s; if (castTv != null) castCmd("speed:$s") else mp.setRate(s); speedMenu = false }
                                )
                            }
                        }
                    }
                }
                IconButton(onClick = { toggleResizeMode(); bump() }, modifier = fh) { Icon(Icons.Filled.AspectRatio, "Resize", tint = Color.White) }
                if (castTv == null && (type == "channel" || videoList.size > 1)) {
                    // streams with several qualities (live channels): pick one
                    Box {
                        TextButton(
                            onClick = { refreshTracks(); qualityMenu = true; bump() },
                            modifier = fh, contentPadding = PaddingValues(horizontal = 10.dp)
                        ) {
                            Text(
                                if (type == "channel") (if (maxHeight == 0) "Auto" else "${maxHeight}p")
                                else videoList.firstOrNull { it.selected }?.name?.substringAfter('[', "")?.substringBefore(']')
                                    ?.takeIf { it.isNotBlank() } ?: "Quality",
                                color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1
                            )
                        }
                        DropdownMenu(expanded = qualityMenu, onDismissRequest = { qualityMenu = false }) {
                            if (type == "channel") {
                                listOf(0, 1080, 720, 480, 360).forEach { h ->
                                    DropdownMenuItem(
                                        text = { Text(if (h == 0) "Auto" else "${h}p", color = if (maxHeight == h) Accent else Color.Unspecified) },
                                        onClick = {
                                            qualityMenu = false
                                            if (maxHeight != h) { maxHeight = h; attempt = 0; restartTick++ }
                                        }
                                    )
                                }
                            } else videoList.forEach { o ->
                                DropdownMenuItem(
                                    text = { Text(o.name, color = if (o.selected) Accent else Color.Unspecified) },
                                    onClick = { mp.setVideoTrack(o.id); qualityMenu = false; refreshTracks() }
                                )
                            }
                        }
                    }
                }
                if (type == "channel" && castTv == null) {
                    // this channel only: a longer buffer for streams that play, stall, play, stall
                    Box {
                        TextButton(onClick = { bufferMenu = true; bump() }, modifier = fh, contentPadding = PaddingValues(horizontal = 10.dp)) {
                            Text(
                                if (bufferSec == 0) "Buffer" else "Buffer ${bufferSec}s",
                                color = if (bufferSec == 0) Color.White else Accent, fontWeight = FontWeight.SemiBold, maxLines = 1
                            )
                        }
                        DropdownMenu(expanded = bufferMenu, onDismissRequest = { bufferMenu = false }) {
                            ChannelBuffer.CHOICES.forEach { sec ->
                                DropdownMenuItem(
                                    text = { Text(if (sec == 0) "Normal" else "${sec} seconds", color = if (bufferSec == sec) Accent else Color.Unspecified) },
                                    onClick = {
                                        bufferMenu = false
                                        if (bufferSec != sec) {
                                            bufferSec = sec; ChannelBuffer.set(ctx, id, sec); attempt = 0; restartTick++
                                            Toast.makeText(ctx, if (sec == 0) "Buffer: normal for this channel" else "Buffer: $sec seconds for this channel", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                IconButton(onClick = openOther, modifier = fh) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open in another player", tint = Color.White) }
                if (!tv && castTv == null) IconButton(
                    onClick = {
                        bump()
                        scope.launch {
                            val (ci, err) = vm.castItem(type, id)
                            if (ci == null) android.widget.Toast.makeText(ctx, err ?: "Can't send this one", android.widget.Toast.LENGTH_SHORT).show()
                            else { mp.pause(); castFor = com.mymovieapp.app.LibraryViewModel.CastItem(ci.mode, ci.uri, ci.title, if (type == "channel") 0L else posNow) }
                        }
                    },
                    modifier = fh
                ) { Icon(Icons.Filled.Cast, "Play on TV", tint = Color.White) }
                IconButton(
                    onClick = { refreshTracks(); wasPlayingForSubs = mp.isPlaying; mp.pause(); showSubs = true; bump() },
                    modifier = fh
                ) { Icon(Icons.Filled.ClosedCaption, "Subtitles", tint = Color.White) }
            }
            // bottom bar
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA000000))))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (showBar) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val shown = dragFrac?.let { (it * lenMs).toLong() } ?: posMs
                        Text(swipeTime(shown), color = Color.White, fontSize = 13.sp)
                        if (tv) {
                            // Remote: focus the bar, then LEFT / RIGHT skip (hold to skip faster)
                            val frac = if (lenMs > 0) (posMs.toFloat() / lenMs).coerceIn(0f, 1f) else 0f
                            Box(
                                Modifier.weight(1f).padding(horizontal = 8.dp).height(36.dp)
                                    .focusHighlight(RoundedCornerShape(8.dp))
                                    .onKeyEvent { ev ->
                                        val left = ev.key == Key.DirectionLeft
                                        val right = ev.key == Key.DirectionRight
                                        if (!left && !right) false
                                        else {
                                            if (ev.type == KeyEventType.KeyDown) {
                                                val step = (10_000L * (1 + ev.nativeKeyEvent.repeatCount / 3)).coerceAtMost(120_000L)
                                                seekTo(posNow + if (right) step else -step)
                                                bump()
                                            }
                                            true
                                        }
                                    }
                                    .focusable(),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Box(Modifier.fillMaxWidth().height(5.dp).background(Color(0x55FFFFFF), RoundedCornerShape(3.dp)))
                                Box(Modifier.fillMaxWidth(frac).height(5.dp).background(Accent, RoundedCornerShape(3.dp)))
                            }
                        } else {
                            Slider(
                                value = dragFrac ?: if (lenMs > 0) (posMs.toFloat() / lenMs).coerceIn(0f, 1f) else 0f,
                                onValueChange = { dragFrac = it; bump() },
                                onValueChangeFinished = { dragFrac?.let { seekTo((it * lenMs).toLong()) }; dragFrac = null },
                                colors = SliderDefaults.colors(
                                    thumbColor = Accent, activeTrackColor = Accent,
                                    inactiveTrackColor = Color(0x55FFFFFF)
                                ),
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                            )
                        }
                        Text(swipeTime(lenMs), color = Color.White, fontSize = 13.sp)
                        if (type == "channel") {
                            val behind = lenMs - posMs > 8_000L
                            TextButton(onClick = { seekTo(lenNow); bump() }, modifier = fh) {
                                Text(if (behind) "GO LIVE" else "LIVE", color = if (behind) Accent else Color(0x99FFFFFF), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { doPrev(); bump() }, enabled = hasPrev || type != "channel", modifier = fh) {
                        Icon(Icons.Filled.SkipPrevious, "Previous", tint = if (hasPrev || type != "channel") Color.White else Color(0x44FFFFFF))
                    }
                    if (showBar) {
                        IconButton(onClick = { seekTo(posMs - 10_000); bump() }, modifier = fh) { Icon(Icons.Filled.Replay10, "Back 10 s", tint = Color.White) }
                    }
                    IconButton(
                        onClick = { togglePlay(); bump() },
                        modifier = Modifier.focusRequester(playFocus).then(fh)
                    ) {
                        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                    if (showBar) {
                        IconButton(onClick = { seekTo(posMs + 10_000); bump() }, modifier = fh) { Icon(Icons.Filled.Forward10, "Forward 10 s", tint = Color.White) }
                    }
                    IconButton(onClick = { doNext(); bump() }, enabled = hasNext, modifier = fh) {
                        Icon(Icons.Filled.SkipNext, "Next", tint = if (hasNext) Color.White else Color(0x44FFFFFF))
                    }
                    if (req?.pool?.isNotEmpty() == true) {
                        IconButton(onClick = { toggleShuffle(); bump() }, modifier = fh) {
                            Icon(Icons.Filled.Shuffle, "Shuffle", tint = if (shuffle) Accent else Color(0x99FFFFFF))
                        }
                    }
                }
            }
        }
        castFor?.let { ci ->
            CastDialog(vm, ci, type == "channel", onHandoff = { t, p ->
                CastSession.set(t, p); castTv = t; mp.pause(); playing = true; buffering = false; controls = true; bump()
            }) { castFor = null }
        }
        error?.let {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(it, color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { nav.popBackStack() }) { Text("Back") }
            }
        }
    }

    val subReq = req
    if (showSubs && subReq != null) {
        val cleanKey = parseStreamRequest(subReq.uri).first
        VlcSubtitleDialog(
            vm = vm, req = subReq, clean = cleanKey,
            tracks = (if (castTv != null) castSpu else spuList).map { SpuChoice(it.id, it.name, it.selected) }, locals = locals, delayUs = spuDelayUs,
            onPickTrack = { tid ->
                if (castTv != null) { castCmd("spu:$tid"); return@VlcSubtitleDialog }
                applied[1] = true
                reqNow?.groupKey?.let { g -> TrackPrefs.set(ctx, g, "s", if (tid < 0) "off" else normTrack(spuList.firstOrNull { it.id == tid }?.name ?: "")) }
                mp.setSpuTrack(tid); refreshTracks()
            },
            onUseFile = { f ->
                castTv?.let { ct -> scope.launch { val r = CastSender.sendSubtitle(ct, CastSession.pin, withContext(Dispatchers.IO) { f.readBytes() }, f.name); Toast.makeText(ctx, r.second.ifBlank { "Subtitles added" }, Toast.LENGTH_SHORT).show() }; showSubs = false; return@VlcSubtitleDialog }
                applied[1] = true; mp.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(f), true); showSubs = false; if (wasPlayingForSubs) mp.play() },
            onDownloaded = {
                scope.launch {
                    val l = withContext(Dispatchers.IO) { SubStore.list(ctx, cleanKey) }
                    locals = l
                    applied[1] = true
                    val ctv = castTv
                    if (ctv != null) l.firstOrNull()?.let { f -> CastSender.sendSubtitle(ctv, CastSession.pin, withContext(Dispatchers.IO) { f.file.readBytes() }, f.file.name) }
                    else l.firstOrNull()?.let { mp.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(it.file), true) }
                    delay(300); refreshTracks()
                }
                showSubs = false
                if (wasPlayingForSubs) mp.play()
            },
            onDeleted = { scope.launch { locals = withContext(Dispatchers.IO) { SubStore.list(ctx, cleanKey) } } },
            onDelay = { d -> spuDelayUs = d; try { mp.setSpuDelay(d) } catch (_: Throwable) {} },
            onStyleChanged = { styleDirty = true },
            onDismiss = {
                showSubs = false
                if (styleDirty) {
                    styleDirty = false
                    saveNow()
                    if (type == "channel" && lenNow > 0) { StyleRestart.key = "$type/$id"; StyleRestart.pos = posNow }
                    goTo(id)
                } else if (wasPlayingForSubs) mp.play()
            }
        )
    }
    if (deleteAsk) {
        AlertDialog(
            onDismissRequest = { deleteAsk = false },
            title = { Text("Remove this channel?") },
            text = { Text("It is removed from this app only and stays removed when the playlist refreshes.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteAsk = false
                    val r = reqNow
                    val target = r?.skipNextId ?: r?.skipPrevId
                    vm.removeChannels(listOf(id))
                    Toast.makeText(ctx, "Channel removed", Toast.LENGTH_SHORT).show()
                    if (target != null) goTo(target) else nav.popBackStack()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { deleteAsk = false }) { Text("Cancel") } }
        )
    }
    if (renameOpen) {
        AlertDialog(
            onDismissRequest = { if (!renameBusy) { renameOpen = false; if (resumeAfterRename) mp.play() } },
            title = { Text("Rename file") },
            text = {
                Column {
                    AppTextField(
                        value = renameText,
                        onValueChange = { renameText = it; renameError = null },
                        singleLine = true,
                        enabled = !renameBusy
                    )
                    renameError?.let {
                        Text(
                            it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !renameBusy && renameText.isNotBlank(),
                    onClick = {
                        val pos = posMs
                        saveNow()
                        mp.stop()                       // release the file so the share lets go of it
                        renameBusy = true
                        renameError = null
                        vm.renameVideo(id, renameText) { ok, msg ->
                            renameBusy = false
                            scope.launch {
                                (vm.playRequest(type, id) ?: reqNow)?.let { req = it.copy(resumeMs = pos) }
                                if (ok) {
                                    renameOpen = false
                                    Toast.makeText(ctx, msg ?: "Renamed", Toast.LENGTH_SHORT).show()
                                } else renameError = msg ?: "Rename failed"
                            }
                        }
                    }
                ) { Text(if (renameBusy) "Renaming…" else "Rename") }
            },
            dismissButton = {
                TextButton(
                    enabled = !renameBusy,
                    onClick = { renameOpen = false; if (resumeAfterRename) mp.play() }
                ) { Text("Cancel") }
            }
        )
    }
    if (extAsk) {
        AlertDialog(
            onDismissRequest = { extAsk = false },
            title = { Text("Open in another player?") },
            text = { Text("This sends the login for this share to the player you choose, so it can open the file.") },
            confirmButton = {
                TextButton(onClick = {
                    extAsk = false
                    reqNow?.let { openExternally(ctx, it.uri, it.title) }
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { extAsk = false }) { Text("Cancel") } }
        )
    }
}
