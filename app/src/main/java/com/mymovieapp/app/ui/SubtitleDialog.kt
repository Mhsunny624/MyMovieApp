package com.mymovieapp.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import java.io.File
import kotlin.math.abs
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import com.mymovieapp.app.LibraryViewModel
import com.mymovieapp.app.PlayRequest
import com.mymovieapp.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

val SUB_LANGS = listOf(
    "EN" to "English", "ES" to "Spanish", "FR" to "French", "DE" to "German", "IT" to "Italian",
    "PT" to "Portuguese", "BR_PT" to "Portuguese (Brazil)", "AR" to "Arabic", "BN" to "Bengali",
    "HI" to "Hindi", "RU" to "Russian", "TR" to "Turkish", "ZH" to "Chinese", "JA" to "Japanese",
    "KO" to "Korean", "ID" to "Indonesian", "NL" to "Dutch", "PL" to "Polish"
)

private fun trackLabel(g: Tracks.Group, i: Int): String {
    val f = g.getTrackFormat(0)
    return f.label
        ?: f.language?.let { l -> Locale.forLanguageTag(l).displayLanguage.ifBlank { l } }
        ?: "Track ${i + 1}"
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun PickRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).focusHighlight().clickable(onClick = onClick)
            .background(CardBg).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (selected) Icon(Icons.Filled.Check, "Selected", tint = Accent)
    }
}

