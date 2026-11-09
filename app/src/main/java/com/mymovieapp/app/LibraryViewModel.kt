package com.mymovieapp.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mymovieapp.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
/** Value of the source filter that means "videos picked one by one, not from a folder". */
const val LOOSE_SOURCE = "\u0000loose"
data class LibraryItem(val title: Title, val files: Int, val watched: Boolean, val videoId: Long = 0, val videoUri: String? = null, val durationMs: Long = 0)
data class Shelf(val items: List<LibraryItem> = emptyList())
data class PlaylistDraft(val name: String, val url: String, val channels: List<Channel>, val groups: List<Pair<String, Int>>)
/** mode: 0 all, 1 favourites, 2 recent, 3 one group */
data class LiveFilter(val mode: Int = 0, val group: String = "", val query: String = "")
data class DupGroup(val label: String, val copies: List<String>)

data class PlayRequest(
    val uri: String, val title: String, val resumeMs: Long,
    val progressKey: String?, val subtitleUri: String?, val nextId: Long?,
    val query: SubtitleQuery? = null,
    val mime: String? = null,
    val skipNextId: Long? = null,
    val skipPrevId: Long? = null,
    val pool: List<Long> = emptyList(),      // the other files in this folder / series, for shuffle
    val channelIds: List<Long> = emptyList(), // live TV: the channels in the list being browsed, in order (number N = Nth)
    val groupKey: String? = null             // series / folder this belongs to, for remembered audio + subtitle choice
)

