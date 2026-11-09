package com.mymovieapp.app.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.concurrent.thread

/** Phone -> TV: what the TV receiver hands to the app. kind: "play" or "cmd". */
data class CastEvent(val kind: String, val uri: String = "", val title: String = "", val mode: String = "", val posMs: Long = 0L)

/** TV side: where playback is right now (filled in by the player screen) so the phone can show a seek bar. */
object CastState {
    @Volatile var reader: (() -> Triple<Long, Long, Boolean>)? = null   // position, length, playing
    @Volatile var title: String = ""
    @Volatile var seeker: ((Long) -> Unit)? = null
    /** Start positions the phone asked for, by address; used once when the TV opens the item. */
    val starts = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** The phone is driving playback (handoff): the TV doesn't pick a next video or retry by itself when this one ends. */
    @Volatile var phoneDriven = false
    @Volatile var phoneLive = false
    @Volatile var ended = false
    /** Address of the last item the phone sent as an ordinary video (not live TV). The TV opens it like a link, but LEFT / RIGHT must seek in it, not change channel. */
    @Volatile var castVideoUri: String = ""
    fun isCastedVideo(uri: String?): Boolean =
        uri != null && castVideoUri.isNotEmpty() && com.mymovieapp.app.ui.parseStreamRequest(castVideoUri).first == com.mymovieapp.app.ui.parseStreamRequest(uri).first
    /** Extra things for the phone's controls: audio and subtitle tracks, speed. */
    @Volatile var info: (() -> JSONObject)? = null
    /** Commands only the player screen can do: "audio:<id>", "spu:<id>", "speed:<x>". */
    @Volatile var cmd: ((String) -> Unit)? = null
}

/** Phone side: the TV a video has been handed over to. While set, the phone's player screen is a remote for that TV. */
object CastSession {
    @Volatile var tv: CastTv? = null
    @Volatile var pin: String = ""
    /** Set while one player screen is replaced by the next one (next / previous), so the session survives the swap. */
    @Volatile var handover = false
    fun set(t: CastTv, p: String) { tv = t; pin = p }
    fun clear() { tv = null; pin = ""; handover = false }
}

/** Things the TV app picks up (collected in App.kt). */
object CastBus {
    val events = MutableSharedFlow<CastEvent>(extraBufferCapacity = 16)
    /** TV: applies a "Sync to TV" payload from the phone and returns a one-line summary (set by the view model). */
    @Volatile var sync: ((JSONObject) -> String)? = null
    /** TV: builds what a phone asked to pull ({items, mirror}) and returns it (set by the view model). */
    @Volatile var pull: ((JSONObject) -> JSONObject)? = null
}

/** How far the background loading after a sync has got (on the device that applied the sync). */
object SyncProgress {
    @Volatile var running = false
    @Volatile var done = 0
    @Volatile var total = 0
    @Volatile var label = ""
    /** Inside the playlist being loaded: how far it is (0..1) and a short text such as "Downloading… 12 MB". */
    @Volatile var sub = 0f
    @Volatile var detail = ""
    val results = java.util.concurrent.CopyOnWriteArrayList<String>()
    fun json(): JSONObject = JSONObject().put("running", running).put("done", done).put("total", total).put("label", label).put("sub", sub.toDouble()).put("detail", detail)
        .put("results", org.json.JSONArray(results.toList()))
}

/** Encrypts the sync payload with a key made from the TV's PIN, so logins never cross the Wi-Fi in the clear and the PIN itself is never sent. */
object SyncCrypto {
    private fun key(pin: String, salt: ByteArray): javax.crypto.spec.SecretKeySpec {
        val k = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, 100_000, 256)).encoded
        return javax.crypto.spec.SecretKeySpec(k, "AES")
    }
    /** salt(16) + iv(12) + ciphertext */
    fun encrypt(pin: String, plain: ByteArray): ByteArray {
        val r = SecureRandom()
        val salt = ByteArray(16).also { r.nextBytes(it) }
        val iv = ByteArray(12).also { r.nextBytes(it) }
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, key(pin, salt), javax.crypto.spec.GCMParameterSpec(128, iv))
        return salt + iv + c.doFinal(plain)
    }
    /** null when the PIN is wrong or the data was changed on the way. */
    fun decrypt(pin: String, data: ByteArray): ByteArray? = try {
        if (data.size < 16 + 12 + 16) null else {
            val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            c.init(javax.crypto.Cipher.DECRYPT_MODE, key(pin, data.copyOfRange(0, 16)), javax.crypto.spec.GCMParameterSpec(128, data, 16, 12))
            c.doFinal(data, 28, data.size - 28)
        }
    } catch (_: Exception) { null }
}

