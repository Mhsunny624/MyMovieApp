package com.mymovieapp.app.data

import android.content.Context
import android.net.ConnectivityManager
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

object NetScan {
    data class Host(val ip: String, val name: String?) {
        val label: String get() = if (name != null) "$name ($ip)" else ip
    }

    private fun toIp(v: Int) = "${(v ushr 24) and 255}.${(v ushr 16) and 255}.${(v ushr 8) and 255}.${v and 255}"

    /** Finds devices on your home network that answer on the SMB port (445). Returns an error message, or null. */
    suspend fun findSmbHosts(ctx: Context, onFound: (Host) -> Unit): String? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = cm.activeNetwork?.let { cm.getLinkProperties(it) } ?: return "Connect to Wi-Fi or Ethernet first."
        val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
            ?: return "No local network address found."
        val me = ByteBuffer.wrap(la.address.address).int
        val prefix = la.prefixLength.coerceIn(24, 30)          // never scan more than one /24
        val net = me and (-1 shl (32 - prefix))
        val count = (1 shl (32 - prefix)) - 2
        val targets = (1..count).map { net + it }.filter { it != me }.map { toIp(it) }

        val gate = Semaphore(48)
        coroutineScope {
            targets.map { ip ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val open = try {
                            Socket().use { it.connect(InetSocketAddress(ip, 445), 600); true }
                        } catch (_: Exception) { false }
                        if (open) {
                            // Looking up the name can hang, so give it a short deadline and detach it.
                            val lookup = CoroutineScope(Dispatchers.IO).async {
                                runCatching { InetAddress.getByName(ip).canonicalHostName }.getOrNull()
                            }
                            val name = withTimeoutOrNull(1500) { lookup.await() }?.takeIf { it != ip && it.isNotBlank() }
                            withContext(Dispatchers.Main) { onFound(Host(ip, name?.substringBefore('.'))) }
                        }
                    }
                }
            }.awaitAll()
        }
        return null
    }

    /** Logs in and returns the names of the folders a server shares (hidden admin shares like C$ are skipped). */
    suspend fun listShares(host: String, user: String, pass: String, domain: String): List<String> =
        withContext(Dispatchers.IO) {
            val client = SMBClient(
                SmbConfig.builder().withSecurityProvider(BCSecurityProvider())
                    .withTimeout(10, TimeUnit.SECONDS).withSoTimeout(15, TimeUnit.SECONDS).build()
            )
            client.use { c ->
                val conn = c.connect(host)
                val auth = if (user.isBlank()) AuthenticationContext.guest()
                else AuthenticationContext(user, pass.toCharArray(), domain)
                val session = conn.authenticate(auth)
                val transport = SMBTransportFactories.SRVSVC.getTransport(session)
                ServerService(transport).shares1
                    .filter { it.type == 0 && !it.netName.endsWith("$") }
                    .map { it.netName }
                    .sorted()
            }
        }
}