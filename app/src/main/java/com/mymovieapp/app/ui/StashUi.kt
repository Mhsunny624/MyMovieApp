package com.mymovieapp.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.mymovieapp.app.LibraryViewModel
import com.mymovieapp.app.data.PerformerEntry
import com.mymovieapp.app.data.StashImport
import com.mymovieapp.app.data.StashRow
import com.mymovieapp.app.data.Thumbs
import com.mymovieapp.app.data.isWatched
import java.io.File

/** The "Performers" tab of the library: everyone in the imported Stash data who has a video on an open source. */
@Composable
fun PerformersGrid(vm: LibraryViewModel, nav: NavController) {
    val all by vm.performers.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var gen by rememberSaveable { mutableStateOf(PerfGender.ALL) }
    val list = remember(all, query, gen) {
        all.filter { (gen.code == null || it.gender == gen.code) && (query.isBlank() || it.name.contains(query.trim(), ignoreCase = true)) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom) {
            Text("Performers", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.width(10.dp))
            Text("${list.size}", color = Muted, modifier = Modifier.padding(bottom = 6.dp))
            Spacer(Modifier.weight(1f))
            MenuChip(gen.label, Icons.Filled.KeyboardArrowDown, PerfGender.values().toList(), { it.label }) { gen = it }
        }
        if (!LocalIsTv.current) OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Search performers", color = Muted) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = Muted) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = CardBg, focusedContainerColor = CardBg,
                unfocusedBorderColor = Color.Transparent, focusedBorderColor = Accent
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
        )
        if (list.isEmpty()) {
            Text("No matches.", color = Muted, modifier = Modifier.padding(20.dp))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (LocalIsTv.current) 130.dp else 100.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(list, key = { it.id }) { p -> PerformerCard(p) { nav.navigate("performer/${p.id}") } }
            }
        }
    }
}

