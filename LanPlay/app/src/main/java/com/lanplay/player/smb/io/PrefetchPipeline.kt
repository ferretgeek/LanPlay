package com.lanplay.player.smb.io

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * 前向预读流水线（播放器规格 §1.2 第 6~10 条、§1.3 缓冲区状态机）。
 *
 * 实现选择：用**定长块缓存**表达规格里的环形缓冲区。语义等价而更适合并发流水线——
 * 每个 worker 独占一个块，互不干扰，无需为环形数组的读写指针做跨线程同步。
 *
 *   窗口 = [windowStart, windowStart + windowBlocks) 个块，合计 prefetchMb
 *   窗口内 seek  → 块已在缓存中，直接返回，**零网络请求**
 *   窗口外 seek  → 提升 generation 作废旧块，窗口跳到目标块重启流水线
 *                  （SMB Session 与文件句柄全程不变，不重连不重认证）
 *
 * 入队顺序天然自读指针递增，离播放位置最近的块总在队首，等同规格 §3.3 的「首块优先」。
 * 窗口填满后不再入队，即规格 §3.4 的「预读节流」。
 */
class PrefetchPipeline(
    private val handle: SmbFileHandle,
    private val blockSize: Int,
    private val windowBlocks: Int,
    private val concurrency: Int,
    private val stats: IoStats,
    private val scope: CoroutineScope,
) {

    private companion object {
        /** readBytes 的初值：worker 尚未写完 */
        const val PENDING = -2

        /** 首帧阶段的预读块数，够播放器起步又不占满带宽 */
        const val INITIAL_BLOCKS = 8

        /**
         * 每推进一块放开的预读量。8 块可在播放器字节缓冲停止拉流前铺满 48 MB 窗口；
         * 初始仍只有 8 块，不与容器索引/首帧争抢带宽。
         */
        const val RAMP_STEP_BLOCKS = 8
        const val WORKER_CLOSE_TIMEOUT_MS = 5_000L
    }

    private class Slot(
        val index: Long,
        val data: ByteBuffer,
        val generation: Int,
    ) {
        val ready = CompletableDeferred<Int>()

        /** worker 写完后在锁内赋值，供窗口逻辑无需 getCompleted() 即可判断就绪 */
        @Volatile
        var readBytes: Int = PENDING

        /** 有 read 正在从 data 拷贝时 > 0，此时禁止把数组还进池，否则会读到别的块的数据 */
        val inUse = AtomicInteger(0)
    }

    private val mutex = Mutex()
    private val closeMutex = Mutex()
    private val slots = HashMap<Long, Slot>()
    private val arrayPool = ArrayDeque<ByteBuffer>()
    // 队列容量与单个窗口绑定，跨窗口 seek 先主动 drain，避免 DirectByteBuffer
    // 按“seek 次数 × 窗口大小”无界积压。
    private val pending = Channel<Slot>(maxOf(1, windowBlocks + concurrency))
    private val workers = mutableListOf<Job>()

    private var windowStart = 0L

    /**
     * 当前实际参与预读的块数，从 [INITIAL_BLOCKS] 起随播放推进逐步放开到 windowBlocks。
     *
     * 一上来就铺满 48 MB 会和容器索引的旁路加载抢带宽，把首帧拖慢——而首帧阶段
     * 播放器只需要开头很少的数据。等播放真正开始推进，再把预读量放开去抗网络抖动。
     * 这正是规格 §3.3「首块优先」要的效果。
     */
    private var activeBlocks = minOf(INITIAL_BLOCKS, windowBlocks)

    /** worker 每块都要读一次，用 volatile 免去加锁 */
    @Volatile
    private var generation = 0

    @Volatile
    private var closed = false

    val fileSize: Long get() = handle.size

    fun start() {
        repeat(concurrency) { workers += scope.launch(Dispatchers.IO) { workerLoop() } }
    }

    /**
     * 从 [offset] 读数据到 [dst]。单次调用不跨块，返回实际拷贝字节数，0 表示已到文件末尾。
     * 调用方（本地 HTTP 代理）循环调用直到满足 Range 请求。
     */
    suspend fun read(
        offset: Long,
        dst: ByteArray,
        dstOffset: Int,
        length: Int,
        isValid: () -> Boolean = { true },
    ): Int {
        if (closed || length <= 0) return 0
        val total = fileSize
        if (total in 0..offset) return 0

        val blockIndex = offset / blockSize
        val offsetInBlock = (offset % blockSize).toInt()

        val slot = mutex.withLock {
            // Seek 的新 HTTP Range 接管主窗口后，旧 Range 可能已进入下一次 read。
            // 在窗口锁内再次核对所有权，避免旧流把刚移交的窗口拉回旧位置。
            if (!isValid()) return@withLock null
            reposition(blockIndex)
            scheduleWindow()
            slots[blockIndex]?.also { it.inUse.incrementAndGet() }
                ?: throw IOException("预读块未能加入读取队列")
        } ?: return 0

        try {
            val n = slot.ready.await()
            if (closed) return 0
            if (n <= 0 || n <= offsetInBlock) {
                if (offset < total) throw IOException("SMB 在文件尾之前返回了空预读块")
                return 0
            }
            val toCopy = minOf(n - offsetInBlock, length)
            // 不为每次读取创建 duplicate/asReadOnlyBuffer：Media3 会高频调用这里，
            // 短命 ByteBuffer 视图会在几十秒内把 256 MB Java 堆推到 GC 临界点。
            // 块写入在 ready 完成前已经结束，之后只需串行保护共享 position。
            synchronized(slot.data) {
                slot.data.position(offsetInBlock)
                slot.data.get(dst, dstOffset, toCopy)
            }
            return toCopy
        } finally {
            slot.inUse.decrementAndGet()
        }
    }

    /** 把窗口提前挪到目标位置，供 seek 预热，不必等第一次 read */
    suspend fun prepareAt(offset: Long) {
        if (closed) return
        val blockIndex = offset / blockSize
        mutex.withLock {
            reposition(blockIndex)
            scheduleWindow()
        }
    }

    /** 目标字节是否已经落在可立即读取的主预读块中。 */
    suspend fun isBufferedAt(offset: Long): Boolean {
        if (closed || offset < 0 || (fileSize >= 0 && offset >= fileSize)) return false
        val blockIndex = offset / blockSize
        val offsetInBlock = (offset % blockSize).toInt()
        return mutex.withLock {
            val readyBytes = slots[blockIndex]?.readBytes ?: return@withLock false
            readyBytes != PENDING && readyBytes > offsetInBlock
        }
    }

    suspend fun close() = withContext(NonCancellable) {
        closeMutex.withLock {
            if (closed) return@withLock
            closed = true
            pending.close()
            val waiting = mutex.withLock {
                val snapshot = slots.values.toList()
                slots.clear()
                while (pending.tryReceive().getOrNull() != null) Unit
                arrayPool.clear()
                snapshot
            }
            val closedError = IOException("预读会话已经关闭")
            waiting.forEach { slot ->
                slot.readBytes = -1
                slot.ready.completeExceptionally(closedError)
            }
            // 同步 SMB read 不一定响应协程 cancel，先关闭底层句柄以打断它。
            handle.close()
            workers.forEach { it.cancel() }
            withTimeoutOrNull(WORKER_CLOSE_TIMEOUT_MS) { workers.joinAll() }
            workers.clear()
        }
    }

    // ── 窗口管理（以下三个方法必须持 mutex 调用）──────────────────

    private fun reposition(blockIndex: Long) {
        val inWindow = blockIndex >= windowStart && blockIndex < windowStart + windowBlocks
        if (inWindow) {
            stats.onBlockRequest(hit = slots[blockIndex]?.readBytes.let { it != null && it != PENDING })
            if (blockIndex > windowStart) {
                // 播放推进：丢弃读指针之前的块，窗口整体前移，并逐步放开预读量
                var i = windowStart
                while (i < blockIndex) {
                    slots.remove(i)?.let { discard(it) }
                    i++
                }
                windowStart = blockIndex
                activeBlocks = minOf(windowBlocks, activeBlocks + RAMP_STEP_BLOCKS)
            }
        } else {
            // 窗口外 seek：先回收尚未被 worker 领取的任务，再作废在途块。
            // 队列只允许保留当前窗口，连续拖动不会累积历史窗口的 direct buffer。
            stats.onBlockRequest(hit = false)
            stats.onWindowReset()
            generation++
            while (true) {
                val queued = pending.tryReceive().getOrNull() ?: break
                if (slots[queued.index] === queued) slots.remove(queued.index)
                queued.readBytes = -1
                queued.ready.completeExceptionally(IOException("预读窗口已切换"))
                if (queued.inUse.get() == 0) recycle(queued.data)
            }
            slots.values.forEach { discard(it) }
            slots.clear()
            windowStart = blockIndex
            activeBlocks = minOf(INITIAL_BLOCKS, windowBlocks)
            stats.bufferedBytes = 0
        }
    }

    private fun scheduleWindow() {
        val total = fileSize
        val lastBlock = if (total > 0) (total - 1) / blockSize else Long.MAX_VALUE
        val end = minOf(windowStart + activeBlocks - 1, lastBlock)
        var i = windowStart
        while (i <= end) {
            if (!slots.containsKey(i)) {
                val slot = Slot(i, obtain(), generation)
                slots[i] = slot
                if (pending.trySend(slot).isFailure) {
                    slots.remove(i)
                    recycle(slot.data)
                    throw IOException("预读队列已满，无法安排目标块")
                }
            }
            i++
        }
    }

    /**
     * 只有「已写完」且「当前无人在拷贝」的数组才能还进池。
     * 未写完的块正被 worker 写入，由 worker 发现 generation 失配后自行回收；
     * 有人在拷贝的块直接交给 GC，不进池——宁可少复用一个数组，也不能给出脏数据。
     */
    private fun discard(slot: Slot) {
        if (slot.readBytes != PENDING && slot.inUse.get() == 0) recycle(slot.data)
    }

    /**
     * 48 MB 预读窗口必须放在堆外。目标手机的 Java 堆只有 256 MB，若这里使用 ByteArray，
     * 再叠加 Media3 缓冲、容器索引和 smbj 在途数据，长播时实测会冲到 255.8 MB。
     * DirectByteBuffer 仍计入进程总内存，但不会挤爆收包线程所在的 Java 堆。
     */
    private fun obtain(): ByteBuffer =
        arrayPool.removeLastOrNull()?.also { it.clear() } ?: ByteBuffer.allocateDirect(blockSize)

    private fun recycle(array: ByteBuffer) {
        if (closed) return
        array.clear()
        if (arrayPool.size < windowBlocks + concurrency) arrayPool.addLast(array)
    }

    /** 自读指针起**连续**已就绪的字节数。中间有洞就截断——这才是真实可播水位。 */
    private fun countBufferedLocked(): Long {
        var bytes = 0L
        var i = windowStart
        while (i < windowStart + windowBlocks) {
            val slot = slots[i] ?: break
            val n = slot.readBytes
            if (n == PENDING || n <= 0) break
            bytes += n
            i++
        }
        return bytes
    }

    // ── worker ────────────────────────────────────────────────

    private suspend fun workerLoop() {
        // smbj 的 API 需要 ByteArray；每个 worker 只保留一个中转块，再复制进堆外窗口。
        val transfer = ByteArray(blockSize)
        for (slot in pending) {
            if (closed) {
                slot.readBytes = -1
                slot.ready.completeExceptionally(IOException("预读会话已经关闭"))
                continue
            }
            // generation 未变化时，窗口内 forward seek 也可能已经移除读指针之前
            // 的排队块；网络读取前同时核对 Slot 归属，避免为旧位置浪费 SMB 带宽。
            val obsolete = mutex.withLock {
                slot.generation != generation || slots[slot.index] !== slot
            }
            if (obsolete) {
                slot.readBytes = -1
                slot.ready.completeExceptionally(IOException("预读窗口已切换"))
                mutex.withLock { recycle(slot.data) }
                continue
            }

            stats.onInflightStart()
            var failure: Throwable? = null
            val n = try {
                handle.readFully(slot.index * blockSize, transfer, 0, blockSize).also { read ->
                    if (read > 0) {
                        slot.data.clear()
                        slot.data.put(transfer, 0, read)
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                failure = t
                -1
            } finally {
                stats.onInflightEnd()
            }

            if (n > 0) stats.onBytesRead(n)

            val stale = mutex.withLock {
                slot.readBytes = n
                if (n <= 0 && slots[slot.index] === slot) {
                    // 失败块不能留在窗口里。否则下一条 HTTP Range 仍会命中这个
                    // readBytes=-1 的 Slot，永远不会重新入队，瞬时网络错误就会
                    // 变成永久黑屏。移除后下一次请求会自动补建并重试。
                    slots.remove(slot.index)
                    stats.bufferedBytes = countBufferedLocked()
                    true
                } else if (slots[slot.index] !== slot || slot.generation != generation) {
                    true
                } else {
                    stats.bufferedBytes = countBufferedLocked()
                    false
                }
            }
            failure?.let { slot.ready.completeExceptionally(it) } ?: slot.ready.complete(n)
            // 失败块可能仍有读取者持有，交给 DirectByteBuffer Cleaner 回收，避免
            // 与读取线程竞态后把同一块放回池两次。正常的过期块仍可立即复用。
            if (stale && n > 0 && slot.inUse.get() == 0) mutex.withLock { recycle(slot.data) }
        }
    }
}
