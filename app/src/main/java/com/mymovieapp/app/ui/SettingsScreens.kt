package com.mymovieapp.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.mymovieapp.app.DupGroup
import com.mymovieapp.app.LibraryViewModel
import com.mymovieapp.app.data.Ftp
import com.mymovieapp.app.data.FtpStore
import com.mymovieapp.app.data.LibraryFolder
import com.mymovieapp.app.data.NetScan
import com.mymovieapp.app.data.Smb
import com.mymovieapp.app.data.SubtitleProviders
import com.mymovieapp.app.data.SubtitleSource
import com.mymovieapp.app.data.Thumbs
import com.mymovieapp.app.data.Title
import com.mymovieapp.app.data.isSeries
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: LibraryViewModel, nav: NavController) {
    val hasPin by vm.vault.hasPin.collectAsStateWithLifecycle()
    val unlocked by vm.vault.unlocked.collectAsStateWithLifecycle()
    var req by remember { mutableStateOf<PinRequest?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    var timing by remember { mutableStateOf(false) }
    val lockAfter by vm.vault.lockAfterSec.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        Text("Settings", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 16.dp, bottom = 12.dp))
        SettingsSection("Media", nav, listOf(
            Triple("Libraries", "Folders, USB and network shares", "settings/libraries"),
            Triple("Scan", "TMDB key and titles that need a match", "settings/scan"),
            Triple("Data & storage", "What this device keeps", "settings/storage")
        ))
        SettingsSection("Live TV", nav, listOf(
            Triple("Playlists", "M3U playlists that feed Live TV", "settings/livetv")
        ))
        if (com.mymovieapp.app.ui.LocalIsTv.current) CastReceiverSection()
        else {
            var syncOpen by remember { mutableStateOf(false) }
            var pullOpen by remember { mutableStateOf(false) }
            Text(
                "TV", color = Muted, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp)
            )
            Card(colors = CardDefaults.cardColors(containerColor = CardBg, contentColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                SettingsRow("Sync to TV", "Send playlists, shares and settings to your TV") { syncOpen = true }
                SettingsRow("Sync from TV", "Bring the TV's playlists, shares and settings to this phone") { pullOpen = true }
            }
            if (syncOpen) SyncToTvDialog(vm) { syncOpen = false }
            if (pullOpen) SyncToTvDialog(vm, pull = true) { pullOpen = false }
        }
        SettingsSection("Playback", nav, listOf(
            Triple("Playback", "Live TV player, autoplay", "settings/playback")
        ))
        SettingsSection("Stash", nav, listOf(
            Triple("Stash", "Browse your library by performer", "settings/stash")
        ))
        SettingsSection("Subtitles", nav, listOf(
            Triple("Subtitles", "Style and download sources", "settings/subtitles")
        ))
        if (hasPin) {
            Text(
                "PRIVATE", color = Muted, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val rows = if (!unlocked) listOf(Triple<String, String, () -> Unit>("Unlock private", "Enter your PIN to show locked sources", { req = PinRequest(PinMode.UNLOCK) }))
                else listOf(
                    Triple<String, String, () -> Unit>("Lock now", "Hide locked sources again", { vm.vault.lockNow() }),
                    Triple<String, String, () -> Unit>("Auto-lock", lockLabel(lockAfter), { timing = true }),
                    Triple<String, String, () -> Unit>("Change PIN", "Choose a new 4-digit PIN", { req = PinRequest(PinMode.CHANGE) }),
                    Triple<String, String, () -> Unit>("Remove PIN", "Unlocks everything and deletes the PIN", { confirmRemove = true })
                )
                rows.forEach { (t, sub, act) ->
                    Card(colors = CardDefaults.cardColors(containerColor = CardBg, contentColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                        SettingsRow(t, sub, act)
                    }
                }
            }
        }
        VaultDialogs(vm, req) { req = null }
        if (timing) LockTimingDialog(lockAfter, { vm.vault.setLockAfter(it) }, { timing = false })
        if (confirmRemove) AlertDialog(
            modifier = Modifier.dialogWidth(),
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove the PIN?") },
            text = { Text("Every locked source becomes visible again and the PIN is deleted.") },
            confirmButton = { TextButton(onClick = { vm.vault.clearPin(); confirmRemove = false }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } }
        )
    }
}

