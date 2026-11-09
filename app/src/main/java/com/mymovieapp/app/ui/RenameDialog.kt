package com.mymovieapp.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mymovieapp.app.*
import com.mymovieapp.app.data.*

@Composable
fun RenameDialog(vm: LibraryViewModel, videoId: Long, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val video by produceState<Video?>(null, videoId) { value = vm.videoById(videoId) }
    val v = video ?: return
    val ext = v.fileName.substringAfterLast('.', "")
    var text by remember(v.id) { mutableStateOf(v.fileName.substringBeforeLast('.')) }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!working) onDone() },
        title = { Text("Rename file") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it; error = null },
                    singleLine = true, enabled = !working, label = { Text("File name") },
                    suffix = { if (ext.isNotEmpty()) Text(".$ext") }
                )
                Text(
                    "This renames the real file, and the subtitle file next to it.",
                    color = Muted, style = MaterialTheme.typography.bodySmall
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !working && text.isNotBlank(),
                onClick = {
                    working = true
                    vm.renameVideo(v.id, text) { ok, msg ->
                        working = false
                        if (ok) {
                            msg?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
                            onDone()
                        } else error = msg
                    }
                }
            ) { Text(if (working) "Renaming…" else "Rename") }
        },
        dismissButton = { TextButton(onClick = onDone, enabled = !working) { Text("Cancel") } }
    )
}