@Composable
private fun PerformerCard(p: PerformerEntry, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val photo = remember(p.id, p.hasPhoto) { if (p.hasPhoto) StashImport.photoFile(ctx, p.id).takeIf { it.exists() } else null }
    Column(
        Modifier.rememberFocus("perf${p.id}").focusHighlight().clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.fillMaxWidth(0.86f).aspectRatio(1f).clip(CircleShape).background(CardBg), contentAlignment = Alignment.Center) {
            if (photo != null) AsyncImage(model = photo, contentDescription = p.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text(
                if (p.id == 0) "?" else p.name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() },
                color = Muted, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(p.name, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
        Text(if (p.count == 1) "1 video" else "${p.count} videos", color = Muted, style = MaterialTheme.typography.labelSmall)
    }
}

private enum class PerfGender(val label: String, val code: String?) { ALL("All", null), MALE("Male", "M"), FEMALE("Female", "F"), TRANS("Trans", "T") }

private enum class PerfSort(val label: String) { RECENT("Newest first"), TITLE("Title A–Z"), RATING("Rating") }

/** One performer's videos. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PerformerScreen(vm: LibraryViewModel, nav: NavController, id: Int) {
    val rows by remember(id) { vm.performerVideos(id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val people by vm.performers.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    var sort by rememberSaveable { mutableStateOf(PerfSort.RECENT) }
    val name = people.firstOrNull { it.id == id }?.name ?: if (id == 0) "Unknown" else ""
    val list = remember(rows, sort) {
        when (sort) {
            PerfSort.RECENT -> rows.sortedWith(compareByDescending<StashRow> { it.date.orEmpty() }.thenBy { sceneName(it).lowercase() })
            PerfSort.TITLE -> rows.sortedBy { sceneName(it).lowercase() }
            PerfSort.RATING -> rows.sortedWith(compareByDescending<StashRow> { it.rating ?: -1 }.thenBy { sceneName(it).lowercase() })
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }, modifier = Modifier.focusHighlight(CircleShape, fill = false)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (rows.size == 1) "1 video" else "${rows.size} videos", color = Muted, style = MaterialTheme.typography.labelMedium)
            }
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            MenuChip(sort.label, Icons.Filled.KeyboardArrowDown, PerfSort.values().toList(), { it.label }) { sort = it }
        }
        if (list.isEmpty()) {
            Text("No videos from this performer on the connected sources.", color = Muted, modifier = Modifier.padding(20.dp))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (LocalIsTv.current) 220.dp else 160.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(list, key = { it.videoId }) { r -> SceneCard(r, progress[r.uri]?.isWatched() == true) { nav.navigate("player/file/${r.videoId}") } }
            }
        }
    }
}

private fun sceneName(r: StashRow): String =
    r.title?.takeIf { it.isNotBlank() } ?: r.fileName.substringBeforeLast('.').replace('_', ' ').replace('.', ' ')

@Composable
private fun SceneCard(r: StashRow, watched: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val thumb by produceState<File?>(null, r.uri) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Thumbs.cached(ctx, r.uri) }
        if (value == null) {
            Thumbs.awaitCreated(r.uri)
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Thumbs.cached(ctx, r.uri) }
        }
    }
    Column(Modifier.rememberFocus("sc${r.videoId}").focusHighlight().clickable(onClick = onClick).padding(4.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(CardBg)) {
            thumb?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            if (watched) Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(Color(0xAA000000)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Check, null, tint = Accent, modifier = Modifier.size(14.dp)) }
            DurationBadge(r.durationMs, Modifier.align(Alignment.BottomEnd).padding(6.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(sceneName(r), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
        val sub = listOfNotNull(r.studio, r.date?.take(4)).joinToString(" · ")
        if (sub.isNotBlank()) Text(sub, color = Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Settings → Stash: import the export folder made on the PC, see what matched, remove it again. */
@Composable
fun StashSettingsScreen(vm: LibraryViewModel, nav: NavController) {
    val stats by vm.stashStats.collectAsStateWithLifecycle()
    val msg by vm.stashMsg.collectAsStateWithLifecycle()
    val pickDrive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u -> if (u != null) vm.addStashDrive(u) }
    val drives by vm.stashDriveList.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    SettingsPage("Stash", nav) {
        Text(
            if (stats.first == 0) "Nothing imported yet."
            else "${stats.first} scenes and ${stats.second} performers imported. ${stats.third} videos on your open sources match.",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            "On the PC, sync your drive in Stash, then open the drive's MyMovieApp_stash folder and double-click \"Export Stash to this drive.bat\". It writes the performers and covers onto the drive. " +
                "When the drive is attached here and scanned, the app reads them by itself. " +
                "Videos are matched to Stash by file name and size, so only videos that are in Stash show up under Performers. " +
                "A locked source stays hidden here too.",
            color = Muted, style = MaterialTheme.typography.bodySmall
        )
        Text("Drives", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Library folders you added with the folder picker are checked automatically. For a USB drive added another way, link the drive here: choose the drive's top folder once.",
            color = Muted, style = MaterialTheme.typography.bodySmall
        )
        drives.forEach { u ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, android.net.Uri.parse(u))?.name ?: "Linked drive", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { vm.removeStashDrive(u) }, modifier = Modifier.focusHighlight()) { Text("Unlink") }
            }
        }
        OutlinedButton(onClick = { pickDrive.launch(null) }, modifier = Modifier.fillMaxWidth().focusHighlight()) { Text("Link a drive…") }
        Button(onClick = { vm.checkStashDrives() }, modifier = Modifier.fillMaxWidth().focusHighlight()) { Text("Check drives now") }
        msg?.let { Text(it, color = Accent, style = MaterialTheme.typography.bodyMedium) }
        if (stats.first > 0) ConfirmButton("Remove Stash data", "Remove the imported Stash data and photos from this device? Your videos are not touched.") { vm.clearStash() }
    }
}