/** TV only: how the phone finds this TV ("Play on TV"). */
@Composable
private fun CastReceiverSection() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var pin by remember { mutableStateOf(com.mymovieapp.app.data.CastReceiver.pin(ctx)) }
    val ip = remember { com.mymovieapp.app.data.CastReceiver.localIp() ?: "not on Wi-Fi" }
    Text(
        "CAST FROM PHONE", color = Muted, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp)
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = CardBg, contentColor = Color.White), shape = RoundedCornerShape(16.dp)) {
            SettingsRow("PIN  $pin", "On the phone: Live TV → cast icon → this TV · address $ip:${com.mymovieapp.app.data.CAST_PORT}") { }
        }
        Card(colors = CardDefaults.cardColors(containerColor = CardBg, contentColor = Color.White), shape = RoundedCornerShape(16.dp)) {
            SettingsRow("New PIN", "Phones will need the new PIN") { pin = com.mymovieapp.app.data.CastReceiver.newPin(ctx) }
        }
    }
}

@Composable
private fun SettingsSection(title: String, nav: NavController, rows: List<Triple<String, String, String>>) {
    Text(
        title.uppercase(), color = Muted, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp)
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { (t, sub, r) ->
            Card(colors = CardDefaults.cardColors(containerColor = CardBg, contentColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                SettingsRow(t, sub) { nav.navigate(r) }
            }
        }
    }
}

