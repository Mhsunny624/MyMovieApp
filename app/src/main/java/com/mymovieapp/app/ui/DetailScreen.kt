package com.mymovieapp.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.mymovieapp.app.*
import com.mymovieapp.app.data.*

private fun pickResume(videos: List<Video>, progress: Map<String, Progress>): Video? {
    if (videos.isEmpty()) return null
    val inProgress = videos.filter { v -> progress[v.uri]?.let { !it.isWatched() && it.positionMs > 5000 } == true }
        .maxByOrNull { progress[it.uri]!!.updatedAt }
    if (inProgress != null) return inProgress
    val last = videos.indexOfLast { progress[it.uri]?.isWatched() == true }
    return if (last >= 0 && last + 1 < videos.size) videos[last + 1] else videos.first()
}

@Composable
fun DetailScreen(vm: LibraryViewModel, nav: NavController, id: Long) {
    val title by remember(id) { vm.title(id) }.collectAsStateWithLifecycle(initialValue = null)
    val videos by remember(id) { vm.videos(id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val progress by vm.progress.collectAsStateWithLifecycle()
    val t = title ?: return

    var matchOpen by remember { mutableStateOf(false) }
    var renameId by remember { mutableStateOf<Long?>(null) }
    var actionVideo by remember { mutableStateOf<Video?>(null) }
    var deleteVideo by remember { mutableStateOf<Video?>(null) }
    var season by rememberSaveable(id) { mutableIntStateOf(-1) }
    val seasons = videos.mapNotNull { it.season }.distinct().sorted()
    val activeSeason = if (season in seasons) season else (seasons.firstOrNull() ?: 0)
    var epInfo by remember { mutableStateOf<Map<Int, Tmdb.Ep>>(emptyMap()) }
    LaunchedEffect(t.tmdbId, activeSeason) {
        epInfo = if (t.isSeries) vm.episodeInfo(t, activeSeason) else emptyMap()
    }

    val resume = pickResume(videos, progress)
    val label = resume?.let { v ->
        val p = progress[v.uri]
        val ep = if (t.isSeries) " S${v.season ?: 0}E${v.episode ?: 0}" else ""
        if (p != null && !p.isWatched() && p.positionMs > 5000) "Resume$ep · ${fmtTime(p.positionMs)}" else "Play$ep"
    } ?: "No files"
    val meta = listOfNotNull(
        t.year?.toString(),
        if (t.isSeries) "${videos.size} episodes" else null,
        t.rating?.let { "★ %.1f".format(it) }
    ).joinToString(" · ")

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).background(CardBg)) {
                val bg = t.backdropUrl ?: t.posterUrl
                if (bg != null) AsyncImage(model = bg, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color(0x66000000), Color.Transparent, Bg))))
                IconButton(onClick = { nav.popBackStack() }, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 20.dp)) {
                Poster(t.posterUrl, t.name, Modifier.width(100.dp))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(t.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                    Text(meta, color = Muted, modifier = Modifier.padding(top = 4.dp))
                    if (!t.matched) Text("Not matched yet", color = Accent, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        item {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { resume?.let { nav.navigate("player/file/${it.id}") } },
                    enabled = resume != null, shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f).height(52.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(label, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = { matchOpen = true }) { Icon(Icons.Filled.Edit, "Fix match") }
                if (!t.isSeries && videos.size == 1) {
                    IconButton(onClick = { renameId = videos[0].id }) { Icon(Icons.Filled.DriveFileRenameOutline, "Rename file") }
                }

            }
        }
        t.overview?.let { o -> item { Text(o, modifier = Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyLarge) } }
        t.castList?.let { c ->
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Text("Cast", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(c, color = Muted, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        if (t.isSeries) {
            if (seasons.size > 1) item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    seasons.forEach { s ->
                        FilterChip(selected = s == activeSeason, onClick = { season = s }, label = { Text("Season $s") })
                    }
                }
            }
            val eps = videos.filter { (it.season ?: 0) == activeSeason }
            items(eps, key = { it.id }) { v ->
                EpisodeRow(v, epInfo[v.episode], progress[v.uri], { actionVideo = v }) { nav.navigate("player/file/${v.id}") }
            }
        } else if (videos.size > 1) {
            item { Text("Files", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
            items(videos, key = { it.id }) { v ->
                EpisodeRow(v, null, progress[v.uri], { actionVideo = v }) { nav.navigate("player/file/${v.id}") }
            }
        }
    }

    actionVideo?.let { v ->
        AlertDialog(
            modifier = Modifier.okGuard(),
            onDismissRequest = { actionVideo = null },
            title = { Text(v.fileName, maxLines = 2) },
            text = {
                OkGuardWindow()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { renameId = v.id; actionVideo = null },
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Rename file") }
                    OpenElsewhereButton(v.uri, t.name, label = "Open in another player") { actionVideo = null }
                    OutlinedButton(
                        onClick = { deleteVideo = v; actionVideo = null },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Delete file") }
                }
            },
            confirmButton = { TextButton(onClick = { actionVideo = null }) { Text("Close") } }
        )
    }
    deleteVideo?.let { v ->
        DeleteFileDialog(vm, v.id, v.fileName) { ok ->
            deleteVideo = null
            if (ok && videos.size <= 1) nav.popBackStack()
        }
    }
    renameId?.let { RenameDialog(vm, it) { renameId = null } }
    if (matchOpen) MatchDialog(vm, t) { matchOpen = false }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(v: Video, info: Tmdb.Ep?, p: Progress?, onLongClick: () -> Unit, onClick: () -> Unit) {
    val tvKeys = LocalIsTv.current
    Row(Modifier.fillMaxWidth().rememberFocus("v${v.id}").focusHighlight().tvLongPress(onLongClick).combinedClickable(onClick = onClick, onLongClick = if (tvKeys) null else onLongClick).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(CardBg)) {
            info?.stillUrl?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.Center))
            DurationBadge(v.durationMs, Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 8.dp))
            if (p != null && p.durationMs > 0) LinearProgressIndicator(
                progress = { (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f) },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = Accent, trackColor = Color(0x66000000)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val head = if (v.episode != null) "${v.episode}. ${info?.name ?: v.fileName}" else v.fileName
            Text(head, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(info?.overview ?: v.fileName, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (p?.isWatched() == true) Icon(Icons.Filled.Check, "Watched", tint = Accent)
    }
}