@Composable
fun SubtitleDialog(
    vm: LibraryViewModel,
    exo: Player,
    req: PlayRequest,
    locals: List<SubStore.Local>,
    onReload: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    // ---- on-screen tracks ----
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(exo) {
        val l = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) { tick++ }
            override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) { tick++ }
        }
        exo.addListener(l)
        onDispose { exo.removeListener(l) }
    }
    val tracks = remember(tick) { exo.currentTracks }
    val textOff = remember(tick) { C.TRACK_TYPE_TEXT in exo.trackSelectionParameters.disabledTrackTypes }
    val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }

    // ---- download ----
    var lang by remember { mutableStateOf(vm.prefs.subLang.value) }
    var results by remember { mutableStateOf<List<SubtitleResult>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf<String?>(null) }
    var shifts by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var syncing by remember { mutableStateOf<Pair<SubStore.Local, Long>?>(null) }
    val q = req.query

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp), color = CardBg,
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f).widthIn(max = 680.dp)
        ) {
            LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Subtitles", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close") }
                    }
                }

                // 1. what's showing
                item { SectionTitle("On screen") }
                item { PickRow("Off", textOff || groups.none { it.isSelected }) {
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                } }
                itemsIndexed(groups) { i, g ->
                    PickRow(trackLabel(g, i), g.isSelected && !textOff) {
                        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build()
                    }
                }
                if (groups.isEmpty()) item { Text("This video has no subtitle tracks yet. Download one below.", color = Muted) }

                // 2. download
                item { SectionTitle("Download subtitles") }
                if (q == null) {
                    item { Text("Subtitle search works for videos in your library, not links or channels.", color = Muted) }
                } else {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            MenuChip(
                                SUB_LANGS.firstOrNull { it.first == lang }?.second ?: lang,
                                Icons.Filled.KeyboardArrowDown, SUB_LANGS, { it.second }
                            ) { lang = it.first; vm.prefs.setSubLang(it.first) }
                            Button(
                                enabled = !searching, shape = RoundedCornerShape(14.dp),
                                onClick = {
                                    searching = true; status = null; results = emptyList()
                                    scope.launch {
                                        val out = vm.searchSubtitles(q.copy(language = lang))
                                        results = out.results; status = out.message; searching = false
                                    }
                                }
                            ) { Icon(Icons.Filled.Search, null); Spacer(Modifier.width(6.dp)); Text(if (searching) "Searching…" else "Search") }
                        }
                    }
                    status?.let { s -> item { Text(s, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
                    items(results, key = { it.provider + it.id }) { r ->
                        val busyThis = downloading == r.provider + r.id
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).focusHighlight()
                                .clickable(enabled = downloading == null) {
                                    downloading = r.provider + r.id
                                    scope.launch {
                                        val err = vm.downloadSubtitle(req.uri, r, q.copy(language = lang))
                                        downloading = null
                                        if (err != null) status = err else { onReload(null); onDismiss() }
                                    }
                                }
                                .background(CardBg).padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(r.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    listOfNotNull(vm.prefs.subSources.value.firstOrNull { it.id == r.provider }?.label, r.note).joinToString(" · "),
                                    color = Muted, style = MaterialTheme.typography.labelSmall
                                )
                            }
                            if (busyThis) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Accent)
                            else Icon(Icons.Filled.Download, "Download", tint = Muted)
                        }
                    }
                }
                if (req.subtitleUri != null && locals.isEmpty()) {
                    item { SectionTitle("Subtitle file next to the video") }
                    item {
                        var copying by remember { mutableStateOf(false) }
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBg).padding(12.dp)) {
                            Text(
                                "To change its timing, make a copy the app can shift. The original file is not touched.",
                                color = Muted, style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(enabled = !copying, onClick = {
                                copying = true
                                scope.launch {
                                    val err = withContext(Dispatchers.IO) { copySidecar(ctx, req) }
                                    copying = false
                                    if (err != null) android.widget.Toast.makeText(ctx, err, android.widget.Toast.LENGTH_LONG).show()
                                    else { onReload(null); onDismiss() }
                                }
                            }) { Text(if (copying) "Copying…" else "Make adjustable copy") }
                        }
                    }
                }

                // 3. downloaded for this video
                if (locals.isNotEmpty()) {
                    item { SectionTitle("Downloaded for this video") }
                    items(locals, key = { it.file.name }) { l ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBg).padding(12.dp)) {
                            Text("${l.lang} · ${l.label}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val total = shifts[l.file.name] ?: 0L
                            if (total != 0L) Text("Shifted %+.1f s".format(total / 1000.0), color = Muted, style = MaterialTheme.typography.labelSmall)
                            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { onReload(l.file.name); onDismiss() }) { Text("Use") }
                                if (SubShift.supports(l.file)) {
                                    TextButton(onClick = { exo.pause(); syncing = l to exo.currentPosition }) { Text("Sync to line") }
                                    TextButton(onClick = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) { SubShift.apply(l.file, -500) }
                                            shifts = shifts + (l.file.name to total - 500); onReload(l.file.name)
                                        }
                                    }) { Text("−0.5 s") }
                                    TextButton(onClick = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) { SubShift.apply(l.file, 500) }
                                            shifts = shifts + (l.file.name to total + 500); onReload(l.file.name)
                                        }
                                    }) { Text("+0.5 s") }
                                }
                                IconButton(onClick = { SubStore.delete(l.file); onReload(null) }) { Icon(Icons.Filled.Delete, "Delete") }
                            }
                        }
                    }
                }

                // 4. look
                item { SectionTitle("Style") }
                item { SubtitleStyleControls(vm) }
            }
        }
    }

    syncing?.let { (l, pos) ->
        SyncDialog(l.file, pos, onApply = { delta ->
            scope.launch {
                withContext(Dispatchers.IO) { SubShift.apply(l.file, delta) }
                shifts = shifts + (l.file.name to (shifts[l.file.name] ?: 0L) + delta)
                syncing = null
                onReload(l.file.name)
            }
        }, onDismiss = { syncing = null })
    }
}   // <- end of SubtitleDialog

