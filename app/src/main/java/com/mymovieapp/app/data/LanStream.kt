package com.mymovieapp.app.data

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * A small web server that other devices on the Wi-Fi can reach (a Cast TV that doesn't have this app).
 * It serves a video straight from this phone, an SMB / FTP share, or a subtitle text, with seeking.
 * Every address carries a random token, so only a link this app handed out can be opened.
 */
object LanStream {
    private class Item(val uri: String?, val text: ByteArray?, val mime: String)
    private val items = ConcurrentHashMap<String, Item>()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var app: Context? = null

    fun init(ctx: Context) { app = ctx.applicationContext }

    /** The phone's address on the Wi-Fi, or null when it has none. */
    fun lanIp(): String? = try {
        val all = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        val addrs = (all.filter { it.name.startsWith("wlan") } + all.filter { !it.name.startsWith("wlan") })
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }
        addrs.firstOrNull()?.hostAddress
    } catch (_: Throwable) { null }

    @Synchronized
    private fun start(): ServerSocket {
        server?.let { if (!it.isClosed) return it }
        val s = ServerSocket(0, 16)
        server = s
        Thread({
            while (!s.isClosed) {
                try {
                    val c = s.accept()
                    Thread { handle(c) }.apply { isDaemon = true }.start()
                } catch (_: Throwable) {}
            }
        }, "LanStream").apply { isDaemon = true }.start()
        return s
    }

    private fun base(): String? {
        val ip = lanIp() ?: return null
        return "http://$ip:${start().localPort}"
    }

    private fun token() = java.util.UUID.randomUUID().toString().replace("-", "").take(14)

    /** An address on the Wi-Fi for a video (smb://, ftp://, content:// or file://), or null with no Wi-Fi. */
    fun urlForMedia(uri: String, name: String): String? {
        val b = base() ?: return null
        val t = token()
        items[t] = Item(uri, null, mimeFor(name))
        return "$b/$t/${Uri.encode(name.ifBlank { "video.mp4" })}"
    }

    /** An address for a piece of text (a WebVTT subtitle). */
    fun urlForText(text: String, mime: String, name: String): String? {
        val b = base() ?: return null
        val t = token()
        items[t] = Item(null, text.toByteArray(Charsets.UTF_8), mime)
        return "$b/$t/${Uri.encode(name)}"
    }

    fun clear() { items.clear() }

    fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase().substringBefore('?')) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "ts", "m2ts", "mts" -> "video/mp2t"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "m3u8" -> "application/x-mpegURL"
        "mpd" -> "application/dash+xml"
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "vtt" -> "text/vtt"
        else -> "video/mp4"
    }

    private fun open(uri: String): MediaDataSource {
        val u = Uri.parse(uri)
        return when {
            uri.startsWith("ftp://", true) -> FtpMediaSource(u)
            uri.startsWith("smb://", true) -> SmbMediaSource(u)
            uri.startsWith("file:", true) -> FileSource(RandomAccessFile(java.io.File(u.path.orEmpty()), "r"))
            else -> {
                val pfd = app!!.contentResolver.openFileDescriptor(u, "r") ?: throw java.io.IOException("can't open")
                ContentSource(pfd)
            }
        }
    }

    private class FileSource(val f: RandomAccessFile) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            synchronized(f) { f.seek(position); return f.read(buffer, offset, size) }
        }
        override fun getSize(): Long = f.length()
        override fun close() { f.close() }
    }

    private class ContentSource(val pfd: android.os.ParcelFileDescriptor) : MediaDataSource() {
        private val ch = java.io.FileInputStream(pfd.fileDescriptor).channel
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
            ch.read(ByteBuffer.wrap(buffer, offset, size), position)
        override fun getSize(): Long = pfd.statSize
        override fun close() { try { ch.close() } catch (_: Throwable) {}; try { pfd.close() } catch (_: Throwable) {} }
    }

    private const val CORS = "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: Range, Content-Type\r\nAccess-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n"

    private fun handle(c: Socket) {
        var src: MediaDataSource? = null
        try {
            c.use { sock ->
                val reader = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val line = reader.readLine() ?: return
                val parts = line.split(" ")
                if (parts.size < 2) return
                var range: String? = null
                while (true) {
                    val h = reader.readLine() ?: break
                    if (h.isEmpty()) break
                    if (h.startsWith("range:", true)) range = h.substring(6).trim()
                }
                val out = BufferedOutputStream(sock.getOutputStream(), 64 * 1024)
                if (parts[0].equals("OPTIONS", true)) {
                    out.write("HTTP/1.1 204 No Content\r\n${CORS}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush(); return
                }
                val item = items[parts[1].trim('/').substringBefore('/')]
                if (item == null) {
                    out.write("HTTP/1.1 404 Not Found\r\n${CORS}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush(); return
                }
                val text = item.text
                if (text != null) {
                    out.write("HTTP/1.1 200 OK\r\n${CORS}Content-Type: ${item.mime}; charset=utf-8\r\nContent-Length: ${text.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    if (!parts[0].equals("HEAD", true)) out.write(text)
                    out.flush(); return
                }
                src = open(item.uri!!)
                val total = src!!.size
                var start = 0L
                var end = total - 1
                val m = range?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
                if (m != null) {
                    val a = m.groupValues[1]; val b = m.groupValues[2]
                    if (a.isEmpty() && b.isNotEmpty()) start = (total - b.toLong()).coerceAtLeast(0)
                    else {
                        if (a.isNotEmpty()) start = a.toLong()
                        if (b.isNotEmpty()) end = minOf(b.toLong(), total - 1)
                    }
                }
                if (start >= total || end < start) {
                    out.write("HTTP/1.1 416 Range Not Satisfiable\r\n${CORS}Content-Range: bytes */$total\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush(); return
                }
                val head = StringBuilder()
                head.append(if (m != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                head.append(CORS)
                head.append("Content-Type: ${item.mime}\r\nAccept-Ranges: bytes\r\n")
                head.append("Content-Length: ${end - start + 1}\r\n")
                if (m != null) head.append("Content-Range: bytes $start-$end/$total\r\n")
                head.append("Connection: close\r\n\r\n")
                out.write(head.toString().toByteArray())
                if (!parts[0].equals("HEAD", true)) {
                    val buf = ByteArray(256 * 1024)
                    var pos = start
                    while (pos <= end) {
                        val n = src!!.readAt(pos, buf, 0, minOf(buf.size.toLong(), end - pos + 1).toInt())
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        pos += n
                    }
                }
                out.flush()
            }
        } catch (_: Throwable) {
            // a TV closing a connection it no longer needs is normal
        } finally {
            try { src?.close() } catch (_: Throwable) {}
        }
    }
}