/** [kind] "app" = a TV running this app; "gcast" = a Cast device (host holds its route id). */
data class CastTv(val name: String, val host: String, val port: Int, val kind: String = "app")

private const val SERVICE_TYPE = "_mymoviecast._tcp."
const val CAST_PORT = 8765

/**
 * The TV side: a very small web server on the home network that the phone talks to,
 * announced as "_mymoviecast._tcp" so the phone finds it. Every request must carry the TV's PIN.
 */
object CastReceiver {
    private var server: ServerSocket? = null
    private var nsd: NsdManager? = null
    private var reg: NsdManager.RegistrationListener? = null
    private var fails = 0
    private var failSince = 0L

    private fun sp(ctx: Context) = ctx.getSharedPreferences("cast", Context.MODE_PRIVATE)

    fun pin(ctx: Context): String {
        val s = sp(ctx)
        s.getString("pin", null)?.let { return it }
        val p = (100000 + SecureRandom().nextInt(900000)).toString()
        s.edit().putString("pin", p).apply()
        return p
    }
    fun newPin(ctx: Context): String {
        val p = (100000 + SecureRandom().nextInt(900000)).toString()
        sp(ctx).edit().putString("pin", p).apply()
        return p
    }
    fun deviceName(ctx: Context): String =
        runCatching { Settings.Global.getString(ctx.contentResolver, "device_name") }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL

    fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    @Synchronized fun start(ctx0: Context) {
        if (server != null) return
        val ctx = ctx0.applicationContext
        val ss = try { ServerSocket(CAST_PORT) } catch (_: Exception) { try { ServerSocket(0) } catch (_: Exception) { return } }
        server = ss
        thread(name = "cast-accept", isDaemon = true) {
            while (!ss.isClosed) {
                val c = try { ss.accept() } catch (_: Exception) { break }
                thread(name = "cast-conn", isDaemon = true) { handle(ctx, c) }
            }
        }
        try {
            val m = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply { serviceName = "MyMovieApp-" + deviceName(ctx); serviceType = SERVICE_TYPE; port = ss.localPort }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) {}
                override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
            }
            m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            nsd = m; reg = l
        } catch (_: Exception) {}
    }

    @Synchronized fun stop() {
        try { reg?.let { nsd?.unregisterService(it) } } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        server = null; reg = null
    }

    private fun handle(ctx: Context, s: Socket) {
        try {
            s.soTimeout = 5000
            val inp = s.getInputStream().buffered()
            fun line(): String? {
                val sb = StringBuilder()
                while (sb.length < 8192) {
                    val b = inp.read()
                    if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                    if (b == '\n'.code) return sb.toString().trimEnd('\r')
                    sb.append(b.toChar())
                }
                return null
            }
            val first = line() ?: return
            val parts = first.split(' ')
            val path = parts.getOrNull(1) ?: return
            var len = 0
            var given = ""
            while (true) {
                val h = line() ?: break
                if (h.isEmpty()) break
                val k = h.substringBefore(':').trim().lowercase()
                val v = h.substringAfter(':', "").trim()
                if (k == "content-length") len = v.toIntOrNull() ?: 0
                if (k == "x-pin") given = v
            }
            val enc = path == "/sync" || path == "/pull"
            val maxLen = if (enc) 8_000_000 else if (path == "/sub") 2_000_000 else 65536
            val raw = if (len in 1..maxLen) ByteArray(len).also { buf ->
                var n = 0
                while (n < len) { val r = inp.read(buf, n, len - n); if (r < 0) break; n += r }
            } else ByteArray(0)
            val body = if (enc) "" else String(raw)
            val now = System.currentTimeMillis()
            if (now - failSince > 60_000) { fails = 0; failSince = now }
            if (fails >= 5) { reply(s, 429, JSONObject().put("error", "Too many wrong PINs, try again in a minute")); return }
            if (path == "/sync") {
                // No PIN header: knowing the PIN is proved by the data decrypting.
                val plain = SyncCrypto.decrypt(pin(ctx), raw)
                if (plain == null) { fails++; reply(s, 403, JSONObject().put("error", "Wrong PIN")); return }
                val h = CastBus.sync
                val pj = runCatching { JSONObject(String(plain)) }.getOrNull()
                if (h == null || pj == null) { reply(s, 400, JSONObject().put("error", "The TV couldn't read that")); return }
                val msg = try { h(pj) } catch (e: Exception) { reply(s, 500, JSONObject().put("error", "TV failed: " + (e.message ?: e.javaClass.simpleName))); return }
                reply(s, 200, JSONObject().put("ok", true).put("name", msg))
                return
            }
            if (path == "/pull") {
                val plain = SyncCrypto.decrypt(pin(ctx), raw)
                if (plain == null) { fails++; reply(s, 403, JSONObject().put("error", "Wrong PIN")); return }
                val h = CastBus.pull
                val rq = runCatching { JSONObject(String(plain)) }.getOrNull()
                if (h == null || rq == null) { reply(s, 400, JSONObject().put("error", "The TV couldn't read that")); return }
                val out = try { h(rq) } catch (e: Exception) { reply(s, 500, JSONObject().put("error", "TV failed: " + (e.message ?: e.javaClass.simpleName))); return }
                val enc2 = SyncCrypto.encrypt(pin(ctx), out.toString().toByteArray())
                val o = s.getOutputStream()
                o.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${enc2.size}\r\nConnection: close\r\n\r\n".toByteArray())
                o.write(enc2); o.flush()
                return
            }
            val ok = MessageDigest.isEqual(given.toByteArray(), pin(ctx).toByteArray())
            if (!ok) { fails++; reply(s, 403, JSONObject().put("error", "Wrong PIN")); return }
            val j = if (body.isNotBlank()) runCatching { JSONObject(body) }.getOrNull() else null
            when (path) {
                "/ping" -> reply(s, 200, JSONObject().put("ok", true).put("name", deviceName(ctx)))
                "/play" -> {
                    val uri = j?.optString("uri").orEmpty()
                    if (uri.isBlank()) { reply(s, 400, JSONObject().put("error", "Nothing to play")); return }
                    CastState.phoneDriven = j?.optBoolean("handoff") == true
                    CastState.phoneLive = j?.optBoolean("live") == true
                    CastState.castVideoUri = if (j?.has("live") == true && j.optBoolean("live") == false) uri else ""
                    CastState.ended = false
                    CastBus.events.tryEmit(CastEvent("play", uri, j?.optString("title").orEmpty(), j?.optString("mode") ?: "url", j?.optLong("pos") ?: 0L))
                    reply(s, 200, JSONObject().put("ok", true))
                }
                "/sub" -> {
                    val text = j?.optString("text").orEmpty()
                    if (text.isBlank()) { reply(s, 400, JSONObject().put("error", "Empty subtitle")); return }
                    val ext = j?.optString("ext")?.takeIf { it in setOf("srt", "vtt", "ass", "ssa") } ?: "srt"
                    val f = java.io.File(ctx.cacheDir, "castsub_${System.currentTimeMillis()}.$ext")
                    ctx.cacheDir.listFiles { x -> x.name.startsWith("castsub_") }?.forEach { it.delete() }
                    f.writeText(text, Charsets.UTF_8)
                    val h = CastState.cmd
                    if (h == null) { reply(s, 400, JSONObject().put("error", "Nothing is playing on the TV")); return }
                    h("subfile:" + f.absolutePath)
                    reply(s, 200, JSONObject().put("ok", true))
                }
                "/status" -> {
                    val r = CastState.reader?.invoke()
                    val out = JSONObject().put("active", r != null).put("pos", r?.first ?: 0L).put("len", r?.second ?: 0L)
                        .put("playing", r?.third ?: false).put("title", CastState.title).put("ended", CastState.ended)
                    try { CastState.info?.invoke()?.let { x -> x.keys().forEach { k -> out.put(k, x.get(k)) } } } catch (_: Exception) {}
                    reply(s, 200, out)
                }
                "/syncstatus" -> reply(s, 200, SyncProgress.json())
                "/cmd" -> {
                    val a = j?.optString("action").orEmpty()
                    if (a.isBlank()) { reply(s, 400, JSONObject().put("error", "No command")); return }
                    CastBus.events.tryEmit(CastEvent("cmd", mode = a))
                    reply(s, 200, JSONObject().put("ok", true))
                }
                else -> reply(s, 404, JSONObject().put("error", "Unknown request"))
            }
        } catch (_: Exception) {
        } finally { try { s.close() } catch (_: Exception) {} }
    }

    private fun reply(s: Socket, code: Int, j: JSONObject) {
        val b = j.toString().toByteArray()
        val st = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 500 -> "Server Error"; else -> "Too Many Requests" }
        val o = s.getOutputStream()
        o.write("HTTP/1.1 $code $st\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
        o.write(b); o.flush()
    }
}

