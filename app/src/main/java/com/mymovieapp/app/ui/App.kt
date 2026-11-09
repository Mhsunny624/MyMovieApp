package com.mymovieapp.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mymovieapp.app.LibraryViewModel

private data class NavTab(val route: String, val label: String, val icon: ImageVector)

@Composable
fun LibraryApp(vm: LibraryViewModel = viewModel()) {
    val nav = rememberNavController()
    val tv = LocalIsTv.current
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val tabs = listOf(
        NavTab("library", "Library", Icons.Filled.GridView),
        NavTab("live", "Live", Icons.Filled.Tv),
        NavTab("settings", "Settings", Icons.Filled.Settings)
    )
    val showBar = tabs.any { it.route == route }
    // remember when we come back from the player, so the TV list can put focus back on the item that was opened
    DisposableEffect(nav) {
        var prev: String? = null
        val l = NavController.OnDestinationChangedListener { _, d, _ ->
            val r = d.route
            if (prev?.startsWith("player") == true && r?.startsWith("player") != true) FocusReturn.mark()
            prev = r
        }
        nav.addOnDestinationChangedListener(l)
        onDispose { nav.removeOnDestinationChangedListener(l) }
    }
    val isPlayer = route?.startsWith("player") == true
    val incoming by vm.incoming.collectAsStateWithLifecycle()
    LaunchedEffect(incoming) {
        val raw = incoming ?: return@LaunchedEffect
        vm.incoming.value = null
        vm.openIncoming(raw) { r -> nav.navigate(r) }
    }
    // TV: things sent from the phone ("Play on TV" and the remote)
    val castCtx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        com.mymovieapp.app.data.CastBus.events.collect { e ->
            val onPlayer = nav.currentDestination?.route?.startsWith("player") == true
            if (e.kind == "play") {
                vm.openCast(e) { r ->
                    if (onPlayer) nav.navigate(r) { popUpTo("player/{type}/{id}") { inclusive = true } } else nav.navigate(r)
                }
            } else {
                var act: android.app.Activity? = null
                var cc: android.content.Context? = castCtx
                while (cc is android.content.ContextWrapper) { if (cc is android.app.Activity) { act = cc; break }; cc = cc.baseContext }
                fun padKey(m: String) {
                    val code = when (m) {
                        "up" -> android.view.KeyEvent.KEYCODE_DPAD_UP; "down" -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
                        "left" -> android.view.KeyEvent.KEYCODE_DPAD_LEFT; "right" -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                        "back" -> android.view.KeyEvent.KEYCODE_BACK; else -> android.view.KeyEvent.KEYCODE_DPAD_CENTER
                    }
                    // Injected like a real remote key, so it reaches whatever has focus, including open dialogs.
                    Thread {
                        try {
                            val inst = android.app.Instrumentation()
                            if (m == "okhold") {
                                val t = android.os.SystemClock.uptimeMillis()
                                inst.sendKeySync(android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, code, 0))
                                Thread.sleep(150)
                                inst.sendKeySync(android.view.KeyEvent(t, android.os.SystemClock.uptimeMillis(), android.view.KeyEvent.ACTION_DOWN, code, 1))
                                Thread.sleep(80)
                                inst.sendKeySync(android.view.KeyEvent(t, android.os.SystemClock.uptimeMillis(), android.view.KeyEvent.ACTION_UP, code, 0))
                            } else inst.sendKeyDownUpSync(code)
                        } catch (_: Throwable) {
                            act?.runOnUiThread {
                                act?.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
                                act?.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
                            }
                        }
                    }.start()
                }
                fun key(code: Int) {
                    act?.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
                    act?.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
                }
                when {
                    e.mode == "volup" || e.mode == "voldown" -> (castCtx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager)
                        .adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                            if (e.mode == "volup") android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER,
                            android.media.AudioManager.FLAG_SHOW_UI)
                    e.mode in setOf("up", "down", "left", "right", "ok", "okhold", "back") -> padKey(e.mode)
                    e.mode == "home" -> nav.navigate("library") { popUpTo("library") { inclusive = false }; launchSingleTop = true }
                    e.mode == "release" -> com.mymovieapp.app.data.CastState.phoneDriven = false
                    !onPlayer -> {}
                    e.mode.startsWith("audio:") || e.mode.startsWith("spu:") || e.mode.startsWith("speed:") -> com.mymovieapp.app.data.CastState.cmd?.invoke(e.mode)
                    e.mode == "stop" -> nav.popBackStack()
                    e.mode.startsWith("seek:") -> e.mode.removePrefix("seek:").toLongOrNull()?.let { com.mymovieapp.app.data.CastState.seeker?.invoke(it) }
                    e.mode == "playpause" -> key(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    e.mode == "back10" -> key(android.view.KeyEvent.KEYCODE_MEDIA_REWIND)
                    e.mode == "fwd10" -> key(android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
                    e.mode == "vnext" -> key(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
                    e.mode == "vprev" -> key(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                    e.mode == "next" -> key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
                    e.mode == "prev" -> key(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
                    e.mode.length == 2 && e.mode[0] == 'd' && e.mode[1].isDigit() -> key(android.view.KeyEvent.KEYCODE_0 + (e.mode[1] - '0'))
                }
            }
        }
    }
    val go: (String) -> Unit = { r ->
        nav.navigate(r) {
            popUpTo("library") { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        containerColor = Bg,
        contentWindowInsets = if (isPlayer) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
        topBar = { if (tv && showBar) TvTopBar(tabs, route, go) },
        bottomBar = {
            if (!tv && showBar) NavigationBar(containerColor = CardBg) {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = { go(t.route) },
                        icon = { Icon(t.icon, t.label) },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White, selectedTextColor = Color.White,
                            unselectedIconColor = Muted, unselectedTextColor = Muted,
                            indicatorColor = Color.Transparent
                        )
                    )
                }
            }
        }
    ) { pad ->
        // The default screen change is a 0.7 s fade, which feels like waiting. On TV the old screen disappears at once instead of lingering; very short fade on the phone.
        // Screens switch instantly (old one removed at once, opaque background behind) so two screens never show through each other.
        val fadeIn = androidx.compose.animation.EnterTransition.None
        val fadeOut = androidx.compose.animation.fadeOut(androidx.compose.animation.core.snap())
        NavHost(
            nav, startDestination = "library", modifier = Modifier.padding(pad).background(Bg),
            enterTransition = { fadeIn }, exitTransition = { fadeOut },
            popEnterTransition = { fadeIn }, popExitTransition = { fadeOut }
        ) {
            composable("search") { WithFocusMemory { SearchScreen(vm, nav) } }
            composable("library") { WithFocusMemory { LibraryScreen(vm, nav) } }
            composable("settings") { Opaque { SettingsScreen(vm, nav) } }
            composable("live") { WithFocusMemory { LiveTvScreen(vm, nav) } }
            composable("settings/libraries") { Opaque { LibrariesScreen(vm, nav) } }
            composable("settings/scan") { Opaque { ScanScreen(vm, nav) } }
            composable("settings/livetv") { Opaque { LiveTvSettingsScreen(vm, nav) } }
            composable("settings/subtitles") { Opaque { SubtitleSettingsScreen(vm, nav) } }
            composable("settings/playback") { Opaque { PlaybackSettingsScreen(vm, nav) } }

            composable("settings/stash") { Opaque { StashSettingsScreen(vm, nav) } }
            composable("performer/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) {
                WithFocusMemory { PerformerScreen(vm, nav, it.arguments!!.getInt("id")) }
            }
            composable("settings/storage") { Opaque { StorageScreen(vm, nav) } }
            composable("web") { WithFocusMemory { WebScreen(vm, nav) } }
            composable("detail/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                WithFocusMemory { DetailScreen(vm, nav, it.arguments!!.getLong("id")) }
            }
            composable(
                "player/{type}/{id}",
                arguments = listOf(
                    navArgument("type") { type = NavType.StringType },
                    navArgument("id") { type = NavType.LongType }
                )
            ) {
                val pType = it.arguments!!.getString("type")!!
                val pId = it.arguments!!.getLong("id")
                // LibVLC plays everything; if it cannot start a file, the old ExoPlayer screen takes over for that file.
                // Live TV: "Auto" starts web links (http/https) in ExoPlayer, everything else in VLC, and falls back to the other one.
                val pKey = "$pType/$pId"
                val liveMode by vm.prefs.livePlayer.collectAsStateWithLifecycle()
                val crashed by vm.prefs.vlcCrashed.collectAsStateWithLifecycle()
                var engine by remember(pKey) { mutableStateOf<String?>(if (pType == "channel") null else "vlc") }
                var fromVlc by remember(pKey) { mutableStateOf(false) }
                LaunchedEffect(pKey) {
                    if (engine == null) {
                        val uri = vm.playRequest(pType, pId)?.uri.orEmpty()
                        engine = when {
                            com.mymovieapp.app.data.CastState.isCastedVideo(uri) -> "vlc"      // a video cast from the phone: only the VLC screen answers the phone's remote
                            liveMode == 1 -> "exo"
                            liveMode == 2 -> "vlc"
                            pId in crashed -> "exo"
                            uri.startsWith("http", ignoreCase = true) -> "exo"
                            else -> "vlc"
                        }
                    }
                }
                when (engine) {
                    null -> Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black))
                    "exo" -> PlayerScreen(vm, nav, pType, pId, onUseVlc = { engine = "vlc" },
                        onExoFail = if (pType == "channel" && liveMode == 0 && !fromVlc && pId !in crashed) ({ engine = "vlc" }) else null)
                    else -> VlcPlayerScreen(vm, nav, pType, pId, onFail = { fromVlc = true; engine = "exo" })
                }
            }
        }
    }
}

@Composable
private fun TvTopBar(tabs: List<NavTab>, route: String?, onPick: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 40.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(Accent), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.VideoLibrary, null, tint = OnAccent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        tabs.forEach { t ->
            val sel = route == t.route
            val pill = RoundedCornerShape(50)
            Row(
                Modifier.focusHighlight(pill).clip(pill).clickable { onPick(t.route) }
                    .background(if (sel) CardBg else Color.Transparent)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(t.icon, null, tint = if (sel) Color.White else Muted)
                Spacer(Modifier.width(8.dp))
                Text(t.label, color = if (sel) Color.White else Muted, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