@Composable
private fun SyncDialog(file: File, positionMs: Long, onApply: (Long) -> Unit, onDismiss: () -> Unit) {
    var cues by remember { mutableStateOf<List<SubCues.Cue>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(file) { cues = withContext(Dispatchers.IO) { SubCues.parse(file) } }
    val shown = remember(cues, query) {
        val all = cues.orEmpty()
        val q = query.trim()
        if (q.isBlank()) all.sortedBy { abs(it.startMs - positionMs) }.take(40)
        else all.filter { it.text.contains(q, ignoreCase = true) }.take(40)
    }
    fun clock(ms: Long) = "%d:%02d:%02d".format(ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60)

    AlertDialog(
        modifier = Modifier.dialogWidth(),
        onDismissRequest = onDismiss,
        title = { Text("Sync to a line") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paused at ${clock(positionMs)}. Pick the subtitle line that was just spoken and the whole file will shift to match. " +
                            "Search for a few words if you don't see it.",
                    color = Muted, style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(query, { query = it }, label = { Text("Search lines") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (cues == null) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = Accent)
                else if (shown.isEmpty()) Text("No matching lines.", color = Muted)
                LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(shown) { c ->
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).focusHighlight()
                                .clickable { onApply(positionMs - c.startMs) }
                                .background(CardBg).padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(c.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            Text(clock(c.startMs), color = Muted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
@Composable
private fun StyleRow(label: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { i, o -> FilterChip(selected = selected == i, onClick = { onPick(i) }, label = { Text(o) }) }
        }
    }
}

/** Shared by the player's subtitle dialog and Settings -> Playback. Changes apply live. */
@Composable
fun SubtitleStyleControls(vm: LibraryViewModel) {
    val size by vm.prefs.subSize.collectAsStateWithLifecycle()
    val color by vm.prefs.subColor.collectAsStateWithLifecycle()
    val pos by vm.prefs.subPos.collectAsStateWithLifecycle()
    val bg by vm.prefs.subBg.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StyleRow("Size", listOf("Small", "Medium", "Large", "Extra large"), size) { vm.prefs.setSubSize(it) }
        StyleRow("Color", listOf("White", "Yellow", "Cyan"), color) { vm.prefs.setSubColor(it) }
        StyleRow("Position", listOf("Low", "Medium", "High"), pos) { vm.prefs.setSubPos(it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Dark background", Modifier.weight(1f))
            Switch(checked = bg, onCheckedChange = { vm.prefs.setSubBg(it) })
        }
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Raised).padding(vertical = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Subtitle preview",
                color = when (color) { 1 -> Color(0xFFFFE066); 2 -> Color(0xFF80DEEA); else -> Color.White },
                fontSize = when (size) { 0 -> 14.sp; 1 -> 18.sp; 2 -> 24.sp; else -> 30.sp },
                modifier = Modifier.background(if (bg) Color(0xA0000000) else Color.Transparent).padding(horizontal = 6.dp)
            )
        }
    }
}
/** Copies the subtitle file stored next to the video into app storage so its timing can be shifted. */
private fun copySidecar(ctx: android.content.Context, req: PlayRequest): String? {
    val s = req.subtitleUri ?: return "No subtitle file found"
    return try {
        val bytes: ByteArray = if (s.startsWith("smb://")) {
            val (h, sh, rel) = Smb.split(android.net.Uri.parse(s))
            Smb.share(h, sh).openFile(
                rel, java.util.EnumSet.of(com.hierynomus.msdtyp.AccessMask.GENERIC_READ), null,
                com.hierynomus.mssmb2.SMB2ShareAccess.ALL, com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OPEN, null
            ).use { f ->
                val out = java.io.ByteArrayOutputStream()
                f.inputStream.use { it.copyTo(out) }
                out.toByteArray()
            }
        } else if (s.startsWith("ftp://")) {
            Ftp.readBytes(android.net.Uri.parse(s))
        } else {
            ctx.contentResolver.openInputStream(android.net.Uri.parse(s))?.use { it.readBytes() }
                ?: return "Couldn't open the subtitle file"
        }
        val ext = if (s.contains(".vtt", ignoreCase = true)) "vtt" else "srt"
        SubStore.save(ctx, req.uri, "und", "from video folder", ext, SubText.decode(bytes, ""))
        null
    } catch (e: Exception) {
        e.message ?: "Couldn't copy the subtitle file"
    }
}