package com.mymovieapp.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymovieapp.app.LibraryViewModel
import com.mymovieapp.app.data.CAST_PORT
import com.mymovieapp.app.data.CastSender
import com.mymovieapp.app.data.CastTv
import com.mymovieapp.app.data.GCast
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Phone only: pick a TV on the home network, optionally send what is playing, then use the phone as a remote.
 * [item] = what to play on the TV now (null = just open the remote).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CastDialog(vm: LibraryViewModel, item: LibraryViewModel.CastItem?, isChannel: Boolean, onHandoff: ((CastTv, String) -> Unit)? = null, pad: Boolean = false, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tvs by CastSender.tvs.collectAsStateWithLifecycle()
    val gtvs by GCast.devices.collectAsStateWithLifecycle()
    var gOk by remember { mutableStateOf(true) }
    GCast.subSize = vm.prefs.subSize.value
    var connected by remember { mutableStateOf<CastTv?>(null) }
    var pinFor by remember { mutableStateOf<CastTv?>(null) }
    var pinText by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pinUsed by remember { mutableStateOf("") }
    var pos by remember { mutableStateOf(0L) }
    var len by remember { mutableStateOf(0L) }
    var tvPlaying by remember { mutableStateOf(false) }
    var tvTitle by remember { mutableStateOf("") }
    var drag by remember { mutableStateOf<Float?>(null) }
    var polled by remember { mutableStateOf(false) }
    var showDigits by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        CastSender.startDiscovery(ctx)
        gOk = GCast.startDiscovery(ctx)
        onDispose { CastSender.stopDiscovery(); GCast.stopDiscovery() }
    }
    // The remote is easier to use upright: hold the phone in portrait while it is open, then put the old setting back.
    DisposableEffect(Unit) {
        var c: android.content.Context? = ctx
        while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
        val act = c as? android.app.Activity
        val old = act?.requestedOrientation
        act?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { if (old != null) act.requestedOrientation = old }
    }

    val pickSub = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            val name = ctx.contentResolver.query(u, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME).let { i -> if (i >= 0) c.getString(i) else null } else null } ?: "subtitle.srt"
            val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { ctx.contentResolver.openInputStream(u)?.use { it.readBytes() } }.getOrNull() }
            val tvNow = connected
            status = if (bytes == null) "Couldn't read that file" else if (tvNow == null) "Not connected" else { status = "Sending subtitles…"; CastSender.sendSubtitle(tvNow, pinUsed, bytes, name).second.ifBlank { "Subtitles added" } }
        }
    }
    fun connect(tv: CastTv, pin: String) {
        busy = true; status = "Connecting to ${tv.name}…"
        scope.launch {
            val (ok, msg) = CastSender.post(tv, pin, "/ping")
            if (!ok) { status = msg; busy = false; pinFor = if (msg.contains("PIN")) tv else null; return@launch }
            if (tv.kind == "app") CastSender.savePin(ctx, tv, pin)
            pinUsed = pin
            if (item != null) {
                // a file on this phone: the phone serves it over Wi-Fi and the app TV plays that link
                val viaPhone = tv.kind == "app" && item.mode == "local"
                val sendUri = if (viaPhone) { com.mymovieapp.app.data.LanStream.init(ctx); com.mymovieapp.app.data.LanStream.urlForMedia(item.uri, item.title) } else item.uri
                if (sendUri == null) { status = "This phone isn't on Wi-Fi"; busy = false; return@launch }
                val body = JSONObject().put("uri", sendUri).put("title", item.title).put("mode", if (viaPhone) "url" else item.mode).put("pos", item.posMs)
                body.put("live", isChannel)
                if (onHandoff != null) body.put("handoff", true)
                val (ok2, msg2) = CastSender.post(tv, pin, "/play", body)
                if (ok2 && onHandoff != null) { onHandoff(tv, pin); busy = false; onClose(); return@launch }
                status = if (ok2) "Playing on ${tv.name}" else msg2
            } else status = "Connected to ${tv.name}"
            connected = tv; busy = false
        }
    }
    fun cmd(a: String) {
        val tv = connected ?: return
        scope.launch {
            val (ok, msg) = CastSender.post(tv, pinUsed, "/cmd", JSONObject().put("action", a))
            if (!ok) status = msg
        }
    }

    // While the remote is open, ask the TV where playback is (about once a second) for the seek bar.
    LaunchedEffect(connected) {
        val tv = connected ?: return@LaunchedEffect
        if (isChannel) return@LaunchedEffect
        while (true) {
            val st = CastSender.statusOf(tv, pinUsed)
            polled = true
            if (st != null && drag == null) {
                pos = st.optLong("pos"); len = st.optLong("len"); tvPlaying = st.optBoolean("playing"); tvTitle = st.optString("title")
            }
            kotlinx.coroutines.delay(1000)
        }
    }
    AlertDialog(
        modifier = Modifier.dialogWidth(),
        onDismissRequest = onClose,
        title = {
            Column {
                Text(if (connected != null) "TV remote" else "Play on TV")
                // The seek bar sits up here so it never scrolls out of view in the small landscape dialog.
                if (connected != null && !isChannel) {
                    if (tvTitle.isNotBlank()) Text(tvTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Muted, style = MaterialTheme.typography.bodySmall)
                    if (len > 0) {
                        val shown = drag?.let { (it * len).toLong() } ?: pos
                        Slider(
                            value = drag ?: (pos.toFloat() / len).coerceIn(0f, 1f),
                            onValueChange = { drag = it },
                            onValueChangeFinished = { drag?.let { f -> cmd("seek:${(f * len).toLong()}"); pos = (f * len).toLong() }; drag = null },
                            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)
                        )
                        Row(Modifier.fillMaxWidth()) {
                            Text(castTime(shown), color = Muted, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.weight(1f))
                            Text(castTime(len), color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val c = connected
                if (c == null && pinFor == null) {
                    if (item != null) Text(titleWithType(item.uri, item.title), maxLines = 4, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text("TVs with this app open (full remote)", color = Muted, style = MaterialTheme.typography.bodySmall)
                    if (tvs.isEmpty()) Text("None found. Open the app on the TV, same Wi-Fi.", color = Muted)
                    tvs.forEach { tv ->
                        TextButton(
                            enabled = !busy, modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val saved = CastSender.pinFor(ctx, tv)
                                if (saved != null) connect(tv, saved) else { pinFor = tv; pinText = ""; status = null }
                            }
                        ) { Text(tv.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    HorizontalDivider()
                    Text("Cast devices, no app needed (play, seek, volume, subtitles)", color = Muted, style = MaterialTheme.typography.bodySmall)
                    if (!gOk) Text("Casting isn't available on this phone.", color = Muted)
                    else if (gtvs.isEmpty()) Text("Looking for Chromecast / Cast-enabled TVs…", color = Muted)
                    gtvs.forEach { tv ->
                        TextButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { connect(tv, "") }) {
                            Text(tv.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    HorizontalDivider()
                    Text("Not listed? Type the TV's address (shown in the TV's Settings)", color = Muted, style = MaterialTheme.typography.bodySmall)
                    AppTextField(manual, { manual = it }, singleLine = true, placeholder = { Text("192.168.0.171") }, modifier = Modifier.fillMaxWidth())
                    TextButton(enabled = manual.isNotBlank() && !busy, onClick = {
                        val ip = manual.trim()
                        val tv = CastTv("TV $ip", ip, CAST_PORT)
                        val saved = CastSender.pinFor(ctx, tv)
                        if (saved != null) connect(tv, saved) else { pinFor = tv; pinText = ""; status = null }
                    }) { Text("Connect") }
                } else if (c == null) {
                    val tv = pinFor!!
                    Text("Enter the PIN shown in ${tv.name}'s Settings → Cast from phone")
                    AppTextField(
                        pinText, { pinText = it.filter { ch -> ch.isDigit() }.take(8) }, singleLine = true,
                        label = { Text("PIN") }, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword)
                    )
                    Row {
                        TextButton(onClick = { pinFor = null; status = null }) { Text("Back") }
                        TextButton(enabled = pinText.length >= 4 && !busy, onClick = { connect(tv, pinText); }) { Text("Connect") }
                    }
                } else if (c.kind == "gcast") {
                    Text(c.name, color = Muted, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { cmd("back10") }, Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text("−10 s") }
                        FilledTonalButton(onClick = { cmd("playpause") }, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp)); Icon(Icons.Filled.Pause, null, Modifier.size(20.dp))
                        }
                        FilledTonalButton(onClick = { cmd("fwd10") }, Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text("+10 s") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { cmd("voldown") }, Modifier.weight(1f)) { Text("Vol −") }
                        FilledTonalButton(onClick = { cmd("volup") }, Modifier.weight(1f)) { Text("Vol +") }
                    }
                    FilledTonalButton(onClick = { pickSub.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) { Text("Subtitles from this phone…") }
                    OutlinedButton(onClick = { cmd("spu:-1") }, Modifier.fillMaxWidth()) { Text("Subtitles off") }
                    Text("This TV runs no copy of the app, so arrows, OK, Home and number keys aren't available.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { cmd("stop"); onClose() }, Modifier.fillMaxWidth()) { Text("Stop and disconnect") }
                } else {
                    Text(c.name, color = Muted, style = MaterialTheme.typography.bodySmall)
                    val arrow = Modifier.size(width = 84.dp, height = 56.dp)
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { cmd("up") }, arrow, contentPadding = PaddingValues(0.dp)) { Text("▲", fontSize = 22.sp) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalButton(onClick = { cmd("left") }, arrow, contentPadding = PaddingValues(0.dp)) { Text("◀", fontSize = 22.sp) }
                            Box(
                                Modifier.size(width = 84.dp, height = 56.dp).clip(RoundedCornerShape(50)).background(Accent)
                                    .combinedClickable(onClick = { cmd("ok") }, onLongClick = { cmd("okhold") }),
                                contentAlignment = Alignment.Center
                            ) { Text("OK", color = OnAccent, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
                            FilledTonalButton(onClick = { cmd("right") }, arrow, contentPadding = PaddingValues(0.dp)) { Text("▶", fontSize = 22.sp) }
                        }
                        FilledTonalButton(onClick = { cmd("down") }, arrow, contentPadding = PaddingValues(0.dp)) { Text("▼", fontSize = 22.sp) }
                    }
                    Text("Hold OK for the long-press menu", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { cmd("back") }, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Back", maxLines = 1, softWrap = false)
                        }
                        FilledTonalButton(onClick = { cmd("home") }, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Icon(Icons.Filled.Home, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Home", maxLines = 1, softWrap = false)
                        }
                        FilledTonalButton(onClick = { cmd("playpause") }, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp)); Icon(Icons.Filled.Pause, null, Modifier.size(20.dp))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { cmd("vprev") }, Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text("⏮") }
                        FilledTonalButton(onClick = { cmd("back10") }, Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text("−10 s") }
                        FilledTonalButton(onClick = { cmd("fwd10") }, Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text("+10 s") }
                        FilledTonalButton(onClick = { cmd("vnext") }, Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text("⏭") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { cmd("voldown") }, Modifier.weight(1f)) { Text("Vol −") }
                        FilledTonalButton(onClick = { cmd("volup") }, Modifier.weight(1f)) { Text("Vol +") }
                    }
                    TextButton(onClick = { showDigits = !showDigits }, Modifier.fillMaxWidth()) { Text(if (showDigits) "Hide number pad" else "Number pad (channels)") }
                    if (showDigits) listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9), listOf(-1, 0, -1)).forEach { r ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            r.forEach { d ->
                                if (d < 0) Spacer(Modifier.weight(1f))
                                else OutlinedButton(onClick = { cmd("d$d") }, Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("$d") }
                            }
                        }
                    }
                    FilledTonalButton(onClick = { pickSub.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) { Text("Subtitles from this phone…") }
                    OutlinedButton(onClick = { cmd("stop") }, Modifier.fillMaxWidth()) { Text("Stop playing on TV") }
                }
                status?.let { Text(it, color = if (connected != null) Muted else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text(if (connected != null) "Close" else "Cancel") } }
    )
}

/** The file type at the end of a video's name or address ("mkv"), or "" when there is none. */
internal fun fileTypeOf(uri: String, title: String): String {
    fun ext(x: String): String {
        val seg = x.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        val e = android.net.Uri.decode(seg).substringAfterLast('.', "")
        return if (e.length in 2..5 && e.all { it.isLetterOrDigit() }) e.lowercase() else ""
    }
    return ext(uri).ifEmpty { ext(title) }
}
/** A video's name with its type in brackets, so a long name never hides what kind of file it is. */
internal fun titleWithType(uri: String, title: String): String {
    val t = fileTypeOf(uri, title)
    return if (t.isEmpty()) title else "$title  [.$t]"
}

private fun castTime(ms: Long): String {
    val t = ms / 1000
    val h = t / 3600; val m = (t % 3600) / 60; val sec = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