/** The phone side: finds TVs on the network and sends them what to play. */
object CastSender {
    val tvs = MutableStateFlow<List<CastTv>>(emptyList())
    private var mgr: NsdManager? = null
    private var disc: NsdManager.DiscoveryListener? = null
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    private fun sp(ctx: Context) = ctx.getSharedPreferences("cast", Context.MODE_PRIVATE)
    fun pinFor(ctx: Context, tv: CastTv): String? = sp(ctx).getString("pin:" + tv.name, null)
    fun savePin(ctx: Context, tv: CastTv, pin: String) { sp(ctx).edit().putString("pin:" + tv.name, pin).apply() }

    @Synchronized fun startDiscovery(ctx0: Context) {
        if (disc != null) return
        val m = ctx0.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        mgr = m
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) { disc = null }
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceFound(si: NsdServiceInfo) { synchronized(pending) { pending.addLast(si) }; next() }
            override fun onServiceLost(si: NsdServiceInfo) { tvs.value = tvs.value.filter { it.name != si.serviceName } }
        }
        disc = l
        try { m.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) } catch (_: Exception) { disc = null }
    }

    @Synchronized fun stopDiscovery() {
        try { disc?.let { mgr?.stopServiceDiscovery(it) } } catch (_: Exception) {}
        disc = null
        synchronized(pending) { pending.clear(); resolving = false }
    }

    private fun next() {
        val si = synchronized(pending) {
            if (resolving) return
            val n = pending.removeFirstOrNull() ?: return
            resolving = true; n
        }
        try {
            @Suppress("DEPRECATION")
            mgr?.resolveService(si, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, e: Int) { synchronized(pending) { resolving = false }; next() }
                override fun onServiceResolved(i: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val host: InetAddress? = i.host
                    val h = host?.hostAddress
                    if (h != null) {
                        val name = i.serviceName.removePrefix("MyMovieApp-")
                        tvs.value = tvs.value.filter { it.name != name } + CastTv(name, h, i.port)
                    }
                    synchronized(pending) { resolving = false }; next()
                }
            })
        } catch (_: Exception) { synchronized(pending) { resolving = false } }
    }

    /** Like [statusOf] but keeps the answer when nothing is playing; null only when the TV can't be reached. */
    suspend fun statusRaw(tv: CastTv, pin: String): JSONObject? {
        if (tv.kind == "gcast") return GCast.status()
        return statusRawApp(tv, pin)
    }
    private suspend fun statusRawApp(tv: CastTv, pin: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val c = URL("http://${tv.host}:${tv.port}/status").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 1500; c.readTimeout = 2500; c.doOutput = true
            c.setRequestProperty("X-Pin", pin)
            c.outputStream.use { it.write("{}".toByteArray()) }
            if (c.responseCode !in 200..299) null else JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Exception) { null }
    }

    /** What the TV is playing: (position, length, playing, title), or null when nothing is. */
    suspend fun statusOf(tv: CastTv, pin: String): JSONObject? {
        if (tv.kind == "gcast") return GCast.status()?.takeIf { it.optBoolean("active") }
        return statusOfApp(tv, pin)
    }
    private suspend fun statusOfApp(tv: CastTv, pin: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val c = URL("http://${tv.host}:${tv.port}/status").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 2500; c.readTimeout = 3000; c.doOutput = true
            c.setRequestProperty("X-Pin", pin)
            c.outputStream.use { it.write("{}".toByteArray()) }
            if (c.responseCode !in 200..299) null
            else JSONObject(c.inputStream.bufferedReader().use { it.readText() }).takeIf { it.optBoolean("active") }
        } catch (_: Exception) { null }
    }

    /** -> (ok, message). */
    suspend fun post(tv: CastTv, pin: String, path: String, body: JSONObject = JSONObject()): Pair<Boolean, String> {
        if (tv.kind == "gcast") return when (path) {
            "/ping" -> GCast.connect(tv)
            "/play" -> GCast.play(body)
            "/cmd" -> GCast.command(body.optString("action"))
            else -> false to "Not available on this TV"
        }
        return postApp(tv, pin, path, body)
    }
    private suspend fun postApp(tv: CastTv, pin: String, path: String, body: JSONObject): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val c = URL("http://${tv.host}:${tv.port}$path").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 4000; c.readTimeout = 6000; c.doOutput = true
            c.setRequestProperty("X-Pin", pin); c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val j = runCatching { JSONObject(txt) }.getOrNull()
            if (code in 200..299) true to (j?.optString("name").orEmpty())
            else false to (j?.optString("error")?.takeIf { it.isNotBlank() } ?: "TV said $code")
        } catch (e: Exception) {
            false to "Can't reach the TV. Is the app open on it, on the same Wi-Fi?"
        }
    }

    /** Sends a subtitle file from this phone to the video playing on [tv] (a TV with the app, or a Cast device). -> (ok, message). */
    suspend fun sendSubtitle(tv: CastTv, pin: String, raw: ByteArray, fileName: String): Pair<Boolean, String> {
        if (tv.kind == "gcast") return GCast.addSubtitle(raw, fileName)
        val text = SubText.decode(raw, "")
        val ext = fileName.substringAfterLast('.', "srt").lowercase()
        return postApp(tv, pin, "/sub", JSONObject().put("text", text).put("ext", ext).put("name", fileName)).let { (ok, m) -> if (ok) true to "Subtitles sent" else false to m }
    }

    /** How far the TV's background loading has got after a sync, or null if it can't be reached. */
    suspend fun syncStatus(tv: CastTv, pin: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val c = URL("http://${tv.host}:${tv.port}/syncstatus").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 3000; c.readTimeout = 4000; c.doOutput = true
            c.setRequestProperty("X-Pin", pin)
            c.outputStream.use { it.write("{}".toByteArray()) }
            if (c.responseCode !in 200..299) null else JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Exception) { null }
    }

    /** Asks the TV for its playlists, shares and settings. -> (what the TV sent, or an error text). */
    suspend fun pull(tv: CastTv, pin: String, items: Set<String>, mirror: Boolean): Pair<JSONObject?, String> = withContext(Dispatchers.IO) {
        try {
            val rq = JSONObject().put("items", org.json.JSONArray(items.toList())).put("mirror", mirror)
            val data = SyncCrypto.encrypt(pin, rq.toString().toByteArray())
            val c = URL("http://${tv.host}:${tv.port}/pull").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 5000; c.readTimeout = 120_000; c.doOutput = true
            c.setFixedLengthStreamingMode(data.size)
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.outputStream.use { it.write(data) }
            val code = c.responseCode
            if (code !in 200..299) {
                val txt = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                return@withContext null to (runCatching { JSONObject(txt).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() } ?: "TV said $code")
            }
            val plain = SyncCrypto.decrypt(pin, c.inputStream.use { it.readBytes() }) ?: return@withContext null to "Couldn't read the TV's answer (wrong PIN?)"
            JSONObject(String(plain)) to ""
        } catch (e: Exception) {
            null to "Can't reach the TV. Is the app open on it, on the same Wi-Fi?"
        }
    }

    /** Sends a sync payload, encrypted with the TV's PIN. -> (ok, summary or error). */
    suspend fun sync(tv: CastTv, pin: String, payload: JSONObject): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val data = SyncCrypto.encrypt(pin, payload.toString().toByteArray())
            val c = URL("http://${tv.host}:${tv.port}/sync").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 5000; c.readTimeout = 120_000; c.doOutput = true
            c.setFixedLengthStreamingMode(data.size)
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.outputStream.use { it.write(data) }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val j = runCatching { JSONObject(txt) }.getOrNull()
            if (code in 200..299) true to (j?.optString("name").orEmpty())
            else false to (j?.optString("error")?.takeIf { it.isNotBlank() } ?: "TV said $code")
        } catch (e: Exception) {
            false to "Can't reach the TV. Is the app open on it, on the same Wi-Fi?"
        }
    }
}
