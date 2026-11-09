package com.mymovieapp.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.mymovieapp.app.LibraryViewModel
import com.mymovieapp.app.data.Channel
import com.mymovieapp.app.data.StreamHeaders
import com.mymovieapp.app.data.Playlist

@Composable
fun LiveTvScreen(vm: LibraryViewModel, nav: NavController) {
    val list by vm.liveList.collectAsStateWithLifecycle()
    val groups by vm.liveGroups.collectAsStateWithLifecycle()
    val fm by vm.groupFolders.collectAsStateWithLifecycle()
    val filter by vm.liveFilter.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val last by vm.lastPlayed.collectAsStateWithLifecycle()
    val dead by vm.deadChannels.collectAsStateWithLifecycle()
    val scanText by vm.deadScan.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    var removeAsk by remember { mutableStateOf(false) }
    var removeOne by remember { mutableStateOf<Channel?>(null) }
    var actionsFor by remember { mutableStateOf<Channel?>(null) }
    var linkWarn by remember { mutableStateOf<String?>(null) }
    var castOpen by remember { mutableStateOf(false) }
    var castCh by remember { mutableStateOf<Channel?>(null) }
    // The pill rows keep their scroll position when you come back from a channel (and start at the chosen group after a restart).
    val sourcesScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    val groupsScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    var selPillX by remember { mutableIntStateOf(-1) }
    var aligned by rememberSaveable { mutableStateOf(false) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(selPillX) {
        if (!aligned && selPillX >= 0) {
            aligned = true
            if (groupsScroll.value == 0 && selPillX > with(density) { 300.dp.roundToPx() }) {
                groupsScroll.scrollTo((selPillX - with(density) { 60.dp.roundToPx() }).coerceAtLeast(0))
            }
        }
    }
    LaunchedEffect(groups, fm, filter.mode, filter.group) {
        if (filter.mode == 4 && groups.isNotEmpty() && groups.none { fm[it.name] == filter.group }) vm.setLiveMode(0)
    }
    var selectMode by remember { mutableStateOf(false) }
    var selIds by remember { mutableStateOf(setOf<Long>()) }
    var bulkMove by remember { mutableStateOf(false) }
    var bulkNew by remember { mutableStateOf(false) }
    var bulkRemove by remember { mutableStateOf(false) }
    var mergeFrom by remember { mutableStateOf<String?>(null) }
    var mergeTo by remember { mutableStateOf<String?>(null) }
    val appCtx = androidx.compose.ui.platform.LocalContext.current
    val copyLink: (String) -> Unit = { link ->
        val cm = appCtx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Channel link", link))
        android.widget.Toast.makeText(appCtx, "Link copied", android.widget.Toast.LENGTH_SHORT).show()
    }
    val lockedKeys by vm.vault.locked.collectAsStateWithLifecycle()
    var groupAsk by remember { mutableStateOf<String?>(null) }
    var sourceAsk by remember { mutableStateOf<Playlist?>(null) }
    var sourceDelete by remember { mutableStateOf<Playlist?>(null) }
    var groupDelete by remember { mutableStateOf<String?>(null) }
    var multiOpen by remember { mutableStateOf(false) }
    var folderAsk by remember { mutableStateOf<String?>(null) }
    var folderDlg by remember { mutableStateOf(false) }
    var folderOrig by remember { mutableStateOf<String?>(null) }
    var folderName by remember { mutableStateOf("") }
    var folderSel by remember { mutableStateOf(setOf<String>()) }
    var folderFilter by remember { mutableStateOf("") }
    var renameSrc by remember { mutableStateOf<Playlist?>(null) }
    var renameSrcText by remember { mutableStateOf("") }
    fun openFolderEditor(orig: String?, extra: Set<String>) {
        folderOrig = orig; folderName = orig ?: ""
        folderSel = (if (orig != null) groups.filter { fm[it.name] == orig }.map { it.name }.toSet() else emptySet()) + extra
        folderFilter = ""; folderDlg = true
    }
    var multiSel by remember { mutableStateOf(setOf<String>()) }
    var multiFilter by remember { mutableStateOf("") }
    var multiConfirm by remember { mutableStateOf(false) }
    val sourceCounts by vm.playlistCounts.collectAsStateWithLifecycle()
    val sourceSel by vm.liveSource.collectAsStateWithLifecycle()
    val sourcesOff by vm.sourcesOff.collectAsStateWithLifecycle()
    val sourcesLockedHidden by vm.lockedHiddenPids.collectAsStateWithLifecycle()
    val shownSources = playlists.filter { it.id !in sourcesLockedHidden }
    var pinReq by remember { mutableStateOf<PinRequest?>(null) }
    var renameFor by remember { mutableStateOf<String?>(null) }
    var moveFor by remember { mutableStateOf<Channel?>(null) }
    var newGroupFor by remember { mutableStateOf<Channel?>(null) }
    var newGroupText by remember { mutableStateOf("") }
    var renameText by remember { mutableStateOf("") }
    val deadShown = list.filter { it.id in dead }
    val names = remember(playlists) { playlists.associate { it.id to it.name } }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    // The search stays in place while a channel plays, so coming back shows the same results.
    val play: (Channel) -> Unit = { c ->
        nav.navigate("player/channel/${c.id}")
    }
    if (searchOpen) {
        val focus = remember { FocusRequester() }
        val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { searchOpen = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(150)
                try { focus.requestFocus() } catch (_: Exception) {}
                kb?.show()
            }
            Box(Modifier.fillMaxWidth(0.8f).clip(RoundedCornerShape(24.dp)).background(CardBg).padding(16.dp)) {
                OutlinedTextField(
                    value = filter.query,
                    onValueChange = { vm.setLiveQuery(it) },
                    singleLine = true,
                    placeholder = { Text("Search channels") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = {
                        if (filter.query.isNotEmpty()) IconButton(onClick = { vm.setLiveQuery("") }) { Icon(Icons.Filled.Close, "Clear") }
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { kb?.hide(); searchOpen = false }),
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Muted,
                        cursorColor = Accent
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
            }
        }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val showTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 3 } }
    // TV remote: Back jumps to the top of a long list first (the up-arrow button is at the far end of the list).
    val tvUi = LocalIsTv.current
    // Back goes to the group pills (Music, Sports, …) of the section being scrolled, with the selected pill focused.
    val pillFocus = remember { FocusRequester() }
    val showDeadRow = scanText == null && deadShown.isNotEmpty()
    val pillsIndex = 1 + (if (scanText != null) 1 else 0) + (if (showDeadRow) 1 else 0) + (if (filter.query.isNotBlank()) 1 else 0) + (if (shownSources.size > 1 || sourcesOff.isNotEmpty()) 1 else 0)
    val toHeader: () -> Unit = {
        scope.launch {
            listState.scrollToItem(pillsIndex)
            androidx.compose.runtime.withFrameNanos { }
            try { pillFocus.requestFocus() } catch (_: Exception) {}
        }
    }
    val toHeaderNow by rememberUpdatedState(toHeader)
    DisposableEffect(tvUi, showTop) {
        val h: (android.view.KeyEvent) -> Boolean = h@{ e ->
            if (e.keyCode != android.view.KeyEvent.KEYCODE_BACK) return@h false
            if (e.action == android.view.KeyEvent.ACTION_DOWN && e.repeatCount == 0) toHeaderNow()
            true
        }
        if (tvUi && showTop) PlayerKeys.handler = h
        onDispose { if (PlayerKeys.handler === h) PlayerKeys.handler = null }
    }

    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Live TV",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.ExtraBold
                )
                RoundButton(Icons.Filled.Search, "Search") { searchOpen = true }
                Spacer(Modifier.width(10.dp))
                RoundButton(Icons.AutoMirrored.Filled.FactCheck, "Check channels") { vm.checkChannels(list) }
                Spacer(Modifier.width(10.dp))
                RoundButton(Icons.Filled.Add, "Add a playlist") { addOpen = true }
                if (!tvUi) Spacer(Modifier.width(10.dp))
                if (!tvUi) RoundButton(Icons.Filled.SettingsRemote, "TV remote") { castOpen = true }
            }
        }
        scanText?.let { t ->
            item(key = "scan") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Accent)
                    if (scanning) Spacer(Modifier.width(10.dp))
                    Text(t, Modifier.weight(1f), color = Muted, style = MaterialTheme.typography.bodyMedium)
                    if (!scanning && deadShown.isNotEmpty()) {
                        TextButton(onClick = { removeAsk = true }) { Text("Remove ${deadShown.size}") }
                    }
                    TextButton(onClick = { if (scanning) vm.cancelCheck() else vm.dismissCheck() }) {
                        Text(if (scanning) "Stop" else "OK")
                    }
                }
            }
        }
        if (showDeadRow) {
            item(key = "dead") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${deadShown.size} not working", Modifier.weight(1f), color = Muted, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { removeAsk = true }) { Text("Remove ${deadShown.size}") }
                }
            }
        }
        if (filter.query.isNotBlank()) {
            item(key = "query") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Results for \u201c${filter.query}\u201d", Modifier.weight(1f), color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { searchOpen = true }) { Text("Edit") }
                    TextButton(onClick = { vm.setLiveQuery("") }) { Text("Clear") }
                }
            }
        }
        if (shownSources.size > 1 || sourcesOff.isNotEmpty()) {
            item(key = "sources") {
                Row(
                    modifier = Modifier
                        .horizontalScroll(sourcesScroll)
                        .padding(start = 20.dp, end = 20.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Pill("All sources", sourceSel < 0) { vm.setLiveSource(-1L); vm.setLiveMode(0) }
                    shownSources.forEach { p ->
                        val off = p.id in sourcesOff
                        val lk = "src:${p.id}" in lockedKeys
                        Pill(
                            (if (lk) "\uD83D\uDD12 " else "") + p.name + (if (off) " · off" else ""),
                            sourceSel == p.id && !off,
                            onLong = { sourceAsk = p }
                        ) {
                            if (off) sourceAsk = p else { vm.setLiveSource(p.id); vm.setLiveMode(0) }
                        }
                    }
                }
            }
        }
        item(key = "groups") {
            Row(
                modifier = Modifier
                    .horizontalScroll(groupsScroll)
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Pill("All", filter.mode == 0, pillFocus.takeIf { filter.mode == 0 }, groups.sumOf { it.n }) { vm.setLiveMode(0) }
                Pill("★ Favourites", filter.mode == 1, pillFocus.takeIf { filter.mode == 1 }) { vm.setLiveMode(1) }
                Pill("Recent", filter.mode == 2, pillFocus.takeIf { filter.mode == 2 }) { vm.setLiveMode(2) }
                val shownFolders = HashSet<String>()
                groups.forEach { g ->
                    val fo = fm[g.name]
                    if (fo != null) {
                        if (shownFolders.add(fo)) {
                            val inside = (filter.mode == 4 && filter.group == fo) || (filter.mode == 3 && fm[filter.group] == fo)
                            Pill(
                                (if ("folder:$fo" in lockedKeys) "\uD83D\uDD12 " else "") + "\uD83D\uDCC1 $fo", inside, pillFocus.takeIf { filter.mode == 4 && filter.group == fo },
                                groups.filter { fm[it.name] == fo }.sumOf { it.n }, onLong = { folderAsk = fo },
                                onPlaced = if (inside) ({ x -> selPillX = x }) else null
                            ) { vm.setLiveMode(4, fo) }
                        }
                    } else {
                        val isLocked = "live:${g.name}" in lockedKeys
                        Pill(
                            (if (isLocked) "\uD83D\uDD12 " else "") + groupLabel(g.name), filter.mode == 3 && filter.group == g.name,
                            pillFocus.takeIf { filter.mode == 3 && filter.group == g.name }, g.n, onLong = { groupAsk = g.name },
                            onPlaced = if (filter.mode == 3 && filter.group == g.name) ({ x -> selPillX = x }) else null
                        ) {
                            vm.setLiveMode(3, g.name)
                        }
                    }
                }
            }
        }
        val openFolder = if (filter.mode == 4) filter.group else if (filter.mode == 3) fm[filter.group] else null
        if (openFolder != null) item(key = "subrow") {
            val members = groups.filter { fm[it.name] == openFolder }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Pill("All in $openFolder", filter.mode == 4, null, members.sumOf { it.n }) { vm.setLiveMode(4, openFolder) }
                members.forEach { g ->
                    val isLocked = "live:${g.name}" in lockedKeys
                    Pill(
                        (if (isLocked) "\uD83D\uDD12 " else "") + groupLabel(g.name), filter.mode == 3 && filter.group == g.name,
                        pillFocus.takeIf { filter.mode == 3 && filter.group == g.name }, g.n, onLong = { groupAsk = g.name }
                    ) { vm.setLiveMode(3, g.name) }
                }
            }
        }
        if (list.isNotEmpty() && groups.isNotEmpty()) item(key = "select") {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!selectMode) {
                    TextButton(onClick = { selectMode = true; selIds = emptySet() }) { Text("Select") }
                } else {
                    Text("${selIds.size} selected", color = Muted, modifier = Modifier.padding(start = 8.dp, end = 4.dp))
                    TextButton(onClick = { selIds = selIds + list.map { it.id } }) { Text("All in view") }
                    TextButton(onClick = { selIds = emptySet() }) { Text("Clear") }
                    TextButton(enabled = selIds.isNotEmpty(), onClick = { bulkMove = true }) { Text("Move…") }
                    TextButton(enabled = selIds.isNotEmpty(), onClick = { bulkRemove = true }) {
                        Text("Remove", color = if (selIds.isNotEmpty()) MaterialTheme.colorScheme.error else Muted)
                    }
                    TextButton(onClick = { selectMode = false; selIds = emptySet() }) { Text("Done") }
                }
            }
        }
        when {
            groups.isEmpty() -> item(key = "empty1") {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("No channels yet.", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Add an .m3u playlist to fill this tab. You can choose which groups to keep.", color = Muted)
                    Button(onClick = { addOpen = true }, shape = RoundedCornerShape(16.dp)) {
                        Text("Add a playlist")
                    }
                }
            }
            list.isEmpty() -> item(key = "empty2") {
                Text(
                    text = when (filter.mode) {
                        1 -> "No favourites yet. Tap the star on a channel."
                        2 -> "Nothing watched yet."
                        else -> "No channels match."
                    },
                    color = Muted,
                    modifier = Modifier.padding(20.dp)
                )
            }
            else -> {
                val hero = if (filter.query.isBlank() && !selectMode) (last ?: list.first()) else null
                if (hero != null) {
                    item(key = "hero") {
                        Hero(hero, if (playlists.size > 1) names[hero.playlistId] ?: "Links" else "", groupLabel(hero.grp)) { play(hero) }
                    }
                }
                items(if (hero != null) list.filter { it.id != hero.id } else list, key = { it.id }) { c ->
                    ChannelRow(
                        c = c,
                        playlist = if (playlists.size > 1) names[c.playlistId] ?: "Links" else "",
                        onClick = { if (selectMode) selIds = if (c.id in selIds) selIds - c.id else selIds + c.id else play(c) },
                        selecting = selectMode, selected = c.id in selIds,
                        dead = c.id in dead,
                        onLongClick = { actionsFor = c },
                        onFav = { vm.toggleFavorite(c.id) }

                    )
                }
            }
        }
    }
        FastScroller(
            itemCount = listState.layoutInfo.totalItemsCount,
            firstVisible = { listState.firstVisibleItemIndex },
            scrolling = listState.isScrollInProgress,
            onJump = { i -> scope.launch { listState.scrollToItem(i) } },
            modifier = Modifier.align(Alignment.TopEnd)
        )
        if (showTop) {
            SmallFloatingActionButton(
                onClick = { scope.launch { listState.scrollToItem(0) } },
                containerColor = CardBg,
                contentColor = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 44.dp, bottom = 20.dp)
            ) { Icon(Icons.Filled.KeyboardArrowUp, "Back to top") }
        }
    }
    if (addOpen) AddPlaylistDialog(vm) { addOpen = false }
    VaultDialogs(vm, pinReq) { pinReq = null }
    moveFor?.let { c ->
        AlertDialog(
            modifier = Modifier.dialogWidth().okGuard(),
            onDismissRequest = { moveFor = null },
            title = { Text("Move ${c.name}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                OkGuardWindow()
                androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    item {
                        TextButton(onClick = { newGroupText = ""; newGroupFor = c; moveFor = null }, modifier = Modifier.fillMaxWidth()) {
                            Text("New group…", Modifier.weight(1f))
                        }
                    }
                    items(groups.filter { it.name != c.grp }.size) { i ->
                        val g = groups.filter { it.name != c.grp }[i]
                        TextButton(onClick = { vm.moveChannel(c, g.name); moveFor = null }, modifier = Modifier.fillMaxWidth()) {
                            Text(groupLabel(g.name), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(g.n.toString(), color = Muted)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { moveFor = null }) { Text("Cancel") } }
        )
    }
    newGroupFor?.let { c ->
        AlertDialog(
            modifier = Modifier.dialogWidth(),
            onDismissRequest = { newGroupFor = null },
            title = { Text("New group") },
            text = { AppTextField(newGroupText, { newGroupText = it }, label = { Text("Group name") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(enabled = newGroupText.isNotBlank(), onClick = {
                    val t = newGroupText.trim()
                    val existing = groups.firstOrNull { groupLabel(it.name).equals(t, ignoreCase = true) }?.name
                    vm.moveChannel(c, existing ?: t); newGroupFor = null
                }) { Text("Move") }
            },
            dismissButton = { TextButton(onClick = { newGroupFor = null }) { Text("Cancel") } }
        )
    }
    renameFor?.let { g ->
        val merges = groups.any { it.name != g && groupLabel(it.name).equals(renameText.trim(), ignoreCase = true) }
        AlertDialog(
            modifier = Modifier.dialogWidth(),
            onDismissRequest = { renameFor = null },
            title = { Text("Rename group") },
            text = {
                Column {
                    AppTextField(renameText, { renameText = it }, label = { Text("Group name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (merges) Text("A group with this name exists. They will be merged.", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = renameText.isNotBlank(), onClick = {
                    val target = groups.firstOrNull { it.name != g && groupLabel(it.name).equals(renameText.trim(), ignoreCase = true) }?.name ?: renameText.trim()
                    vm.renameGroup(g, target); renameFor = null
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text("Cancel") } }
        )
    }
    renameSrc?.let { p ->
        AlertDialog(
            modifier = Modifier.dialogWidth(),
            onDismissRequest = { renameSrc = null },
            title = { Text("Rename source") },
            text = { AppTextField(renameSrcText, { renameSrcText = it }, label = { Text("Source name") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(enabled = renameSrcText.isNotBlank(), onClick = { vm.renamePlaylistTo(p, renameSrcText); renameSrc = null }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renameSrc = null }) { Text("Cancel") } }
        )
    }
    folderAsk?.let { f ->
        AlertDialog(
            modifier = Modifier.okGuard(),
            onDismissRequest = { folderAsk = null },
            title = { Text("\uD83D\uDCC1 $f", maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                OkGuardWindow()
                Column {
                    TextButton(onClick = { folderAsk = null; openFolderEditor(f, emptySet()) }) { Text("Rename or change groups…") }
                    val fLocked = "folder:$f" in lockedKeys
                    TextButton(onClick = {
                        folderAsk = null
                        val want = !fLocked
                        pinReq = PinRequest(PinMode.GATE) {
                            vm.vault.setLocked("folder:$f", want)
                            if (want) { if ((filter.mode == 4 && filter.group == f) || (filter.mode == 3 && fm[filter.group] == f)) vm.setLiveMode(0); vm.vault.lockNow() }
                        }
                    }) { Text(if (fLocked) "Remove lock" else "Lock folder (hide behind PIN)") }
                    if (fLocked) TextButton(onClick = { folderAsk = null; vm.vault.lockNow() }) { Text("Lock now (hide all locked)") }
                    TextButton(onClick = { folderAsk = null; vm.releaseFolder(f) }) { Text("Release folder (keep the groups)", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { folderAsk = null }) { Text("Cancel") } }
        )
    }
    if (folderDlg) {
        val shownGroups = groups.filter { folderFilter.isBlank() || groupLabel(it.name).contains(folderFilter.trim(), true) }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { folderDlg = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Column(
                Modifier.fillMaxWidth(0.92f).heightIn(max = 640.dp).clip(RoundedCornerShape(24.dp)).background(CardBg).padding(16.dp)
            ) {
                Text(if (folderOrig == null) "New folder" else "Edit folder", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                AppTextField(folderName, { folderName = it }, label = { Text("Folder name, e.g. Series") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                AppTextField(
                    value = folderFilter, onValueChange = { folderFilter = it }, singleLine = true,
                    placeholder = { Text("Find groups, e.g. series") }, modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { folderSel = folderSel + shownGroups.map { it.name } }) { Text("Tick shown") }
                    TextButton(onClick = { folderSel = emptySet() }) { Text("Clear") }
                    Spacer(Modifier.weight(1f))
                    Text("${folderSel.size} ticked", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(shownGroups.size) { i ->
                        val gg = shownGroups[i]
                        val on = gg.name in folderSel
                        val other = fm[gg.name]?.takeIf { it != folderOrig }
                        Row(
                            Modifier.fillMaxWidth().focusHighlight(RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))
                                .clickable { folderSel = if (on) folderSel - gg.name else folderSel + gg.name }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = on, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(groupLabel(gg.name), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (other != null) Text("now in $other", color = Muted, style = MaterialTheme.typography.labelSmall)
                            }
                            Text("%,d".format(gg.n), color = Muted, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { folderDlg = false }) { Text("Cancel") }
                    TextButton(enabled = folderName.isNotBlank() && (folderSel.isNotEmpty() || folderOrig != null), onClick = {
                        vm.saveFolder(folderOrig, folderName, folderSel, groups.map { it.name }.toSet()); folderDlg = false
                    }) { Text("Save") }
                }
            }
        }
    }
    if (multiOpen) {
        val shownGroups = groups.filter { multiFilter.isBlank() || groupLabel(it.name).contains(multiFilter.trim(), true) }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { multiOpen = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Column(
                Modifier.fillMaxWidth(0.92f).heightIn(max = 620.dp).clip(RoundedCornerShape(24.dp)).background(CardBg).padding(16.dp)
            ) {
                Text("Remove groups", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                AppTextField(
                    value = multiFilter, onValueChange = { multiFilter = it }, singleLine = true,
                    placeholder = { Text("Filter groups, e.g. Series") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { multiSel = multiSel + shownGroups.map { it.name } }) { Text("Select shown") }
                    TextButton(onClick = { multiSel = emptySet() }) { Text("Clear") }
                    Spacer(Modifier.weight(1f))
                    Text("${multiSel.size} selected", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(shownGroups.size) { i ->
                        val gg = shownGroups[i]
                        val on = gg.name in multiSel
                        Row(
                            Modifier.fillMaxWidth().focusHighlight(RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))
                                .clickable { multiSel = if (on) multiSel - gg.name else multiSel + gg.name }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = on, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Text(groupLabel(gg.name), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("%,d".format(gg.n), color = Muted, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { multiOpen = false }) { Text("Cancel") }
                    TextButton(enabled = multiSel.isNotEmpty(), onClick = { multiConfirm = true }) {
                        Text("Remove ${multiSel.size}", color = if (multiSel.isNotEmpty()) MaterialTheme.colorScheme.error else Muted)
                    }
                }
            }
        }
    }
    if (multiConfirm) {
        val selChannels = groups.filter { it.name in multiSel }.sumOf { it.n }
        AlertDialog(
            modifier = Modifier.okGuard(),
            onDismissRequest = { multiConfirm = false },
            title = { Text("Remove ${multiSel.size} groups?") },
            text = {
                OkGuardWindow()
                Text("%,d channels will be removed. Refreshing the playlist won't bring them back.".format(selChannels))
            },
            confirmButton = {
                TextButton(onClick = { multiConfirm = false; multiOpen = false; vm.removeGroups(multiSel); multiSel = emptySet() }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { multiConfirm = false }) { Text("Cancel") } }
        )
    }
    groupDelete?.let { g ->
        val n = groups.firstOrNull { it.name == g }?.n ?: 0
        AlertDialog(
            modifier = Modifier.okGuard(),
            onDismissRequest = { groupDelete = null },
            title = { Text("Remove group?") },
            text = {
                OkGuardWindow()
                Text("\u201c${groupLabel(g)}\u201d and its %,d channels will be removed. Refreshing the playlist won't bring them back.".format(n))
            },
            confirmButton = { TextButton(onClick = { groupDelete = null; vm.removeGroup(g) }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { groupDelete = null }) { Text("Cancel") } }
        )
    }
    sourceDelete?.let { p ->
        AlertDialog(
            modifier = Modifier.okGuard(),
            onDismissRequest = { sourceDelete = null },
            title = { Text("Remove source?") },
            text = {
                OkGuardWindow()
                Text("\u201c${p.name}\u201d and its ${sourceCounts[p.id] ?: 0} channels will be removed from the app. Favourites in it are lost too.")
            },
            confirmButton = { TextButton(onClick = { sourceDelete = null; vm.removePlaylist(p) }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { sourceDelete = null }) { Text("Cancel") } }
        )
    }
    sourceAsk?.let { p ->
        val key = "src:${p.id}"
        val isLocked = key in lockedKeys
        val isOff = p.id in sourcesOff
        AlertDialog(
            modifier = Modifier.okGuard(),
            onDismissRequest = { sourceAsk = null },
            title = { Text(p.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                OkGuardWindow()
                Column {
                    TextButton(onClick = { sourceAsk = null; vm.setSourceOn(p.id, isOff) }) {
                        Text(if (isOff) "Turn on" else "Turn off (hide its channels)")
                    }
                    TextButton(onClick = {
                        sourceAsk = null
                        val want = !isLocked
                        pinReq = PinRequest(PinMode.GATE) {
                            vm.vault.setLocked(key, want)
                            if (want) { if (sourceSel == p.id) vm.setLiveSource(-1L); vm.vault.lockNow() }
                        }
                    }) { Text(if (isLocked) "Remove lock" else "Lock source (hide behind PIN)") }
                    if (isLocked) TextButton(onClick = {
                        sourceAsk = null
                        vm.vault.lockNow()
                    }) { Text("Lock now (hide all locked)") }
                    TextButton(onClick = { sourceAsk = null; renameSrcText = p.name; renameSrc = p }) { Text("Rename source…") }
                    TextButton(onClick = { sourceAsk = null; sourceDelete = p }) { Text("Remove source…", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { sourceAsk = null }) { Text("Cancel") } }
        )
    }
    groupAsk?.let { g ->
        val key = "live:$g"
        val isLocked = key in lockedKeys
        AlertDialog(
            modifier = Modifier.okGuard(),
            onDismissRequest = { groupAsk = null },
            title = { Text(groupLabel(g), maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                OkGuardWindow()
                Column {
                    TextButton(onClick = { groupAsk = null; renameText = groupLabel(g); renameFor = g }) { Text("Rename group…") }
                    TextButton(onClick = {
                        groupAsk = null
                        val want = !isLocked
                        pinReq = PinRequest(PinMode.GATE) {
                            vm.vault.setLocked(key, want)
                            if (want) { if (filter.mode == 3 && filter.group == g) vm.setLiveMode(0); vm.vault.lockNow() }
                        }
                    }) { Text(if (isLocked) "Remove lock" else "Lock group (hide behind PIN)") }
                    TextButton(onClick = { groupAsk = null; mergeFrom = g }) { Text("Merge into group…") }
                    TextButton(onClick = { groupAsk = null; openFolderEditor(fm[g], setOf(g)) }) { Text(if (fm[g] != null) "Edit folder \u201c${fm[g]}\u201d…" else "Put in a folder…") }
                    if (fm[g] != null) TextButton(onClick = { groupAsk = null; vm.takeOutOfFolder(g) }) { Text("Take out of folder") }
                    TextButton(onClick = { groupAsk = null; groupDelete = g }) { Text("Remove group…", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { groupAsk = null; multiSel = setOf(g); multiFilter = ""; multiOpen = true }) { Text("Remove several groups…", color = MaterialTheme.colorScheme.error) }
                    if (isLocked) TextButton(onClick = {
                        groupAsk = null
                        if (filter.mode == 3 && "live:${filter.group}" in lockedKeys) vm.setLiveMode(0)
                        vm.vault.lockNow()
                    }) { Text("Lock now (hide all locked)") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { groupAsk = null }) { Text("Cancel") } }
        )
    }
    actionsFor?.let { c ->
        AlertDialog(
            modifier = Modifier.okGuard(),
            onDismissRequest = { actionsFor = null },
            title = { Text(c.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                OkGuardWindow()
                Column {
                    TextButton(onClick = { vm.toggleFavorite(c.id); actionsFor = null }) {
                        Text(if (c.favorite) "Remove from favourites" else "Add to favourites")
                    }
                    if (!tvUi) TextButton(onClick = { actionsFor = null; castCh = c }) { Text("Play on TV") }
                    TextButton(onClick = {
                        actionsFor = null
                        val link = StreamHeaders.split(c.url).first
                        if (linkHasLogin(link)) linkWarn = link else copyLink(link)
                    }) { Text("Copy channel link") }
                    TextButton(onClick = { moveFor = c; actionsFor = null }) { Text("Move to group…") }
                    TextButton(onClick = { removeOne = c; actionsFor = null }) { Text("Remove channel…") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { actionsFor = null }) { Text("Cancel") } }
        )
    }
    mergeFrom?.let { from ->
        AlertDialog(
            modifier = Modifier.dialogWidth().okGuard(),
            onDismissRequest = { mergeFrom = null },
            title = { Text("Merge \u201c${groupLabel(from)}\u201d into…", maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                OkGuardWindow()
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(groups.filter { it.name != from }.size) { i ->
                        val gg = groups.filter { it.name != from }[i]
                        TextButton(onClick = { mergeTo = gg.name }, modifier = Modifier.fillMaxWidth()) {
                            Text(groupLabel(gg.name), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("%,d".format(gg.n), color = Muted)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { mergeFrom = null }) { Text("Cancel") } }
        )
        mergeTo?.let { to ->
            val n = groups.firstOrNull { it.name == from }?.n ?: 0
            AlertDialog(
                modifier = Modifier.okGuard(),
                onDismissRequest = { mergeTo = null },
                title = { Text("Merge groups?") },
                text = {
                    OkGuardWindow()
                    Text("All %,d channels of \u201c${groupLabel(from)}\u201d move into \u201c${groupLabel(to)}\u201d, and the empty group disappears.".format(n))
                },
                confirmButton = { TextButton(onClick = { vm.mergeGroup(from, to); mergeTo = null; mergeFrom = null }) { Text("Merge") } },
                dismissButton = { TextButton(onClick = { mergeTo = null }) { Text("Cancel") } }
            )
        }
    }
    if (bulkMove) {
        AlertDialog(
            modifier = Modifier.dialogWidth().okGuard(),
            onDismissRequest = { bulkMove = false },
            title = { Text("Move ${selIds.size} channels to…") },
            text = {
                OkGuardWindow()
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    item {
                        TextButton(onClick = { newGroupText = ""; bulkNew = true; bulkMove = false }, modifier = Modifier.fillMaxWidth()) {
                            Text("New group…", Modifier.weight(1f))
                        }
                    }
                    items(groups.size) { i ->
                        val gg = groups[i]
                        TextButton(onClick = { vm.moveChannels(selIds, gg.name); bulkMove = false; selectMode = false; selIds = emptySet() }, modifier = Modifier.fillMaxWidth()) {
                            Text(groupLabel(gg.name), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("%,d".format(gg.n), color = Muted)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { bulkMove = false }) { Text("Cancel") } }
        )
    }
    if (bulkNew) {
        AlertDialog(
            modifier = Modifier.dialogWidth(),
            onDismissRequest = { bulkNew = false },
            title = { Text("New group") },
            text = { AppTextField(newGroupText, { newGroupText = it }, label = { Text("Group name") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(enabled = newGroupText.isNotBlank(), onClick = {
                    val t = newGroupText.trim()
                    val existing = groups.firstOrNull { groupLabel(it.name).equals(t, ignoreCase = true) }?.name
                    vm.moveChannels(selIds, existing ?: t); bulkNew = false; selectMode = false; selIds = emptySet()
                }) { Text("Move") }
            },
            dismissButton = { TextButton(onClick = { bulkNew = false }) { Text("Cancel") } }
        )
    }
    if (bulkRemove) {
        AlertDialog(
            modifier = Modifier.okGuard(),
            onDismissRequest = { bulkRemove = false },
            title = { Text("Remove ${selIds.size} channels?") },
            text = { OkGuardWindow(); Text("They are removed from this app only and stay removed when the playlist refreshes.") },
            confirmButton = {
                TextButton(onClick = { vm.removeChannels(selIds.toList()); bulkRemove = false; selectMode = false; selIds = emptySet() }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { bulkRemove = false }) { Text("Cancel") } }
        )
    }
    if (castOpen) CastDialog(vm, null, false, pad = true) { castOpen = false }
    castCh?.let { c -> CastDialog(vm, LibraryViewModel.CastItem("url", c.url, c.name), true) { castCh = null } }
    linkWarn?.let { link ->
        AlertDialog(
            modifier = Modifier.okGuard(),
            onDismissRequest = { linkWarn = null },
            title = { Text("This link contains login details") },
            text = {
                OkGuardWindow()
                Text("It looks like it includes your username, password or key. Anyone who gets it can use your subscription, and it may stop your own viewing. Only send it to someone you trust.")
            },
            confirmButton = { TextButton(onClick = { linkWarn = null; copyLink(link) }) { Text("Copy anyway") } },
            dismissButton = { TextButton(onClick = { linkWarn = null }) { Text("Cancel") } }
        )
    }
    removeOne?.let { c ->
        AlertDialog(
            modifier = Modifier.dialogWidth(),
            onDismissRequest = { removeOne = null },
            title = { Text("Remove ${c.name}?") },
            text = { Text("This removes the channel from this app only. It stays removed when the playlist refreshes.") },
            confirmButton = {
                TextButton(onClick = { vm.removeChannels(listOf(c.id)); removeOne = null }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removeOne = null }) { Text("Cancel") } }
        )
    }
    if (removeAsk) AlertDialog(
        modifier = Modifier.dialogWidth(),
        onDismissRequest = { removeAsk = false },
        title = { Text("Remove ${deadShown.size} channels?") },
        text = {
            Text("These channels are marked as not working. They are removed from this app only. Refreshing a playlist can bring them back.")
        },
        confirmButton = {
            TextButton(onClick = {
                vm.removeChannels(deadShown.map { it.id })
                removeAsk = false
                vm.dismissCheck()
            }) { Text("Remove") }
        },
        dismissButton = { TextButton(onClick = { removeAsk = false }) { Text("Cancel") } }
    )
}

@Composable
private fun RoundButton(icon: ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .focusHighlight(CircleShape)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .background(CardBg),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, desc)
    }
}

/** "Undefined" is what playlists without a group-title get; shown as "Other". */
private fun groupLabel(g: String) = if (g == "Undefined") "Other" else g

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun Pill(label: String, selected: Boolean, focus: FocusRequester? = null, count: Int? = null, onLong: (() -> Unit)? = null, onPlaced: ((Int) -> Unit)? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val tvKeys = LocalIsTv.current
    // The modifier is always present (only its requester changes) so the focused pill keeps focus
    // when the list below it changes; it is also re-focused after the click as a safety net.
    val own = remember { FocusRequester() }
    val req = focus ?: own
    val pscope = rememberCoroutineScope()
    val clicked: () -> Unit = {
        onClick()
        if (tvKeys) pscope.launch {
            androidx.compose.runtime.withFrameNanos { }
            try { req.requestFocus() } catch (_: Exception) {}
        }
    }
    Box(
        modifier = Modifier
            .onPlaced { onPlaced?.invoke(it.positionInParent().x.toInt()) }
            .focusRequester(req)
            .focusHighlight(shape)
            .clip(shape)
            .tvLongPress(onLong)
            .combinedClickable(onClick = clicked, onLongClick = if (tvKeys) null else onLong)
            .background(if (selected) Accent else CardBg)
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = if (selected) OnAccent else Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            if (count != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = count.toString(),
                    color = if (selected) OnAccent.copy(alpha = 0.7f) else Muted,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun Hero(c: Channel, playlist: String, group: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .height(230.dp)
            .clip(shape)
            .focusHighlight(shape)
            .clickable(onClick = onClick)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF5E3D14), Color(0xFF2A1D0F), Bg)
                )
            )
    ) {
        Text(
            text = "● LIVE",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Danger)
                .padding(horizontal = 10.dp, vertical = 5.dp)
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .size(width = 96.dp, height = 64.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Raised)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            if (c.logo != null) {
                AsyncImage(
                    model = c.logo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Filled.Tv, null, tint = Muted)
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(20.dp)
        ) {
            val meta = listOfNotNull(c.number.takeIf { it > 0 }?.toString(), group, playlist.ifBlank { null }).joinToString(" · ")
            Text(meta, color = Muted, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = c.name,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(c: Channel, playlist: String, dead: Boolean = false, selecting: Boolean = false, selected: Boolean = false, onLongClick: () -> Unit = {}, onClick: () -> Unit, onFav: () -> Unit) {
    val tvKeys = LocalIsTv.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .rememberFocus("c${c.id}")
            .focusHighlight(RoundedCornerShape(16.dp))
            .tvLongPress(onLongClick)
            .combinedClickable(onClick = onClick, onLongClick = if (tvKeys) null else onLongClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selecting) { Checkbox(checked = selected, onCheckedChange = null); Spacer(Modifier.width(10.dp)) }
        Box(
            modifier = Modifier
                .size(width = 76.dp, height = 56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(CardBg)
                .padding(6.dp),
            contentAlignment = Alignment.Center
        ) {
            if (c.logo != null) {
                AsyncImage(
                    model = c.logo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Filled.Tv, null, tint = Muted)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
    c.name,
    color = if (dead) Muted else Color.Unspecified,
    maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold
)
            Text(listOf(groupLabel(c.grp), playlist).filter { it.isNotBlank() }.joinToString(" · "), color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
        if (dead) Icon(
            Icons.Filled.Warning, "Failed to play last time",
            tint = Color(0xFFFFB020), modifier = Modifier.padding(end = 6.dp).size(20.dp)
        )
        if (c.number > 0) {
            Text("${c.number}", color = Muted, style = MaterialTheme.typography.labelMedium)
        }
        IconButton(onClick = onFav) {
            Icon(
                imageVector = if (c.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = "Favourite",
                tint = if (c.favorite) Accent else Muted
            )
        }
    }
}

/** True when a stream address seems to carry the account's own login (user:pass@, username=, token=, Xtream /live/user/pass/id). */
private fun linkHasLogin(u: String): Boolean =
    Regex("(?i)([?&](username|user|password|pass|pwd|token|key|auth)=)|(://[^/@]+@)|(/(live|movie|series)/[^/]+/[^/]+/\\d+)").containsMatchIn(u)
