package com.mymovieapp.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymovieapp.app.LibraryViewModel
import com.mymovieapp.app.PlaylistDraft

/** Step 1: paste a link or pick a file. Step 2: preview, choose which groups to keep, add. */
@Composable
fun AddPlaylistDialog(vm: LibraryViewModel, onDismiss: () -> Unit) {
    val draft by vm.draft.collectAsStateWithLifecycle()
    val busy by vm.draftBusy.collectAsStateWithLifecycle()
    val error by vm.draftError.collectAsStateWithLifecycle()
    val clip = LocalClipboardManager.current
    var url by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        clip.getText()?.text?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }?.let { url = it }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.loadPlaylistFile(uri)
    }
    DisposableEffect(Unit) { onDispose { vm.cancelDraft() } }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp), color = Color(0xFF16161A),
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f).widthIn(max = 680.dp)
        ) {
            val d = draft
            if (d == null) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Add a playlist", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { if (!busy) onDismiss() }) { Icon(Icons.Filled.Close, "Close") }
                    }
                    Text("Paste an .m3u link, or choose a playlist file.", color = Muted)
                    OutlinedTextField(
                        url, { url = it }, singleLine = true, placeholder = { Text("https://…/playlist.m3u") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent); Text("Loading… large playlists take a moment.", color = Muted) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(enabled = !busy && url.isNotBlank(), onClick = { vm.loadPlaylistUrl(url) }, shape = RoundedCornerShape(14.dp)) { Text("Load") }
                        OutlinedButton(enabled = !busy, onClick = { pickFile.launch(arrayOf("*/*")) }, shape = RoundedCornerShape(14.dp)) { Text("Choose file…") }
                    }
                }
            } else {
                PreviewStep(
                    d, busy,
                    onBack = { vm.cancelDraft() },
                    onCancel = onDismiss,
                    onAdd = { name, groups, renames -> vm.commitDraft(name, groups, renames) { onDismiss() } }
                )
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(CardBg).padding(14.dp)) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        Text(label, color = Muted)
    }
}

@Composable
private fun PreviewStep(
    d: PlaylistDraft, busy: Boolean,
    onBack: () -> Unit, onCancel: () -> Unit, onAdd: (String, Set<String>, Map<String, String>) -> Unit
) {
    var name by remember(d) { mutableStateOf(d.name) }
    val all = remember(d) { d.groups.map { it.first }.toSet() }
    var selected by remember(d) { mutableStateOf(all) }
    val count = d.groups.filter { it.first in selected }.sumOf { it.second }
    val newNames = remember(d) { mutableStateMapOf<String, String>() }      // original group name -> the name you chose
    var editing by remember(d) { mutableStateOf<String?>(null) }
    var editText by remember(d) { mutableStateOf("") }

    editing?.let { g ->
        val t = editText.trim()
        val merges = t.isNotBlank() && d.groups.any { it.first != g && (newNames[it.first] ?: it.first).equals(t, ignoreCase = true) }
        AlertDialog(
            modifier = Modifier.dialogWidth(),
            onDismissRequest = { editing = null },
            title = { Text("Rename group") },
            text = {
                Column {
                    AppTextField(editText, { editText = it }, label = { Text("Group name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Original: $g", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    if (merges) Text("Another group has this name. They will be merged.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = t.isNotBlank(), onClick = {
                    if (t == g) newNames.remove(g) else newNames[g] = t
                    editing = null
                }) { Text("Rename") }
            },
            dismissButton = {
                Row {
                    if (newNames.containsKey(g)) TextButton(onClick = { newNames.remove(g); editing = null }) { Text("Reset") }
                    TextButton(onClick = { editing = null }) { Text("Cancel") }
                }
            }
        )
    }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Add a playlist", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("%,d".format(d.channels.size), "channels", Modifier.weight(1f))
            Stat("${d.groups.size}", "groups", Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Show these groups", Modifier.weight(1f), color = Muted)
            TextButton(onClick = { selected = if (selected.isEmpty()) all else emptySet() }) {
                Text(if (selected.isEmpty()) "Select all" else "Select none", color = Accent)
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(d.groups, key = { it.first }) { (g, n) ->
                val on = g in selected
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).focusHighlight(RoundedCornerShape(14.dp))
                        .clickable { selected = if (on) selected - g else selected + g }
                        .background(CardBg).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = on, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = Accent, checkmarkColor = OnAccent))
                    Spacer(Modifier.width(12.dp))
                    val shown = newNames[g]
                    Column(Modifier.weight(1f)) {
                        Text(shown ?: g, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, color = if (shown != null) Accent else Color.Unspecified)
                        if (shown != null) Text("was $g", color = Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("%,d".format(n), color = Muted)
                    IconButton(onClick = { editText = shown ?: g; editing = g }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) {
                        Icon(Icons.Filled.Edit, "Rename $g", tint = Muted)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = !busy, onClick = onBack) { Text("Back") }
            Spacer(Modifier.weight(1f))
            OutlinedButton(enabled = !busy, onClick = onCancel) { Text("Cancel") }
            Button(enabled = !busy && count > 0, onClick = { onAdd(name, selected, newNames.toMap()) }, shape = RoundedCornerShape(14.dp)) {
                Text(if (busy) "Adding…" else "Add %,d channels".format(count), maxLines = 1)
            }
        }
    }
}
