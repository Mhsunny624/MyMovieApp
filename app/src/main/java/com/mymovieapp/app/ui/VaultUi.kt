package com.mymovieapp.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymovieapp.app.LibraryViewModel

enum class PinMode { GATE, UNLOCK, CHANGE }

/** GATE: run [action] once the PIN is set / entered. UNLOCK: just unlock. CHANGE: choose a new PIN. */
class PinRequest(val mode: PinMode, val action: () -> Unit = {})

@Composable
fun VaultDialogs(vm: LibraryViewModel, req: PinRequest?, onClose: () -> Unit) {
    if (req == null) return
    val v = vm.vault
    val hasPin by v.hasPin.collectAsStateWithLifecycle()
    val unlocked by v.unlocked.collectAsStateWithLifecycle()
    var first by remember(req) { mutableStateOf<String?>(null) }
    var error by remember(req) { mutableStateOf<String?>(null) }
    var finished by remember(req) { mutableStateOf(false) }
    if (finished) return
    val creating = req.mode == PinMode.CHANGE || !hasPin
    if (!creating && req.mode == PinMode.GATE && unlocked) {
        LaunchedEffect(req) { finished = true; req.action(); onClose() }
        return
    }
    PinDialog(
        title = when {
            creating && first == null -> if (hasPin) "Choose a new PIN" else "Choose a 4-digit PIN"
            creating -> "Repeat the PIN"
            else -> "Enter PIN"
        },
        error = error,
        onDismiss = onClose
    ) { pin ->
        if (creating) {
            val f = first
            if (f == null) { first = pin; error = null }
            else if (f == pin) { finished = true; v.setPin(pin); v.unlocked.value = true; req.action(); onClose() }
            else { first = null; error = "The PINs didn't match. Try again." }
        } else {
            if (v.unlock(pin)) { finished = true; req.action(); onClose() } else error = "Wrong PIN"
        }
    }
}

/** A number pad made of buttons, so it works with a TV remote and needs no keyboard. */
@Composable
fun PinDialog(title: String, error: String?, onDismiss: () -> Unit, onPin: (String) -> Unit) {
    var pin by remember(title) { mutableStateOf("") }
    val start = remember { FocusRequester() }
    LaunchedEffect(title) { runCatching { start.requestFocus() } }
    fun press(d: String) {
        if (pin.length >= 4) return
        pin += d
        if (pin.length == 4) { val p = pin; pin = ""; onPin(p) }
    }
    // Back erases the last digit first and only closes the pad when it is empty; tapping outside doesn't close it.
    Dialog(
        onDismissRequest = { if (pin.isNotEmpty()) pin = pin.dropLast(1) else onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = false)
    ) {
        Surface(shape = RoundedCornerShape(24.dp), color = CardBg, contentColor = Color.White) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.height(20.dp)) {
                    repeat(4) { i -> Box(Modifier.size(16.dp).background(if (i < pin.length) Accent else Raised, CircleShape)) }
                }
                Text(error ?: " ", color = if (error != null) MaterialTheme.colorScheme.error else Muted, style = MaterialTheme.typography.bodyMedium)
                listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "<")).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { k ->
                            if (k.isEmpty()) Spacer(Modifier.size(64.dp))
                            else {
                                val shape = RoundedCornerShape(18.dp)
                                Box(
                                    Modifier.size(64.dp)
                                        .then(if (k == "5") Modifier.focusRequester(start) else Modifier)
                                        .focusHighlight(shape)
                                        .clip(shape)
                                        .background(Raised)
                                        .clickable { if (k == "<") pin = pin.dropLast(1) else press(k) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (k == "<") Icon(Icons.AutoMirrored.Filled.Backspace, "Delete")
                                    else Text(k, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    }
}

@Composable
fun LockButton(on: Boolean, name: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.focusHighlight(RoundedCornerShape(50))) {
        Icon(if (on) Icons.Filled.Lock else Icons.Filled.LockOpen, if (on) "Unlock $name" else "Lock $name", tint = if (on) Accent else Muted)
    }
}

val LOCK_CHOICES = listOf(0 to "Immediately", 30 to "After 30 seconds", 120 to "After 2 minutes", 600 to "After 10 minutes")
fun lockLabel(sec: Int) = LOCK_CHOICES.firstOrNull { it.first == sec }?.second ?: "After $sec seconds"

@Composable
fun LockTimingDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.dialogWidth(),
        onDismissRequest = onDismiss,
        title = { Text("Lock again") },
        text = {
            Column {
                Text("How long after you leave the app?", color = Muted)
                Spacer(Modifier.height(8.dp))
                LOCK_CHOICES.forEach { (sec, label) ->
                    Row(
                        Modifier.fillMaxWidth().focusHighlight(RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(sec); onDismiss() }.padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = sec == current, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