@Composable
fun LibrariesScreen(vm: LibraryViewModel, nav: NavController) {
    val ctx = LocalContext.current
    val folders by vm.folders.collectAsStateWithLifecycle()
    val loose by vm.looseCount.collectAsStateWithLifecycle()
    val webServers by vm.webServers.collectAsStateWithLifecycle()
    val lockedKeys by vm.vault.locked.collectAsStateWithLifecycle()
    var pinReq by remember { mutableStateOf<PinRequest?>(null) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val volumes = remember { vm.mediaVolumes() }
    var smbDialog by remember { mutableStateOf(false) }
    var ftpDialog by remember { mutableStateOf(false) }
    var editFolder by remember { mutableStateOf<LibraryFolder?>(null) }
    var pendingVolume by remember { mutableStateOf<Pair<String, String>?>(null) }
    var speed by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { if (it != null) vm.addFolder(it) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.addFiles(it) }
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
    val askPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val v = pendingVolume
        pendingVolume = null
        if (ok && v != null) vm.addMediaVolume(v.first, v.second)
        else if (!ok) Toast.makeText(ctx, "Storage permission is needed to read your videos.", Toast.LENGTH_LONG).show()
    }
    fun addVolume(v: Pair<String, String>) {
        if (ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED) {
            vm.addMediaVolume(v.first, v.second)
        } else {
            pendingVolume = v
            askPerm.launch(perm)
        }
    }

    VaultDialogs(vm, pinReq) { pinReq = null }
    SettingsPage("Libraries", nav) {
        busy?.let { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent); Text(it, color = Muted) }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (folders.isEmpty() && loose == 0 && webServers.isEmpty()) Text("No sources yet.", color = Muted)
        folders.forEach { f ->
            val net = f.uri.startsWith("smb://") || f.uri.startsWith("ftp://")
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardBg)
                    .then(if (net && !LocalIsTv.current) Modifier.clickable { editFolder = f } else Modifier)
                    .padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.FolderOpen, null, tint = Accent)
                Text(f.name, Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                LockButton(f.uri in lockedKeys, f.name) { val want = f.uri !in lockedKeys; pinReq = PinRequest(PinMode.GATE) { vm.vault.setLocked(f.uri, want); if (want) vm.vault.lockNow() } }
                if (net) IconButton(onClick = { editFolder = f }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) { Icon(Icons.Filled.Edit, "Edit ${f.name}") }
                IconButton(onClick = { vm.removeFolder(f) }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) { Icon(Icons.Filled.Delete, "Remove ${f.name}") }
            }
        }
        webServers.forEach { w ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardBg)
                    .focusHighlight(RoundedCornerShape(16.dp)).clickable { nav.navigate("web") }
                    .padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Language, null, tint = Accent)
                Text(w.name, Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                LockButton("web:${w.id}" in lockedKeys, w.name) { val k = "web:${w.id}"; val want = k !in lockedKeys; pinReq = PinRequest(PinMode.GATE) { vm.vault.setLocked(k, want); if (want) vm.vault.lockNow() } }
                IconButton(onClick = { vm.removeWeb(w.id) }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) { Icon(Icons.Filled.Delete, "Remove ${w.name}") }
            }
        }
        if (loose > 0) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardBg).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.VideoLibrary, null, tint = Accent)
            Text("Individual videos ($loose)", Modifier.weight(1f).padding(horizontal = 12.dp))
            IconButton(onClick = { vm.removeLooseFiles() }, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) { Icon(Icons.Filled.Delete, "Remove videos") }
        }

        Text("Add a source", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        volumes.forEach { v ->
            val already = folders.any { it.uri == "media://${v.first}" }
            OutlinedButton(
                onClick = { addVolume(v) }, enabled = !already && busy == null,
                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)
            ) { Text(if (already) "${v.second} (added)" else "Device storage \u00b7 ${v.second}", fontWeight = FontWeight.SemiBold) }
        }
        OutlinedButton(onClick = { smbDialog = true }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
            Text("Network share (SMB)…", fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(onClick = { ftpDialog = true }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
            Text("FTP server…", fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(onClick = { nav.navigate("web") }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
            Text("Web folder (HTTP)…", fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(onClick = { pickFolder.launchSafe(ctx, null) }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
            Text("Folder (system picker)…")
        }
        OutlinedButton(onClick = { pickFiles.launchSafe(ctx, arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
            Text("Individual videos…")
        }
        OutlinedButton(onClick = { vm.rescanAll() }, enabled = busy == null, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Rescan all sources")
        }
        OutlinedButton(
            onClick = { testing = true; speed = null; scope.launch { speed = vm.smbSpeedTest(); testing = false } },
            enabled = !testing, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)
        ) { Text(if (testing) "Testing for 8 seconds…" else "Test network share speed") }
        speed?.let { Text(it, color = Muted) }
        Text(
            "Device storage reads what the system has indexed, including USB drives (sidecar subtitles aren't available there). " +
                "If a network share is offline, rescans keep your existing library.",
            color = Muted, style = MaterialTheme.typography.bodySmall
        )
    }
    if (smbDialog) SmbDialog(vm) { smbDialog = false }
    if (ftpDialog) FtpDialog(vm) { ftpDialog = false }
    editFolder?.let { f -> EditShareDialog(vm, f) { editFolder = null } }
}

/** Edit a saved network share (SMB or FTP): new address, folder or login. Library, posters and resume positions are kept. */
@Composable
private fun EditShareDialog(vm: LibraryViewModel, folder: LibraryFolder, onDismiss: () -> Unit) {
    val uri = remember { android.net.Uri.parse(folder.uri) }
    val isSmb = uri.scheme == "smb"
    val smb = remember { if (isSmb) Smb.split(uri) else null }
    val ftp = remember { if (!isSmb) Ftp.split(uri) else null }
    val smbCreds = remember { smb?.let { Smb.credsFor(it.first) } }
    val ftpCreds = remember { ftp?.let { FtpStore.get(it.first, it.second) } }
    var host by remember { mutableStateOf(smb?.first ?: ftp?.first ?: "") }
    var port by remember { mutableStateOf((ftp?.second ?: 21).toString()) }
    var share by remember { mutableStateOf(smb?.second ?: "") }
    var path by remember { mutableStateOf(if (isSmb) smb!!.third.replace('\\', '/') else ftp!!.third.let { if (it == "/") "" else it }) }
    var user by remember { mutableStateOf(smbCreds?.user ?: ftpCreds?.user ?: "") }
    var pass by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf(smbCreds?.domain ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    AlertDialog(
        modifier = Modifier.dialogWidth(),
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(if (isSmb) "Edit network share" else "Edit FTP server") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Your library, posters and resume positions are kept. If the address changes, every share on that PC moves with it.",
                    color = Muted, style = MaterialTheme.typography.bodySmall
                )
                AppTextField(host, { host = it }, label = { Text("Host or IP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (!isSmb) AppTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (isSmb) AppTextField(share, { share = it }, label = { Text("Share name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AppTextField(path, { path = it }, label = { Text(if (isSmb) "Folder inside share (optional)" else "Folder (e.g. /Movies)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AppTextField(user, { user = it }, label = { Text("Username (blank = guest)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AppTextField(pass, { pass = it }, label = { Text("Password (blank = keep the saved one)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                if (isSmb) AppTextField(domain, { domain = it }, label = { Text("Domain (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !working && host.isNotBlank() && (!isSmb || share.isNotBlank()),
                onClick = {
                    working = true; error = null
                    val finish: (String?) -> Unit = { e -> working = false; if (e == null) onDismiss() else error = e }
                    if (isSmb) vm.editSmb(folder, host, share, path, user, pass, domain, finish)
                    else vm.editFtp(folder, host, port.toIntOrNull() ?: 21, path, user, pass, finish)
                }
            ) { Text(if (working) "Connecting…" else "Save") }
        },
        dismissButton = { TextButton(enabled = !working, onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SmbDialog(vm: LibraryViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var share by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    val hosts = remember { mutableStateListOf<NetScan.Host>() }
    var scanning by remember { mutableStateOf(false) }
    var scanMsg by remember { mutableStateOf<String?>(null) }
    var shares by remember { mutableStateOf<List<String>>(emptyList()) }
    var finding by remember { mutableStateOf(false) }

    suspend fun doScan() {
        scanning = true; scanMsg = null; hosts.clear()
        val err = NetScan.findSmbHosts(ctx) { hosts.add(it) }
        hosts.sortBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 0 }
        scanMsg = err ?: if (hosts.isEmpty()) "No file servers found on this network. Type the address below." else null
        scanning = false
    }
    LaunchedEffect(Unit) { doScan() }

    AlertDialog(
        modifier = Modifier.dialogWidth(),
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("Network share (SMB)") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (scanning) "Looking for servers…" else "Servers on your network",
                        Modifier.weight(1f), fontWeight = FontWeight.SemiBold
                    )
                    if (scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Accent)
                    else TextButton(onClick = { scope.launch { doScan() } }) { Text("Rescan") }
                }
                if (hosts.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        hosts.forEach { h ->
                            FilterChip(
                                selected = host == h.ip,
                                onClick = { host = h.ip; shares = emptyList(); share = "" },
                                label = { Text(h.label) },
                                modifier = Modifier.focusHighlight(RoundedCornerShape(10.dp))
                            )
                        }
                    }
                }
                scanMsg?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }

                AppTextField(host, { host = it }, label = { Text("Host or IP (e.g. 192.168.1.10)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AppTextField(user, { user = it }, label = { Text("Username (blank = guest)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AppTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                AppTextField(domain, { domain = it }, label = { Text("Domain (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                OutlinedButton(
                    enabled = host.isNotBlank() && !finding, shape = RoundedCornerShape(14.dp),
                    onClick = {
                        finding = true; error = null
                        scope.launch {
                            try {
                                shares = NetScan.listShares(host.trim(), user.trim(), pass, domain.trim())
                                if (shares.isEmpty()) error = "No shared folders found."
                            } catch (e: Exception) {
                                error = e.message ?: "Couldn't list the shares. Type the share name below."
                            }
                            finding = false
                        }
                    }
                ) { Text(if (finding) "Looking…" else "Find shares") }
                if (shares.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        shares.forEach { s -> FilterChip(selected = share == s, onClick = { share = s }, label = { Text(s) }, modifier = Modifier.focusHighlight(RoundedCornerShape(10.dp))) }
                    }
                }

                AppTextField(share, { share = it }, label = { Text("Share name (e.g. Movies)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AppTextField(folder, { folder = it }, label = { Text("Folder inside share (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !working && host.isNotBlank() && share.isNotBlank(),
                onClick = {
                    working = true; error = null
                    vm.addSmb(host, share, folder, user, pass, domain) { e ->
                        working = false
                        if (e == null) onDismiss() else error = e
                    }
                }
            ) { Text(if (working) "Connecting…" else "Add") }
        },
        dismissButton = { TextButton(enabled = !working, onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ScanScreen(vm: LibraryViewModel, nav: NavController) {
    val key by vm.prefs.tmdbKey.collectAsStateWithLifecycle()
    val list by vm.unmatched.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var matching by remember { mutableStateOf<Title?>(null) }

    SettingsPage("Scan", nav) {
        Text("Posters and cast come from TMDB. Only the title and year are sent, never your files.", color = Muted)
        AppTextField(
            value = key, onValueChange = { vm.prefs.setTmdbKey(it) }, singleLine = true,
            label = { Text("TMDB API key or read token") }, modifier = Modifier.fillMaxWidth()
        )
        Text("Free at themoviedb.org → Settings → API.", color = Muted, style = MaterialTheme.typography.bodySmall)
        Button(onClick = { vm.matchNow() }, enabled = busy == null && key.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
            Text("Find posters now")
        }
        busy?.let { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent); Text(it, color = Muted) }
        Text("Titles that need a match (${list.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        if (list.isEmpty()) Text("Everything is matched.", color = Muted)
        list.forEach { t ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardBg).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    Text(t.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(if (t.isSeries) "Series" else "Film", t.year?.toString()).joinToString(" · "), color = Muted, style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = { matching = t }) { Text("Match") }
            }
        }
    }
    matching?.let { MatchDialog(vm, it) { matching = null } }
}
@Composable
private fun FtpDialog(vm: LibraryViewModel, onDismiss: () -> Unit) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("21") }
    var folder by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    AlertDialog(
        modifier = Modifier.dialogWidth(),
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("FTP server") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTextField(host, { host = it }, label = { Text("Host, IP or ftp:// link") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AppTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port (usually 21)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AppTextField(folder, { folder = it }, label = { Text("Folder (optional, e.g. /Movies)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AppTextField(user, { user = it }, label = { Text("Username (blank = anonymous)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AppTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Text("Plain FTP is not encrypted, so avoid passwords you use elsewhere.", color = Muted, style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !working && host.isNotBlank(),
                onClick = {
                    var h = host.trim(); var p = port.toIntOrNull() ?: 21
                    var fo = folder; var us = user; var pw = pass
                    if (h.startsWith("ftp://", ignoreCase = true)) {       // a pasted link fills in the rest
                        val u = android.net.Uri.parse(h)
                        h = u.host ?: h
                        if (u.port > 0) p = u.port
                        if (fo.isBlank()) fo = u.path.orEmpty()
                        u.userInfo?.let { ui -> if (us.isBlank()) us = ui.substringBefore(':'); if (pw.isBlank()) pw = ui.substringAfter(':', "") }
                    }
                    working = true; error = null
                    vm.addFtp(h, p, fo, us, pw) { e ->
                        working = false
                        if (e == null) onDismiss() else error = e
                    }
                }
            ) { Text(if (working) "Connecting…" else "Add") }
        },
        dismissButton = { TextButton(enabled = !working, onClick = onDismiss) { Text("Cancel") } }
    )
}
@Composable
fun LiveTvSettingsScreen(vm: LibraryViewModel, nav: NavController) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val counts by vm.playlistCounts.collectAsStateWithLifecycle()
    var playlistDialog by remember { mutableStateOf(false) }
    var removeAsk by remember { mutableStateOf<com.mymovieapp.app.data.Playlist?>(null) }

    SettingsPage("Live TV", nav) {
        busy?.let { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent); Text(it, color = Muted) }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Playlists", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Needs internet · feeds Live TV", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { playlistDialog = true }, shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add a playlist")
            }
        }
        if (playlists.isEmpty()) Text("No playlists yet.", color = Muted)
        removeAsk?.let { p ->
            AlertDialog(
                modifier = Modifier.okGuard(),
                onDismissRequest = { removeAsk = null },
                title = { Text("Remove source?") },
                text = {
                    OkGuardWindow()
                    Text("\u201c${p.name}\u201d and its ${counts[p.id] ?: 0} channels will be removed from the app. Favourites in it are lost too.")
                },
                confirmButton = { TextButton(onClick = { removeAsk = null; vm.removePlaylist(p) }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = { removeAsk = null }) { Text("Cancel") } }
            )
        }
        playlists.forEach { p ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardBg).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF7A2E2A)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.PlayArrow, null)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (p.url.isNotBlank()) Text(p.url, color = Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text("${counts[p.id] ?: 0} channels", color = Muted, modifier = Modifier.weight(1f))
                    if (p.url.isNotBlank()) IconButton(onClick = { vm.refreshPlaylist(p) }, enabled = busy == null) {
                        Icon(Icons.Filled.Refresh, "Refresh ${p.name}")
                    }
                    IconButton(onClick = { removeAsk = p }) { Icon(Icons.Filled.Delete, "Remove ${p.name}") }
                }
            }
        }
    }
    if (playlistDialog) AddPlaylistDialog(vm) { playlistDialog = false }
}

@Composable
fun PlaybackSettingsScreen(vm: LibraryViewModel, nav: NavController) {
    val autoplay by vm.prefs.autoplayNext.collectAsStateWithLifecycle()

    SettingsPage("Playback", nav) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Autoplay next episode", fontWeight = FontWeight.SemiBold)
                Text("Continue to the next episode when one ends", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = autoplay, onCheckedChange = { vm.prefs.setAutoplayNext(it) })
        }
        val livePlayer by vm.prefs.livePlayer.collectAsStateWithLifecycle()
        val crashedN by vm.prefs.vlcCrashed.collectAsStateWithLifecycle()
        Spacer(Modifier.height(16.dp))
        Text("Live TV player", fontWeight = FontWeight.SemiBold)
        Text("Auto uses ExoPlayer for web links and VLC for udp / rtsp / rtmp, and switches if one can't play a channel.", color = Muted, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            listOf("Auto", "ExoPlayer", "VLC").forEachIndexed { i, label ->
                FilterChip(selected = livePlayer == i, onClick = { vm.prefs.setLivePlayer(i) }, label = { Text(label) },
                    modifier = Modifier.focusHighlight(RoundedCornerShape(10.dp)))
            }
        }
        if (crashedN.isNotEmpty()) {
            Text("${crashedN.size} channel(s) crashed VLC and now use ExoPlayer.", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            TextButton(onClick = { vm.prefs.clearVlcCrashed() }) { Text("Forget crashed channels") }
        }
    }
}

@Composable
fun SubtitleSettingsScreen(vm: LibraryViewModel, nav: NavController) {
    val sources by vm.prefs.subSources.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SubtitleSource?>(null) }

    SettingsPage("Subtitles", nav) {
        Text("Style", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        SubtitleStyleControls(vm)

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Sources", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Used by the CC button in the player", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { adding = true }, shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add a source")
            }
        }
        if (sources.isEmpty()) Text("No sources yet. Tap Add source.", color = Muted)
        sources.forEach { s ->
            val typeLabel = SubtitleProviders.types.firstOrNull { it.type == s.type }?.label ?: s.type
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardBg).padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    Text(s.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!typeLabel.equals(s.label, ignoreCase = true)) Text(typeLabel, color = Muted, style = MaterialTheme.typography.labelMedium)
                }
                Switch(checked = s.enabled, onCheckedChange = { vm.prefs.upsertSource(s.copy(enabled = it)) })
                IconButton(onClick = { editing = s }) { Icon(Icons.Filled.Edit, "Edit ${s.label}") }
                IconButton(onClick = { vm.prefs.removeSource(s.id) }) { Icon(Icons.Filled.Delete, "Remove ${s.label}") }
            }
        }
        Text("Subtitle files next to a video (.srt / .vtt) are loaded automatically. Tracks inside the video appear under CC → On screen.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
    if (adding) SourceDialog(vm, null) { adding = false }
    editing?.let { SourceDialog(vm, it) { editing = null } }
}

@Composable
private fun SourceDialog(vm: LibraryViewModel, existing: SubtitleSource?, onDismiss: () -> Unit) {
    var type by remember { mutableStateOf(existing?.type ?: SubtitleProviders.types.first().type) }
    var label by remember { mutableStateOf(existing?.label ?: "") }
    var key by remember { mutableStateOf(existing?.key ?: "") }
    var user by remember { mutableStateOf(existing?.user ?: "") }
    var pass by remember { mutableStateOf(existing?.pass ?: "") }
    var url by remember { mutableStateOf(existing?.url ?: "") }
    var result by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val info = SubtitleProviders.types.first { it.type == type }

    fun draft() = SubtitleSource(
        existing?.id ?: "${type}_${System.currentTimeMillis()}", type, label.ifBlank { info.label },
        key.trim(), user.trim(), pass, url.trim(), existing?.enabled ?: true
    )

    AlertDialog(
        modifier = Modifier.dialogWidth(),
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add subtitle source" else "Edit source") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (existing == null) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SubtitleProviders.types.forEach { t ->
                            FilterChip(selected = type == t.type, onClick = { type = t.type; result = null }, label = { Text(t.label) }, modifier = Modifier.focusHighlight(RoundedCornerShape(10.dp)))
                        }
                    }
                }
                Text(info.hint, color = Muted, style = MaterialTheme.typography.bodySmall)
                AppTextField(label, { label = it }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (info.url) AppTextField(url, { url = it }, label = { Text("Search URL") }, modifier = Modifier.fillMaxWidth())
                if (info.key) AppTextField(key, { key = it }, label = { Text("API key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (info.login) {
                    AppTextField(user, { user = it }, label = { Text("Username (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    AppTextField(pass, { pass = it }, label = { Text("Password (optional)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                }
                result?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Works")) Accent else MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !info.url || url.isNotBlank(), onClick = { vm.prefs.upsertSource(draft()); onDismiss() }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(enabled = !testing, onClick = {
                    testing = true; result = null
                    scope.launch { result = vm.testSource(draft()); testing = false }
                }) { Text(if (testing) "Testing…" else "Test") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}



@Composable
fun StorageScreen(vm: LibraryViewModel, nav: NavController) {
    val stats by vm.stats.collectAsStateWithLifecycle()
    var dups by remember { mutableStateOf<List<DupGroup>?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var cacheTick by remember { mutableIntStateOf(0) }
    val cacheBytes by produceState(0L, cacheTick) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Thumbs.cacheBytes(ctx) }
    }
    SettingsPage("Data & storage", nav) {
        Text("This device keeps", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("${stats.first} titles · ${stats.second} files · ${stats.third} channels & links", color = Muted)
        Text("Your library index, posters (cached), and watch progress live only on this phone. Video files are never copied or uploaded.", color = Muted, style = MaterialTheme.typography.bodySmall)
        val autoThumbs by vm.prefs.autoThumbs.collectAsStateWithLifecycle()
        val thumbMsg by vm.thumbProgress.collectAsStateWithLifecycle()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Make thumbnails automatically", fontWeight = FontWeight.SemiBold)
                Text("After a share is connected or rescanned, makes a picture from each video that has no poster, in the background. Turn off to save bandwidth with heavy files.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = autoThumbs, onCheckedChange = { vm.prefs.setAutoThumbs(it) })
        }
        val hwDec by vm.prefs.hwDecoder.collectAsStateWithLifecycle()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Use hardware video decoder (experimental)", fontWeight = FontWeight.SemiBold)
                Text("Forces the device's own video chip instead of Android's software decoder. Smoother for big 4K files if it works; if a video then fails to play, turn this off.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = hwDec, onCheckedChange = { vm.prefs.setHwDecoder(it) })
        }
        Button(onClick = { vm.makeThumbs(force = true) }, enabled = thumbMsg == null, shape = RoundedCornerShape(16.dp)) {
            Text(thumbMsg ?: "Make thumbnails now")
        }
        val thumbResult by vm.thumbResult.collectAsStateWithLifecycle()
        thumbResult?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
        ConfirmButton(
            "Recreate all thumbnails",
            "Every picture made from your own videos is deleted and made again, which can take a while with many or large files. TMDB posters are not affected."
        ) { Thumbs.clearCache(ctx); cacheTick++; vm.makeThumbs(force = true) }
        ConfirmButton(
            "Clear poster-still cache (${"%.1f".format(cacheBytes / 1048576.0)} MB)",
            "Frames made from your own videos are removed and rebuilt when needed. TMDB posters are not affected."
        ) { Thumbs.clearCache(ctx); cacheTick++ }
        OutlinedButton(
            onClick = {
                scope.launch {
                    val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.mymovieapp.app.data.AppLog.build(ctx) }
                    val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("MyMovieApp log", text))
                    Toast.makeText(ctx, "App log copied", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)
        ) { Text("Copy app log") }
        Button(
            onClick = { scope.launch { dups = vm.findDuplicates() } },
            shape = RoundedCornerShape(16.dp)
        ) { Text("Find duplicates") }
        ConfirmButton("Clear watch progress", "Resume positions and watched marks will be reset.") { vm.clearProgress() }
        ConfirmButton("Clear channels & links", "All imported channels and pasted links will be removed.") { vm.clearChannels() }
        ConfirmButton("Reset library", "Folders, titles and progress will be removed. Your video files are not touched.") { vm.resetLibrary() }
        Text("MyMovieApp 1.0", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
        Text("This product uses the TMDB API but is not endorsed or certified by TMDB.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
    dups?.let { groups ->
        AlertDialog(
            modifier = Modifier.dialogWidth(),
            onDismissRequest = { dups = null },
            confirmButton = { TextButton(onClick = { dups = null }) { Text("Close") } },
            title = { Text(if (groups.isEmpty()) "No duplicates" else "${groups.size} duplicated") },
            text = {
                if (groups.isEmpty()) Text("Every title is stored only once.")
                else androidx.compose.foundation.lazy.LazyColumn {
                    items(groups.size) { i ->
                        val g = groups[i]
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Text(g.label, fontWeight = FontWeight.Bold)
                            g.copies.forEach { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        )
    }
}
