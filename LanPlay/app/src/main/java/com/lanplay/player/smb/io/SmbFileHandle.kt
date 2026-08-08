package com.lanplay.player.smb.io

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.lanplay.player.core.log.Metric
import com.lanplay.player.smb.SmbConnectionManager
import com.lanplay.player.smb.SmbErrorCode
import com.lanplay.player.smb.SmbException
import com.lanplay.player.smb.SmbFileRepository
import com.lanplay.player.smb.SmbTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicReference
import com.hierynomus.smbj.share.File as SmbFile

/**
 * 单个 SMB 文件的随机读通道。
 *
 * 播放器规格 §1.2 第 5/13 条：
 *  - 所有偏移一律 [Long]。素材最大 13,086,246,463 字节，用 Int 必在 4 GB 处溢出（风险 R-5）。
 *  - 播放期间保持文件句柄打开，不反复 open/close。
 *
 * 第 12 条：读失败时重建连接并从**断点偏移**继续，指数退避 0.5/1/2/4s 最多 5 次。
 */
class SmbFileHandle private constructor(
    private val connections: SmbConnectionManager,
    private val target: SmbTarget,
    val relativePath: String,
    private val channel: SmbConnectionManager.Channel,
    private val onReconnect: (() -> Unit)?,
) {

    private data class HandleState(val file: SmbFile, val generation: Long)

    private val handleState = AtomicReference<HandleState?>(null)
    private val mutex = Mutex()

    @Volatile
    var size: Long = -1L
        private set

    @Volatile
    var reconnects: Int = 0
        private set

    @Volatile
    private var closed = false

    private suspend fun openLocked() {
        check(!closed) { "文件句柄已经关闭" }
        val smbPath = SmbFileRepository.toSmbPath(relativePath)
        var candidate: SmbFile? = null
        try {
            val leased = connections.withShareLease(target, channel) { share ->
                share.openFile(
                    smbPath,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null,
                )
            }
            val opened = leased.value.also { candidate = it }
            val openedSize = opened.fileInformation.standardInformation.endOfFile
            check(!closed) { "文件句柄已经关闭" }
            handleState.set(HandleState(opened, leased.generation))
            size = openedSize
            candidate = null
        } catch (t: Throwable) {
            runCatching { candidate?.close() }
            if (t is CancellationException) throw t
            val code = connections.classify(t)
            throw SmbException(code, "无法打开这个文件", t)
        }
    }

    /**
     * 从 [offset] 读取，最多 [length] 字节写入 [dst] 的 [dstOffset] 处。
     * 内部循环补齐，直到读满 length 或到达文件末尾。
     *
     * @return 实际读取字节数；0 表示 offset 已达或超过文件末尾
     */
    suspend fun readFully(
        offset: Long,
        dst: ByteArray,
        dstOffset: Int,
        length: Int,
    ): Int = withContext(Dispatchers.IO) {
        require(offset >= 0) { "offset must be >= 0, got $offset" }
        if (closed) return@withContext 0
        if (size in 0..offset) return@withContext 0

        var produced = 0
        var attempt = 0
        val deadlineNs = System.nanoTime() + READ_RECOVERY_DEADLINE_MS * 1_000_000L
        val backoff = longArrayOf(500, 1000, 2000, 4000, 4000)

        while (produced < length) {
            val want = length - produced
            val absolute = offset + produced
            if (size in 0..absolute) break

            var usedState: HandleState? = null
            val n = try {
                // 首次重开句柄也属于可恢复的网络操作。若 currentState() 放在
                // try 外面，安装切换或 Wi-Fi 短抖时的一次 connect timeout 会直接
                // 逃出重试循环，并把预读块永久标成 EOF。
                currentState().also { usedState = it }
                    .file.read(dst, absolute, dstOffset + produced, want)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (closed) return@withContext produced
                if (System.nanoTime() >= deadlineNs) {
                    Metric.error(
                        SmbErrorCode.READ_FAILED, t.message,
                        "path_hash" to relativePath.hashCode().toUInt().toString(16),
                        "offset" to absolute,
                    )
                    throw SmbException(SmbErrorCode.READ_FAILED, "读取中断，无法恢复", t)
                }
                // 断线：作废连接并从断点偏移重开句柄，播放器侧无感（规格 §1.2 第 12 条）
                reconnects++
                onReconnect?.invoke()
                val failedGeneration = usedState?.generation
                    ?: handleState.get()?.generation
                    ?: -1L
                val remainingMs = ((deadlineNs - System.nanoTime()) / 1_000_000L).coerceAtLeast(1L)
                delay(minOf(backoff[minOf(attempt, backoff.lastIndex)], remainingMs))
                attempt++
                mutex.withLock {
                    val current = handleState.get()
                    if (current?.generation == failedGeneration) {
                        handleState.set(null)
                        runCatching { current.file.close() }
                    }
                }
                if (failedGeneration >= 0L) {
                    connections.invalidate(target, channel, failedGeneration)
                }
                Metric.emit(
                    "io_reconnect",
                    "path" to relativePath,
                    "offset" to absolute,
                    "attempt" to attempt,
                )
                continue
            }

            if (n <= 0) break          // EOF
            produced += n
            attempt = 0                // 一次成功即重置退避
        }
        produced
    }

    private suspend fun currentState(): HandleState {
        if (closed) error("文件句柄已经关闭")
        handleState.get()?.let { return it }
        mutex.withLock {
            check(!closed) { "文件句柄已经关闭" }
            handleState.get()?.let { return it }
            openLocked()
            check(!closed) { "文件句柄已经关闭" }
            return checkNotNull(handleState.get())
        }
    }

    suspend fun close() = withContext(Dispatchers.IO + NonCancellable) {
        closed = true
        mutex.withLock {
            runCatching { handleState.getAndSet(null)?.file?.close() }
        }
    }

    companion object {
        private const val READ_RECOVERY_DEADLINE_MS = 60_000L
        suspend fun open(
            connections: SmbConnectionManager,
            target: SmbTarget,
            relativePath: String,
            channel: SmbConnectionManager.Channel = SmbConnectionManager.Channel.PLAYBACK,
            onReconnect: (() -> Unit)? = null,
        ): SmbFileHandle = withContext(Dispatchers.IO) {
            SmbFileHandle(connections, target, relativePath, channel, onReconnect).apply {
                mutex.withLock { openLocked() }
            }
        }
    }
}
