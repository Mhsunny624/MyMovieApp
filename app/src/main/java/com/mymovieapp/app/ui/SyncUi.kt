package com.mymovieapp.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymovieapp.app.LibraryViewModel
import com.mymovieapp.app.data.CAST_PORT
import com.mymovieapp.app.data.CastSender
import com.mymovieapp.app.data.CastTv
import kotlinx.coroutines.launch

private val SYNC_ITEMS = listOf(
    "playlists" to "Live TV playlists (with kept groups, renames, moves, removed channels, pasted links)",
    "shares" to "SMB and FTP shares (with logins)",
    "web" to "Web folders (with logins)",
    "favs" to "Favourite channels and sources switched off",
    "keys" to "API keys (TMDB, subtitle sources)",
    "locks" to "Private locks and PIN"
)

/** Phone only (Settings): push this phone's Live TV playlists, shares and settings to a TV. */
@Composable
fun SyncToTvDialog(vm: LibraryViewModel, pull: Boolean = false, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tvs by CastSender.tvs.collectAsStateWithLifecycle()
    var tv by remember { mutableStateOf<CastTv?>(null) }
    var pinFor by remember { mutableStateOf<CastTv?>(null) }
    var pinText by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var mirror by remember { mutableStateOf(true) }
    var confirm by remember { mutableStateOf(false) }
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var picked by remember { mutableStateOf(SYNC_ITEMS.map { it.first }.toSet()) }
    var prog by remember { mutableStateOf<org.json.JSONObject?>(null) }
    val other = if (pull) "this phone" else "the TV"          // what ends up changed
    val source = if (pull) "the TV" else "this phone"         // where the data comes from

    DisposableEffect(Unit) {
        CastSender.startDiscovery(ctx)
        onDispose { CastSender.stopDiscovery() }
    }
    LaunchedEffect(Unit) { counts = vm.syncCounts() }

    fun connect(t: CastTv, p: String) {
        busy = true; status = "Connecting to ${t.name}…"
        scope.launch {
            val (ok, msg) = CastSender.post(t, p, "/ping")
            busy = false
            if (!ok) { status = msg; pinFor = if (msg.contains("PIN")) t else null; return@launch }
            CastSender.savePin(ctx, t, p)
            pin = p; tv = t; pinFor = null; status = null
        }
    }
    fun send() {
        val t = tv ?: return
        busy = true; status = if (pull) "Asking ${t.name}…" else "Sending to ${t.name}…"
        prog = null
        var finished = false
        // While the TV (or this phone, when pulling) applies the data, show how far it has got.
        scope.launch {
            while (!finished) {
                val st = if (pull) com.mymovieapp.app.data.SyncProgress.json() else CastSender.syncStatus(t, pin)
                if (st != null && st.optBoolean("running")) prog = st
                kotlinx.coroutines.delay(600)
            }
        }
        scope.launch {
            try {
                if (pull) {
                    val (j, err) = CastSender.pull(t, pin, picked, mirror)
                    busy = false
                    if (j == null) { status = err; return@launch }
                    status = try { vm.applySync(j) } catch (e: Exception) { "Failed: ${e.message ?: e.javaClass.simpleName}" }
                    done = true
                } else {
                    val payload = vm.buildSync(picked, mirror)
                    val (ok, msg) = CastSender.sync(t, pin, payload)
                    busy = false
                    status = if (ok) msg.ifBlank { "Done." } else msg
                    if (ok) done = true
                }
            } finally { finished = true; busy = false }
        }
    }
    // After a sync the loading carries on in the background (on the TV when sending, here when pulling): follow it.
    LaunchedEffect(done) {
        val t = tv
        if (!done || t == null) return@LaunchedEffect
        var misses = 0
        val until = System.currentTimeMillis() + 30 * 60_000L
        while (System.currentTimeMillis() < until) {
            val st = if (pull) com.mymovieapp.app.data.SyncProgress.json() else CastSender.syncStatus(t, pin)
            if (st == null) { if (++misses >= 5) break } else {
                misses = 0; prog = st
                if (!st.optBoolean("running")) break
            }
            kotlinx.coroutines.delay(1000)
        }
    }

    if (confirm) AlertDialog(
        modifier = Modifier.dialogWidth(),
        onDismissRequest = { confirm = false },
        title = { Text("Make $other match $source?") },
        text = { Text("For the ticked items, anything on $other that isn't on $source is removed from $other: playlists, network shares, web folders and subtitle sources. Folders and USB drives that are only on ${if (pull) "this phone" else "the TV"} are not touched.") },
        confirmButton = { TextButton(onClick = { confirm = false; send() }) { Text("Mirror") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
    )

    AlertDialog(
        modifier = Modifier.dialogWidth(),
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(if (pull) "Sync from TV" else "Sync to TV") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val t = tv
                if (t == null && pinFor == null) {
                    Text("TVs on this Wi-Fi with the app open", color = Muted, style = MaterialTheme.typography.bodySmall)
                    if (tvs.isEmpty()) Text("Looking… make sure the app is open on the TV and both are on the same Wi-Fi.", color = Muted)
                    tvs.forEach { x ->
                        TextButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                            val saved = CastSender.pinFor(ctx, x)
                            if (saved != null) connect(x, saved) else { pinFor = x; pinText = ""; status = null }
                        }) { Text(x.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    HorizontalDivider()
                    Text("Not listed? Type the TV's address (shown in the TV's Settings)", color = Muted, style = MaterialTheme.typography.bodySmall)
                    AppTextField(manual, { manual = it }, singleLine = true, placeholder = { Text("192.168.0.171") }, modifier = Modifier.fillMaxWidth())
                    TextButton(enabled = manual.isNotBlank() && !busy, onClick = {
                        val x = CastTv("TV ${manual.trim()}", manual.trim(), CAST_PORT)
                        val saved = CastSender.pinFor(ctx, x)
                        if (saved != null) connect(x, saved) else { pinFor = x; pinText = ""; status = null }
                    }) { Text("Connect") }
                } else if (t == null) {
                    val x = pinFor!!
                    Text("Enter the PIN shown in ${x.name}'s Settings → Cast from phone")
                    AppTextField(
                        pinText, { pinText = it.filter { ch -> ch.isDigit() }.take(8) }, singleLine = true,
                        label = { Text("PIN") }, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword)
                    )
                    Row {
                        TextButton(onClick = { pinFor = null; status = null }) { Text("Back") }
                        TextButton(enabled = pinText.length >= 4 && !busy, onClick = { connect(x, pinText) }) { Text("Connect") }
                    }
                } else if (!done && prog?.optBoolean("running") != true) {
                    Text(t.name, fontWeight = FontWeight.SemiBold)
                    Text(if (pull) "What to bring from the TV" else "What to send", color = Muted, style = MaterialTheme.typography.bodySmall)
                    SYNC_ITEMS.forEach { (k, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !busy) { picked = if (k in picked) picked - k else picked + k },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = k in picked, onCheckedChange = { picked = if (it) picked + k else picked - k }, enabled = !busy)
                            Column(Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                                if (!pull) counts[k]?.let { Text("$it on this phone", color = Muted, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { mirror = true }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mirror, onClick = { mirror = true }, enabled = !busy)
                        Column(Modifier.weight(1f)) {
                            Text("Mirror")
                            Text("$other ends up exactly like $source for the ticked items; extras on $other are removed.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { mirror = false }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !mirror, onClick = { mirror = false }, enabled = !busy)
                        Column(Modifier.weight(1f)) {
                            Text("Add what's missing")
                            Text("Only adds things $other doesn't have. Nothing is changed or removed.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(
                        "Logins and keys are encrypted with the TV's PIN while they cross the Wi-Fi. Lock your shares and sync locks together, or ${if (pull) "this phone" else "the TV"} will show them unlocked.",
                        color = Muted, style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(if (!done) (if (pull) "Syncing from ${t.name}…" else "Syncing to ${t.name}…") else (if (pull) "Synced from ${t.name}" else "Synced to ${t.name}"), fontWeight = FontWeight.SemiBold)
                    val p = prog
                    val total = p?.optInt("total") ?: 0
                    val running = p?.optBoolean("running") == true
                    if (p == null) Text("Checking progress…", color = Muted, style = MaterialTheme.typography.bodySmall)
                    else if (total == 0 && !running) Text("Nothing needed loading. All done.", fontWeight = FontWeight.SemiBold)
                    else if (total == 0) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Accent)
                        Text(p.optString("label"), color = Muted, style = MaterialTheme.typography.bodySmall)
                    } else {
                        val d = p.optInt("done")
                        if (running) {
                            val sub = p.optDouble("sub", 0.0).toFloat()
                            LinearProgressIndicator(progress = { ((d + sub) / total).coerceIn(0.02f, 1f) }, modifier = Modifier.fillMaxWidth(), color = Accent)
                            Text("${p.optString("label")}  ($d of $total)", color = Muted, style = MaterialTheme.typography.bodySmall)
                            p.optString("detail").takeIf { it.isNotBlank() }?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
                            Text("Big playlists take a few minutes. You can close this and keep using the app.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        } else {
                            LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth(), color = Accent)
                            Text("All done ($d of $total)", fontWeight = FontWeight.SemiBold)
                        }
                        val rs = p.optJSONArray("results")
                        if (rs != null) for (i in 0 until rs.length()) Text("• " + rs.getString(i), color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                status?.takeIf { !(done && prog != null) }?.let { Text(it, color = if (done || busy) Muted else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (tv != null && !done) TextButton(enabled = picked.isNotEmpty() && !busy, onClick = { if (mirror) confirm = true else send() }) { Text(if (pull) (if (mirror) "Mirror from TV" else "Add from TV") else (if (mirror) "Mirror to TV" else "Add to TV")) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text(if (done) "Close" else "Cancel") } }
    )
}