private fun buildShelf(titles: List<Title>, allVideos: List<Video>, prog: List<Progress>, source: String?, sub: String? = null): Shelf {
    val inSource = if (source == null) allVideos
    else allVideos.filter { if (source == LOOSE_SOURCE) it.folderUri == null else it.folderUri == source }
    val videos = if (sub == null) inSource else inSource.filter { val p = it.subPath.orEmpty(); p == sub || p.startsWith("$sub/") }
    val pm = prog.associateBy { it.uri }
    val byTitle = videos.groupBy { it.titleId }

    val items = titles.mapNotNull { t ->
        val vs = byTitle[t.id] ?: return@mapNotNull null
        val first = vs.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }, { it.fileName })).first()
        LibraryItem(t, vs.size, vs.all { pm[it.uri]?.isWatched() == true }, first.id, first.uri, first.durationMs)
    }

    return Shelf(items)
}

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDb.get(app).dao()
    val prefs = Prefs(app)
    private val tmdb = Tmdb { prefs.tmdbKey.value }
    private val scanner = Scanner(app, dao)
    private val http = OkHttpClient()
    private val mutex = Mutex()
    private val epCache = HashMap<String, Map<Int, Tmdb.Ep>>()

    private fun <T> Flow<T>.state(init: T) = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), init)

    val busy = MutableStateFlow<String?>(null)
    val notice = MutableStateFlow<String?>(null)
    val vault = Vault(app)
    /** Live TV groups hidden behind the PIN right now. */
    // Live TV sources (playlists): switched off by the user, or locked behind the PIN ("src:<id>").
    private val srcPrefs = app.getSharedPreferences("live_sources", Context.MODE_PRIVATE)
    val sourcesOff = MutableStateFlow(srcPrefs.getStringSet("off", emptySet())?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet())
    /** Playlist ids whose channels must not show right now: switched off, or locked and not unlocked. */
    private val hiddenPids = combine(vault.hidden, sourcesOff) { h, off ->
        h.filter { it.startsWith("src:") }.mapNotNull { it.removePrefix("src:").toLongOrNull() }.toSet() + off
    }
    /** Playlists hidden only because of the lock (their chip disappears too). */
    val lockedHiddenPids = vault.hidden.map { h -> h.filter { it.startsWith("src:") }.mapNotNull { it.removePrefix("src:").toLongOrNull() }.toSet() }.state(emptySet())
    private fun hiddenPidsNow(): Set<Long> =
        (if (vault.unlocked.value) emptySet() else vault.locked.value.filter { it.startsWith("src:") }.mapNotNull { it.removePrefix("src:").toLongOrNull() }.toSet()) + sourcesOff.value
    /** The source chosen in Live TV: -1 = all sources. */
    val liveSource = MutableStateFlow(srcPrefs.getLong("selected", -1L))
    fun setLiveSource(id: Long) { liveSource.value = id; srcPrefs.edit().putLong("selected", id).apply() }
    fun setSourceOn(id: Long, on: Boolean) {
        val n = if (on) sourcesOff.value - id else sourcesOff.value + id
        sourcesOff.value = n
        srcPrefs.edit().putStringSet("off", n.map { it.toString() }.toSet()).apply()
        if (!on && liveSource.value == id) setLiveSource(-1L)
    }
    private fun hiddenGroupsNow(): Set<String> =
        if (vault.unlocked.value) emptySet() else lockedGroupSet(vault.locked.value, groupFolders.value)
    private val openFolders = combine(dao.folders(), vault.hidden) { f, h -> f.filter { it.uri !in h } }
    val folders = openFolders.state(emptyList())
    private val openVideos = combine(dao.allVideos(), vault.hidden) { v, h ->
        if (h.isEmpty()) v else v.filter { it.folderUri == null || it.folderUri !in h }
    }
    val looseCount = dao.looseCount().state(0)
    // ---- Live TV: channels that failed to play ----
    private val deadPrefs = app.getSharedPreferences("dead_channels", android.content.Context.MODE_PRIVATE)
    val deadChannels = MutableStateFlow(
        deadPrefs.getStringSet("ids", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
    )
    fun markChannel(id: Long, dead: Boolean) {
        if ((id in deadChannels.value) == dead) return
        deadChannels.value = if (dead) deadChannels.value + id else deadChannels.value - id
        deadPrefs.edit().putStringSet("ids", deadChannels.value.map { it.toString() }.toSet()).apply()
    }
    // Links the user removed as "not working": a playlist refresh won't bring them back.
    private val removedPrefs = app.getSharedPreferences("removed_channels", android.content.Context.MODE_PRIVATE)
    private fun removedUrls(): Set<String> = removedPrefs.getStringSet("urls", emptySet()).orEmpty()
    // ---- Live TV: check which channels work ----
    val deadScan = MutableStateFlow<String?>(null)
    val scanning = MutableStateFlow(false)
    private var scanJob: kotlinx.coroutines.Job? = null

    fun checkChannels(list: List<Channel>) {
        if (scanning.value || list.isEmpty()) return
        scanning.value = true
        scanJob = viewModelScope.launch {
            val results = HashMap<Long, Boolean>()          // channel id -> works
            var bad = 0
            val client = http.newBuilder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
            val gate = Semaphore(6)
            try {
                kotlinx.coroutines.coroutineScope {
                    list.map { c ->
                        async {
                            gate.withPermit {
                                val ok = withContext(Dispatchers.IO) { probeChannel(client, c.url) }
                                results[c.id] = ok
                                if (!ok) bad++
                                deadScan.value = "Checking ${results.size}/${list.size} · $bad not working"
                            }
                        }
                    }.awaitAll()
                }
                deadScan.value = "Checked ${list.size} channels · $bad not working"
            } catch (e: kotlinx.coroutines.CancellationException) {
                deadScan.value = "Stopped after ${results.size} · $bad not working"
                throw e
            } finally {
                val dead = deadChannels.value.filter { it !in results }.toSet() + results.filterValues { !it }.keys
                deadChannels.value = dead
                deadPrefs.edit().putStringSet("ids", dead.map { it.toString() }.toSet()).apply()
                scanning.value = false
            }
        }
    }
    fun cancelCheck() { scanJob?.cancel() }
    fun dismissCheck() { deadScan.value = null }
    /** Removes channels from the app and forgets their "not working" marks. */
    fun removeChannels(ids: List<Long>) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val urls = ids.mapNotNull { dao.channel(it)?.url }
                removedPrefs.edit().putStringSet("urls", removedUrls() + urls).apply()
                ids.chunked(500).forEach { dao.deleteChannelsByIds(it) }
            }
            val left = deadChannels.value - ids.toSet()
            deadChannels.value = left
            deadPrefs.edit().putStringSet("ids", left.map { it.toString() }.toSet()).apply()
        }
    }

    /** true = the link answers with something that looks like a stream. */
    private fun probeChannel(client: OkHttpClient, raw: String): Boolean {
        try {
            val (clean, headers) = StreamHeaders.split(raw)
            if (!clean.startsWith("http", ignoreCase = true)) return true     // can't test other kinds
            val rb = Request.Builder().url(clean).header("User-Agent", StreamHeaders.uaOf(headers))
            headers.filterKeys { !it.equals("user-agent", true) }.forEach { (k, v) -> rb.header(k, v) }
            client.newCall(rb.build()).execute().use { r ->
                if (!r.isSuccessful) return false
                val ct = r.header("Content-Type").orEmpty().lowercase()
                if (ct.startsWith("text/html")) return false
                val src = r.body?.source() ?: return false
                src.request(16)
                if (src.buffer.size == 0L) return false
                return if (clean.contains(".m3u8", true) || ct.contains("mpegurl"))
                    src.buffer.snapshot().utf8().trimStart('\uFEFF').startsWith("#EXT")
                else true
            }
        } catch (e: Exception) {
            return false
        }
    }

    val unmatched = dao.unmatched().state(emptyList())
    val favoriteIds = dao.favoriteIds().state(emptyList())
    val progress = dao.allProgress().map { l -> l.associateBy { it.uri } }.state(emptyMap())
    val stats = combine(dao.countTitles(), dao.countVideos(), dao.countChannels()) { a, b, c -> Triple(a, b, c) }
        .state(Triple(0, 0, 0))
    /** null = all sources; otherwise a folder uri, or LOOSE_SOURCE. */
    val sourceFilter = MutableStateFlow<String?>(null)
    fun setSource(s: String?) { sourceFilter.value = s; folderFilter.value = null }
    /** null = all folders; otherwise a path inside the source, e.g. "Mike Adriano/True Anal". */
    val folderFilter = MutableStateFlow<String?>(null)
    fun setFolder(s: String?) { folderFilter.value = s }
    /** Every folder of the chosen source(s) with its number of videos (a folder counts what is in its sub-folders too). */
    val folderOptions = combine(openVideos, sourceFilter) { v, src ->
        val vs = if (src == null) v else v.filter { if (src == LOOSE_SOURCE) it.folderUri == null else it.folderUri == src }
        val counts = HashMap<String, Int>()
        for (x in vs) {
            val p = x.subPath ?: continue
            var cur = ""
            for (seg in p.split('/')) { cur = if (cur.isEmpty()) seg else "$cur/$seg"; counts[cur] = (counts[cur] ?: 0) + 1 }
        }
        counts.entries.sortedBy { it.key.lowercase() }.map { it.key to it.value }
    }.state(emptyList())

    val shelf = combine(dao.visibleTitles(), openVideos, dao.allProgress(), combine(sourceFilter, folderFilter) { a, b -> a to b }, openFolders) { t, v, p, sf, f ->
        // If the chosen folder was removed, fall back to "all".
        val s = sf.first?.takeIf { it == LOOSE_SOURCE || f.any { x -> x.uri == it } }
        buildShelf(t, v, p, s, sf.second)
    }.state(Shelf())

    // ---- Live TV state ----
    private val listClient = http.newBuilder()
        .callTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
    // Folders: several groups shown under one pill (group name -> folder name). Kept apart from the channels, so refreshes don't undo them.
    private val folderPrefs = app.getSharedPreferences("group_folders", Context.MODE_PRIVATE)
    val groupFolders = MutableStateFlow(
        runCatching { org.json.JSONObject(folderPrefs.getString("map", "{}") ?: "{}").let { o -> o.keys().asSequence().associateWith { o.getString(it) } } }.getOrDefault(emptyMap())
    )
    /** Groups hidden by the lock: locked groups plus every group inside a locked folder. */
    private fun lockedGroupSet(locked: Set<String>, fm: Map<String, String>): Set<String> =
        locked.filter { it.startsWith("live:") }.map { it.removePrefix("live:") }.toSet() +
            fm.filterValues { "folder:$it" in locked }.keys
    private val hiddenGroups = combine(vault.hidden, groupFolders) { h, fm -> lockedGroupSet(h, fm) }
    private fun storeFolders(m: Map<String, String>) {
        groupFolders.value = m
        folderPrefs.edit().putString("map", org.json.JSONObject(m).toString()).apply()
    }
    /** The groups in [folder] become exactly [ticked] (among the groups on screen, [visible]); a changed name renames the folder. */
    fun saveFolder(orig: String?, name: String, ticked: Set<String>, visible: Set<String>) {
        val n = name.trim()
        if (n.isBlank()) return
        val m = groupFolders.value.toMutableMap()
        if (orig != null && orig != n) {
            m.keys.toList().forEach { if (m[it] == orig) m[it] = n }
            if ("folder:$orig" in vault.locked.value) { vault.setLocked("folder:$orig", false); vault.setLocked("folder:$n", true) }
        }
        visible.forEach { g -> if (g in ticked) m[g] = n else if (m[g] == n) m.remove(g) }
        storeFolders(m)
        val f = liveFilter.value
        if (m.values.none { it == n }) { if (f.mode == 4 && (f.group == n || f.group == orig)) setLiveMode(0) }
        else setLiveMode(4, n)
    }
    fun releaseFolder(name: String) {
        vault.setLocked("folder:$name", false)
        storeFolders(groupFolders.value.filterValues { it != name })
        val f = liveFilter.value
        if (f.mode == 4 && f.group == name) setLiveMode(0)
    }
    fun takeOutOfFolder(g: String) {
        val f = groupFolders.value[g] ?: return
        storeFolders(groupFolders.value - g)
        val lf = liveFilter.value
        if (lf.mode == 4 && lf.group == f && groupFolders.value.values.none { it == f }) setLiveMode(0)
    }
    private fun dropFromFolders(gs: Set<String>) {
        if (gs.any { it in groupFolders.value }) storeFolders(groupFolders.value - gs)
    }
    private fun folderKeyRenamed(old: String, new: String) {
        val f = groupFolders.value[old] ?: return
        val m = groupFolders.value.toMutableMap().also { it.remove(old) }
        if (new !in m) m[new] = f
        storeFolders(m)
    }
    fun renamePlaylistTo(p: Playlist, name: String) {
        val n = name.trim()
        if (n.isBlank() || n == p.name) return
        viewModelScope.launch { dao.renamePlaylist(p.id, n) }
    }
    val liveFilter = MutableStateFlow(LiveFilter(mode = prefs.liveMode, group = prefs.liveGroup))
    @OptIn(ExperimentalCoroutinesApi::class)
    private data class LiveGate(val f: LiveFilter, val hg: Set<String>, val hp: Set<Long>, val sel: Long, val fm: Map<String, String> = emptyMap())
    // The chosen source counts as "all" if it just got locked or switched off.
    private val liveGate = combine(liveFilter, hiddenGroups, hiddenPids, liveSource, groupFolders) { f, h, p, sel0, fm ->
        LiveGate(f, h, p, if (sel0 in p) -1L else sel0, fm)
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    val liveList = liveGate.flatMapLatest { g ->
        val like = "%${g.f.query.trim()}%"
        when (g.f.mode) {
            1 -> dao.favoriteChannels(like)
            2 -> dao.recentChannels(like)
            3 -> dao.channelsInGroup(g.f.group, like, g.sel, g.hp.toList())
            4 -> dao.channelsInGroups(g.fm.filterValues { it == g.f.group }.keys.toList(), like, g.sel, g.hp.toList(), g.hg.toList())
            else -> dao.allChannels(like, g.sel, g.hp.toList(), g.hg.toList())
        }.map { l -> l.filter { it.grp !in g.hg && it.playlistId !in g.hp && (g.sel < 0 || it.playlistId == g.sel) } }
    }.state(emptyList())
    @OptIn(ExperimentalCoroutinesApi::class)
    // Group counts only depend on the source and the locks, not on which pill or search is active, so they aren't re-counted on every press.
    val liveGroups = combine(hiddenGroups, hiddenPids, liveSource) { h, p, sel0 -> Triple(h, p, if (sel0 in p) -1L else sel0) }
        .distinctUntilChanged()
        .flatMapLatest { (h, p, sel) -> dao.groupCountsFor(sel, p.toList(), h.toList()) }.state(emptyList())
    val lastPlayed = combine(dao.lastPlayedChannel(), hiddenGroups, hiddenPids) { c, h, p -> c?.takeIf { it.grp !in h && it.playlistId !in p } }.state(null)
    val playlists = dao.playlists().state(emptyList())
    val playlistCounts = dao.playlistCounts().map { l -> l.associate { it.playlistId to it.n } }.state(emptyMap())
    val draft = MutableStateFlow<PlaylistDraft?>(null)
    val draftBusy = MutableStateFlow(false)
    val draftError = MutableStateFlow<String?>(null)

    fun title(id: Long) = dao.title(id)
    fun videos(id: Long) = dao.videosOf(id)

    // ---------- scanning & matching ----------
    private suspend fun runBusy(msg: String, block: suspend () -> Unit) = mutex.withLock {
        busy.value = msg
        try { block() } finally { busy.value = null }
    }

    private suspend fun matchAllInternal() {
        if (!tmdb.hasKey) return
        val todo = dao.unmatchedNow()
        todo.forEachIndexed { i, t ->
            busy.value = "Finding posters ${i + 1}/${todo.size}…"
            matchOne(t)
        }
    }

    private suspend fun matchOne(t: Title): Boolean {
        var hits = tmdb.search(t.kind, t.name, t.year)
        if (hits.isEmpty() && t.year != null) hits = tmdb.search(t.kind, t.name, null)
        val hit = hits.firstOrNull() ?: return false
        return applyInfo(t, hit.id)
    }

    private suspend fun applyInfo(t: Title, tmdbId: Int): Boolean {
        val i = tmdb.info(t.kind, tmdbId) ?: return false
        dao.updateTitle(
            t.copy(
                tmdbId = i.id, name = i.name.ifBlank { t.name }, year = i.year ?: t.year,
                posterUrl = i.posterUrl, backdropUrl = i.backdropUrl, overview = i.overview,
                castList = i.cast, rating = i.rating, matched = true
            )
        )
        return true
    }

    fun addFolder(uri: Uri) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
                try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            }
            val name = DocumentFile.fromTreeUri(ctx, uri)?.name ?: "Folder"
            val f = LibraryFolder(uri.toString(), name, System.currentTimeMillis())
            dao.upsertFolder(f)
            runBusy("Scanning $name…") { safeScan(f) }
        }
    }

    fun addFiles(uris: List<Uri>) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            uris.forEach {
                try {
                    ctx.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
            }
            runBusy("Adding videos…") { scanner.addFiles(uris) }
            fillDurations()
        }
    }

    init {
        FtpStore.init(app)
        CastBus.sync = { j -> kotlinx.coroutines.runBlocking(Dispatchers.IO) { applySync(j) } }
        CastBus.pull = { rq ->
            val its = (0 until (rq.optJSONArray("items")?.length() ?: 0)).map { rq.getJSONArray("items").getString(it) }.toSet()
            kotlinx.coroutines.runBlocking(Dispatchers.IO) { buildSync(its, rq.optBoolean("mirror")) }
        }
        viewModelScope.launch { dao.unhideAll() }
        // New titles without posters (after connecting or rescanning a share), or the option switched on: make their stills.
        viewModelScope.launch {
            combine(shelf.map { s -> s.items.count { it.title.posterUrl == null } }.distinctUntilChanged(), prefs.autoThumbs) { n, on -> n to on }
                .collectLatest { (n, on) ->
                    kotlinx.coroutines.delay(2000)
                    if (on && n > 0) makeThumbs()
                }
        }
        viewModelScope.launch {
            var first = true
            dao.smbServers().collect { list ->
                list.forEach { s ->
                    val pass = try { Secrets.decrypt(s.pass) } catch (e: Exception) { "" }
                    Smb.put(SmbCreds(s.host, s.user, s.domain, pass))
                }
                if (first) { first = false; fillDurations() }
            }
        }
    }

    // ---------- video stills ----------
    val thumbProgress = MutableStateFlow<String?>(null)
    private var thumbJob: kotlinx.coroutines.Job? = null

    val thumbResult = MutableStateFlow<String?>(null)

    /** Makes a still for every title without a poster, one at a time, in the background. */
    fun makeThumbs(force: Boolean = false) {
        if (!force && !prefs.autoThumbs.value) return
        if (thumbJob?.isActive == true) return
        if (force) { Thumbs.forgetFailures(); thumbResult.value = null }
        thumbJob = viewModelScope.launch {
            val ctx = getApplication<Application>()
            var wanted = 0
            var made = 0
            try {
                while (true) {
                    // Stash covers first: only videos still without a picture get one made from the video itself
                    try { seedStashCovers() } catch (_: Throwable) {}
                    val all = shelf.value.items.filter { it.title.posterUrl == null }.mapNotNull { it.videoUri }
                    val todo = all.filter { Thumbs.cached(ctx, it) == null && !Thumbs.hasFailed(it) }
                    if (todo.isEmpty()) {
                        if (force) {
                            val failed = all.count { Thumbs.cached(ctx, it) == null }
                            thumbResult.value = when {
                                wanted == 0 && failed == 0 -> "Every file already has a thumbnail."
                                failed == 0 -> "Made $made thumbnail${if (made == 1) "" else "s"}."
                                else -> "Made $made. $failed file${if (failed == 1) "" else "s"} couldn't be read:\n" +
                                    all.filter { Thumbs.cached(ctx, it) == null }.take(3).joinToString("\n") {
                                        "• " + android.net.Uri.decode(it.substringAfterLast('/')) + " — " + (Thumbs.reason(it) ?: "unknown")
                                    }
                            }
                        }
                        break
                    }
                    wanted += todo.size
                    todo.forEachIndexed { i, u ->
                        thumbProgress.value = "Making thumbnails ${i + 1}/${todo.size}…"
                        if (Thumbs.get(ctx, u) != null) made++
                    }
                }
            } finally { thumbProgress.value = null }
        }
    }

    private var durJob: kotlinx.coroutines.Job? = null
    /** Reads missing video lengths in the background; does nothing if it's already running. */
    fun fillDurations(folder: String? = null) {
        viewModelScope.launch { try { Durations.fill(getApplication(), dao, folder) } catch (_: Exception) {} }
    }
    private suspend fun safeScan(f: LibraryFolder) {
        try {
            scanner.scan(f)
            fillDurations(f.uri)
            try { syncStashDrives(false) } catch (_: Throwable) {}
        } catch (e: Exception) {
            notice.value = "Couldn't read ${f.name}: ${e.message ?: "unreachable"}. Your existing library was kept."
        }
    }

    fun mediaVolumes(): List<Pair<String, String>> = Sources.mediaVolumes(getApplication())

    fun addMediaVolume(volume: String, label: String) {
        viewModelScope.launch {
            val f = LibraryFolder("media://$volume", label, System.currentTimeMillis())
            dao.upsertFolder(f)
            runBusy("Scanning $label…") { safeScan(f) }
        }
    }

    /** Verifies the share is reachable, saves the (encrypted) login, adds it as a library folder and scans it. */
    fun addSmb(host: String, share: String, folder: String, user: String, pass: String, domain: String, done: (String?) -> Unit) {
        viewModelScope.launch {
            val h = host.trim()
            val s = share.trim().trim('/', '\\')
            val rel = folder.trim().trim('/', '\\').replace('/', '\\')
            val creds = SmbCreds(h, user.trim(), domain.trim(), pass)
            val err = withContext(Dispatchers.IO) {
                try {
                    Smb.put(creds); Smb.invalidate(h, s); Smb.share(h, s).list(rel); null
                } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
            }
            if (err != null) { done(err); return@launch }
            dao.upsertSmb(SmbServer(h, creds.user, creds.domain, Secrets.encrypt(pass)))
            val name = rel.substringAfterLast('\\').ifBlank { s } + " on " + h
            val f = LibraryFolder(Smb.uri(h, s, rel).toString(), name, System.currentTimeMillis())
            dao.upsertFolder(f)
            done(null)
            runBusy("Scanning $name…") { safeScan(f) }
        }
    }
    /** Checks the FTP folder exists, saves the (encrypted) login, adds it as a library folder and scans it. */
    fun addFtp(host: String, port: Int, folder: String, user: String, pass: String, done: (String?) -> Unit) {
        viewModelScope.launch {
            val h = host.trim()
            val path = "/" + folder.trim().trim('/')
            val err = withContext(Dispatchers.IO) {
                try {
                    FtpStore.put(FtpCreds(h, port, user.trim(), pass))
                    val c = Ftp.connect(h, port)
                    try {
                        if (path != "/" && !c.changeWorkingDirectory(path)) "That folder wasn't found on the server." else null
                    } finally { Ftp.quit(c) }
                } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
            }
            if (err != null) { done(err); return@launch }
            val name = path.trimEnd('/').substringAfterLast('/').ifBlank { h } + " on " + h
            val lib = LibraryFolder(Ftp.uri(h, port, path).toString(), name, System.currentTimeMillis())
            dao.upsertFolder(lib)
            done(null)
            runBusy("Scanning $name…") { safeScan(lib) }
        }
    }

    /** Re-points every stored address that starts with [oldPrefix] to [newPrefix]. Library, posters and resume positions stay. */
    private suspend fun moveSource(oldPrefix: String, newPrefix: String) {
        dao.moveVideoUris(oldPrefix, newPrefix)
        dao.moveVideoFolders(oldPrefix, newPrefix)
        dao.moveSubtitleUris(oldPrefix, newPrefix)
        dao.moveProgressUris(oldPrefix, newPrefix)
        dao.moveFolderUris(oldPrefix, newPrefix)
    }

    /**
     * Edits a network share: new address, share, folder or login. Nothing is changed unless the new details connect.
     * If the host changed, every share on that PC moves with it. Passes blank = keep the saved password.
     */
    fun editSmb(folder: LibraryFolder, host: String, share: String, folderPath: String, user: String, pass: String, domain: String, done: (String?) -> Unit) {
        viewModelScope.launch {
            val old = Smb.split(Uri.parse(folder.uri))
            val oldHost = old.first
            val oldCreds = Smb.credsFor(oldHost)
            val h = host.trim()
            val s = share.trim().trim('/', '\\')
            val rel = folderPath.trim().trim('/', '\\').replace('/', '\\')
            val pw = if (pass.isEmpty()) (oldCreds?.pass ?: "") else pass
            val creds = SmbCreds(h, user.trim(), domain.trim(), pw)
            val err = withContext(Dispatchers.IO) {
                try {
                    Smb.put(creds); Smb.invalidate(h, s); Smb.share(h, s).list(rel); null
                } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
            }
            if (err != null) {
                if (oldCreds != null) Smb.put(oldCreds)
                done(err); return@launch
            }
            dao.upsertSmb(SmbServer(h, creds.user, creds.domain, Secrets.encrypt(pw)))
            if (!h.equals(oldHost, ignoreCase = true)) {
                moveSource("smb://" + (Uri.parse(folder.uri).encodedAuthority ?: oldHost), "smb://$h")
                dao.foldersNow().filter { it.uri.startsWith("smb://$h") && it.name.contains(oldHost) }
                    .forEach { dao.renameFolder(it.uri, it.name.replace(oldHost, h)) }
            }
            val here = Smb.uri(h, old.second, old.third).toString()
            val there = Smb.uri(h, s, rel).toString()
            var target = here
            if (here != there) {
                moveSource(here, there)
                dao.renameFolder(there, rel.substringAfterLast('\\').ifBlank { s } + " on " + h)
                target = there
            }
            done(null)
            dao.foldersNow().firstOrNull { it.uri == target }?.let { f -> runBusy("Scanning ${f.name}…") { safeScan(f) } }
        }
    }

    /** Same as [editSmb] for FTP servers. */
    fun editFtp(folder: LibraryFolder, host: String, port: Int, folderPath: String, user: String, pass: String, done: (String?) -> Unit) {
        viewModelScope.launch {
            val old = Ftp.split(Uri.parse(folder.uri))
            val oldCreds = FtpStore.get(old.first, old.second)
            val h = host.trim()
            val path = "/" + folderPath.trim().trim('/')
            val pw = if (pass.isEmpty()) (oldCreds?.pass ?: "") else pass
            val err = withContext(Dispatchers.IO) {
                try {
                    FtpStore.put(FtpCreds(h, port, user.trim(), pw))
                    val c = Ftp.connect(h, port)
                    try {
                        if (path != "/" && !c.changeWorkingDirectory(path)) "That folder wasn't found on the server." else null
                    } finally { Ftp.quit(c) }
                } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
            }
            if (err != null) {
                if (oldCreds != null) FtpStore.put(oldCreds)
                done(err); return@launch
            }
            if (!h.equals(old.first, ignoreCase = true) || port != old.second) {
                moveSource("ftp://${old.first}:${old.second}", "ftp://$h:$port")
                dao.foldersNow().filter { it.uri.startsWith("ftp://$h:$port") && it.name.contains(old.first) }
                    .forEach { dao.renameFolder(it.uri, it.name.replace(old.first, h)) }
            }
            val here = Ftp.uri(h, port, old.third).toString()
            val there = Ftp.uri(h, port, path).toString()
            var target = here
            if (here != there) {
                moveSource(here, there)
                dao.renameFolder(there, path.trimEnd('/').substringAfterLast('/').ifBlank { h } + " on " + h)
                target = there
            }
            done(null)
            dao.foldersNow().firstOrNull { it.uri == target }?.let { f -> runBusy("Scanning ${f.name}…") { safeScan(f) } }
        }
    }

    fun removeFolder(f: LibraryFolder) {
        viewModelScope.launch {
            try {
                getApplication<Application>().contentResolver
                    .releasePersistableUriPermission(Uri.parse(f.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            dao.deleteVideosOfFolder(f.uri); dao.deleteFolder(f.uri); dao.deleteOrphanTitles(); vault.setLocked(f.uri, false)
        }
    }

    fun removeLooseFiles() { viewModelScope.launch { dao.deleteLooseVideos(); dao.deleteOrphanTitles() } }

    /** Deletes the real file (network share, FTP server or phone) and then removes it from the library. If the delete fails, nothing changes. */
    fun deleteVideoFile(videoId: Long, done: (String?) -> Unit) {
        viewModelScope.launch {
            val v = dao.video(videoId)
            if (v == null) { done("That file isn't in the library any more."); return@launch }
            val err = withContext(Dispatchers.IO) {
                try { deleteSourceFile(v.uri); null } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
            }
            if (err != null) { done(err); return@launch }
            dao.deleteVideos(listOf(v.id))
            dao.deleteOrphanTitles()
            done(null)
        }
    }

    private fun deleteSourceFile(uri: String) {
        val u = Uri.parse(uri)
        when (u.scheme) {
            "smb" -> { val (h, s, rel) = Smb.split(u); Smb.share(h, s).rm(rel) }
            "ftp" -> {
                val (h, p, path) = Ftp.split(u)
                val c = Ftp.connect(h, p)
                try {
                    if (!c.deleteFile(path)) throw java.io.IOException("The server refused to delete the file.")
                } finally { Ftp.quit(c) }
            }
            "content" -> {
                val cr = getApplication<Application>().contentResolver
                val ok = try {
                    if (u.authority == "media") cr.delete(u, null, null) > 0
                    else android.provider.DocumentsContract.deleteDocument(cr, u)
                } catch (e: SecurityException) { false }
                if (!ok) throw java.io.IOException("Android didn't allow the app to delete this file. Delete it with your file manager instead.")
            }
            "file" -> if (!java.io.File(u.path.orEmpty()).delete()) throw java.io.IOException("Couldn't delete the file.")
            else -> throw java.io.IOException("This kind of source can't be deleted from the app.")
        }
    }

    /** Looks for new/removed files in every folder and share. Posters are searched separately (Settings > Scan). */
    fun rescanAll() {
        viewModelScope.launch {
            runBusy("Scanning…") {
                notice.value = null
                dao.foldersNow().forEach { busy.value = "Scanning ${it.name}…"; safeScan(it) }
            }
        }
    }

    fun matchNow() { viewModelScope.launch { runBusy("Finding posters…") { matchAllInternal() } } }

    suspend fun searchTmdb(kind: String, q: String): List<Tmdb.Hit> = tmdb.search(kind, q, null)

    fun rematch(t: Title, tmdbId: Int) { viewModelScope.launch { runBusy("Matching…") { applyInfo(t, tmdbId) } } }


    suspend fun videoById(id: Long): Video? = dao.video(id)

    /** done(ok, message): when ok is false the message is the error to show; when true it is an optional note. */
    fun renameVideo(videoId: Long, newName: String, done: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            var retitleId: Long? = null
            val res: Pair<Boolean, String?> = withContext(Dispatchers.IO) {
                try {
                    val base = newName.trim()
                    FileRenamer.check(base)?.let { return@withContext false to it }
                    val v = dao.video(videoId) ?: return@withContext false to "That file isn't in the library any more."
                    if (v.uri.startsWith("ftp://")) return@withContext false to "Renaming files on an FTP folder isn't supported yet."
                    val r = FileRenamer.rename(getApplication(), v, base)
                    dao.updateVideoFile(v.id, r.uri, r.fileName, r.subtitleUri)
                    dao.moveProgress(v.uri, r.uri)
                    // An unmatched film with one file: the library title follows the new file name.
                    val t = dao.titleNow(v.titleId)
                    if (t != null && !t.isSeries && !t.matched && dao.videoCountOf(t.id) == 1) {
                        val p = NameParser.parse(r.fileName, emptyList())
                        val other = dao.titleByKey(p.key)
                        if (other != null && other.id != t.id) { dao.moveVideos(t.id, other.id); dao.deleteOrphanTitles() }
                        else dao.retitle(t.id, p.name, p.year, p.key)
                        retitleId = if (other != null && other.id != t.id) other.id else t.id
                    }
                    true to r.note
                } catch (e: Exception) {
                    false to (e.message ?: "Rename failed")
                }
            }
            done(res.first, res.second)
            retitleId?.let { matchTitleQuietly(it) }
        }
    }
    /** Looks up the poster for just this title, with no banner and without touching other titles. */
    private suspend fun matchTitleQuietly(titleId: Long) {
        if (!tmdb.hasKey) return
        mutex.withLock {
            val t = dao.titleNow(titleId) ?: return@withLock
            if (!t.matched) matchOne(t)
        }
    }

    suspend fun episodeInfo(t: Title, season: Int): Map<Int, Tmdb.Ep> {
        val id = t.tmdbId ?: return emptyMap()
        val k = "$id:$season"
        epCache[k]?.let { return it }
        val m = tmdb.season(id, season).associateBy { it.number }
        if (m.isNotEmpty()) epCache[k] = m
        return m
    }
    /** Films with no TMDB info play straight away; everything else opens its detail page. */
    fun openTitle(t: Title, go: (String) -> Unit) {
        if (!t.isSeries && !t.matched) {
            viewModelScope.launch {
                val v = dao.videosOfNow(t.id).firstOrNull()
                go(if (v != null) "player/file/${v.id}" else "detail/${t.id}")
            }
        } else go("detail/${t.id}")
    }
    // ---------- search ----------
    suspend fun search(q: String): Pair<List<Title>, List<VideoWithTitle>> {
        val like = "%${q.trim()}%"
        val h = vault.hidden.first()
        if (h.isEmpty()) return dao.searchTitles(like) to dao.searchVideos(like)
        val vids = dao.searchVideos(like).filter { it.video.folderUri == null || it.video.folderUri !in h }
        val shown = openVideos.first().map { it.titleId }.toSet()
        return dao.searchTitles(like).filter { it.id in shown } to vids
    }

    // ---------- playback ----------
    /** Subtitle search for videos that are not in the library (links, channels, web folders): work out title / year / episode from the name or address. */
    private fun guessQuery(name: String, url: String): SubtitleQuery {
        val file = Uri.decode(url.substringBefore('|').substringBefore('?').trimEnd('/').substringAfterLast('/'))
        val base = if (file.contains('.')) file else name
        val p = NameParser.parse(base.ifBlank { name }, emptyList())
        return SubtitleQuery(p.name, p.year, null, p.kind, p.season, p.episode, "", file.ifBlank { name })
    }

    fun saveProgress(uri: String, pos: Long, dur: Long) {
        viewModelScope.launch { dao.saveProgress(Progress(uri, pos, dur, System.currentTimeMillis())) }
    }

    /** True when this video / channel / web item sits in something the PIN currently hides. */
    suspend fun sourceLocked(type: String, id: Long): Boolean = withContext(Dispatchers.IO) {
        if (vault.unlocked.value) return@withContext false
        val lk = vault.locked.value
        if (lk.isEmpty()) return@withContext false
        when (type) {
            "channel" -> dao.channel(id)?.let { "src:${it.playlistId}" in lk || it.grp in hiddenGroupsNow() } ?: false
            "web" -> webPlays[id]?.let { it.group != null && it.group in lk } ?: false
            else -> dao.video(id)?.folderUri?.let { it in lk } ?: false
        }
    }
    suspend fun playRequest(type: String, id: Long): PlayRequest? = withContext(Dispatchers.IO) {
        if (type == "web") {
            val w = webPlays[id] ?: return@withContext null
            StreamHeaders.apply(emptyMap())
            val wp = dao.progressOf(w.key)
            val wResume = if (wp != null && !wp.isWatched() && wp.positionMs > 5000) wp.positionMs else 0L
            return@withContext PlayRequest(w.uri, w.title, wResume, w.key, w.sub, w.next, guessQuery(w.title, w.key), null, skipNextId = w.next, skipPrevId = w.prev, groupKey = w.group)
        }
        if (type == "channel") {
            val c = dao.channel(id) ?: return@withContext null
            dao.markPlayed(id, System.currentTimeMillis())
            val (clean, headers) = StreamHeaders.split(c.url)
            StreamHeaders.apply(headers)
            // Follow the list the user was browsing (favourites or a group); otherwise all channels.
val shown = if (liveFilter.value.mode != 2 || liveFilter.value.query.isNotBlank()) liveList.value else emptyList()
val pool = if (shown.any { it.id == id }) shown else dao.groupNow(c.grp, hiddenPidsNow().toList(), hiddenGroupsNow().toList())
// ⏮ ⏭ skip channels marked as not working (the current one always stays in the list)
val all = pool.filter { it.id == id || it.id !in deadChannels.value }
val i = all.indexOfFirst { it.id == id }
val nextCh = if (all.size > 1 && i >= 0) all[(i + 1) % all.size].id else null
val prevCh = if (all.size > 1 && i >= 0) all[(i - 1 + all.size) % all.size].id else null
PlayRequest(clean, c.name, CastState.starts.remove(c.url) ?: 0L, null, null, null, guessQuery(c.name, clean), detectMime(clean, headers),
    skipNextId = nextCh, skipPrevId = prevCh, channelIds = all.map { it.id })
        } else {
            StreamHeaders.apply(emptyMap())
            val v = dao.video(id) ?: return@withContext null
            val t = dao.titleNow(v.titleId)
            val p = dao.progressOf(v.uri)
            val resume = CastState.starts.remove(v.uri) ?: if (p != null && !p.isWatched() && p.positionMs > 5000) p.positionMs else 0L
            var next: Long? = null
            var skipNext: Long? = null
            var skipPrev: Long? = null
            var pool: List<Long> = emptyList()
            var label = t?.name ?: v.fileName
            var gk: String? = null
            if (t != null && t.isSeries) {
                val vs = dao.videosOfNow(t.id)
                pool = vs.filter { it.id != v.id }.map { it.id }
                val idx = vs.indexOfFirst { it.id == v.id }
                if (idx >= 0) {
                    next = vs.getOrNull(idx + 1)?.id            // autoplay only follows episodes
                    skipNext = next
                    skipPrev = vs.getOrNull(idx - 1)?.id
                }
                label = "${t.name} · S${v.season ?: 0}E${v.episode ?: 0}"
                gk = "t${t.id}"
            } else if (v.folderUri != null) {
                val sib = dao.videosInFolder(v.folderUri)
                gk = "f${v.folderUri}"
                pool = sib.filter { it.id != v.id }.map { it.id }
                val i = sib.indexOfFirst { it.id == v.id }
                if (i >= 0) {
                    skipNext = sib.getOrNull(i + 1)?.id
                    skipPrev = sib.getOrNull(i - 1)?.id
                    next = skipNext            // autoplay moves on to the next video in the same folder
                }
            }
            val q = SubtitleQuery(
                title = t?.name ?: v.fileName.substringBeforeLast('.'), year = t?.year, tmdbId = t?.tmdbId,
                kind = t?.kind ?: "film", season = v.season, episode = v.episode, language = "", fileName = v.fileName
            )
            PlayRequest(v.uri, label, resume, v.uri, v.subtitleUri, next, q, skipNextId = skipNext, skipPrevId = skipPrev, pool = pool, groupKey = gk)
        }
    }

    // ---------- subtitles ----------


    suspend fun searchSubtitles(q: SubtitleQuery): SubSearch = SubtitleService.search(prefs.subSources.value, q)

    suspend fun downloadSubtitle(videoUri: String, r: SubtitleResult, q: SubtitleQuery): String? =
        SubtitleService.download(getApplication(), prefs.subSources.value, videoUri.substringBefore('|'), r, q)

    /** Runs a sample search so you can check a source before relying on it. */
    suspend fun testSource(s: SubtitleSource): String {
        /** Reads a network-share video as fast as possible for 8 s, with no player involved. */

        val out = SubtitleService.search(
            listOf(s.copy(enabled = true)),
            SubtitleQuery("Inception", 2010, 27205, "film", null, null, "EN", "")
        )
        return if (out.results.isNotEmpty()) "Works: ${out.results.size} results found" else out.message ?: "Connected, but no results"
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    suspend fun smbSpeedTest(): String = withContext(Dispatchers.IO) {
        val uri = dao.anySmbVideo() ?: return@withContext "No video from a network share in your library yet."
        val src = SmbDataSource()
        try {
            src.open(DataSpec(Uri.parse(uri)))
            val buf = ByteArray(512 * 1024)
            val t0 = System.nanoTime()
            var total = 0L
            while (System.nanoTime() - t0 < 8_000_000_000L) {
                val n = src.read(buf, 0, buf.size)
                if (n <= 0) break
                total += n
            }
            val sec = (System.nanoTime() - t0) / 1e9
            "%.1f MB/s  (%.0f MB in %.1f s)".format(total / 1_048_576.0 / sec, total / 1_048_576.0, sec)
        } catch (e: Exception) {
            e.message ?: "Speed test failed"
        } finally {
            try { src.close() } catch (_: Exception) {}
        }
    }

    fun updateSubSize(size: Int) {
        viewModelScope.launch { prefs.setSubSize(size) }
    }

    fun updateSubColor(color: Int) {
        viewModelScope.launch { prefs.setSubColor(color) }
    }

    fun updateSubBg(bg: Boolean) {
        viewModelScope.launch { prefs.setSubBg(bg) }
    }

    fun updateSubPos(pos: Int) {
        viewModelScope.launch { prefs.setSubPos(pos) }
    }

    // ---------- live TV & links ----------
    private suspend fun importText(text: String): Int {
        val list = M3u.parse(text)
        dao.insertChannels(list)
        return list.size
    }

    fun importM3uUrl(url: String, done: (Int, String?) -> Unit) {
        viewModelScope.launch {
            val (clean, headers) = StreamHeaders.split(url.trim())
            val res: Pair<String?, String?> = withContext(Dispatchers.IO) {
                try {
                    val rb = Request.Builder().url(clean).header("User-Agent", StreamHeaders.uaOf(headers))
                    headers.filterKeys { !it.equals("user-agent", true) }.forEach { (k, v) -> rb.header(k, v) }
                    http.newCall(rb.build()).execute().use { r ->
                        if (r.isSuccessful) (r.body?.string() to null) else (null to "HTTP ${r.code}")
                    }
                } catch (e: Exception) { null to (e.message ?: "network error") }
            }
            val text = res.first
            if (text == null) done(-1, res.second)
            else { val n = importText(text); done(n, if (n == 0) "no channels found in that playlist" else null) }
        }
    }

    fun importM3uFile(uri: Uri, done: (Int, String?) -> Unit) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } catch (e: Exception) { null }
            }
            if (text == null) { done(-1, "couldn't open that file"); return@launch }
            val n = importText(text)
            done(n, if (n == 0) "no channels found in that file" else null)
        }
    }

    /** Links without a file extension may still be HLS playlists; peek at the first bytes to tell. */
    private fun detectMime(url: String, headers: Map<String, String>): String? {
        val ext = url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
        if (ext in setOf("m3u8", "m3u", "ts", "mp4", "mkv", "mpd", "flv", "avi", "webm", "mov")) return null
        return try {
            val rb = Request.Builder().url(url).header("User-Agent", StreamHeaders.uaOf(headers))
            headers.filterKeys { !it.equals("user-agent", true) }.forEach { (k, v) -> rb.header(k, v) }
            http.newBuilder().callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build().newCall(rb.build()).execute().use { r ->
                val src = r.body?.source() ?: return@use null
                src.request(16)
                val head = src.buffer.snapshot().utf8().trimStart('\uFEFF')
                if (head.startsWith("#EXTM3U")) androidx.media3.common.MimeTypes.APPLICATION_M3U8 else null
            }
        } catch (e: Exception) { null }
    }

    fun addLink(url: String, name: String? = null, onId: (Long) -> Unit) {
        viewModelScope.launch {
            val u = url.trim()
            val id = dao.channelByUrl(u)?.id ?: dao.insertChannel(
                Channel(
                    name = name?.takeIf { it.isNotBlank() } ?: u.substringAfterLast('/').substringBefore('?').ifBlank { u },
                    url = u, grp = "Links", logo = null, addedAt = System.currentTimeMillis()
                )
            )
            onId(id)
        }
    }
    // ---------- Play on TV (phone -> TV) ----------
    class CastItem(val mode: String, val uri: String, val title: String, val posMs: Long = 0L)

    /** What to send to the TV for the item playing here: a link, or a file the TV is expected to have too. */
    suspend fun castItem(type: String, id: Long): Pair<CastItem?, String?> = withContext(Dispatchers.IO) {
        when (type) {
            "channel" -> dao.channel(id)?.let { CastItem("url", it.url, it.name) to null } ?: (null to "Channel not found")
            "web" -> webPlays[id]?.let { CastItem("url", it.uri, it.title) to null } ?: (null to "Can't send this one")
            else -> {
                val v = dao.video(id) ?: return@withContext (null to "Can't send this one")
                when {
                    v.uri.startsWith("http", true) -> CastItem("url", v.uri, v.fileName) to null
                    v.uri.startsWith("content:") || v.uri.startsWith("file:") -> CastItem("local", v.uri, v.fileName) to null     // only a Cast device can take these (the phone serves them)
                    else -> CastItem("file", v.uri, v.fileName) to null
                }
            }
        }
    }

    /** The TV got a "play this" from the phone. */
    fun openCast(e: CastEvent, go: (String) -> Unit) {
        viewModelScope.launch {
            if (e.posMs > 5000) CastState.starts[e.uri] = e.posMs else CastState.starts.remove(e.uri)
            if (e.mode == "file") {
                val v = dao.videoByUri(e.uri)
                if (v != null) go("player/file/${v.id}")
                else if (e.uri.startsWith("http", true)) addLink(e.uri, e.title) { go("player/channel/$it") }
                else notice.value = "This TV doesn't have that file yet. Add the same share on the TV first."
            } else addLink(e.uri, e.title) { go("player/channel/$it") }
        }
    }

    // ---------- opened from another app ----------
    val incoming = MutableStateFlow<String?>(null)

    /** A link or file sent by another app: play it straight away. */
    fun openIncoming(raw: String, go: (String) -> Unit) {
        viewModelScope.launch {
            val u = raw.trim()
            if (u.startsWith("http", ignoreCase = true)) {
                addLink(u) { id -> go("player/channel/$id") }
                return@launch
            }
            val uri = Uri.parse(u)
            try {
                getApplication<Application>().contentResolver
                    .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            withContext(Dispatchers.IO) { scanner.addFiles(listOf(uri)) }
            val v = dao.videoByUri(u)
            if (v != null) go("player/file/${v.id}") else notice.value = "Couldn't open that file."
        }
    }

    // ---------- web folders ("Index of" pages) ----------
    private val webAll = MutableStateFlow(WebStore.load(app))
    val webServers = combine(webAll, vault.hidden) { l, h -> l.filter { "web:" + it.id !in h } }.state(WebStore.load(app).filter { "web:" + it.id !in vault.locked.value })

    fun addWeb(name: String, url: String, user: String, pass: String) {
        val u = url.trim().let { if (it.startsWith("http", ignoreCase = true)) it else "http://$it" }
        val host = Uri.parse(u).host ?: u
        webAll.value = WebStore.add(
            getApplication(),
            WebServer(System.currentTimeMillis().toString(), name.trim().ifBlank { host }, u, user.trim(), pass)
        )
    }

    fun removeWeb(id: String) { webAll.value = WebStore.remove(getApplication(), id); vault.setLocked("web:$id", false) }

    suspend fun webList(s: WebServer, url: String): List<WebEntry> =
        withContext(Dispatchers.IO) { WebIndex.list(url, s.user, s.pass) }

    private class WebPlay(val uri: String, val title: String, val sub: String?, val next: Long?, val prev: Long?, val key: String, val group: String)
    private val webPlays = HashMap<Long, WebPlay>()
    private var webSeq = 1L

    /** Registers the videos of one web folder for playback (with Next/Previous) and returns the id of the tapped one. */
    fun webOpen(s: WebServer, entries: List<WebEntry>, tapped: WebEntry, folder: String = ""): Long {
        val group = "w${s.id}:" + folder.removePrefix(s.url).trim('/').substringBefore('/')
        val vids = entries.filter { it.isVideo }
        val subs = entries.filter { it.isSub }.associateBy { it.name.substringBeforeLast('.').lowercase() }
        val auth = if (s.user.isNotBlank()) "|Authorization=" + okhttp3.Credentials.basic(s.user, s.pass) else ""
        val first = webSeq
        webSeq += vids.size
        vids.forEachIndexed { i, v ->
            val id = first + i
            val base = v.name.substringBeforeLast('.')
            webPlays[id] = WebPlay(
                v.url + auth, base, subs[base.lowercase()]?.url,
                if (i + 1 < vids.size) id + 1 else null,
                if (i > 0) id - 1 else null,
                v.url, group
            )
        }
        return first + vids.indexOfFirst { it.url == tapped.url }.coerceAtLeast(0)
    }
    fun deleteChannel(id: Long) { viewModelScope.launch { dao.deleteChannel(id) } }

    // ---------- Live TV: playlists, filters, favourites ----------
    fun setLiveMode(mode: Int, group: String = "") {
        liveFilter.value = liveFilter.value.copy(mode = mode, group = group)
        prefs.setLive(mode, group)
    }
    fun setLiveQuery(q: String) { liveFilter.value = liveFilter.value.copy(query = q) }
    fun toggleFavorite(id: Long) { viewModelScope.launch { dao.toggleFavorite(id) } }

    private fun nameFromUrl(u: String): String =
        u.substringBefore('|').substringBefore('?').substringAfterLast('/').substringBeforeLast('.').ifBlank { "Playlist" }

    /** Downloads and reads a playlist as a stream. -> (channels, error) */
    /** Counts what is read so a sync can show how much of a big playlist has come down. */
    private class CountingIn(src: java.io.InputStream, private val total: Long) : java.io.FilterInputStream(src) {
        private var n = 0L
        private var t0 = 0L
        private fun add(k: Int) {
            if (k <= 0 || !SyncProgress.running) return
            n += k
            val now = System.currentTimeMillis()
            if (now - t0 < 250) return
            t0 = now
            SyncProgress.detail = "Downloading… ${n / 1_000_000} MB" + (if (total > 0) " of ${total / 1_000_000} MB" else "")
            if (total > 0) SyncProgress.sub = (n.toFloat() / total).coerceIn(0f, 1f) * 0.6f
        }
        override fun read(): Int = super.read().also { if (it >= 0) add(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { add(it) }
    }

    private suspend fun fetchChannels(raw: String): Pair<List<Channel>?, String?> = withContext(Dispatchers.IO) {
        try {
            val (clean, headers) = StreamHeaders.split(raw.trim())
            val rb = Request.Builder().url(clean).header("User-Agent", StreamHeaders.uaOf(headers))
            headers.filterKeys { !it.equals("user-agent", true) }.forEach { (k, v) -> rb.header(k, v) }
            listClient.newCall(rb.build()).execute().use { r ->
                val body = r.body
                if (!r.isSuccessful || body == null) return@use (null to "HTTP ${r.code}")
                (java.io.InputStreamReader(CountingIn(body.byteStream(), body.contentLength()), body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8).buffered().useLines { M3u.parseLines(it) } to null)
            }
        } catch (e: OutOfMemoryError) {
            null to "That playlist is too big for this device to load in one go."
        } catch (e: Exception) {
            null to (e.message ?: "network error")
        }
    }

    private fun buildDraft(name: String, url: String, channels: List<Channel>) {
        if (channels.isEmpty()) { draftError.value = "No channels found in that playlist."; return }
        val groups = channels.groupingBy { it.grp }.eachCount().toList().sortedByDescending { it.second }
        draft.value = PlaylistDraft(name, url, channels, groups)
    }

    fun loadPlaylistUrl(raw: String) {
        viewModelScope.launch {
            draftBusy.value = true; draftError.value = null
            val (chs, err) = fetchChannels(raw)
            if (chs == null) draftError.value = "Couldn't load the playlist: ${err ?: "unknown error"}"
            else buildDraft(nameFromUrl(raw.trim()), raw.trim(), chs)
            draftBusy.value = false
        }
    }

    fun loadPlaylistFile(uri: Uri) {
        viewModelScope.launch {
            draftBusy.value = true; draftError.value = null
            val ctx = getApplication<Application>()
            val chs = withContext(Dispatchers.IO) {
                try { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { rd -> rd.useLines { M3u.parseLines(it) } } }
                catch (e: OutOfMemoryError) { null } catch (e: Exception) { null }
            }
            val nm = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                } catch (e: Exception) { null }
            }
            if (chs == null) draftError.value = "Couldn't open that file."
            else buildDraft((nm ?: "Playlist").substringBeforeLast('.'), "", chs)
            draftBusy.value = false
        }
    }

    // ---------- channels moved to another group ----------
    // channel link -> group the user put it in. Kept so a refresh from the playlist URL doesn't move it back.
    private val movedPrefs = app.getSharedPreferences("moved_channels", android.content.Context.MODE_PRIVATE)

    fun moveChannel(c: Channel, group: String) {
        val g = group.trim()
        if (g.isBlank() || g == c.grp) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                dao.setChannelGroup(c.id, g)
                movedPrefs.edit().putString(c.url, g).apply()
            }
            val gone = withContext(Dispatchers.IO) { dao.groupCounts().first().none { it.name == c.grp } }
            if (gone && liveFilter.value.mode == 3 && liveFilter.value.group == c.grp) setLiveMode(0)
        }
    }

    /** Moves many channels into one group at once (remembered across refreshes, like single moves). */
    fun moveChannels(ids: Collection<Long>, group: String) {
        val g = group.trim()
        if (g.isBlank() || ids.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val e = movedPrefs.edit()
                ids.toList().chunked(500).forEach { chunk ->
                    dao.channelsByIds(chunk).forEach { e.putString(it.url, g) }
                    dao.setGroupOfIds(chunk, g)
                }
                e.apply()
            }
            val f = liveFilter.value
            val gone = f.mode == 3 && withContext(Dispatchers.IO) { dao.groupCounts().first().none { it.name == f.group } }
            if (gone) setLiveMode(0)
        }
    }

    /** Moves every channel of one group into another (only in the selected source, if one is chosen). */
    fun mergeGroup(from: String, to: String) {
        if (from == to) return
        val sel = liveSource.value.let { if (it in hiddenPidsNow()) -1L else it }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val pids = dao.playlistsOfGroupIn(from, sel)
                dao.renameGroupIn(from, to, sel)
                if (sel < 0) dropFromFolders(setOf(from))
                pids.forEach { pid ->
                    val m = renamesOf(pid).toMutableMap()
                    val origs = m.filterValues { it == from }.keys
                    if (origs.isEmpty()) m[from] = to else origs.forEach { m[it] = to }
                    saveRenames(pid, m)
                }
                val e = movedPrefs.edit()
                movedPrefs.all.filterValues { it == from }.keys.forEach { e.putString(it, to) }
                e.apply()
            }
            if ("live:$from" in vault.locked.value) vault.setLocked("live:$to", true)   // never expose a locked group's channels
            if (sel < 0) vault.setLocked("live:$from", false)
            if (liveFilter.value.mode == 3 && liveFilter.value.group == from) setLiveMode(3, to)
        }
    }

    // ---------- renamed channel groups ----------
    // Names the user chose for a playlist's groups (original name -> new name). Kept so a refresh from the URL doesn't bring the old names back.
    private val renamePrefs = app.getSharedPreferences("group_renames", android.content.Context.MODE_PRIVATE)
    private fun renamesOf(pid: Long): Map<String, String> = runCatching {
        val o = org.json.JSONObject(renamePrefs.getString(pid.toString(), "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())
    private fun saveRenames(pid: Long, m: Map<String, String>) {
        renamePrefs.edit().putString(pid.toString(), org.json.JSONObject(m).toString()).apply()
    }

    /** Renames a group everywhere. Two groups given the same name become one. */
    fun renameGroup(old: String, new: String) {
        val n = new.trim()
        if (n.isBlank() || n == old) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val pids = dao.playlistsOfGroup(old)
                dao.renameGroup(old, n)
                folderKeyRenamed(old, n)
                pids.forEach { pid ->
                    val m = renamesOf(pid).toMutableMap()
                    val origs = m.filterValues { it == old }.keys
                    if (origs.isEmpty()) m[old] = n else origs.forEach { m[it] = n }
                    saveRenames(pid, m)
                }
            }
            if ("live:$old" in vault.locked.value) { vault.setLocked("live:$old", false); vault.setLocked("live:$n", true) }
            if (liveFilter.value.mode == 3 && liveFilter.value.group == old) setLiveMode(3, n)
        }
    }

    /** Removes a group and every channel in it (only in the selected source, if one is chosen). A refresh won't bring it back. */
    fun removeGroup(g: String) = removeGroups(setOf(g))

    fun removeGroups(gs: Set<String>) {
        if (gs.isEmpty()) return
        val sel = liveSource.value.let { if (it in hiddenPidsNow()) -1L else it }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { for (g in gs) {
                val pids = dao.playlistsOfGroupIn(g, sel)
                dao.deleteGroup(g, sel)
                pids.forEach { pid ->
                    val p = dao.playlistRow(pid) ?: return@forEach
                    val ren = renamesOf(pid)
                    val origs = ren.filterValues { it == g }.keys + g
                    val base = p.groupList.split("\n").filter { it.isNotBlank() }.ifEmpty { dao.groupsOfPlaylist(pid) }
                    val kept = base.filter { it !in origs }
                    dao.setGroupList(pid, if (kept.isEmpty()) "\u0000" else kept.joinToString("\n"))   // "\u0000" = keep nothing
                    if (ren.any { it.value == g }) saveRenames(pid, ren.filterValues { it != g })
                }
                val e = movedPrefs.edit()
                movedPrefs.all.filterValues { it == g }.keys.forEach { e.remove(it) }
                e.apply()
            } }
            if (sel < 0) dropFromFolders(gs)
            if (liveFilter.value.mode == 3 && liveFilter.value.group in gs) setLiveMode(0)
            if (sel < 0) gs.forEach { vault.setLocked("live:$it", false) }
        }
    }

    fun cancelDraft() { draft.value = null; draftError.value = null; draftBusy.value = false }

    fun commitDraft(name: String, selected: Set<String>, renames: Map<String, String> = emptyMap(), done: () -> Unit) {
        val d = draft.value ?: return
        viewModelScope.launch {
            draftBusy.value = true
            withContext(Dispatchers.IO) {
                val same = if (d.url.isNotBlank()) dao.playlistByUrl(d.url) else null
                val pid = same?.id ?: dao.insertPlaylist(
                    Playlist(name = name.trim().ifBlank { d.name }, url = d.url, addedAt = System.currentTimeMillis(), groupList = selected.joinToString("\n"))
                )
                // Adding more groups to a playlist that is already here: its "kept groups" list must grow too, or a refresh drops them again.
                if (same != null && same.groupList.isNotBlank()) {
                    val old = same.groupList.split("\n").filter { it.isNotBlank() && it != "\u0000" }
                    dao.setGroupList(pid, (old + selected).distinct().joinToString("\n"))
                }
                val ren = renames.filter { (k, v) -> k in selected && v.isNotBlank() && v != k }
                if (ren.isNotEmpty()) saveRenames(pid, renamesOf(pid) + ren)
                dao.insertChannels(d.channels.filter { it.grp in selected }.map { it.copy(playlistId = pid, grp = ren[it.grp] ?: it.grp) })
            }
            draftBusy.value = false
            draft.value = null
            done()
        }
    }

    fun refreshPlaylist(p: Playlist) {
        if (p.url.isBlank()) return
        viewModelScope.launch { refreshNow(p) }
    }

    /** [extraFavs]: links to mark as favourites while loading (used when a phone syncs its favourites to this device). */
    private suspend fun refreshNow(p: Playlist, extraFavs: Set<String> = emptySet()): String {
        if (p.url.isBlank()) return "${p.name}: no link"
        var res = "${p.name}: nothing loaded"
        if (SyncProgress.running) { SyncProgress.sub = 0f; SyncProgress.detail = "Connecting…" }
        run {
            runBusy("Refreshing ${p.name}…") {
                val (parsed, err) = fetchChannels(p.url)
                if (parsed == null) { notice.value = "Couldn't refresh ${p.name}: ${err ?: "unknown error"}"; res = "${p.name}: failed (${err ?: "unknown error"})"; return@runBusy }
                val keep = p.groupList.split("\n").filter { it.isNotBlank() }.toSet()
                val gone = removedUrls()
                val fresh = parsed
                    .filter { (keep.isEmpty() || it.grp in keep) && it.url !in gone }
                val favs = dao.favoriteUrlsOf(p.id).toSet()
                if (SyncProgress.running) { SyncProgress.sub = 0.6f; SyncProgress.detail = "Saving ${fresh.size} channels…" }
                dao.deleteChannelsOfPlaylist(p.id)
                val ren = renamesOf(p.id)
                var saved = 0
                fresh.chunked(1000).forEach { part ->
                    dao.insertChannels(part.map { it.copy(playlistId = p.id, favorite = it.url in favs || it.url in extraFavs, grp = movedPrefs.getString(it.url, null) ?: ren[it.grp] ?: it.grp) })
                    saved += part.size
                    if (SyncProgress.running) { SyncProgress.sub = 0.6f + 0.4f * saved / fresh.size; SyncProgress.detail = "Saving channels… $saved of ${fresh.size}" }
                }
                val n = dao.channelCountOf(p.id)
                val note = if (fresh.size < parsed.size) " [file has ${parsed.size}; ${parsed.count { keep.isNotEmpty() && it.grp !in keep }} outside the ${keep.size} kept groups; ${parsed.count { it.url in gone }} removed links]" else ""
                res = "${p.name}: $n channels$note" + (if (n < fresh.size) " (${fresh.size - n} skipped, link already in another playlist)" else "")
            }
        }
        return res
    }

    fun removePlaylist(p: Playlist) {
        viewModelScope.launch { dao.deleteChannelsOfPlaylist(p.id); dao.deletePlaylist(p.id) }
        vault.setLocked("src:${p.id}", false); setSourceOn(p.id, true)
        if (liveSource.value == p.id) setLiveSource(-1L)
    }
    // ---------- sync to TV ----------
    /** How many things each sync item would send (shown next to the tick boxes). */
    suspend fun syncCounts(): Map<String, Int> = withContext(Dispatchers.IO) {
        mapOf(
            "playlists" to dao.playlists().first().count { it.url.isNotBlank() },
            "shares" to dao.foldersNow().count { it.uri.startsWith("smb://") || it.uri.startsWith("ftp://") },
            "web" to webAll.value.size,
            "favs" to dao.favoriteUrls().size,
            "keys" to ((if (prefs.tmdbKey.value.isNotBlank()) 1 else 0) + prefs.subSources.value.size),
            "locks" to vault.locked.value.size
        )
    }

    /** The groups a playlist keeps on refresh, made to agree with the channels it really has (an older "kept groups" list can lag behind). */
    private suspend fun effectiveGroupList(p: Playlist): String {
        if (p.groupList.isBlank()) return p.groupList            // empty = keep everything
        val base = p.groupList.split("\n").filter { it.isNotBlank() && it != "\u0000" }
        val ren = renamesOf(p.id)
        val have = dao.groupsOfPlaylist(p.id).map { g -> ren.filterValues { it == g }.keys.firstOrNull() ?: g }
        val all = (base + have).distinct()
        val eff = if (all.isEmpty()) p.groupList else all.joinToString("\n")
        if (eff != p.groupList) dao.setGroupList(p.id, eff)
        return eff
    }

    /** Everything the chosen items need, as one JSON object for the TV. Contains logins: it is only ever sent encrypted. */
    suspend fun buildSync(items: Set<String>, mirror: Boolean): org.json.JSONObject = withContext(Dispatchers.IO) {
        val o = org.json.JSONObject().put("v", 1).put("mirror", mirror).put("items", org.json.JSONArray(items.toList()))
        val pls = dao.playlists().first().filter { it.url.isNotBlank() }
        val pidUrl = pls.associate { it.id to it.url }
        if ("playlists" in items) {
            val a = org.json.JSONArray()
            pls.forEach { p ->
                a.put(org.json.JSONObject().put("name", p.name).put("url", p.url).put("groupList", effectiveGroupList(p))
                    .put("renames", org.json.JSONObject(renamesOf(p.id))))
            }
            o.put("playlists", a)
            val moved = org.json.JSONObject()
            movedPrefs.all.forEach { (k, v) -> if (v is String) moved.put(k, v) }
            o.put("moved", moved)
            o.put("removed", org.json.JSONArray(removedUrls().toList()))
            val links = org.json.JSONArray()
            dao.linkChannels().forEach { c -> links.put(org.json.JSONObject().put("name", c.name).put("url", c.url).put("grp", c.grp)) }
            o.put("links", links)
            o.put("folders", org.json.JSONObject(groupFolders.value))
        }
        if ("favs" in items) {
            o.put("favs", org.json.JSONArray(dao.favoriteUrls()))
            o.put("off", org.json.JSONArray(pls.filter { it.id in sourcesOff.value }.map { it.url }))
        }
        if ("shares" in items) {
            val smbs = dao.smbServers().first()
            val a = org.json.JSONArray()
            dao.foldersNow().forEach { f ->
                val u = Uri.parse(f.uri)
                when (u.scheme) {
                    "smb" -> {
                        val h = Smb.split(u).first
                        val s = smbs.firstOrNull { it.host.equals(h, true) }
                        a.put(org.json.JSONObject().put("kind", "smb").put("uri", f.uri).put("name", f.name).put("host", h)
                            .put("user", s?.user.orEmpty()).put("domain", s?.domain.orEmpty())
                            .put("pass", s?.let { runCatching { Secrets.decrypt(it.pass) }.getOrDefault("") }.orEmpty()))
                    }
                    "ftp" -> {
                        val (h, p, _) = Ftp.split(u)
                        val c = FtpStore.get(h, p)
                        a.put(org.json.JSONObject().put("kind", "ftp").put("uri", f.uri).put("name", f.name).put("host", h).put("port", p)
                            .put("user", c?.user.orEmpty()).put("pass", c?.pass.orEmpty()))
                    }
                }
            }
            o.put("shares", a)
        }
        if ("web" in items) {
            val a = org.json.JSONArray()
            webAll.value.forEach { w -> a.put(org.json.JSONObject().put("id", w.id).put("name", w.name).put("url", w.url).put("user", w.user).put("pass", w.pass)) }
            o.put("web", a)
        }
        if ("keys" in items) {
            o.put("tmdb", prefs.tmdbKey.value)
            val a = org.json.JSONArray()
            prefs.subSources.value.forEach { s ->
                a.put(org.json.JSONObject().put("id", s.id).put("type", s.type).put("label", s.label).put("key", s.key)
                    .put("user", s.user).put("pass", s.pass).put("url", s.url).put("enabled", s.enabled))
            }
            o.put("subs", a)
        }
        if ("locks" in items) {
            vault.export()?.let { v ->
                val keys = org.json.JSONArray()
                vault.locked.value.forEach { k ->
                    val out = when {
                        k.startsWith("src:") -> pidUrl[k.removePrefix("src:").toLongOrNull()]?.let { "src:$it" }
                        k.startsWith("smb://") || k.startsWith("ftp://") || k.startsWith("web:") || k.startsWith("live:") || k.startsWith("folder:") -> k
                        else -> null
                    }
                    if (out != null) keys.put(out)
                }
                o.put("locks", v.put("keys", keys))
            }
        }
        o
    }

    /** TV side: apply what the phone sent. Quick work happens here; downloads and scans carry on in the background. Returns a one-line summary. */
    suspend fun applySync(j: org.json.JSONObject): String {
        // Progress is visible from the first moment, not only once the playlists start loading.
        SyncProgress.results.clear(); SyncProgress.done = 0; SyncProgress.total = 0; SyncProgress.sub = 0f; SyncProgress.detail = ""
        SyncProgress.label = "Applying settings…"; SyncProgress.running = true
        try { return applySyncInner(j) } catch (e: Throwable) { SyncProgress.running = false; throw e }
    }
    private suspend fun applySyncInner(j: org.json.JSONObject): String = withContext(Dispatchers.IO) {
        fun strs(a: org.json.JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
        val mirror = j.optBoolean("mirror")
        val items = strs(j.optJSONArray("items")).toSet()
        val now = System.currentTimeMillis()
        val parts = ArrayList<String>()
        fun tally(label: String, add: Int, upd: Int, del: Int) {
            val t = listOfNotNull(if (add > 0) "+$add" else null, if (upd > 0) "~$upd" else null, if (del > 0) "−$del" else null)
            parts.add(label + " " + (if (t.isEmpty()) "no change" else t.joinToString(" ")))
        }
        val toRefresh = ArrayList<Playlist>()
        val toScan = ArrayList<LibraryFolder>()
        val addedPids = HashSet<Long>()

        if ("playlists" in items) {
            SyncProgress.label = "Applying playlists…"
            // links you removed and channels you moved
            val removed = strs(j.optJSONArray("removed")).toSet()
            val rm = if (mirror) removed else removedUrls() + removed
            removedPrefs.edit().putStringSet("urls", rm.toSet()).apply()
            removed.toList().chunked(500).forEach { dao.deleteChannelsByUrls(it) }
            val moved = j.optJSONObject("moved")
            val me = movedPrefs.edit()
            if (mirror) me.clear()
            val newMoves = ArrayList<String>()
            moved?.keys()?.forEach { k -> if (mirror || !movedPrefs.contains(k)) { me.putString(k, moved.getString(k)); newMoves.add(k) } }
            me.apply()
            newMoves.forEach { k -> dao.setGroupByUrl(k, moved!!.getString(k)) }
            j.optJSONObject("folders")?.let { f ->
                val m = HashMap<String, String>().also { if (!mirror) it.putAll(groupFolders.value) }
                f.keys().forEach { k -> if (mirror || k !in m) m[k] = f.getString(k) }
                storeFolders(m)
            }
            // the playlists themselves
            val arr = j.optJSONArray("playlists")
            val phoneUrls = HashSet<String>()
            var add = 0; var upd = 0; var del = 0
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val url = o.getString("url"); phoneUrls.add(url)
                val ren = o.optJSONObject("renames")?.let { r -> r.keys().asSequence().associateWith { r.getString(it) } } ?: emptyMap()
                val same = dao.playlistByUrl(url)
                if (same == null) {
                    val pid = dao.insertPlaylist(Playlist(name = o.optString("name", url), url = url, addedAt = now, groupList = o.optString("groupList")))
                    if (ren.isNotEmpty()) saveRenames(pid, ren)
                    addedPids.add(pid); add++
                    dao.playlistRow(pid)?.let { toRefresh.add(it) }
                } else if (mirror) {
                    dao.setGroupList(same.id, o.optString("groupList"))
                    if (o.optString("name").isNotBlank()) dao.renamePlaylist(same.id, o.optString("name"))
                    saveRenames(same.id, ren)
                    upd++
                    dao.playlistRow(same.id)?.let { toRefresh.add(it) }
                }
            }
            if (mirror) dao.playlists().first().filter { it.url.isNotBlank() && it.url !in phoneUrls }.forEach { removePlaylist(it); del++ }
            tally("Playlists", add, upd, del)
            // pasted links
            val links = j.optJSONArray("links")
            val have = dao.linkChannels()
            val phoneLinks = HashSet<String>()
            var la = 0; var ld = 0
            if (links != null) for (i in 0 until links.length()) {
                val o = links.getJSONObject(i)
                phoneLinks.add(o.getString("url"))
                if (have.none { it.url == o.getString("url") }) {
                    dao.insertChannel(Channel(name = o.optString("name"), url = o.getString("url"), grp = o.optString("grp", "Links"), logo = null, addedAt = now)); la++
                }
            }
            if (mirror) have.filter { it.url !in phoneLinks }.map { it.id }.chunked(500).forEach { dao.deleteChannelsByIds(it); ld += it.size }
            if (la + ld > 0) tally("Links", la, 0, ld)
        }

        var pendFavs = emptySet<String>()
        if ("favs" in items) {
            SyncProgress.label = "Applying favourites…"
            val urls = strs(j.optJSONArray("favs"))
            pendFavs = urls.toSet()
            if (mirror) dao.clearFavorites()
            urls.chunked(500).forEach { dao.setFavoriteByUrls(it) }
            val off = strs(j.optJSONArray("off")).toSet()
            dao.playlists().first().filter { it.url.isNotBlank() }.forEach { p ->
                if (mirror || p.id in addedPids) setSourceOn(p.id, p.url !in off)
            }
            parts.add("Favourites ${urls.size}, ${off.size} source(s) off")
        }

        if ("shares" in items) {
            SyncProgress.label = "Applying network shares…"
            val arr = j.optJSONArray("shares")
            val phoneUris = HashSet<String>()
            var add = 0; var upd = 0; var del = 0
            val existing = dao.foldersNow()
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val uri = o.getString("uri"); phoneUris.add(uri)
                val had = existing.any { it.uri == uri }
                if (had && !mirror) continue
                if (o.getString("kind") == "smb") {
                    val h = o.getString("host")
                    Smb.put(SmbCreds(h, o.optString("user"), o.optString("domain"), o.optString("pass")))
                    dao.upsertSmb(SmbServer(h, o.optString("user"), o.optString("domain"), Secrets.encrypt(o.optString("pass"))))
                } else {
                    FtpStore.put(FtpCreds(o.getString("host"), o.optInt("port", 21), o.optString("user"), o.optString("pass")))
                }
                if (!had) {
                    val f = LibraryFolder(uri, o.optString("name", uri), now)
                    dao.upsertFolder(f); toScan.add(f); add++
                } else { dao.renameFolder(uri, o.optString("name", uri)); upd++ }
            }
            if (mirror) existing.filter { (it.uri.startsWith("smb://") || it.uri.startsWith("ftp://")) && it.uri !in phoneUris }.forEach { removeFolder(it); del++ }
            tally("Shares", add, upd, del)
        }

        if ("web" in items) {
            SyncProgress.label = "Applying web folders…"
            val arr = j.optJSONArray("web")
            val ctx = getApplication<Application>()
            val phoneIds = HashSet<String>()
            var add = 0; var del = 0
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getString("id"); phoneIds.add(id)
                val cur = webAll.value
                if (cur.none { it.id == id || it.url == o.getString("url") }) {
                    webAll.value = WebStore.add(ctx, WebServer(id, o.optString("name"), o.getString("url"), o.optString("user"), o.optString("pass"))); add++
                }
            }
            if (mirror) webAll.value.filter { it.id !in phoneIds }.forEach { removeWeb(it.id); del++ }
            tally("Web folders", add, 0, del)
        }

        if ("keys" in items) {
            SyncProgress.label = "Applying keys…"
            val key = j.optString("tmdb")
            if (mirror || prefs.tmdbKey.value.isBlank()) prefs.setTmdbKey(key)
            val subs = j.optJSONArray("subs")
            val ids = HashSet<String>()
            var n = 0
            if (subs != null) for (i in 0 until subs.length()) {
                val o = subs.getJSONObject(i)
                val id = o.getString("id"); ids.add(id)
                if (mirror || prefs.subSources.value.none { it.id == id }) {
                    prefs.upsertSource(SubtitleSource(id, o.getString("type"), o.optString("label"), o.optString("key"), o.optString("user"), o.optString("pass"), o.optString("url"), o.optBoolean("enabled", true))); n++
                }
            }
            if (mirror) prefs.subSources.value.filter { it.id !in ids }.forEach { prefs.removeSource(it.id) }
            parts.add("Keys: TMDB${if (key.isBlank()) " (empty)" else ""}, $n subtitle source(s)")
        }

        if ("locks" in items) SyncProgress.label = "Applying locks…"
        j.optJSONObject("locks")?.takeIf { "locks" in items }?.let { l ->
            if (mirror || !vault.hasPin.value) vault.importPin(l.getString("salt"), l.getString("hash"), l.optInt("lockAfter"))
            val folders = dao.foldersNow().map { it.uri }.toSet()
            val webIds = webAll.value.map { it.id }.toSet()
            val keys = HashSet<String>()
            for (k in strs(l.optJSONArray("keys"))) {
                when {
                    k.startsWith("src:") -> dao.playlistByUrl(k.removePrefix("src:"))?.let { keys.add("src:${it.id}") }
                    k.startsWith("web:") -> if (k.removePrefix("web:") in webIds) keys.add(k)
                    k.startsWith("live:") || k.startsWith("folder:") -> keys.add(k)
                    else -> if (k in folders) keys.add(k)
                }
            }
            if (mirror) vault.setAllLocked(keys) else keys.forEach { vault.setLocked(it, true) }
            parts.add("Locks ${keys.size}")
        }

        if (toRefresh.isNotEmpty() || toScan.isNotEmpty()) {
            SyncProgress.results.clear()
            SyncProgress.done = 0; SyncProgress.total = toRefresh.size + toScan.size
            SyncProgress.label = "Starting…"; SyncProgress.running = true
            viewModelScope.launch {
                try {
                    toRefresh.forEach {
                        SyncProgress.label = "Loading ${it.name}…"; SyncProgress.sub = 0f; SyncProgress.detail = ""
                        SyncProgress.results.add(refreshNow(it, pendFavs))
                        SyncProgress.done++
                    }
                    toScan.forEach {
                        SyncProgress.label = "Scanning ${it.name}…"; SyncProgress.sub = 0f; SyncProgress.detail = ""
                        busy.value = null; runBusy("Scanning ${it.name}…") { safeScan(it) }
                        SyncProgress.results.add("${it.name}: scanned")
                        SyncProgress.done++
                    }
                } finally { SyncProgress.label = "Done"; SyncProgress.detail = ""; SyncProgress.sub = 0f; SyncProgress.running = false }
            }
        } else { SyncProgress.results.clear(); SyncProgress.done = 0; SyncProgress.total = 0; SyncProgress.running = false }
        val tail = if (toRefresh.isNotEmpty() || toScan.isNotEmpty()) " Loading in the background." else ""
        (if (mirror) "Mirrored. " else "Added what was missing. ") + parts.joinToString(" · ") + "." + tail
    }

    // ---------- duplicates ----------
    private fun sizeText(b: Long) = if (b >= 1_000_000_000L) "%.2f GB".format(b / 1e9) else "${b / 1_000_000} MB"

    /** Same title (and same season/episode for series) present in 2+ files. */
    suspend fun findDuplicates(): List<DupGroup> = withContext(Dispatchers.IO) {
        val names = dao.foldersNow().associate { it.uri to it.name }
        dao.allVideos().first()
            .groupBy { Triple(it.titleId, it.season, it.episode) }
            .filter { it.value.size > 1 }
            .mapNotNull { (k, list) ->
                val t = dao.titleNow(k.first) ?: return@mapNotNull null
                if (t.isSeries && (k.second == null || k.third == null)) return@mapNotNull null
                val label = if (t.isSeries) "${t.name} · S${k.second}E${k.third}"
                else t.name + (t.year?.let { " ($it)" } ?: "")
                DupGroup(label, list.map { v ->
                    "${v.folderUri?.let { names[it] } ?: "Individual video"} · ${v.fileName} · ${sizeText(v.sizeBytes)}"
                })
            }
            .sortedBy { it.label.lowercase() }
    }

    // ---------- Stash (performers) ----------
    private val stashVisible = combine(dao.stashRows(), vault.hidden) { rows, h ->
        if (h.isEmpty()) rows else rows.filter { it.folderUri == null || it.folderUri !in h }
    }
    /** Performers that have at least one video on a source that is open right now (so a locked or unplugged source shows nothing). */
    val performers = combine(stashVisible, dao.stashPerformers()) { rows, ps ->
        val byId = ps.associateBy { it.id }
        rows.groupBy { it.performerId }.mapNotNull { (pid, rs) ->
            val n = rs.map { it.videoId }.distinct().size
            if (pid == 0) PerformerEntry(0, "Unknown", false, n) else byId[pid]?.let { PerformerEntry(pid, it.name, it.hasPhoto, n, it.gender) }
        }.sortedWith(compareBy({ it.id == 0 }, { it.name.lowercase() }))
    }.state(emptyList())
    /** scenes in Stash, performers in Stash, videos of the app that match */
    val stashStats = combine(dao.stashSceneCount(), dao.stashPerformerCount(), stashVisible) { s, p, r -> Triple(s, p, r.map { it.videoId }.distinct().size) }.state(Triple(0, 0, 0))
    fun performerVideos(id: Int): Flow<List<StashRow>> = stashVisible.map { rows -> rows.filter { it.performerId == id }.distinctBy { it.videoId } }
    val stashMsg = MutableStateFlow<String?>(null)
    fun stashDefaultDir() = StashImport.defaultDir(getApplication()).takeIf { java.io.File(it, "stash.json").exists() }
    fun importStash(tree: android.net.Uri?) {
        if (stashMsg.value?.startsWith("Importing") == true) return
        stashMsg.value = "Importing…"
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val db = AppDb.get(ctx)
            stashMsg.value = try {
                val r = if (tree != null) StashImport.fromTree(ctx, db, tree) else StashImport.fromDir(ctx, db, StashImport.defaultDir(ctx))
                "Imported ${r.scenes} scenes and ${r.performers} performers (${r.photos} photos)."
            } catch (t: Throwable) { "Import failed: ${t.message ?: t.javaClass.simpleName}" }
        }
    }
    // Drives: the Stash data written onto a drive by the PC (MyMovieApp_stash\\stash.json) is picked up when the drive is there.
    private val stashDrives = StashDrives(app)
    val stashDriveList = MutableStateFlow(stashDrives.trees().toList())
    init { viewModelScope.launch { kotlinx.coroutines.delay(2500); try { syncStashDrives(false) } catch (_: Throwable) {} } }

    /** Looks for new or changed Stash data on every connected drive (library folders and drives linked in Settings). Returns a one-line result. */
    suspend fun syncStashDrives(report: Boolean): String = withContext(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        val db = AppDb.get(ctx)
        val candidates = (stashDrives.trees() + dao.foldersNow().map { it.uri }.filter { it.startsWith("content://") }).distinct()
        var scenes = 0; var seen = 0; var failed: String? = null
        for (u in candidates) {
            try {
                val root = DocumentFile.fromTreeUri(ctx, Uri.parse(u)) ?: continue
                if (!root.exists()) continue
                val dir = root.findFile("MyMovieApp_stash") ?: continue
                val js = dir.findFile("stash.json") ?: continue
                seen++
                val sig = "${js.lastModified()}:${js.length()}"
                if (stashDrives.sig(u) == sig) continue
                scenes += StashImport.fromDoc(ctx, db, dir, true).scenes
                stashDrives.setSig(u, sig)
            } catch (t: Throwable) { failed = t.message ?: t.javaClass.simpleName }
        }
        // network shares (SMB, e.g. over Tailscale): the same MyMovieApp_stash folder on the share
        for (f in dao.foldersNow().map { it.uri }.filter { it.startsWith("smb://") }) {
            try {
                val (host, shareName, baseRel) = Smb.split(Uri.parse(f))
                if (Smb.credsFor(host) == null) continue
                val share = Smb.share(host, shareName)
                val dirRel = if (baseRel.isEmpty()) "MyMovieApp_stash" else "$baseRel\\MyMovieApp_stash"
                val js = "$dirRel\\stash.json"
                if (!share.fileExists(js)) continue
                seen++
                val info = share.getFileInformation(js)
                val sig = "${info.basicInformation.lastWriteTime.toEpochMillis()}:${info.standardInformation.endOfFile}"
                if (stashDrives.sig(f) == sig) continue
                scenes += StashImport.fromSmb(ctx, db, share, dirRel, true).scenes
                stashDrives.setSig(f, sig)
            } catch (t: Throwable) { failed = t.message ?: t.javaClass.simpleName }
        }
        try { seedStashCovers() } catch (_: Throwable) {}
        val msg = when {
            failed != null && scenes == 0 -> "Couldn't read the Stash data: $failed"
            scenes > 0 -> "Synced Stash data from the drive ($scenes scenes)."
            seen > 0 -> "Stash data on the drive is up to date."
            else -> "No Stash data found on the connected drives. Run \"Export Stash to drive.bat\" on the PC, and link the drive here if it isn't a library folder."
        }
        if (scenes > 0 || report) stashMsg.value = msg
        msg
    }
    /** Stash covers take the place of the app's own video stills (once per video, so later stills the app makes itself stay). */
    private suspend fun seedStashCovers() {
        val ctx = getApplication<Application>()
        val done = stashDrives.seeded()
        val made = ArrayList<String>()
        for (r in dao.stashRows().first().distinctBy { it.videoId }) {
            // already applied, and the picture is still there (the system can clear the cache folder)
            if (r.uri in done && Thumbs.cached(ctx, r.uri) != null) continue
            val f = StashImport.coverFile(ctx, r.sceneId)
            if (f.exists() && f.length() > 0) { Thumbs.seed(ctx, r.uri, f); made += r.uri }
        }
        if (made.isNotEmpty()) stashDrives.addSeeded(made)
    }
    fun checkStashDrives() { stashMsg.value = "Checking drives…"; stashDrives.clearSigs(); viewModelScope.launch { try { syncStashDrives(true) } catch (t: Throwable) { stashMsg.value = "Check failed: ${t.message}" } } }
    fun addStashDrive(tree: Uri) {
        val ctx = getApplication<Application>()
        try { ctx.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        stashDrives.add(tree.toString()); stashDrives.setSig(tree.toString(), null)
        stashDriveList.value = stashDrives.trees().toList()
        checkStashDrives()
    }
    fun removeStashDrive(uri: String) { stashDrives.remove(uri); stashDriveList.value = stashDrives.trees().toList() }
    fun clearStash() { viewModelScope.launch { StashImport.clear(getApplication(), AppDb.get(getApplication())); stashMsg.value = "Stash data removed." } }

    // ---------- storage ----------
    fun clearProgress() { viewModelScope.launch { dao.clearProgress() } }
    fun clearChannels() {
        removedPrefs.edit().clear().apply()
        viewModelScope.launch { dao.clearChannels(); dao.clearPlaylists() }
    }
    fun resetLibrary() {
        viewModelScope.launch { dao.resetVideos(); dao.resetTitles(); dao.resetFolders(); dao.clearProgress() }
    }
}
