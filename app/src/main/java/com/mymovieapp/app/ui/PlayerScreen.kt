package com.mymovieapp.app.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.net.Uri
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import kotlin.math.roundToInt
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Edit
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Refresh
import android.widget.ImageButton
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.media3.datasource.DataSource
import com.mymovieapp.app.data.SchemeDataSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import kotlinx.coroutines.withTimeoutOrNull
import com.mymovieapp.app.data.SmbDataSource
import com.mymovieapp.app.data.FtpDataSource
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.abs
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import android.content.ComponentName
import com.mymovieapp.app.data.Ftp
import com.mymovieapp.app.data.FtpStore
import com.mymovieapp.app.data.Smb
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import com.mymovieapp.app.LibraryViewModel
import com.mymovieapp.app.PlayRequest
import com.mymovieapp.app.data.SubStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * Quality Selection Helpers
 */
data class QualityOption(
    val label: String,
    val group: Tracks.Group? = null,
    val trackIndex: Int = -1,
    val isAuto: Boolean = false
)
@OptIn(UnstableApi::class)
private fun canRetry(e: PlaybackException): Boolean = when (e.errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> true
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
        ((e.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode ?: 0) >= 500
    else -> false
}
@OptIn(UnstableApi::class)
private fun getAvailableQualities(player: Player): List<QualityOption> {
    val options = mutableListOf<QualityOption>()
    options.add(QualityOption(label = "Auto (Recommended)", isAuto = true))

    val tracks = player.currentTracks
    for (group in tracks.groups) {
        if (group.type == C.TRACK_TYPE_VIDEO) {
            val mediaTrackGroup = group.mediaTrackGroup
            for (i in 0 until mediaTrackGroup.length) {
                val format = mediaTrackGroup.getFormat(i)
                if (format.height != Format.NO_VALUE && format.height > 0) {
                    options.add(
                        QualityOption(
                            label = "${format.height}p",
                            group = group,
                            trackIndex = i
                        )
                    )
                }
            }
        }
    }
    return options.distinctBy { it.label }.sortedByDescending {
        it.label.filter { char -> char.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE
    }
}

@OptIn(UnstableApi::class)
private fun setQualityOption(player: Player, option: QualityOption) {
    if (option.isAuto || option.group == null) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .build()
    } else {
        val override = TrackSelectionOverride(option.group.mediaTrackGroup, option.trackIndex)
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(override)
            .build()
    }
}
private fun qualityName(o: QualityOption): String {
    if (o.isAuto) return "Auto"
    val h = o.label.filter { it.isDigit() }.toIntOrNull() ?: return o.label
    return if (h >= 2160) "4K · ${h}p" else "${h}p"
}
private class AudioOption(val label: String, val group: Tracks.Group, val index: Int, val selected: Boolean)

@OptIn(UnstableApi::class)
private fun getAudioOptions(player: Player): List<AudioOption> {
    val out = mutableListOf<AudioOption>()
    for (g in player.currentTracks.groups) {
        if (g.type != C.TRACK_TYPE_AUDIO) continue
        for (i in 0 until g.length) {
            if (!g.isTrackSupported(i)) continue
            val f = g.getTrackFormat(i)
            val lang = f.language?.takeIf { it != "und" }?.let { code ->
                java.util.Locale.forLanguageTag(code).displayLanguage.ifBlank { code }
            }
            val ch = when (f.channelCount) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> null }
            val name = listOfNotNull(lang ?: f.label, ch).joinToString(" · ").ifBlank { "Track ${out.size + 1}" }
            out.add(AudioOption(name, g, i, g.isTrackSelected(i)))
        }
    }
    return out
}

