package com.mymovieapp.app.ui

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import java.io.File
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
import coil.compose.AsyncImage
import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import com.mymovieapp.app.*
import com.mymovieapp.app.data.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

val Accent = Color(0xFFF59E42)
val Bg = Color(0xFF0C0C0E)
val CardBg = Color(0xFF1A1A1F)
val Muted = Color(0xFF9A9AA3)
val OnAccent = Color(0xFF1F1200)
val Raised = Color(0xFF26262C)
val Danger = Color(0xFFE5484D)

@Composable
fun LibraryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, onPrimary = OnAccent,
            background = Bg, onBackground = Color.White,
            surface = Bg, onSurface = Color.White,
            surfaceVariant = CardBg, onSurfaceVariant = Muted,
            secondary = Accent, onSecondary = OnAccent, secondaryContainer = Accent, onSecondaryContainer = OnAccent,
            tertiary = Accent, onTertiary = OnAccent, primaryContainer = Color(0xFF3A2A18), onPrimaryContainer = Accent,
            outline = Color(0xFF55555E), outlineVariant = Color(0xFF2E2E35),
            surfaceContainer = CardBg, surfaceContainerHigh = CardBg, surfaceContainerHighest = CardBg
        ),
        content = content
    )
}

fun fmtTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
fun fmtLen(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${maxOf(m, 1)}m"
}

@Composable
fun DurationBadge(ms: Long, modifier: Modifier = Modifier) {
    if (ms <= 0) return
    Text(
        fmtLen(ms), color = Color.White, style = MaterialTheme.typography.labelSmall,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xAA000000)).padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
