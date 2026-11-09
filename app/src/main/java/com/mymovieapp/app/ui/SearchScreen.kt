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
import androidx.compose.ui.platform.LocalClipboardManager
import kotlinx.coroutines.delay

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(vm: LibraryViewModel, nav: NavController) {
    var q by rememberSaveable { mutableStateOf("") }
    var titles by remember { mutableStateOf<List<Title>>(emptyList()) }
    var files by remember { mutableStateOf<List<VideoWithTitle>>(emptyList()) }
    val clip = LocalClipboardManager.current
    val shelf by vm.shelf.collectAsStateWithLifecycle()
    var preview by remember { mutableStateOf<LibraryItem?>(null) }
    val trimmed = q.trim()
    val isUrl = trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)

    LaunchedEffect(q) {
        if (trimmed.isEmpty() || isUrl) { titles = emptyList(); files = emptyList() }
        else { delay(200); val r = vm.search(trimmed); titles = r.first; files = r.second }
    }

    Column(Modifier.fillMaxSize()) {
        LogoHeader()
        OutlinedTextField(
            value = q, onValueChange = { q = it }, singleLine = true,
            placeholder = { Text("Search, or paste a video link") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (q.isNotEmpty()) IconButton(onClick = { q = "" }) { Icon(Icons.Filled.Close, "Clear") } },
            shape = RoundedCornerShape(20.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = CardBg, cursorColor = Accent),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
        )
        when {
            trimmed.isEmpty() -> Column(Modifier.padding(20.dp)) {
                Text("Search titles, episodes and filenames across every folder — or paste a video link to play it.", color = Muted, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(20.dp))
                AssistChip(
                    onClick = { clip.getText()?.text?.let { q = it } },
                    label = { Text("Paste link") }, leadingIcon = { Icon(Icons.Filled.Link, null, Modifier.size(18.dp)) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = CardBg, labelColor = Color.White, leadingIconContentColor = Color.White),
                    border = null, shape = RoundedCornerShape(20.dp)
                )
            }
            isUrl -> Row(
                Modifier.fillMaxWidth().padding(20.dp).clip(RoundedCornerShape(16.dp)).background(CardBg)
                    .focusHighlight().clickable { vm.addLink(trimmed) { id -> nav.navigate("player/channel/$id") } }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = Accent)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Play this link", fontWeight = FontWeight.Bold)
                    Text(trimmed, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            titles.isEmpty() && files.isEmpty() -> Text("No results.", color = Muted, modifier = Modifier.padding(20.dp))
            else -> LazyColumn(contentPadding = PaddingValues(vertical = 12.dp)) {
                if (titles.isNotEmpty()) item { SectionLabel("Titles") }
                items(titles, key = { "t${it.id}" }) { t ->
                    val tvKeys = LocalIsTv.current
                    val openPreview: () -> Unit = { preview = shelf.items.firstOrNull { it.title.id == t.id } ?: LibraryItem(t, 0, false) }
                    Row(
    Modifier.fillMaxWidth().rememberFocus("t${t.id}").focusHighlight()
        .tvLongPress(openPreview)
        .combinedClickable(
            onClick = { vm.openTitle(t) { nav.navigate(it) } },
            onLongClick = if (tvKeys) null else openPreview
        )
        .padding(horizontal = 20.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically
) {
                        Poster(t.posterUrl, t.name, Modifier.width(48.dp))
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(t.name, fontWeight = FontWeight.SemiBold)
                            Text(listOfNotNull(if (t.isSeries) "Series" else "Film", t.year?.toString()).joinToString(" · "), color = Muted, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if (files.isNotEmpty()) item { SectionLabel("Episodes & files") }
                items(files, key = { "v${it.video.id}" }) { r ->
                    val v = r.video
                    Row(Modifier.fillMaxWidth().rememberFocus("v${v.id}").focusHighlight().clickable { nav.navigate("player/file/${v.id}") }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.PlayArrow, null, tint = Muted)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            val ep = if (v.season != null && v.episode != null) " · S${v.season}E${v.episode}" else ""
                            Text(r.titleName + ep, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(v.fileName, color = Muted, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
    preview?.let { PosterPreview(vm, nav, it) { preview = null } }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), color = Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
}
