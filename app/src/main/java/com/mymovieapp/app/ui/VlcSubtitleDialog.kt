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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymovieapp.app.LibraryViewModel
import com.mymovieapp.app.PlayRequest
import com.mymovieapp.app.data.SubStore
import com.mymovieapp.app.data.SubtitleResult
import kotlinx.coroutines.launch
import java.io.File

/** One subtitle track the VLC player currently knows about. */
class SpuChoice(val id: Int, val name: String, val selected: Boolean)

/** Lets the player screen carry the position across a restart that applies new subtitle style (links and channels have no saved progress). */
object StyleRestart {
    var key: String? = null
    var pos: Long = 0L
}

@Composable
private fun VSection(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun VPickRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).focusHighlight().clickable(onClick = onClick)
            .background(CardBg).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (selected) Icon(Icons.Filled.Check, "Selected", tint = Accent)
    }
}

/** The VLC player's subtitle panel: pick a track, search + download, adjust timing and style. */
@Composable
fun VlcSubtitleDialog(
    vm: LibraryViewModel,
    req: PlayRequest,
    clean: String,
    tracks: List<SpuChoice>,
    locals: List<SubStore.Local>,
    delayUs: Long,
    onPickTrack: (Int) -> Unit,
    onUseFile: (File) -> Unit,
    onDownloaded: () -> Unit,
    onDeleted: () -> Unit,
    onDelay: (Long) -> Unit,
    onStyleChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var lang by remember { mutableStateOf(vm.prefs.subLang.value) }
    var results by remember { mutableStateOf<List<SubtitleResult>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf<String?>(null) }
    val q = req.query

    // VLC reads the subtitle style when it starts, so a change is applied by restarting the video when this panel closes
    val size by vm.prefs.subSize.collectAsStateWithLifecycle()
    val color by vm.prefs.subColor.collectAsStateWithLifecycle()
    val pos by vm.prefs.subPos.collectAsStateWithLifecycle()
    val bg by vm.prefs.subBg.collectAsStateWithLifecycle()
    val first = remember { listOf(size, color, pos, if (bg) 1 else 0) }
    LaunchedEffect(size, color, pos, bg) {
        if (listOf(size, color, pos, if (bg) 1 else 0) != first) onStyleChanged()
    }

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

                item { VSection("On screen") }
                item { VPickRow("Off", tracks.none { it.selected }) { onPickTrack(-1) } }
                items(tracks, key = { it.id }) { t -> VPickRow(t.name, t.selected) { onPickTrack(t.id) } }
                if (tracks.isEmpty()) item { Text("This video has no subtitle tracks yet. Download one below.", color = Muted) }

                item { VSection("Download subtitles") }
                if (q == null) {
                    item { Text("Subtitle search isn't available for this video.", color = Muted) }
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
                    item { Text("Searching for: ${q.title}" + (q.year?.let { " ($it)" } ?: "") +
                        (if (q.season != null && q.episode != null) "  S${q.season}E${q.episode}" else ""), color = Muted, style = MaterialTheme.typography.bodySmall) }
                    status?.let { s -> item { Text(s, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
                    items(results, key = { it.provider + it.id }) { r ->
                        val busyThis = downloading == r.provider + r.id
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).focusHighlight()
                                .clickable(enabled = downloading == null) {
                                    downloading = r.provider + r.id
                                    scope.launch {
                                        val err = vm.downloadSubtitle(clean, r, q.copy(language = lang))
                                        downloading = null
                                        if (err != null) status = err else onDownloaded()
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

                if (locals.isNotEmpty()) {
                    item { VSection("Downloaded for this video") }
                    items(locals, key = { it.file.name }) { l ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBg).padding(12.dp)) {
                            Text("${l.lang} · ${l.label}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { onUseFile(l.file) }) { Text("Use") }
                                IconButton(onClick = { SubStore.delete(l.file); onDeleted() }) { Icon(Icons.Filled.Delete, "Delete") }
                            }
                        }
                    }
                }

                item { VSection("Timing") }
                item {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBg).padding(12.dp)) {
                        Text(
                            if (delayUs == 0L) "In sync" else "Shifted %+.1f s".format(delayUs / 1_000_000.0),
                            color = Muted, style = MaterialTheme.typography.labelMedium
                        )
                        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { onDelay(delayUs - 500_000L) }) { Text("−0.5 s (earlier)") }
                            TextButton(onClick = { onDelay(delayUs + 500_000L) }) { Text("+0.5 s (later)") }
                            TextButton(enabled = delayUs != 0L, onClick = { onDelay(0L) }) { Text("Reset") }
                        }
                    }
                }

                item { VSection("Style") }
                item { SubtitleStyleControls(vm) }
                item { Text("A style change is applied when you close this panel. The video restarts at the same place.", color = Muted, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
