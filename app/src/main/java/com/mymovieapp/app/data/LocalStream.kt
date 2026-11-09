package com.mymovieapp.app.data

import android.media.MediaDataSource
import android.net.Uri
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * A tiny web server that only this app can reach (127.0.0.1). It hands a file from an SMB / FTP share to the
 * player over plain http with seeking, using the share code the app already has and trusts.
 */
object LocalStream {
    private val sources = ConcurrentHashMap<String, String>()
    @Volatile private var server: ServerSocket? = null
    private val served = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var lastNote = "no request yet"
    /** For the loading screen: what the app has handed to the player so far. */
    fun status() = "served ${served.get() / 1_000_000} MB, $lastNote"

    @Synchronized
    private fun start(): ServerSocket {
        server?.let { if (!it.isClosed) return it }
        val s = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = s
        Thread({
            while (!s.isClosed) {
                try {
                    val c = s.accept()
                    Thread { handle(c) }.apply { isDaemon = true }.start()
                } catch (_: Throwable) {}
            }
        }, "LocalStream").apply { isDaemon = true }.start()
        return s
    }

    /** An http://127.0.0.1 address that serves [uri] (smb:// or ftp://, no login in it). */
    fun urlFor(uri: String): String {
        val s = start()
        val token = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
        sources[token] = uri
        val name = Uri.parse(uri).lastPathSegment ?: "video.mp4"
        return "http://127.0.0.1:${s.localPort}/$token/${Uri.encode(name)}"
    }

    private fun open(uri: String): MediaDataSource {
        val u = Uri.parse(uri)
        return if (uri.startsWith("ftp://", true)) FtpMediaSource(u) else SmbMediaSource(u)
    }

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
                val uri = sources[parts[1].trim('/').substringBefore('/')]
                if (uri == null) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush(); return
                }
                lastNote = "opening share…"
                src = open(uri)
                lastNote = "request ${range ?: "full"}"
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
                    out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush(); return
                }
                val partial = m != null
                val head = StringBuilder()
                head.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                head.append("Content-Type: application/octet-stream\r\nAccept-Ranges: bytes\r\n")
                head.append("Content-Length: ${end - start + 1}\r\n")
                if (partial) head.append("Content-Range: bytes $start-$end/$total\r\n")
                head.append("Connection: close\r\n\r\n")
                out.write(head.toString().toByteArray())
                if (!parts[0].equals("HEAD", true)) {
                    val buf = ByteArray(256 * 1024)
                    var pos = start
                    while (pos <= end) {
                        val n = src!!.readAt(pos, buf, 0, minOf(buf.size.toLong(), end - pos + 1).toInt())
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        served.addAndGet(n.toLong())
                        pos += n
                    }
                }
                out.flush()
            }
        } catch (t: Throwable) {
            // a player closing a connection it no longer needs is normal, not an error
            if (t !is java.net.SocketException) lastNote = "error: ${t.javaClass.simpleName} ${t.message}".take(80)
        } finally {
            try { src?.close() } catch (_: Throwable) {}
        }
    }
}
