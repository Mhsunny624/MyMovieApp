package com.mymovieapp.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.mymovieapp.app.LOOSE_SOURCE
import com.mymovieapp.app.LibraryViewModel
import com.mymovieapp.app.data.isSeries

/** TV: when the remote moves focus off screen, scroll quickly instead of with the slow default animation. */
@OptIn(ExperimentalFoundationApi::class)
private val FastBringIntoView = object : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = tween(durationMillis = 90, easing = LinearEasing)
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailing = offset + size
        return when {
            offset >= 0f && trailing <= containerSize -> 0f
            offset < 0f && trailing > containerSize -> 0f
            kotlin.math.abs(offset) < kotlin.math.abs(trailing - containerSize) -> offset
            else -> trailing - containerSize
        }
    }
}

enum class SortBy(val label: String) { RECENT("Recently added"), TITLE("Title A–Z"), YEAR("Year") }
enum class Filt(val label: String) { ALL("All"), UNWATCHED("Unwatched"), WATCHED("Watched") }

@Composable
fun <T> MenuChip(label: String, icon: ImageVector, options: List<T>, text: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true }, label = { Text(label) }, modifier = Modifier.focusHighlight(RoundedCornerShape(16.dp)),
            leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) },
            colors = AssistChipDefaults.assistChipColors(containerColor = CardBg, labelColor = Color.White, leadingIconContentColor = Color.White),
            border = null, shape = RoundedCornerShape(16.dp)
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(text(o)) }, onClick = { onPick(o); open = false }) }
        }
    }
}
/** "All sources / <folder>" picker. Hidden when there is only one place to choose from. */
@Composable
fun SourceChip(vm: LibraryViewModel) {
    val folders by vm.folders.collectAsStateWithLifecycle()
    val loose by vm.looseCount.collectAsStateWithLifecycle()
    val sel by vm.sourceFilter.collectAsStateWithLifecycle()
    val options = buildList<Pair<String?, String>> {
        add(null to "All sources")
        folders.forEach { add(it.uri to it.name) }
        if (loose > 0) add(LOOSE_SOURCE to "Individual videos")
    }
    if (options.size <= 2) return
    val label = options.firstOrNull { it.first == sel }?.second ?: "All sources"
    MenuChip(label.take(24), Icons.Filled.Folder, options, { it.second }) { vm.setSource(it.first) }
}
/** "Category" picker: the folders inside the source (Trans, Interracial, …). Hidden when the videos have no sub-folders. */
@Composable
fun FolderChip(vm: LibraryViewModel) {
    val opts by vm.folderOptions.collectAsStateWithLifecycle()
    val sel by vm.folderFilter.collectAsStateWithLifecycle()
    LaunchedEffect(opts, sel) { if (sel != null && opts.none { it.first == sel }) vm.setFolder(null) }
    if (opts.isEmpty()) return
    val options = buildList<Pair<String?, String>> {
        add(null to "All categories")
        opts.forEach { (p, n) -> add(p to ("    ".repeat(p.count { it == '/' }) + p.substringAfterLast('/') + "  ($n)")) }
    }
    val label = sel?.substringAfterLast('/') ?: "Category"
    MenuChip(label.take(24), Icons.Filled.Folder, options, { it.second }) { vm.setFolder(it.first) }
}
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(vm: LibraryViewModel, nav: NavController) {
    val shelf by vm.shelf.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val thumbMsg by vm.thumbProgress.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var sort by rememberSaveable { mutableStateOf(SortBy.RECENT) }
    var filt by rememberSaveable { mutableStateOf(Filt.ALL) }
    val gridState = rememberLazyGridState()
    val tv = LocalIsTv.current
    val pullState = rememberPullToRefreshState()
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    val performers by vm.performers.collectAsStateWithLifecycle()
    val hasPerformers = performers.isNotEmpty()
    LaunchedEffect(hasPerformers) { if (!hasPerformers && tab == 3) tab = 0 }

    val wantSeries = tab == 1
    val all = remember(shelf, wantSeries) { shelf.items.filter { it.title.isSeries == wantSeries } }
    val list = remember(all, query, filt, sort) {
        all
            .filter { query.isBlank() || it.title.name.contains(query.trim(), ignoreCase = true) }
            .filter { when (filt) { Filt.ALL -> true; Filt.UNWATCHED -> !it.watched; Filt.WATCHED -> it.watched } }
            .let { l ->
                when (sort) {
                    SortBy.RECENT -> l.sortedByDescending { it.title.addedAt }
                    SortBy.TITLE -> l.sortedBy { it.title.name.lowercase() }
                    SortBy.YEAR -> l.sortedByDescending { it.title.year ?: 0 }
                }
            }
    }

    var castOpen by remember { mutableStateOf(false) }
    if (castOpen) CastDialog(vm, null, false, pad = true) { castOpen = false }
    CompositionLocalProvider(if (tv) LocalBringIntoViewSpec provides FastBringIntoView else LocalBringIntoViewSpec provides LocalBringIntoViewSpec.current) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth()
                .clip(RoundedCornerShape(20.dp)).background(CardBg).padding(6.dp)
        ) {
            (listOf("Films", "Series", "Web") + if (hasPerformers) listOf("Performers") else emptyList()).forEachIndexed { i, label ->
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                        .background(if (tab == i) Accent else Color.Transparent)
                        .focusHighlight(RoundedCornerShape(14.dp), ring = if (tab == i) Color.White else Accent).clickable { tab = i }.padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) { Text(label, color = if (tab == i) Color.Black else Muted, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)) }
            }
        }
        if (tab == 2) {
            Box(Modifier.weight(1f).fillMaxWidth()) { WebScreen(vm, nav, embedded = true) }
        } else if (tab == 3 && hasPerformers) {
            Box(Modifier.weight(1f).fillMaxWidth()) { PerformersGrid(vm, nav) }
        } else {
        Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(if (wantSeries) "Series" else "Films", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.width(10.dp))
            Text("${all.size}", color = Muted, modifier = Modifier.padding(bottom = 6.dp))
            Spacer(Modifier.weight(1f))
            if (!LocalIsTv.current) IconButton(onClick = { castOpen = true }) {
                Icon(Icons.Filled.SettingsRemote, contentDescription = "TV remote")
            }
            IconButton(onClick = {
                val pick = list.filter { !it.watched }.ifEmpty { list }.randomOrNull()
                if (pick != null) vm.openTitle(pick.title) { nav.navigate(it) }
            }) {
                Icon(Icons.Filled.Casino, contentDescription = "Surprise me")
            }
        }
        if (!LocalIsTv.current) OutlinedTextField(
            value = query, onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Search ${if (wantSeries) "series" else "films"}", color = Muted) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = Muted) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear") }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = CardBg, focusedContainerColor = CardBg,
                unfocusedBorderColor = Color.Transparent, focusedBorderColor = Accent
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SourceChip(vm)
            FolderChip(vm)
        }
        thumbMsg?.let { Text(it, color = Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 20.dp)) }
        if (list.isEmpty()) {
            Text(
                if (all.isEmpty()) "No ${if (wantSeries) "series" else "films"} yet. Add a folder in Settings."
                else if (query.isNotBlank()) "No matches for \"${query.trim()}\"." else "Nothing matches this filter.",
                color = Muted, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(20.dp)
            )
        } else {
            val gridBody: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(if (LocalIsTv.current) 115.dp else 90.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(list, key = { it.title.id }) { item -> TitleCard(vm, nav, item) }
                }
                FastScroller(
                    itemCount = list.size,
                    firstVisible = { gridState.firstVisibleItemIndex },
                    scrolling = gridState.isScrollInProgress,
                    onJump = { i -> scope.launch { gridState.scrollToItem(i) } },
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }
            // TV has no pulling: a plain Box avoids the pull-to-refresh layers (lighter, no stray overlay while the screen opens).
            if (tv) Box(Modifier.weight(1f).fillMaxWidth().background(Bg)) { gridBody() }
            else PullToRefreshBox(
                isRefreshing = busy != null,
                onRefresh = { if (busy == null) vm.rescanAll() },
                state = pullState,
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pullState, isRefreshing = busy != null, modifier = Modifier.align(Alignment.TopCenter)
                    )
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                content = gridBody
            )
        }
        }
    }
    }
}
