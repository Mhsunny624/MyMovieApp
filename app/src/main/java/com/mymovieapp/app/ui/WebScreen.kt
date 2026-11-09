package com.mymovieapp.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.mymovieapp.app.LibraryViewModel
import com.mymovieapp.app.data.WebEntry
import com.mymovieapp.app.data.WebServer

/** The last video played from a web folder, so it can be picked up again. */
data class WebLastItem(val serverId: String, val folderUrl: String, val url: String, val name: String)

object WebLast {
    private fun sp(ctx: android.content.Context) = ctx.getSharedPreferences("web_last", android.content.Context.MODE_PRIVATE)
    fun get(ctx: android.content.Context): WebLastItem? {
        val p = sp(ctx)
        val sid = p.getString("sid", null) ?: return null
        val url = p.getString("url", null) ?: return null
        return WebLastItem(sid, p.getString("folder", "") ?: "", url, p.getString("name", "") ?: "")
    }
    fun clear(ctx: android.content.Context) { sp(ctx).edit().clear().apply() }
    fun set(ctx: android.content.Context, i: WebLastItem) {
        sp(ctx).edit().putString("sid", i.serverId).putString("folder", i.folderUrl).putString("url", i.url).putString("name", i.name).apply()
    }
}

@Composable
fun WebScreen(vm: LibraryViewModel, nav: NavController, embedded: Boolean = false) {
    val servers by vm.webServers.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf<String?>(null) }      // id of the web folder being browsed
    var addOpen by remember { mutableStateOf(false) }
    var removeAsk by remember { mutableStateOf<WebServer?>(null) }
    var startFolder by rememberSaveable { mutableStateOf<String?>(null) }   // open a web folder straight at this sub-folder
    val current = servers.firstOrNull { it.id == open }
    val tvNow = LocalIsTv.current
    var returned by remember { mutableStateOf(false) }
    val serverFocus = remember { FocusRequester() }
    LaunchedEffect(current == null) {
        // Coming back from a web folder: the row that was open gets the remote's focus again (otherwise it jumps to the Home tab).
        if (current == null && returned && tvNow) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { serverFocus.requestFocus() }
        }
    }

    if (current != null) {
        WebBrowser(vm, nav, current, startFolder) { open = null; startFolder = null; returned = true }
    } else {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!embedded) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text(
                    "Web folders", style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { addOpen = true }) { Icon(Icons.Filled.Add, "Add a web folder") }
            }
            if (servers.isEmpty()) {
                Text("Add the address of a web folder: a page that lists files, like \"Index of /movies\".", color = Muted)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { addOpen = true }, shape = RoundedCornerShape(16.dp)) { Text("Add a web folder") }
            }
            val ctx = LocalContext.current
            val scope = rememberCoroutineScope()
            var lastState by remember { mutableStateOf(WebLast.get(ctx)) }
            val last = lastState
            val lastServer = last?.let { l -> servers.firstOrNull { it.id == l.serverId } }
            var contMenu by remember { mutableStateOf(false) }
            if (last != null && lastServer != null) {
                val continueNow: () -> Unit = {
                    scope.launch {
                        val list = runCatching { vm.webList(lastServer, last.folderUrl) }.getOrNull()
                        val e = list?.firstOrNull { it.url == last.url }
                        if (list != null && e != null) nav.navigate("player/web/${vm.webOpen(lastServer, list, e, last.folderUrl)}")
                        else Toast.makeText(ctx, "Couldn't open the last video. Is the server reachable?", Toast.LENGTH_LONG).show()
                    }
                }
                if (contMenu) AlertDialog(
                    modifier = Modifier.okGuard(),
                    onDismissRequest = { contMenu = false },
                    title = { Text(last.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    text = {
                        OkGuardWindow()
                        Column {
                            TextButton(onClick = { contMenu = false; continueNow() }) { Text("Continue playing") }
                            TextButton(onClick = { contMenu = false; startFolder = last.folderUrl; open = lastServer.id }) { Text("Open folder") }
                            TextButton(onClick = { contMenu = false; WebLast.clear(ctx); lastState = null }) { Text("Remove from here") }
                        }
                    },
                    confirmButton = {},
                    dismissButton = { TextButton(onClick = { contMenu = false }) { Text("Cancel") } }
                )
                Card(
                    onClick = continueNow,
                    colors = CardDefaults.cardColors(containerColor = CardBg, contentColor = Color.White), shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).focusHighlight(RoundedCornerShape(16.dp))
                        .tvLongPress { contMenu = true }
                ) {
                    Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.PlayArrow, null, tint = Accent)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Continue where you left off", color = Muted, style = MaterialTheme.typography.labelMedium)
                            Text(last.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { startFolder = last.folderUrl; open = lastServer.id }) { Text("Folder") }
                        IconButton(onClick = { WebLast.clear(ctx); lastState = null }) { Icon(Icons.Filled.Close, "Remove", tint = Muted) }
                    }
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(servers, key = { it.id }) { s ->
                    Card(colors = CardDefaults.cardColors(containerColor = CardBg, contentColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                        Row(
                            Modifier.fillMaxWidth()
                                .then(if (s.id == (servers.firstOrNull { it.id == open }?.id ?: servers.first().id)) Modifier.focusRequester(serverFocus) else Modifier)
                                .focusHighlight(RoundedCornerShape(16.dp)).clickable { open = s.id }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Folder, null, tint = Accent)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(s.url, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { removeAsk = s }) { Icon(Icons.Filled.Delete, "Remove", tint = Muted) }
                        }
                    }
                }
            }
        }
    }
    if (addOpen) AddWebDialog(vm) { addOpen = false }
    removeAsk?.let { s ->
        AlertDialog(
            modifier = Modifier.dialogWidth(),
            onDismissRequest = { removeAsk = null },
            title = { Text("Remove web folder?") },
            text = { Text("${s.name} will be removed from this list. Nothing on the server is touched.") },
            confirmButton = { TextButton(onClick = { vm.removeWeb(s.id); removeAsk = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removeAsk = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun WebBrowser(vm: LibraryViewModel, nav: NavController, server: WebServer, startUrl: String?, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val lastUrl = remember { WebLast.get(ctx)?.url }
    var stackS by rememberSaveable(server.id) { mutableStateOf(if (startUrl != null && startUrl != server.url) server.url + "\n" + startUrl else server.url) }
    val stack = stackS.split("\n")
    var reload by remember { mutableIntStateOf(0) }
    val url = stack.last()
    val result by produceState<Result<List<WebEntry>>?>(null, url, reload) {
        value = null
        value = runCatching { vm.webList(server, url) }
    }
    var searching by remember(url) { mutableStateOf(false) }
    var query by remember(url) { mutableStateOf("") }
    // The remote's focus must land inside the new list: first row when going into a folder, the folder just left when going back.
    var focusUrl by remember { mutableStateOf<String?>(null) }
    val rowFocus = remember { FocusRequester() }
    val loadFocus = remember { FocusRequester() }
    val tvUi = LocalIsTv.current
    val memory = LocalFocusMemory.current
    val goBack = {
        if (searching) { searching = false; query = "" }
        else if (stack.size > 1) { focusUrl = url; stackS = stack.dropLast(1).joinToString("\n") } else onExit()
    }
    BackHandler { goBack() }
    val title = if (stack.size == 1) server.name else Uri.decode(url.trimEnd('/').substringAfterLast('/'))

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(
                title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { if (searching) { searching = false; query = "" } else searching = true }) {
                Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, if (searching) "Close search" else "Search this folder")
            }
            IconButton(onClick = { reload++ }) { Icon(Icons.Filled.Refresh, "Refresh") }
        }
        if (searching) {
            val focus = remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Search in this folder") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
                    .focusRequester(focus)
            )
        }
        val r = result
        when {
            r == null -> Box(Modifier.fillMaxSize().focusRequester(loadFocus).focusable(), contentAlignment = Alignment.Center) {
                // While a folder loads the remote's focus rests here, so it does not jump up to the Home tab and back.
                LaunchedEffect(url) { if (tvUi) runCatching { loadFocus.requestFocus() } }
                CircularProgressIndicator(color = Accent)
            }
            r.isFailure -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Couldn't open this folder.", fontWeight = FontWeight.Bold)
                Text(r.exceptionOrNull()?.message ?: "Unknown error", color = Muted)
                Button(onClick = { reload++ }, shape = RoundedCornerShape(16.dp)) { Text("Try again") }
            }
            else -> {
                val all = r.getOrThrow()
                val shown = all.filter { (it.dir || it.isVideo) && (query.isBlank() || it.name.contains(query.trim(), ignoreCase = true)) }
                val target = shown.firstOrNull { it.url == focusUrl } ?: shown.firstOrNull()
                LaunchedEffect(all) {
                    if (tvUi && !searching && memory?.armed != true && target != null) {
                        androidx.compose.runtime.withFrameNanos { }
                        runCatching { rowFocus.requestFocus() }
                    }
                }
                if (shown.isEmpty()) {
                    Text(if (query.isBlank()) "No folders or videos here." else "Nothing matches \"${query.trim()}\".", color = Muted, modifier = Modifier.padding(20.dp))
                } else LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(shown, key = { it.url }) { e ->
                        Row(
                            Modifier.fillMaxWidth()
                                .rememberFocus(e.url)
                                .then(if (e === target) Modifier.focusRequester(rowFocus) else Modifier)
                                .focusHighlight(RoundedCornerShape(12.dp))
                                .clickable {
                                    if (e.dir) { focusUrl = null; stackS = (stack + e.url).joinToString("\n") }
                                    else {
                                        WebLast.set(ctx, WebLastItem(server.id, url, e.url, e.name))
                                        nav.navigate("player/web/${vm.webOpen(server, all, e, url)}")
                                    }
                                }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (e.dir) Icons.Filled.Folder else Icons.Filled.PlayArrow, null,
                                tint = if (e.dir) Accent else Color.White
                            )
                            Spacer(Modifier.width(14.dp))
                            Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (e.url == lastUrl) Accent else Color.Unspecified)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddWebDialog(vm: LibraryViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    AlertDialog(
        modifier = Modifier.dialogWidth(),
        onDismissRequest = onDismiss,
        title = { Text("Add a web folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTextField(name, { name = it }, singleLine = true, label = { Text("Name (optional)") }, modifier = Modifier.fillMaxWidth())
                AppTextField(url, { url = it }, singleLine = true, label = { Text("Address, e.g. http://192.168.0.10/movies/") }, modifier = Modifier.fillMaxWidth())
                AppTextField(user, { user = it }, singleLine = true, label = { Text("Username (optional)") }, modifier = Modifier.fillMaxWidth())
                AppTextField(
                    pass, { pass = it }, singleLine = true, label = { Text("Password (optional)") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(enabled = url.isNotBlank(), onClick = { vm.addWeb(name, url, user, pass); onDismiss() }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}