fun LogoHeader(trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!LocalIsTv.current) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(Accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.VideoLibrary, null, tint = OnAccent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
        }
        Text("MyMovieApp", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun Poster(url: String?, name: String, modifier: Modifier = Modifier, thumbFor: String? = null) {
    val ctx = LocalContext.current
    // No TMDB poster? Use a still from the video itself.
    // Only shows stills that are already made: they are made in the background after a scan, never while scrolling.
    val thumb by produceState<File?>(null, url, thumbFor) {
        if (url != null || thumbFor == null) { value = null; return@produceState }
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Thumbs.cached(ctx, thumbFor) }
        if (value == null) {
            Thumbs.awaitCreated(thumbFor)
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Thumbs.cached(ctx, thumbFor) }
        }
    }
    Box(
        modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(CardBg),
        contentAlignment = Alignment.Center
    ) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (thumb != null) {
            AsyncImage(model = thumb, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                name, color = Muted, textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterCard(item: LibraryItem, onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null) {
    val tvKeys = LocalIsTv.current
    Column(modifier.rememberFocus("t${item.title.id}").focusHighlight().tvLongPress(onLongClick).combinedClickable(onClick = onClick, onLongClick = if (tvKeys) null else onLongClick).padding(4.dp)) {
        Box {
            Poster(item.title.posterUrl, item.title.name, Modifier.fillMaxWidth(), thumbFor = item.videoUri)
            if (item.watched) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xAA000000)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Check, null, tint = Accent, modifier = Modifier.size(14.dp)) }
            }
            if (!item.title.isSeries) DurationBadge(item.durationMs, Modifier.align(Alignment.BottomEnd).padding(6.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(item.title.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
        val sub = if (item.title.isSeries) "${item.files} episodes" else item.title.year?.toString().orEmpty()
        Text(sub, color = Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** A poster card that plays/opens on tap and shows a poster preview on long press. */
@Composable
fun TitleCard(vm: LibraryViewModel, nav: androidx.navigation.NavController, item: LibraryItem, modifier: Modifier = Modifier) {
    var preview by remember { mutableStateOf(false) }
    PosterCard(item, { vm.openTitle(item.title) { nav.navigate(it) } }, modifier, onLongClick = { preview = true })
    if (preview) PosterPreview(vm, nav, item) { preview = false }
}

@Composable
fun PosterPreview(vm: LibraryViewModel, nav: androidx.navigation.NavController, item: LibraryItem, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val t = item.title
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val thumb by produceState<File?>(null, t.posterUrl, item.videoUri) {
        value = if (t.posterUrl == null && item.videoUri != null) Thumbs.get(ctx, item.videoUri) else null
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.okGuard(), shape = RoundedCornerShape(24.dp), color = CardBg) {
        OkGuardWindow()
            Column(
                Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when {
                    t.posterUrl != null -> Poster(t.posterUrl, t.name, Modifier.fillMaxWidth(0.7f))
                    thumb != null -> AsyncImage(
                        model = thumb, contentDescription = t.name, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    )
                    else -> Poster(null, t.name, Modifier.fillMaxWidth(0.7f))
                }
                Text(t.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(
                    if (t.isSeries) "Series · ${item.files} episodes" else listOfNotNull("Film", t.year?.toString()).joinToString(" · "),
                    color = Muted, style = MaterialTheme.typography.labelLarge
                )
                t.overview?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = Muted, maxLines = 5, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!t.isSeries && item.videoId != 0L) {
                        Button(onClick = { onDismiss(); nav.navigate("player/file/${item.videoId}") }, shape = RoundedCornerShape(14.dp)) {
                            Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play")
                        }
                    }
                    OutlinedButton(onClick = { onDismiss(); nav.navigate("detail/${t.id}") }, shape = RoundedCornerShape(14.dp)) { Text("Details") }
                    if (!t.isSeries && item.videoId != 0L) {
                        OutlinedIconButton(onClick = { rename = true }) { Icon(Icons.Filled.Edit, "Rename file") }
                    }
                }
                if (!t.isSeries && item.videoUri != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        OpenElsewhereButton(item.videoUri, t.name, label = "Open in another player") { onDismiss() }
                        if (item.videoId != 0L) {
                            OutlinedIconButton(onClick = { delete = true }) { Icon(Icons.Filled.Delete, "Delete file", tint = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }
    if (rename) RenameDialog(vm, item.videoId) { rename = false; onDismiss() }
    if (delete) DeleteFileDialog(vm, item.videoId, t.name) { ok -> delete = false; if (ok) onDismiss() }
}

/** Asks first, then deletes the real file from where it is stored and removes it from the library. */
@Composable
fun DeleteFileDialog(vm: LibraryViewModel, videoId: Long, name: String, onDone: (Boolean) -> Unit) {
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        modifier = Modifier.dialogWidth(),
        onDismissRequest = { if (!working) onDone(false) },
        title = { Text("Delete this file?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, fontWeight = FontWeight.SemiBold)
                Text(
                    "This deletes the actual file from where it is stored (your PC share, FTP server or phone) and removes it from the library. It cannot be undone.",
                    color = Muted
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !working,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                onClick = {
                    working = true; error = null
                    vm.deleteVideoFile(videoId) { e -> working = false; if (e == null) onDone(true) else error = e }
                }
            ) { Text(if (working) "Deleting…" else "Delete file") }
        },
        dismissButton = { TextButton(enabled = !working, onClick = { onDone(false) }) { Text("Cancel") } }
    )
}

/** Every popup dialog is at least this wide, so they don't jump between narrow and wide. */
fun Modifier.dialogWidth(): Modifier = this.widthIn(min = 340.dp)

@Composable
fun SettingsRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().focusHighlight(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Muted)
    }
}

@Composable
fun SettingsPage(title: String, nav: androidx.navigation.NavController, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }, modifier = Modifier.focusHighlight(CircleShape, fill = false)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun ConfirmButton(label: String, message: String, onConfirm: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(label) }
    if (open) AlertDialog(
        modifier = Modifier.dialogWidth(),
        onDismissRequest = { open = false },
        title = { Text(label) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { onConfirm(); open = false }) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } }
    )
}

@Composable
fun MatchDialog(vm: LibraryViewModel, title: Title, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf(title.name) }
    var hits by remember { mutableStateOf<List<Tmdb.Hit>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val key by vm.prefs.tmdbKey.collectAsStateWithLifecycle()
    fun run() { scope.launch { hits = vm.searchTmdb(title.kind, q); searched = true } }
    LaunchedEffect(Unit) { if (key.isNotBlank()) run() }

    AlertDialog(
        modifier = Modifier.dialogWidth(),
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Match “${title.name}”") },
        text = {
            Column {
                if (key.isBlank()) {
                    Text("Add your TMDB key in Settings → Scan first.", color = Muted)
                } else {
                    AppTextField(
                        q, { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        trailingIcon = { IconButton(onClick = { run() }) { Icon(Icons.Filled.Search, "Search") } },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { run() })
                    )
                    if (searched && hits.isEmpty()) Text("No results.", color = Muted, modifier = Modifier.padding(top = 12.dp))
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(hits) { h ->
                            Row(
                                Modifier.fillMaxWidth().focusHighlight(RoundedCornerShape(12.dp)).clickable { vm.rematch(title, h.id); onDismiss() }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Poster(h.posterUrl, h.title, Modifier.width(44.dp))
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(h.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                    Text(h.year?.toString().orEmpty(), color = Muted, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}

/** True on Android TV / Google TV. Provided by MainActivity. */
val LocalIsTv = compositionLocalOf { false }

/** Marks a modifier chain that already has a focus ring, so the shared TV controls don't add a second one. */
object FocusRingMarker : Modifier.Element

private fun androidx.compose.ui.graphics.Outline.toRingPath(): androidx.compose.ui.graphics.Path = when (this) {
    is androidx.compose.ui.graphics.Outline.Generic -> path
    is androidx.compose.ui.graphics.Outline.Rounded -> androidx.compose.ui.graphics.Path().apply { addRoundRect(roundRect) }
    is androidx.compose.ui.graphics.Outline.Rectangle -> androidx.compose.ui.graphics.Path().apply { addRect(rect) }
}

/**
 * TV focus style, used by every focusable item: an accent ring (thicker on small items), a slight zoom,
 * and a lighter fill on rows and buttons. It reacts to D-pad focus of the next focusable in the chain.
 */
fun Modifier.focusHighlight(shape: Shape = RoundedCornerShape(12.dp), ring: Color = Accent, fill: Boolean = true): Modifier =
    this.then(FocusRingMarker).then(composed {
        var focused by remember { mutableStateOf(false) }
        val p by androidx.compose.animation.core.animateFloatAsState(
            if (focused) 1f else 0f,
            // On TV the highlight jumps at once: a fade leaves the old and new item both lit for a moment (looks like ghosting).
            if (LocalIsTv.current) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(110), label = "focusRing"
        )
        Modifier
            .zIndex(if (focused) 1f else 0f)
            .onFocusChanged { focused = it.hasFocus }
            .graphicsLayer {
                val big = size.width > 300.dp.toPx()
                val s = 1f + (if (big) 0f else 0.06f) * p
                scaleX = s; scaleY = s
            }
            .drawWithContent {
                drawContent()
                if (p > 0.01f) {
                    val small = minOf(size.width, size.height) < 64.dp.toPx()
                    val w = (if (small) 4.dp else 3.dp).toPx()
                    if (fill && size.height < 110.dp.toPx()) {
                        drawPath(shape.createOutline(size, layoutDirection, this).toRingPath(), Color.White.copy(alpha = 0.12f * p))
                    }
                    inset(w / 2f) {
                        val path = shape.createOutline(this.size, layoutDirection, this).toRingPath()
                        drawPath(path, ring.copy(alpha = p), style = androidx.compose.ui.graphics.drawscope.Stroke(w))
                    }
                }
            }
    })

/** Launches a system picker, but explains instead of crashing on devices (many TVs) that don't have one. */
fun <I> ManagedActivityResultLauncher<I, *>.launchSafe(ctx: Context, input: I) {
    try {
        launch(input)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "This device has no system file picker. Use Device storage or Network share instead.", Toast.LENGTH_LONG).show()
    }
}


/**
 * Text field that, on a TV, only opens the keyboard when you press OK on it (moving over it with the
 * remote just highlights it). On a phone it is the normal text field.
 */
@Composable
fun AppTextField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    label: (@Composable () -> Unit)? = null, placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null, trailingIcon: (@Composable () -> Unit)? = null,
    isError: Boolean = false, visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false, shape: Shape = OutlinedTextFieldDefaults.shape,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors()
) {
    if (!LocalIsTv.current) {
        OutlinedTextField(
            value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled, label = label,
            placeholder = placeholder, leadingIcon = leadingIcon, trailingIcon = trailingIcon, isError = isError,
            visualTransformation = visualTransformation, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
            singleLine = singleLine, shape = shape, colors = colors
        )
        return
    }
    var editing by remember { mutableStateOf(false) }
    var wasFocused by remember { mutableStateOf(false) }
    var gateFocused by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val gate = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    LaunchedEffect(editing) { if (editing) field.requestFocus() }
    fun finish() { editing = false; kb?.hide(); try { gate.requestFocus() } catch (_: Exception) {} }
    Box(
        modifier.focusRequester(gate).onFocusChanged { gateFocused = it.hasFocus }.focusHighlight(shape, fill = false)
            .clickable(enabled = enabled && !editing) { editing = true }
    ) {
        OutlinedTextField(
            value = value, onValueChange = onValueChange, enabled = enabled, label = label,
            placeholder = placeholder, leadingIcon = leadingIcon, trailingIcon = trailingIcon, isError = isError,
            visualTransformation = visualTransformation, keyboardOptions = keyboardOptions,
            keyboardActions = KeyboardActions(
                onDone = { keyboardActions.onDone?.invoke(this); finish() },
                onGo = { keyboardActions.onGo?.invoke(this); finish() },
                onNext = { keyboardActions.onNext?.invoke(this); finish() },
                onSearch = { keyboardActions.onSearch?.invoke(this); finish() },
                onSend = { keyboardActions.onSend?.invoke(this); finish() },
                onPrevious = { keyboardActions.onPrevious?.invoke(this); finish() }
            ),
            singleLine = singleLine, shape = shape,
            // While the remote is on the field the orange ring is its border, so the field's own outline is hidden (no double border).
            colors = if (gateFocused && !editing) colors.copy(unfocusedIndicatorColor = Color.Transparent, focusedIndicatorColor = Color.Transparent) else colors,
            modifier = Modifier.fillMaxWidth()
                .focusRequester(field)
                .focusProperties { canFocus = editing }
                .onFocusChanged {
                    if (it.isFocused) wasFocused = true
                    else if (wasFocused) { wasFocused = false; editing = false }
                }
        )
    }
}


// ---------- TV remote helpers ----------

/** Set when the player screen is left, so a list can tell "came back from the player" from "switched tab". */
object FocusReturn {
    @Volatile private var at = 0L
    fun mark() { at = android.os.SystemClock.uptimeMillis() }
    fun fresh(): Boolean = android.os.SystemClock.uptimeMillis() - at < 4000
}

class FocusMemory(last: String = "", armed: Boolean = false) {
    var last by mutableStateOf(last)
    var armed by mutableStateOf(armed)
}

val LocalFocusMemory = compositionLocalOf<FocusMemory?> { null }

/** Remembers which item had the remote's focus, and puts focus back on it after returning from the player. */
@Composable
fun WithFocusMemory(content: @Composable () -> Unit) {
    val saved = rememberSaveable { mutableStateOf("") }
    val mem = remember { FocusMemory(saved.value, saved.value.isNotEmpty() && FocusReturn.fresh()) }
    LaunchedEffect(mem.last) { saved.value = mem.last }
    LaunchedEffect(mem) { delay(3000); mem.armed = false }
    CompositionLocalProvider(LocalFocusMemory provides mem) { Opaque { content() } }
}

/** A screen paints its own solid background, so the screen it replaces can never show through while it is still drawing in. */
@Composable
fun Opaque(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Bg)) { content() }
}

/** Put this before focusHighlight / clickable on a list item. TV only. */
@Composable
fun Modifier.rememberFocus(key: String): Modifier {
    val mem = LocalFocusMemory.current
    if (mem == null || !LocalIsTv.current) return this
    val fr = remember { FocusRequester() }
    if (mem.armed && mem.last == key) {
        LaunchedEffect(Unit) {
            delay(60)
            runCatching { fr.requestFocus() }
            mem.armed = false
        }
    }
    return this.focusRequester(fr).onFocusChanged { if (it.hasFocus) mem.last = key }
}

/** After a long-press opens a menu while OK is still held, the held key must not press a button in that menu. */
object PressGuard {
    @Volatile var armedAt = 0L
    fun arm() { armedAt = System.currentTimeMillis() }
    fun clear() { armedAt = 0L }
    val active get() = armedAt != 0L && System.currentTimeMillis() - armedAt < 8000
}

private fun isOkKey(k: Int) = k == android.view.KeyEvent.KEYCODE_DPAD_CENTER || k == android.view.KeyEvent.KEYCODE_ENTER ||
    k == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER

/** Call inside a Dialog: swallows the OK key at window level until it has been let go once after a long-press. */
@Composable
fun OkGuardWindow() {
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view) {
        val w = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        val old = w?.callback
        if (w != null && old != null) {
            w.callback = object : android.view.Window.Callback by old {
                override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                    if (isOkKey(event.keyCode) && PressGuard.active) {
                        if (event.action == android.view.KeyEvent.ACTION_UP) PressGuard.clear()
                        return true
                    }
                    return old.dispatchKeyEvent(event)
                }
            }
        }
        onDispose { if (w != null && old != null) w.callback = old }
    }
}

/** Put on a menu opened by a long-press: ignores the OK key until it has been let go once. */
fun Modifier.okGuard(): Modifier = this.onPreviewKeyEvent { ev ->
    if (!isOkKey(ev.nativeKeyEvent.keyCode) || !PressGuard.active) false
    else { if (ev.type == KeyEventType.KeyUp) PressGuard.clear(); true }
}

/** TV: holding OK (centre / enter) on an item runs onLong the moment the hold is recognised. Put this before the clickable. */
@Composable
fun Modifier.tvLongPress(onLong: (() -> Unit)?): Modifier {
    if (onLong == null || !LocalIsTv.current) return this
    val cur by rememberUpdatedState(onLong)
    var fired by remember { mutableStateOf(false) }
    return this.onPreviewKeyEvent { ev ->
        val k = ev.nativeKeyEvent.keyCode
        if (!isOkKey(k)) false
        else when (ev.type) {
            KeyEventType.KeyDown -> when {
                ev.nativeKeyEvent.repeatCount == 0 -> { fired = false; false }
                fired -> true
                else -> { fired = true; PressGuard.arm(); cur(); true }
            }
            // A "cancelled" up arrives when the menu window takes over while OK is still held: the guard must stay on then.
            KeyEventType.KeyUp -> if (fired) { fired = false; if (!ev.nativeKeyEvent.isCanceled) PressGuard.clear(); true } else false
            else -> false
        }
    }
}
