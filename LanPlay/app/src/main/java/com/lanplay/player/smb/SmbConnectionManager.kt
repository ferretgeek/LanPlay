package com.lanplay.player.smb

import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.lanplay.player.core.log.Metric
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.net.SocketFactory
import javax.inject.Singleton

/**
 * 播放器规格 §1.2 第 11/12 条：全局 Connection → Session → DiskShare 复用，
 * 断开自动重建（指数退避 0.5/1/2/4s，最多 5 次），seek 与重连都不重新认证。
 *
 * 规格 §1.2 第 14 条要求元数据探测走独立低优先级通道，因此这里按 [Channel] 维护
 * 两套互不影响的连接：PLAYBACK 供播放读取，AUX 供列目录/探测/缩略图。
 */
@Singleton
class SmbConnectionManager @Inject constructor() {

    enum class Channel { PLAYBACK, AUX }

    private data class HolderKey(val channel: Channel, val identity: String)

    private class ConnectTimeoutSocketFactory(
        private val timeoutMs: Int,
    ) : SocketFactory() {
        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: java.net.SocketAddress?) {
                super.connect(endpoint, timeoutMs)
            }
        }

        override fun createSocket(host: String, port: Int): Socket =
            Socket().apply { connect(InetSocketAddress(host, port), timeoutMs) }

        override fun createSocket(host: InetAddress, port: Int): Socket =
            Socket().apply { connect(InetSocketAddress(host, port), timeoutMs) }

        override fun createSocket(
            host: String,
            port: Int,
            localHost: InetAddress,
            localPort: Int,
        ): Socket = Socket().apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(host, port), timeoutMs)
        }

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int,
        ): Socket = Socket().apply {
            bind(InetSocketAddress(localAddress, localPort))
            connect(InetSocketAddress(address, port), timeoutMs)
        }
    }

    private class Holder {
        var client: SMBClient? = null
        var connection: Connection? = null
        var session: Session? = null
        var share: DiskShare? = null
        var identity: String? = null
        var generation: Long = 0L
        @Volatile var retired: Boolean = false
        val mutex = Mutex()
    }

    internal data class ShareLease(val share: DiskShare, val generation: Long)
    internal data class LeasedResult<T>(val value: T, val generation: Long)

    private val holders = ConcurrentHashMap<HolderKey, Holder>()
    private val holdersMutex = Mutex()

    @Volatile
    var reconnectCount: Int = 0
        private set

    @Volatile
    var negotiatedDialect: String = "-"
        private set

    /**
     * 取得可用的 DiskShare。已有连接且身份未变则直接复用；断线则重建。
     * 调用方无需关心连接状态。
     */
    suspend fun share(target: SmbTarget, channel: Channel = Channel.PLAYBACK): DiskShare =
        lease(target, channel).share

    internal suspend fun lease(
        target: SmbTarget,
        channel: Channel = Channel.PLAYBACK,
    ): ShareLease {
        target.requireValid()
        val key = HolderKey(channel, target.identity)
        while (true) {
            val h = holdersMutex.withLock {
                holders.computeIfAbsent(key) { Holder() }
            }
            val leased = h.mutex.withLock {
                if (h.retired) {
                    null
                } else {
                    val existing = h.share
                    if (existing != null && h.identity == target.identity && isAlive(h)) {
                        ShareLease(existing, h.generation)
                    } else {
                        closeLocked(h)
                        ShareLease(connectWithRetry(target, h, channel), h.generation)
                    }
                }
            }
            if (leased != null) return leased
            holders.remove(key, h)
        }
    }

    /** 只作废调用方实际使用的连接代际，迟到错误不能关闭后来建立的新连接。 */
    internal suspend fun invalidate(
        target: SmbTarget,
        channel: Channel = Channel.PLAYBACK,
        expectedGeneration: Long? = null,
    ) {
        val h = holders[HolderKey(channel, target.identity)] ?: return
        h.mutex.withLock {
            if (expectedGeneration == null || h.generation == expectedGeneration) closeLocked(h)
        }
    }

    /**
     * 所有 SMB 操作的统一入口：失败一次就作废连接重建并重试。
     *
     * 必要性在于 share 句柄的失效比连接断开更隐蔽——服务端可能只回收了 tree connect，
     * TCP 连接仍然活着，此时任何操作都会撞上 "DiskShare has already been closed"。
     * 与其在每个调用点判断，不如在这里统一兜底。
     */
    suspend fun <T> withShare(
        target: SmbTarget,
        channel: Channel = Channel.PLAYBACK,
        block: suspend (DiskShare) -> T,
    ): T = withShareLease(target, channel, block).value

    internal suspend fun <T> withShareLease(
        target: SmbTarget,
        channel: Channel = Channel.PLAYBACK,
        block: suspend (DiskShare) -> T,
    ): LeasedResult<T> {
        var lastError: Throwable? = null
        repeat(2) { attempt ->
            val leased = lease(target, channel)
            try {
                return LeasedResult(block(leased.share), leased.generation)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                lastError = t
                if (attempt == 1 || t is SmbException) throw t
                invalidate(target, channel, leased.generation)
            }
        }
        throw lastError ?: SmbException(SmbErrorCode.CONNECT_FAILED, "连接失败")
    }

    suspend fun closeAll() {
        val closing = holdersMutex.withLock {
            holders.values.toList().also { snapshot ->
                snapshot.forEach { it.retired = true }
                holders.clear()
            }
        }
        closing.forEach { h -> h.mutex.withLock { closeLocked(h) } }
    }

    /**
     * 只关闭指定服务器身份的连接。保存设置时仅作废 AUX，避免配置页操作打断
     * PLAYBACK 正在读取的媒体；删除服务器时可不传 channel 关闭该身份的两条通道。
     */
    suspend fun closeIdentity(identity: String, channel: Channel? = null) {
        val closing = holdersMutex.withLock {
            holders.entries
                .filter { (key, _) -> key.identity == identity && (channel == null || key.channel == channel) }
                .map { (key, holder) ->
                    holder.retired = true
                    holders.remove(key, holder)
                    holder
                }
        }
        closing.distinct().forEach { holder ->
            holder.mutex.withLock { closeLocked(holder) }
        }
    }

    /**
     * 连接活着不等于 share 可用：服务端可能单独回收 tree connect 而保持 TCP 连接。
     * 两者都要查，否则会拿到一个已关闭的 DiskShare。
     */
    private fun isAlive(h: Holder): Boolean =
        try {
            h.connection?.isConnected == true && h.share?.isConnected == true
        } catch (_: Throwable) {
            false
        }

    private suspend fun connectWithRetry(
        target: SmbTarget,
        h: Holder,
        channel: Channel,
    ): DiskShare = withContext(Dispatchers.IO) {
        // withShare 还会对失效 tree connect 重试一次；连接层只保留一次退避，
        // 避免与文件读取恢复预算相乘成数十次握手。
        val backoffMs = longArrayOf(500)
        var lastError: Throwable? = null

        for (attempt in 0..backoffMs.size) {
            if (attempt > 0) {
                reconnectCount++
                delay(backoffMs[attempt - 1])
            }
            try {
                val started = System.nanoTime()
                val share = doConnect(target, h, channel)
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                Metric.emit(
                    "smb",
                    "dialect" to negotiatedDialect,
                    "connect_ms" to elapsedMs,
                    "channel" to channel.name,
                    "attempt" to attempt,
                    "reconnect" to reconnectCount,
                )
                return@withContext share
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                lastError = t
                closeLocked(h)
                // 认证失败、共享不存在等确定性配置错误，重试只会重复泄漏时间。
                if (classify(t) in NON_RETRYABLE_CONNECT_ERRORS) break
            }
        }

        val code = classify(lastError)
        Metric.error(
            code,
            "SMB 连接失败（$code）",
            "endpoint_hash" to target.identity.hashCode().toUInt().toString(16),
        )
        throw SmbException(code, describe(code, target), lastError)
    }

    private fun doConnect(target: SmbTarget, h: Holder, channel: Channel): DiskShare {
        val config = SmbConfig.builder()
            .withSocketFactory(ConnectTimeoutSocketFactory(CONNECT_TIMEOUT_MS))
            .withDialects(
                SMB2Dialect.SMB_3_1_1,
                SMB2Dialect.SMB_3_0_2,
                SMB2Dialect.SMB_3_0,
                SMB2Dialect.SMB_2_1,
            )
            // 默认要求消息签名，防止同一局域网中的响应篡改和 NTLM relay。
            // 数据加密仍由服务端能力决定，后续可按服务器提供显式兼容档位。
            .withSigningRequired(true)
            .withEncryptData(false)
            .withMultiProtocolNegotiate(true)
            .withDfsEnabled(false)
            // 读缓冲要能容纳最大读块（2MB 档），实际上限再与服务端 MaxReadSize 取小
            .withReadBufferSize(MAX_READ_BUFFER)
            .withWriteBufferSize(MAX_READ_BUFFER)
            .withTransactBufferSize(MAX_READ_BUFFER)
            // soTimeout 必须保持 smbj 的默认 0（无限）。设成有限值会让预读窗口填满、
            // SMB 链路空闲期间的 socket read 超时，PacketReader 据此判定连接出错并关闭——
            // 表现为播放中途「DiskShare has already been closed」。
            .withTimeout(TRANSACT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()

        var candidateClient: SMBClient? = null
        var candidateConnection: Connection? = null
        var candidateSession: Session? = null
        var candidateShare: DiskShare? = null
        var transferred = false
        try {
            val client = SMBClient(config).also { candidateClient = it }
            val connection = client.connect(target.host, target.port)
                .also { candidateConnection = it }
            val session = connection.authenticate(buildAuth(target))
                .also { candidateSession = it }
            val share = (session.connectShare(target.share) as? DiskShare)
                ?.also { candidateShare = it }
                ?: throw SmbException(
                    SmbErrorCode.SHARE_NOT_FOUND,
                    "「${target.share}」不是可用的文件共享",
                )

            // 全部成功后才把所有权一次性交给 Holder。此前任何异常都由 finally
            // 逆序关闭局部候选，避免认证/TreeConnect 失败泄漏 socket 和线程。
            h.client = client
            h.connection = connection
            h.session = session
            h.share = share
            h.identity = target.identity
            h.generation++
            transferred = true
            // 两条通道都要记：列目录走 AUX，若只在 PLAYBACK 记录，未播放时方言就查不到
            negotiatedDialect = runCatching {
                connection.connectionContext.negotiatedProtocol.dialect.name
            }.getOrDefault(negotiatedDialect)
            return share
        } finally {
            if (!transferred) {
                runCatching { candidateShare?.close() }
                runCatching { candidateSession?.close() }
                runCatching { candidateConnection?.close() }
                runCatching { candidateClient?.close() }
            }
        }
    }

    private fun buildAuth(target: SmbTarget): AuthenticationContext = when (target.authMode) {
        AuthMode.ANONYMOUS -> AuthenticationContext.anonymous()
        AuthMode.GUEST -> AuthenticationContext.guest()
        // 空密码是合法情形：部分家庭共享允许访客或空密码账户登录。
        AuthMode.ACCOUNT -> AuthenticationContext(
            target.username,
            target.password.toCharArray(),
            target.domain,
        )
    }

    private fun closeLocked(h: Holder) {
        runCatching { h.share?.close() }
        runCatching { h.session?.close() }
        runCatching { h.connection?.close() }
        runCatching { h.client?.close() }
        h.share = null
        h.session = null
        h.connection = null
        h.client = null
        h.identity = null
    }

    internal fun classify(t: Throwable?): String {
        var cur = t
        while (cur != null) {
            if (cur is SmbException) return cur.code
            if (cur is SMBApiException) {
                val name = cur.status.name
                return when {
                    name.contains("LOGON_FAILURE") ||
                        name.contains("ACCOUNT_") ||
                        name.contains("PASSWORD") ||
                        name.contains("TRUST") -> SmbErrorCode.AUTH_FAILED
                    name.contains("BAD_NETWORK_NAME") ||
                        name.contains("NETWORK_NAME_DELETED") -> SmbErrorCode.SHARE_NOT_FOUND
                    name.contains("ACCESS_DENIED") -> SmbErrorCode.ACCESS_DENIED
                    name.contains("OBJECT_NAME_NOT_FOUND") ||
                        name.contains("OBJECT_PATH_NOT_FOUND") -> SmbErrorCode.FILE_NOT_FOUND
                    else -> SmbErrorCode.CONNECT_FAILED
                }
            }
            cur = cur.cause
        }
        return SmbErrorCode.CONNECT_FAILED
    }

    /** 中文、具体、可行动（设计系统 §8.3），不暴露异常类名与堆栈 */
    private fun describe(code: String, target: SmbTarget): String = when (code) {
        SmbErrorCode.AUTH_FAILED -> "用户名或密码不正确"
        SmbErrorCode.SHARE_NOT_FOUND -> "找不到共享「${target.share}」，名称可能已更改"
        SmbErrorCode.ACCESS_DENIED -> "没有访问这个共享的权限"
        else -> "无法连接到 ${target.host}，检查电脑是否开机、是否在同一网络"
    }

    private companion object {
        const val MAX_READ_BUFFER = 2 * 1024 * 1024
        const val CONNECT_TIMEOUT_MS = 10_000

        /** 单个 SMB 请求等待响应的上限，不是连接超时 */
        const val TRANSACT_TIMEOUT_SEC = 30L
        val NON_RETRYABLE_CONNECT_ERRORS = setOf(
            SmbErrorCode.AUTH_FAILED,
            SmbErrorCode.SHARE_NOT_FOUND,
            SmbErrorCode.ACCESS_DENIED,
            SmbErrorCode.FILE_NOT_FOUND,
        )
    }
}
