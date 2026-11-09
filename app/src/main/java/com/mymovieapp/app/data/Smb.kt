package com.mymovieapp.app.data

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import java.util.ArrayDeque
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.io.IOException
import java.security.KeyStore
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SmbCreds(val host: String, val user: String, val domain: String, val pass: String)

/** Encrypts saved share passwords with a key that never leaves the Android Keystore. */
object Secrets {
    private const val ALIAS = "library_smb"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    fun decrypt(enc: String): String {
        val b = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12))
        return String(c.doFinal(b, 12, b.size - 12))
    }
}

/** Connection pool + helpers for smb://host/share/path URIs. Paths inside a share use backslashes. */
object Smb {
    private val client by lazy {
        SMBClient(
            SmbConfig.builder()
                .withSecurityProvider(BCSecurityProvider())
                .withMultiProtocolNegotiate(true)
                .withSocketFactory(object : javax.net.SocketFactory() {
                    private fun tune(s: java.net.Socket) = s.apply { receiveBufferSize = 4 shl 20; tcpNoDelay = true }
                    override fun createSocket() = tune(java.net.Socket())
                    override fun createSocket(host: String?, port: Int) = tune(java.net.Socket(host, port))
                    override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int) =
                        tune(java.net.Socket(host, port, localHost, localPort))
                    override fun createSocket(host: java.net.InetAddress?, port: Int) = tune(java.net.Socket(host, port))
                    override fun createSocket(address: java.net.InetAddress?, port: Int, localAddress: java.net.InetAddress?, localPort: Int) =
                        tune(java.net.Socket(address, port, localAddress, localPort))
                })
                .withReadBufferSize(1 shl 20)
                .withTransactBufferSize(1 shl 20)
                .withTimeout(30, TimeUnit.SECONDS)
                .withSoTimeout(60, TimeUnit.SECONDS)
                .build()
        )
    }
    private val creds = ConcurrentHashMap<String, SmbCreds>()
    private val shares = HashMap<String, DiskShare>()

    fun put(c: SmbCreds) { creds[c.host.lowercase()] = c }
    fun credsFor(host: String): SmbCreds? = creds[host.lowercase()]

    @Synchronized
    fun share(host: String, name: String): DiskShare {
        val c = credsFor(host) ?: throw IOException("No login saved for $host")
        val key = "${host.lowercase()}|$name"
        shares[key]?.let { if (it.isConnected) return it }
        try { shares.remove(key)?.close() } catch (_: Exception) {}
        val conn = client.connect(host)
        try {
            val auth = if (c.user.isBlank()) AuthenticationContext.guest()
            else AuthenticationContext(c.user, c.pass.toCharArray(), c.domain)
            val session = conn.authenticate(auth)
            conn.negotiatedProtocol.let {
                android.util.Log.d("SmbSpeed", "connected: dialect=${it.dialect} maxRead=${it.maxReadSize} maxTransact=${it.maxTransactSize}")
            }
            val s = session.connectShare(name) as? DiskShare ?: throw IOException("$name is not a file share")
            shares[key] = s
            return s
        } catch (t: Throwable) {
            // a failed login must not leave a half-open connection that the next try would reuse
            try { conn.close(true) } catch (_: Exception) {}
            throw t
        }
    }

    @Synchronized
    fun invalidate(host: String, name: String) {
        shares.remove("${host.lowercase()}|$name")?.let { try { it.close() } catch (_: Exception) {} }
    }

    fun uri(host: String, share: String, rel: String): Uri {
        val b = Uri.Builder().scheme("smb").authority(host).appendPath(share)
        rel.split('\\').filter { it.isNotEmpty() }.forEach { b.appendPath(it) }
        return b.build()
    }

    /** -> (host, share, path inside share with backslashes) */
    fun split(uri: Uri): Triple<String, String, String> {
        val seg = uri.pathSegments
        return Triple(uri.host ?: "", seg.firstOrNull() ?: "", seg.drop(1).joinToString("\\"))
    }
}
object SmbStats {
    private val bytes = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var since = System.nanoTime()

    fun add(n: Int) {
        val total = bytes.addAndGet(n.toLong())
        val now = System.nanoTime()
        val dt = (now - since) / 1e9
        if (dt >= 2.0) {
            synchronized(this) {
                if ((System.nanoTime() - since) / 1e9 >= 2.0) {
                    android.util.Log.d("SmbSpeed", "%.1f MB/s".format(bytes.getAndSet(0) / 1_048_576.0 / ((System.nanoTime() - since) / 1e9)))
                    since = System.nanoTime()
                }
            }
        }
    }
}