@OptIn(UnstableApi::class)
private fun setAudioOption(player: Player, o: AudioOption) {
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, o.index))
        .build()
}
@Composable
fun QualityPickerDialog(
    qualities: List<QualityOption>,
    currentQualityLabel: String,
    onQualitySelected: (QualityOption) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Video Quality", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                qualities.forEach { option ->
                    val isSelected = if (option.isAuto) {
                        currentQualityLabel.startsWith("Auto")
                    } else {
                        currentQualityLabel.contains(option.label)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusHighlight(RoundedCornerShape(10.dp))
                            .clickable {
                                onQualitySelected(option)
                                onDismiss()
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = {
                                onQualitySelected(option)
                                onDismiss()
                            }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(text = option.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

/**
 * Intercepts HLS .m3u8 manifests and rewrites non-standard `#EXT-X-PROGRAM-DATE-TIME` tags
 * (e.g., MM/DD/YYYYT...) into valid ISO 8601 format (YYYY-MM-DD) to prevent
 * ERROR_CODE_PARSING_CONTAINER_MALFORMED crashes in ExoPlayer/Media3.
 */
@OptIn(UnstableApi::class)
class SanitizedHlsPlaylistParserFactory : HlsPlaylistParserFactory {

    private val defaultFactory = DefaultHlsPlaylistParserFactory()
    private val dateRegex = Regex("""#EXT-X-PROGRAM-DATE-TIME:(\d{2})/(\d{2})/(\d{4})""")

    private fun sanitizeManifest(inputStream: InputStream): InputStream {
        val rawManifest = inputStream.bufferedReader().use { it.readText() }
        val cleanedManifest = rawManifest.replace(dateRegex) { matchResult ->
            val (month, day, year) = matchResult.destructured
            "#EXT-X-PROGRAM-DATE-TIME:$year-$month-$day"
        }
        return cleanedManifest.byteInputStream()
    }

    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> {
        val delegate = defaultFactory.createPlaylistParser()
        return ParsingLoadable.Parser { uri, inputStream ->
            delegate.parse(uri, sanitizeManifest(inputStream))
        }
    }

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?
    ): ParsingLoadable.Parser<HlsPlaylist> {
        val delegate = defaultFactory.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist)
        return ParsingLoadable.Parser { uri, inputStream ->
            delegate.parse(uri, sanitizeManifest(inputStream))
        }
    }
}

/**
 * Parses pipe-delimited URLs (`url|Header=Value&Header2=Value2`),
 * injects missing Referer headers to fix HTTP 403 Forbidden, and cleans spaces to fix HTTP 400.
 */
internal fun parseStreamRequest(rawUrl: String): Pair<String, Map<String, String>> {
    val parts = rawUrl.split("|", limit = 2)
    var urlStr = parts[0].trim()

    if (urlStr.contains(" ") && !urlStr.contains("%20")) {
        urlStr = urlStr.replace(" ", "%20")
    }

    val headers = mutableMapOf<String, String>()

    if (parts.size > 1) {
        val headerString = parts[1]
        headerString.split("&").forEach { pair ->
            val kv = pair.split("=", limit = 2)
            if (kv.size == 2) {
                val key = kv[0].trim()
                val value = kv[1].trim()
                if (key.isNotEmpty() && value.isNotEmpty()) {
                    headers[key] = value
                }
            }
        }
    }

    val hasReferer = headers.keys.any { it.equals("referer", ignoreCase = true) }
    if (!hasReferer) {
        try {
            val uri = Uri.parse(urlStr)
            if (uri.scheme != null && uri.host != null) {
                headers["Referer"] = "${uri.scheme}://${uri.host}/"
            }
        } catch (_: Exception) {}
    }

    return Pair(urlStr, headers)
}

private fun mimeFor(ext: String) = when (ext.lowercase()) {
    "vtt" -> MimeTypes.TEXT_VTT
    "ass", "ssa" -> MimeTypes.TEXT_SSA
    else -> MimeTypes.APPLICATION_SUBRIP
}

private fun subConfig(uri: String, ext: String, lang: String, label: String, default: Boolean) =
    MediaItem.SubtitleConfiguration.Builder(Uri.parse(uri))
        .setMimeType(mimeFor(ext)).setLanguage(lang).setLabel(label)
        .setSelectionFlags(if (default) C.SELECTION_FLAG_DEFAULT else 0)
        .build()

private fun buildItem(r: PlayRequest, locals: List<SubStore.Local>, select: String?): MediaItem {
    val (cleanUrl, _) = parseStreamRequest(r.uri)
    val b = MediaItem.Builder().setUri(Uri.parse(cleanUrl))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(r.title).build())
    r.mime?.let { b.setMimeType(it) }
    val chosen = locals.firstOrNull { it.file.name == select } ?: locals.firstOrNull()
    val subs = ArrayList<MediaItem.SubtitleConfiguration>()
    locals.forEach { l ->
        subs += subConfig(Uri.fromFile(l.file).toString(), l.file.extension, l.lang.lowercase(), "${l.lang} ·${l.label}", l === chosen)
    }
    r.subtitleUri?.let { s ->
        subs += subConfig(s, if (s.contains(".vtt", ignoreCase = true)) "vtt" else "srt", "und", "Subtitle file", chosen == null)
    }
    if (subs.isNotEmpty()) b.setSubtitleConfigurations(subs)
    return b.build()
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun smbAwareFactory(ctx: android.content.Context, http: DefaultHttpDataSource.Factory): DataSource.Factory =
    DefaultDataSource.Factory(
        ctx,
        DataSource.Factory { SchemeDataSource(http.createDataSource(), SmbDataSource(), FtpDataSource()) }
    )
internal fun swipeTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600; val m = (s / 60) % 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
/** Sends the video to another installed player (MX, VLC …). SMB/FTP get the saved login added to the address. */
fun openExternally(ctx: android.content.Context, raw: String, title: String) {
    val (clean, _) = parseStreamRequest(raw)
    val src = Uri.parse(clean)
    // A share's address and login stay inside this app: the other player gets a local http address that this app serves.
    val uri = when (src.scheme?.lowercase()) {
        "smb", "ftp" -> Uri.parse(com.mymovieapp.app.data.LocalStream.urlFor(clean))
        else -> src
    }
    val mime = if (clean.contains(".m3u8", ignoreCase = true)) "application/x-mpegURL" else "video/*"
    val view = android.content.Intent(android.content.Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .putExtra("title", title)
        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val chooser = android.content.Intent.createChooser(view, "Open with")
        .putExtra(
            android.content.Intent.EXTRA_EXCLUDE_COMPONENTS,
            arrayOf(ComponentName(ctx, "com.mymovieapp.app.MainActivity"))
        )
    try { ctx.startActivity(chooser); return } catch (_: Exception) {}
    // Some TV boxes have no "Open with" chooser: fall back to VLC directly.
    try { ctx.startActivity(android.content.Intent(view).setPackage("org.videolan.vlc")) }
    catch (_: Exception) { Toast.makeText(ctx, "No other video player found", Toast.LENGTH_SHORT).show() }
}
/** Sends a video to another installed player. Asks first when a share login would be passed along. */
@Composable
fun OpenElsewhereButton(uri: String, title: String, label: String? = null, onDone: () -> Unit = {}) {
    val ctx = LocalContext.current
    var ask by remember { mutableStateOf(false) }
    val click = {
        openExternally(ctx, uri, title); onDone()
    }
    if (label == null) {
        OutlinedIconButton(onClick = click) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open in another player") }
    } else {
        OutlinedButton(onClick = click, shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, null)
            Spacer(Modifier.width(8.dp))
            Text(label)
        }
    }
    if (ask) {
        AlertDialog(
            onDismissRequest = { ask = false },
            title = { Text("Open in another player?") },
            text = { Text("This sends the login for this share to the player you choose, so it can open the file.") },
            confirmButton = {
                TextButton(onClick = { ask = false; openExternally(ctx, uri, title); onDone() }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { ask = false }) { Text("Cancel") } }
        )
    }
}
/** Set when one player screen replaces another, so the old one doesn't reset orientation/system bars. */
internal var keepPlayerWindow = false
/** Lets the TV player see remote-control keys before the rest of the screen does. */
object PlayerKeys { var handler: ((android.view.KeyEvent) -> Boolean)? = null }

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(vm: LibraryViewModel, nav: NavController, type: String, id: Long, onUseVlc: (() -> Unit)? = null, onExoFail: (() -> Unit)? = null) {
    val onExoFailNow by rememberUpdatedState(onExoFail)
    val ctx = LocalContext.current
    val view = LocalView.current
    val tv = LocalIsTv.current
    val fh = if (tv) Modifier.focusHighlight(CircleShape) else Modifier   // visible focus ring for the remote
    var req by remember { mutableStateOf<PlayRequest?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var controls by remember { mutableStateOf(false) }
    var locals by remember { mutableStateOf<List<SubStore.Local>>(emptyList()) }
    var showSubs by remember { mutableStateOf(false) }
    var resumeAfter by remember { mutableStateOf(false) }

    // Video Quality Selection States
    var qualityMenu by remember { mutableStateOf(false) }
    var audioMenu by remember { mutableStateOf(false) }
    var audioList by remember { mutableStateOf<List<AudioOption>>(emptyList()) }
    var availableQualities by remember { mutableStateOf<List<QualityOption>>(emptyList()) }
    var currentQualityLabel by remember { mutableStateOf("Auto") }
    var attempt by remember { mutableIntStateOf(0) }      // automatic reconnect tries so far
    var speed by remember { mutableFloatStateOf(1f) }     // chosen with the speed button

    // Resize mode state: Default is RESIZE_MODE_FIT
    var resizeMode by remember { mutableIntStateOf(vm.prefs.videoResize.value) }
    // Rename
    val scope = rememberCoroutineScope()
    var renameOpen by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var renameBusy by remember { mutableStateOf(false) }
    var renameError by remember { mutableStateOf<String?>(null) }
    var resumeAfterRename by remember { mutableStateOf(false) }
    var extAsk by remember { mutableStateOf(false) }      // confirm before sending a share login to another app

    val autoplay by vm.prefs.autoplayNext.collectAsStateWithLifecycle()
    val subSize by vm.prefs.subSize.collectAsStateWithLifecycle()
    val subColor by vm.prefs.subColor.collectAsStateWithLifecycle()
    val subBg by vm.prefs.subBg.collectAsStateWithLifecycle()
    val subPos by vm.prefs.subPos.collectAsStateWithLifecycle()

    val defaultUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    val exo = remember {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(defaultUserAgent)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(
                mapOf(
                    "Accept" to "*/*",
                    "Connection" to "keep-alive"
                )
            )
        val dataSourceFactory = smbAwareFactory(ctx, httpDataSourceFactory)

        ExoPlayer.Builder(
            ctx,
            DefaultRenderersFactory(ctx).setEnableDecoderFallback(true),
            DefaultMediaSourceFactory(dataSourceFactory)
        )
            .setLoadControl(
            androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(30_000, 90_000, 2_000, 5_000)
                .setTargetBufferBytes(96 * 1024 * 1024)   // cap memory: 4K video would otherwise fill the app's heap and cause stutter
                .setPrioritizeTimeOverSizeThresholds(false)
                .build()
        ).build().also {
            it.shuffleModeEnabled = vm.prefs.shuffle.value
        }
    }
    val reqNow by rememberUpdatedState(req)
    val autoplayNow by rememberUpdatedState(autoplay)
    val controlsNow by rememberUpdatedState(controls)
    val attemptNow by rememberUpdatedState(attempt)
    val speedNow by rememberUpdatedState(speed)
    val act = ctx as? Activity
    val audioMgr = remember { ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager }
    val favIds by vm.favoriteIds.collectAsStateWithLifecycle()
    val isFav = type == "channel" && id in favIds

    val openSubs: () -> Unit = {
        resumeAfter = exo.playWhenReady
        exo.pause()
        showSubs = true
    }
    val reload: () -> Unit = {
        error = null
        attempt = 0
        exo.stop()
        exo.seekToDefaultPosition()
        exo.prepare()
        exo.playWhenReady = true
        Toast.makeText(ctx, "Reconnecting…", Toast.LENGTH_SHORT).show()
    }
    val openOther: () -> Unit = {
        val r = reqNow
        if (r != null) {
            exo.pause()
            openExternally(ctx, r.uri, r.title)
        }
    }
    val cycleSpeed: () -> Unit = {
        val steps = listOf(1f, 2f, 4f, 6f)
        val next = steps[(steps.indexOf(speed) + 1) % steps.size]
        speed = next
        exo.setPlaybackSpeed(next)
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

    fun saveNow(finished: Boolean = false) {
        val r = reqNow ?: return
        val key = r.progressKey ?: return
        val dur = exo.duration
        if (dur <= 0 || dur == C.TIME_UNSET) return
        vm.saveProgress(key, if (finished) dur else exo.currentPosition, dur)
    }
    val goTo: (Long) -> Unit = { nid ->
        saveNow()
        keepPlayerWindow = true
        nav.navigate("player/$type/$nid") { popUpTo("player/{type}/{id}") { inclusive = true } }
    }
    // Where ⏭ goes: the next file in order, or in shuffle mode a random other file from the same folder / series.
    fun nextTarget(): Long? {
        val r = reqNow ?: return null
        return if (exo.shuffleModeEnabled && r.pool.isNotEmpty()) r.pool.random() else r.skipNextId
    }
    // The controller's ⏮ ⏭ buttons call into this wrapper.
    val shownPlayer = remember(exo) {
        object : ForwardingPlayer(exo) {
            override fun getAvailableCommands(): Player.Commands {
                val b = super.getAvailableCommands().buildUpon()
                val r = reqNow
                val hasNext = r?.skipNextId != null || (exo.shuffleModeEnabled && r?.pool?.isNotEmpty() == true)
                val hasPrev = r?.skipPrevId != null
                if (hasNext) b.addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                else b.removeAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                if (hasPrev) b.addAll(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                else b.removeAll(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                // the shuffle button only makes sense when there are other files to pick from
                if (r?.pool?.isNotEmpty() == true) b.add(Player.COMMAND_SET_SHUFFLE_MODE)
                else b.remove(Player.COMMAND_SET_SHUFFLE_MODE)
                return b.build()
            }
            override fun isCommandAvailable(command: Int) = availableCommands.contains(command)
            override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
                super.setShuffleModeEnabled(shuffleModeEnabled)
                vm.prefs.setShuffle(shuffleModeEnabled)
                Toast.makeText(
                    ctx,
                    if (shuffleModeEnabled) "Shuffle: next plays a random file" else "Continuous: next plays the next file",
                    Toast.LENGTH_SHORT
                ).show()
            }
            override fun hasNextMediaItem() = availableCommands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            override fun hasPreviousMediaItem() = reqNow?.skipPrevId != null
            override fun seekToNext() { nextTarget()?.let(goTo) }
            override fun seekToNextMediaItem() { nextTarget()?.let(goTo) }
            override fun seekToPrevious() {
                // Like most players: restart if more than 3 s in, otherwise go to the previous file.
                val prev = reqNow?.skipPrevId
                if (prev == null || (type != "channel" && currentPosition > 3000)) seekTo(0) else goTo(prev)
            }
            override fun seekToPreviousMediaItem() { reqNow?.skipPrevId?.let(goTo) }
        }
    }
    var swipeLabel by remember { mutableStateOf<String?>(null) }

    fun reloadSubs(select: String?) {
        val r = reqNow ?: return
        val pos = exo.currentPosition
        val was = exo.playWhenReady
        val (cleanUrl, _) = parseStreamRequest(r.uri)
        val l = SubStore.list(ctx, cleanUrl)
        locals = l
        exo.setMediaItem(buildItem(r, l, select), pos)
        exo.prepare()
        exo.playWhenReady = was
    }

    LaunchedEffect(type, id) {
        req = vm.playRequest(type, id)
        if (req == null) error = "File not found"
    }
    LaunchedEffect(req) {
        if (type == "channel") req?.let { swipeLabel = it.title; delay(2000); swipeLabel = null }
    }

    LaunchedEffect(req) {
        val r = req ?: return@LaunchedEffect
        val (cleanUrl, streamHeaders) = parseStreamRequest(r.uri)
        val l = withContext(Dispatchers.IO) { SubStore.list(ctx, cleanUrl) }
        locals = l

        val userAgent = streamHeaders["User-Agent"]
            ?: streamHeaders["user-agent"]
            ?: defaultUserAgent

        val requestHeaders = mutableMapOf(
            "Accept" to "*/*",
            "Connection" to "keep-alive"
        )
        requestHeaders.putAll(streamHeaders)

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(requestHeaders)

        val customDataSourceFactory = smbAwareFactory(ctx, httpDataSourceFactory)

        val mediaItem = buildItem(r.copy(uri = cleanUrl), locals, null)

        val mediaSource = if (cleanUrl.contains(".m3u8", ignoreCase = true) || r.mime == MimeTypes.APPLICATION_M3U8) {
            HlsMediaSource.Factory(customDataSourceFactory)
                .setPlaylistParserFactory(SanitizedHlsPlaylistParserFactory())
                .createMediaSource(mediaItem)
        } else {
            DefaultMediaSourceFactory(customDataSourceFactory)
                .createMediaSource(mediaItem)
        }

        exo.setMediaSource(mediaSource, r.resumeMs)
        exo.prepare()
        exo.playWhenReady = true
    }

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                audioList = getAudioOptions(exo)
                availableQualities = getAvailableQualities(exo)
                val format = exo.videoFormat
                currentQualityLabel = if (format != null && format.height > 0) {
                    val hasOverride = exo.trackSelectionParameters.overrides.keys.any { group ->
                        tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.mediaTrackGroup == group }
                    }
                    if (hasOverride) "${format.height}p" else "Auto (${format.height}p)"
                } else {
                    "Auto"
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && type == "channel") { vm.markChannel(id, false); attempt = 0 }
                if (state == Player.STATE_ENDED) {
                    saveNow(true)
                    val next = if (exo.shuffleModeEnabled) (reqNow?.pool?.randomOrNull() ?: reqNow?.nextId) else reqNow?.nextId
                    if (next != null && autoplayNow) {
                        keepPlayerWindow = true
                        nav.navigate("player/$type/$next") { popUpTo("player/{type}/{id}") { inclusive = true } }
                    }
                }
            }
            override fun onPlayerError(e: PlaybackException) {
                if (type == "channel" && canRetry(e) && attemptNow < 3) {
                    attempt = attemptNow + 1       // the effect below waits, then reloads
                    return
                }
                onExoFailNow?.let { keepPlayerWindow = true; it(); return }   // Auto mode: let VLC try this channel
                if (type == "channel") vm.markChannel(id, true)
                val isWeb = reqNow?.uri?.startsWith("http", ignoreCase = true) == true
                error = when {
                    isWeb && e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
                        "This link is a web page, not a video file.\n\nPaste a direct link to the video itself (ending in .mp4, .mkv, .m3u8 …)."
                    e.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                        "This link didn't load. The server refused it or the link has expired."
                    e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                            e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        "This link couldn't be reached. Check your connection and try again."
                    else -> e.errorCodeName + generateSequence(e.cause) { it.cause }.mapNotNull { it.message }.joinToString("") { "\n→ $it" }
                }
            }
        }
        exo.addListener(listener)
        onDispose {
            saveNow()
            exo.removeListener(listener)
            exo.release()
        }
    }
    LaunchedEffect(attempt) {
        if (attempt in 1..3) {
            delay(2000L shl (attempt - 1))         // 2 s, 4 s, 8 s
            exo.stop()
            exo.seekToDefaultPosition()
            exo.prepare()
            exo.playWhenReady = true
        }
    }

    // Pause when the app goes to the background (Home button), so audio doesn't keep playing.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exo) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) { saveNow(); exo.pause() }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(exo) {
        while (true) {
            delay(5000)
            if (exo.isPlaying) saveNow()
        }
    }

    DisposableEffect(Unit) {
        val act = ctx as? Activity
        val ctrl = act?.window?.let { WindowCompat.getInsetsController(it, view) }
        act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        com.mymovieapp.app.data.Thumbs.playersOpen.incrementAndGet()     // pause thumbnail making while playing
        ctrl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctrl?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            com.mymovieapp.app.data.Thumbs.playersOpen.decrementAndGet()
            if (keepPlayerWindow) {
                keepPlayerWindow = false      // another video is taking over; stay landscape and fullscreen
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
    // Gesture navigation: show the home bar together with the player controls, hide it with them.
    LaunchedEffect(controls) {
        if (tv) return@LaunchedEffect
        val c = (ctx as? Activity)?.window?.let { WindowCompat.getInsetsController(it, view) }
            ?: return@LaunchedEffect
        if (controls) c.show(WindowInsetsCompat.Type.navigationBars())
        else c.hide(WindowInsetsCompat.Type.navigationBars())
    }

    val pvHolder = remember { arrayOfNulls<PlayerView>(1) }
    // TV remote. Keys are caught here (MainActivity passes them on first) so they work wherever focus is.
    DisposableEffect(tv, type) {
        val myHandler: (android.view.KeyEvent) -> Boolean = handler@{ e ->
            val pv = pvHolder[0]
            // Back while the controls are showing: just hide them, keep playing.
            if (pv != null && pv.isControllerFullyVisible && e.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                if (e.action == android.view.KeyEvent.ACTION_UP) pv.hideController()
                return@handler true
            }
            if (pv == null || pv.isControllerFullyVisible) return@handler false
            val k = e.keyCode
            val back = k == android.view.KeyEvent.KEYCODE_DPAD_LEFT || k == android.view.KeyEvent.KEYCODE_MEDIA_REWIND
            val fwd = k == android.view.KeyEvent.KEYCODE_DPAD_RIGHT || k == android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            when {
                (back || fwd) && type == "channel" -> {
                    // live TV: LEFT / RIGHT change channel
                    if (e.action == android.view.KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                        (if (fwd) reqNow?.skipNextId else reqNow?.skipPrevId)?.let(goTo)
                    }
                    true
                }
                (back || fwd) && type != "channel" -> {
                    if (e.action == android.view.KeyEvent.ACTION_DOWN) {
                        val dur = exo.duration
                        if (dur > 0 && dur != C.TIME_UNSET) {
                            val step = (10_000L * (1 + e.repeatCount / 3)).coerceAtMost(120_000L)
                            val to = (exo.currentPosition + if (fwd) step else -step).coerceIn(0L, dur)
                            exo.seekTo(to)
                            swipeLabel = "${swipeTime(to)}  (${if (fwd) "+" else "−"}${step / 1000}s)"
                        }
                    } else if (e.action == android.view.KeyEvent.ACTION_UP) swipeLabel = null
                    true
                }
                k == android.view.KeyEvent.KEYCODE_DPAD_CENTER || k == android.view.KeyEvent.KEYCODE_ENTER ||
                    k == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER || k == android.view.KeyEvent.KEYCODE_DPAD_UP ||
                    k == android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (e.action == android.view.KeyEvent.ACTION_DOWN) pv.showController()
                    true
                }
                else -> false
            }
        }
        if (tv) PlayerKeys.handler = myHandler
        // the previous video's screen may be removed after the next one starts: only clear our own handler
        onDispose { if (PlayerKeys.handler === myHandler) PlayerKeys.handler = null }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize().pointerInput(exo) {
                var total = 0f
                var startPos = 0L
                var target = 0L
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f; startPos = exo.currentPosition; target = startPos },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        total += dx
                        val dur = exo.duration
                        if (dur > 0 && dur != C.TIME_UNSET) {
                            // A swipe across the whole screen moves 2 minutes.
                            target = (startPos + (total / size.width * 120_000f).toLong()).coerceIn(0L, dur)
                            val diff = target - startPos
                            swipeLabel = "${swipeTime(target)}  (${if (diff >= 0) "+" else "−"}${swipeTime(abs(diff))})"
                        }
                    },
                    onDragEnd = { if (swipeLabel != null) exo.seekTo(target); swipeLabel = null },
                    onDragCancel = { swipeLabel = null }
                )
            }.pointerInput(exo) {
                // Drag up/down: left half = brightness, right half = volume.
                var left = true
                var start = 0f
                var moved = 0f
                detectVerticalDragGestures(
                    onDragStart = { p ->
                        left = p.x < size.width / 2
                        moved = 0f
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
                        } else {
                            val max = audioMgr.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                            audioMgr.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (v * max).roundToInt(), 0)
                            swipeLabel = "Volume ${(v * 100).roundToInt()}%"
                        }
                    },
                    onDragEnd = { swipeLabel = null },
                    onDragCancel = { swipeLabel = null }
                )
            }.pointerInput(exo) {
                // Hold a finger on the picture to fast-forward: 2x, then 4x, then 6x the longer you hold.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        waitForUpOrCancellation()
                        true
                    }
                    if (released == null && !controlsNow && type != "channel") {
                        val steps = listOf(2f, 4f, 6f)
                        var level = 0
                        exo.setPlaybackSpeed(steps[0])
                        swipeLabel = "2×  speed"
                        var held = true
                        while (held) {
                            val up = if (level < steps.lastIndex)
                                withTimeoutOrNull(2000L) { waitForUpOrCancellation(); true }   // next step after 2 s
                            else { waitForUpOrCancellation(); true }
                            if (up != null) held = false
                            else {
                                level++
                                exo.setPlaybackSpeed(steps[level])
                                swipeLabel = "${steps[level].toInt()}×  speed"
                            }
                        }
                        exo.setPlaybackSpeed(speedNow)
                        swipeLabel = null
                    }
                }
            },
            factory = { c ->
                PlayerView(c).apply {
                    pvHolder[0] = this
                    player = shownPlayer
                    keepScreenOn = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                    post { requestFocus() }
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    controllerAutoShow = false        // don't pop up by itself when a video loads, pauses or ends
                    controllerShowTimeoutMs = if (tv) 6000 else 3000    // when you tap, hide again after 3 seconds
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> controls = v == View.VISIBLE })
                    setShowShuffleButton(true)        // Continuous / Shuffle toggle
                    // the ⚙ button (speed + audio) is not needed on the phone: the top bar has both. Keep it on TV.
                    findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.visibility = View.GONE

                }
            },
            update = { pv ->
                // Apply current resize mode
                pv.resizeMode = resizeMode
                pv.findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.visibility = View.GONE

                pv.subtitleView?.apply {
                    setFractionalTextSize(when (subSize) { 0 -> 0.045f; 1 -> 0.0533f; 2 -> 0.07f; else -> 0.09f })
                    setBottomPaddingFraction(when (subPos) { 0 -> 0.08f; 1 -> 0.16f; else -> 0.28f })
                    val fg = when (subColor) {
                        1 -> android.graphics.Color.rgb(255, 224, 102)
                        2 -> android.graphics.Color.rgb(128, 222, 234)
                        else -> android.graphics.Color.WHITE
                    }
                    setStyle(
                        CaptionStyleCompat(
                            fg,
                            if (subBg) android.graphics.Color.argb(160, 0, 0, 0) else android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT,
                            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                            android.graphics.Color.BLACK,
                            null
                        )
                    )
                }
            }
        )
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
        if (controls) {
            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent)))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { nav.popBackStack() }, modifier = fh) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Text(
                    text = req?.title.orEmpty(),
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "ExoPlayer", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .then(if (tv) Modifier.focusHighlight(RoundedCornerShape(6.dp)) else Modifier)
                        .clickable { onUseVlc?.let { saveNow(); keepPlayerWindow = true; it() } }
                        .background(Accent)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                if (audioList.size > 1) {
                    Box {
                        TextButton(onClick = { audioMenu = true }, modifier = fh) {
                            Icon(Icons.Filled.Audiotrack, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                audioList.firstOrNull { it.selected }?.label ?: "Audio",
                                color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        DropdownMenu(expanded = audioMenu, onDismissRequest = { audioMenu = false }) {
                            audioList.forEach { o ->
                                DropdownMenuItem(
                                    text = { Text(o.label, color = if (o.selected) Accent else Color.Unspecified) },
                                    onClick = { setAudioOption(exo, o); audioMenu = false }
                                )
                            }
                        }
                    }
                }
                if (type == "channel") {
                    IconButton(onClick = reload, modifier = Modifier.focusHighlight(CircleShape)) { Icon(Icons.Filled.Refresh, "Reload", tint = Color.White) }
                    IconButton(modifier = Modifier.focusHighlight(CircleShape), onClick = {
                        vm.toggleFavorite(id)
                        Toast.makeText(ctx, if (isFav) "Removed from favourites" else "Added to favourites", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(
                            if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            "Favourite",
                            tint = if (isFav) Danger else Color.White
                        )
                    }
                }
                if (type == "file" && req?.uri?.startsWith("ftp://") != true) {
                    IconButton(modifier = fh, onClick = {
                        scope.launch {
                            renameText = vm.videoById(id)?.fileName?.substringBeforeLast('.').orEmpty()
                            renameError = null
                            resumeAfterRename = exo.playWhenReady
                            exo.pause()
                            renameOpen = true
                        }
                    }) { Icon(Icons.Filled.Edit, "Rename", tint = Color.White) }
                }
                if (type != "channel") {
                    TextButton(onClick = cycleSpeed, modifier = fh, contentPadding = PaddingValues(horizontal = 10.dp)) {
                        Text(
                            "${speed.toInt()}×",
                            color = if (speed == 1f) Color.White else Accent,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                IconButton(onClick = toggleResizeMode, modifier = Modifier.focusHighlight(CircleShape)) { Icon(Icons.Filled.AspectRatio, "Resize", tint = Color.White) }
                Box {
                    TextButton(
                        onClick = { availableQualities = getAvailableQualities(exo); qualityMenu = true },
                        modifier = fh,
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Text(
                            if (currentQualityLabel.startsWith("Auto")) "Auto" else currentQualityLabel,
                            color = Color.White, fontWeight = FontWeight.SemiBold
                        )
                    }
                    DropdownMenu(expanded = qualityMenu, onDismissRequest = { qualityMenu = false }) {
                        availableQualities.forEach { o ->
                            val on = if (o.isAuto) currentQualityLabel.startsWith("Auto") else currentQualityLabel == o.label
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        qualityName(o),
                                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                        color = if (on) Accent else Color.Unspecified
                                    )
                                },
                                onClick = { setQualityOption(exo, o); qualityMenu = false }
                            )
                        }
                    }
                }
                IconButton(onClick = openOther, modifier = fh) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open in another player", tint = Color.White) }
                IconButton(onClick = openSubs, modifier = fh) { Icon(Icons.Filled.ClosedCaption, "Subtitles", tint = Color.White) }
            }
        }
        error?.let {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    if (it.startsWith("This link")) it
                    else "$it\n\nIf this is an audio/codec problem, the device may not support that track.",
                    color = Color.White, textAlign = TextAlign.Center
                )
                if (type == "channel") {
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = reload) { Text("Try again") }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = openOther) { Text("Open in another player") }
            }
        }
    }



    reqNow?.let { r ->
        if (showSubs) SubtitleDialog(
            vm = vm, exo = exo, req = r, locals = locals,
            onReload = { reloadSubs(it) },
            onDismiss = { showSubs = false; if (resumeAfter) exo.play() }
        )
    }
    if (renameOpen) {
        AlertDialog(
            onDismissRequest = {
                if (!renameBusy) { renameOpen = false; if (resumeAfterRename) exo.play() }
            },
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
                        val pos = exo.currentPosition
                        saveNow()
                        exo.stop()                      // release the file so the share/provider lets go of it
                        renameBusy = true
                        renameError = null
                        vm.renameVideo(id, renameText) { ok, msg ->
                            renameBusy = false
                            scope.launch {
                                // reload with the (possibly new) address and carry on from the same spot
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
                    onClick = { renameOpen = false; if (resumeAfterRename) exo.play() }
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