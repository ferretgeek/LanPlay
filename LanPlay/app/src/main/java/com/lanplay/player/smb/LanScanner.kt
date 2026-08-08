package com.lanplay.player.smb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

data class DiscoveredHost(
    val address: String,
    val hostName: String?,
    val port: Int = 445,
)

internal fun scanAddresses(prefixes: List<String>): List<String> =
    prefixes.flatMap { prefix -> (1..254).map { "$prefix.$it" } }.distinct()

/**
 * C-01：扫描手机当前所在 /24 网段的 TCP 445。200 路并发、单地址 300ms 超时，
 * 结果流式回调，完整扫描通常在 1 秒左右结束，最坏不超过规格的 3 秒。
 */
@Singleton
class LanScanner @Inject constructor() {
    suspend fun scan(
        onProgress: (
            done: Int,
            total: Int,
            found: List<DiscoveredHost>,
        ) -> Unit = { _, _, _ -> },
    ) =
        withContext(Dispatchers.IO) {
            val prefixes = localPrefixes()
            if (prefixes.isEmpty()) {
                onProgress(0, 0, emptyList())
                return@withContext emptyList<DiscoveredHost>()
            }
            val addresses = scanAddresses(prefixes)
            onProgress(0, addresses.size, emptyList())
            val semaphore = Semaphore(200)
            val found = mutableListOf<DiscoveredHost>()
            var done = 0
            coroutineScope {
                // 结果由单一 collector 排序提交，worker 不会在状态锁内做 DNS 或执行外部回调。
                val results = Channel<DiscoveredHost?>(addresses.size)
                val collector = launch {
                    for (result in results) {
                        if (result != null) found += result
                        done++
                        onProgress(done, addresses.size, found.toList())
                    }
                }
                val workers = addresses.map { address ->
                    async {
                        val open = semaphore.withPermit { canConnect(address, 445, 300) }
                        val host = if (open) {
                            withTimeoutOrNull(DNS_TIMEOUT_MS) {
                                runInterruptible(Dispatchers.IO) {
                                    InetAddress.getByName(address).canonicalHostName
                                        .takeUnless { it == address }
                                }
                            }
                        } else null
                        results.send(if (open) DiscoveredHost(address, host) else null)
                    }
                }
                workers.awaitAll()
                results.close()
                collector.join()
            }
            found.distinctBy { it.address }.sortedBy { it.address }
        }

    private fun localPrefixes(): List<String> =
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress?.substringBeforeLast('.') }
            .distinct()

    private fun canConnect(host: String, port: Int, timeoutMs: Int): Boolean =
        runCatching {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            true
        }.getOrDefault(false)

    private companion object {
        const val DNS_TIMEOUT_MS = 250L
    }
}