@UnstableApi
class SmbDataSource : BaseDataSource(true) {
    private companion object {
        const val BLOCK = 1 shl 20   // 1 MB per request
        const val AHEAD = 12          // 12 requests in flight at once
        val pool: ExecutorService = Executors.newFixedThreadPool(AHEAD) { r ->
            Thread(r, "smb-read").apply { isDaemon = true }
        }
    }

    private var file: com.hierynomus.smbj.share.File? = null
    private var current: Uri? = null
    private var start = 0L
    private var end = 0L
    private var nextBlock = 0
    private val pending = ArrayDeque<Future<ByteArray>>()
    private var cur: ByteArray? = null
    private var curPos = 0
    private var opened = false

    private fun openFile(h: String, s: String, rel: String): com.hierynomus.smbj.share.File =
        Smb.share(h, s).openFile(
            rel, EnumSet.of(AccessMask.GENERIC_READ), null,
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null
        )

    override fun open(dataSpec: DataSpec): Long {
        current = dataSpec.uri
        transferInitializing(dataSpec)
        val (h, s, rel) = Smb.split(dataSpec.uri)
        val remaining: Long
        try {
            val f = try { openFile(h, s, rel) } catch (e: Exception) { Smb.invalidate(h, s); openFile(h, s, rel) }
            file = f
            val size = f.fileInformation.standardInformation.endOfFile
            start = dataSpec.position
            remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - start
            if (remaining < 0) throw IOException("Position out of range")
            end = start + remaining
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.message ?: "SMB error", e)
        }
        nextBlock = 0
        cur = null; curPos = 0
        pending.clear()
        opened = true
        transferStarted(dataSpec)
        fill()
        return remaining
    }

    /** Keeps up to AHEAD block reads running in parallel. */
    private fun fill() {
        val f = file ?: return
        while (pending.size < AHEAD) {
            val off = start + nextBlock.toLong() * BLOCK
            if (off >= end) return
            val len = minOf(BLOCK.toLong(), end - off).toInt()
            nextBlock++
            pending.add(pool.submit(Callable {
                val b = ByteArray(len)
                var got = 0
                while (got < len) {
                    val n = f.read(b, off + got, got, len - got)
                    if (n <= 0) break
                    got += n
                }
                SmbStats.add(got)
                if (got == len) b else b.copyOf(got)
            }))
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var block = cur
        if (block == null || curPos >= block.size) {
            fill()
            val fut = pending.pollFirst() ?: return C.RESULT_END_OF_INPUT
            block = try {
                fut.get()
            } catch (e: Exception) {
                throw IOException(e.cause?.message ?: e.message ?: "SMB read failed", e)
            }
            if (block.isEmpty()) return C.RESULT_END_OF_INPUT
            cur = block; curPos = 0
            fill()
        }
        val c = minOf(length, block.size - curPos)
        System.arraycopy(block, curPos, buffer, offset, c)
        curPos += c
        bytesTransferred(c)
        return c
    }

    override fun getUri(): Uri? = current

    override fun close() {
        pending.forEach { it.cancel(false) }
        pending.clear()
        cur = null
        try { file?.close() } catch (_: Exception) {} finally {
            file = null
            current = null
            if (opened) { opened = false; transferEnded() }
        }
    }
}

@UnstableApi
fun appDataSourceFactory(ctx: Context): DataSource.Factory =
    DefaultDataSource.Factory(
        ctx,
        DataSource.Factory {
            SchemeDataSource(
                DefaultHttpDataSource.Factory()
                    .setUserAgent(StreamHeaders.userAgent)
                    .setDefaultRequestProperties(StreamHeaders.props)
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
                    .createDataSource(),
                SmbDataSource(),
                FtpDataSource()
            )
        }
    )

/** Sends smb:// to [SmbDataSource] and everything else (http/https) to the normal HTTP source. */
@UnstableApi
class SchemeDataSource(
    private val http: DataSource,
    private val smb: DataSource,
    private val ftp: DataSource? = null
) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(l: TransferListener) { http.addTransferListener(l); smb.addTransferListener(l); ftp?.addTransferListener(l) }

    override fun open(dataSpec: DataSpec): Long {
        val a = when (dataSpec.uri.scheme) {
            "smb" -> smb
            "ftp" -> ftp ?: throw IOException("FTP isn't supported here")
            else -> http
        }
        active = a
        return a.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active!!.read(buffer, offset, length)
    override fun getUri(): Uri? = active?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()
    override fun close() { try { active?.close() } finally { active = null } }
}
