package com.mymovieapp.app.data

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

data class FtpCreds(val host: String, val port: Int, val user: String, val pass: String)

/** Saved FTP logins. Passwords are encrypted with the same key as the SMB ones. */
object FtpStore {
    private val creds = ConcurrentHashMap<String, FtpCreds>()
    private var prefs: android.content.SharedPreferences? = null
    private fun key(host: String, port: Int) = "${host.lowercase()}:$port"

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.getSharedPreferences("ftp_servers", Context.MODE_PRIVATE)
        prefs = p
        for ((k, v) in p.all) {
            try {
                val o = org.json.JSONObject(v as String)
                val host = k.substringBeforeLast(':')
                val port = k.substringAfterLast(':').toIntOrNull() ?: 21
                creds[k] = FtpCreds(host, port, o.getString("u"), Secrets.decrypt(o.getString("p")))
            } catch (_: Exception) {}
        }
    }

    fun put(c: FtpCreds) {
        creds[key(c.host, c.port)] = c
        val o = org.json.JSONObject().put("u", c.user).put("p", Secrets.encrypt(c.pass))
        prefs?.edit()?.putString(key(c.host, c.port), o.toString())?.apply()
    }

    fun get(host: String, port: Int): FtpCreds? = creds[key(host, port)]
}

/** Helpers for ftp://host:port/path URIs. Passwords are never put in the URI. */
object Ftp {
    fun uri(host: String, port: Int, path: String): Uri {
        val b = Uri.Builder().scheme("ftp").encodedAuthority("$host:$port")
        path.split('/').filter { it.isNotEmpty() }.forEach { b.appendPath(it) }
        return b.build()
    }

    /** -> (host, port, absolute path such as /movies/a.mkv) */
    fun split(uri: Uri): Triple<String, Int, String> =
        Triple(uri.host ?: "", if (uri.port > 0) uri.port else 21, "/" + uri.pathSegments.joinToString("/"))

    fun quit(f: FTPClient?) { try { f?.disconnect() } catch (_: Exception) {} }

    fun connect(host: String, port: Int): FTPClient {
        val c = FtpStore.get(host, port)
        val f = FTPClient()
        try {
            f.setConnectTimeout(15_000)
            f.setDefaultTimeout(30_000)
            f.setControlEncoding("UTF-8")
            f.connect(host, port)
            if (!FTPReply.isPositiveCompletion(f.replyCode)) throw IOException("The FTP server refused the connection")
            val anon = c == null || c.user.isBlank()
            if (!f.login(if (anon) "anonymous" else c!!.user, if (anon) "guest@example.com" else c!!.pass))
                throw IOException("FTP login failed. Check the username and password.")
            f.sendCommand("OPTS UTF8 ON")
            f.enterLocalPassiveMode()
            f.setFileType(FTP.BINARY_FILE_TYPE)
            f.setSoTimeout(30_000)
            f.setBufferSize(1 shl 20)
            return f
        } catch (e: Exception) {
            quit(f)
            throw if (e is IOException) e else IOException(e.message ?: "FTP error", e)
        }
    }

    fun sizeOf(f: FTPClient, path: String): Long {
        val s = try { f.getSize(path) } catch (_: Exception) { null }
        s?.trim()?.toLongOrNull()?.let { if (it >= 0) return it }
        return try { f.listFiles(path).firstOrNull()?.size ?: -1L } catch (_: Exception) { -1L }
    }

    /** Opens [path] at [offset]. -> (connection, data stream, file size or -1). Close with [quit]. */
    fun open(host: String, port: Int, path: String, offset: Long): Triple<FTPClient, InputStream, Long> {
        val f = connect(host, port)
        try {
            val size = sizeOf(f, path)
            f.setRestartOffset(offset)
            val s = f.retrieveFileStream(path) ?: throw IOException("FTP: ${f.replyString.trim()}")
            return Triple(f, s, size)
        } catch (e: Exception) {
            quit(f)
            throw if (e is IOException) e else IOException(e.message ?: "FTP error", e)
        }
    }

    fun readBytes(uri: Uri): ByteArray {
        val (h, p, path) = split(uri)
        val (f, s, _) = open(h, p, path, 0)
        try { return s.use { it.readBytes() } } finally { quit(f) }
    }
}

/** Streams an ftp:// file straight into the player. Seeking reconnects at the new position (REST). */
@UnstableApi
class FtpDataSource : BaseDataSource(true) {
    private var client: FTPClient? = null
    private var stream: InputStream? = null
    private var current: Uri? = null
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        current = dataSpec.uri
        transferInitializing(dataSpec)
        val (h, p, path) = Ftp.split(dataSpec.uri)
        val (c, s, size) = Ftp.open(h, p, path, dataSpec.position)
        client = c; stream = s
        remaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            size > 0 -> size - dataSpec.position
            else -> C.LENGTH_UNSET.toLong()
        }
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val want = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), remaining).toInt()
        val n = stream!!.read(buffer, offset, want)
        if (n < 0) return C.RESULT_END_OF_INPUT
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = current

    override fun close() {
        try { stream?.close() } catch (_: Exception) {}
        Ftp.quit(client)
        stream = null; client = null; current = null
        if (opened) { opened = false; transferEnded() }
    }
}

/** Lets MediaMetadataRetriever (durations, poster stills) read from an FTP server. */
internal class FtpMediaSource(uri: Uri) : MediaDataSource() {
    private val host: String
    private val port: Int
    private val path: String
    private val total: Long
    private var client: FTPClient? = null
    private var stream: InputStream? = null
    private var pos = 0L

    init {
        val (h, p, pa) = Ftp.split(uri)
        host = h; port = p; path = pa
        val f = Ftp.connect(h, p)
        total = try { Ftp.sizeOf(f, pa) } finally { Ftp.quit(f) }
        if (total <= 0) throw IOException("Unknown file size")
    }

    private fun drop() {
        try { stream?.close() } catch (_: Exception) {}
        Ftp.quit(client); stream = null; client = null
    }

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= total) return -1
        var s = stream
        if (s != null && position >= pos && position - pos <= 256 * 1024) {
            var skip = position - pos
            val scratch = ByteArray(8192)
            while (skip > 0) {
                val n = s.read(scratch, 0, minOf(skip, scratch.size.toLong()).toInt())
                if (n < 0) return -1
                skip -= n
            }
        } else {
            drop()
            val (f, st, _) = Ftp.open(host, port, path, position)
            client = f; stream = st; s = st
        }
        val n = s!!.read(buffer, offset, minOf(size.toLong(), total - position).toInt())
        if (n < 0) return -1
        pos = position + n
        return n
    }

    override fun getSize(): Long = total

    @Synchronized
    override fun close() = drop()
}