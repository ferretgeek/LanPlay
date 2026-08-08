package com.lanplay.player.smb.proxy

import com.lanplay.player.core.log.Metric
import com.lanplay.player.data.prefs.IoSettings
import com.lanplay.player.smb.SmbConnectionManager
import com.lanplay.player.smb.SmbFileRepository
import com.lanplay.player.smb.SmbTarget
import com.lanplay.player.smb.io.IoStats
import com.lanplay.player.smb.io.PrefetchPipeline
import com.lanplay.player.smb.io.SmbFileHandle
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore as JavaSemaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地 HTTP 代理（播放器规格 §1.1、§1.2 第 1~4 条）。
 *
 * 存在的理由是要**同时喂两个内核**：Media3 走 DataSource、libVLC 走自己的 IO 回调，
 * 在 IO 层统一成 HTTP + Range 之后，切换内核不需要动 IO 层一行代码。
 *
 * 安全（风险 R-9）：只监听 127.0.0.1、端口由系统分配、URL 带一次性 token、退出即关闭。
 * 明文回环由 network_security_config 单独放行（风险 R-7）。
 */
@Singleton
class LocalMediaProxy @Inject constructor(
    private val connections: SmbConnectionManager,
) {

    class Session(
        val token: String,
        val relativePath: String,
        val channel: SmbConnectionManager.Channel,
        val handle: SmbFileHandle,
        val pipeline: PrefetchPipeline,
        val stats: IoStats,
        val scope: CoroutineScope,
        val mimeType: String,
        val concurrency: Int,
        private val blockSize: Int,
        private val target: SmbTarget,
        private val connections: SmbConnectionManager,
        preloadedHead: ByteArray?,
        private val bypassBudget: JavaSemaphore,
    ) {
        private val preloadedHeadRef = AtomicReference(preloadedHead)
        /** 仅用于统计本会话服务过多少个 Range 请求 */
        val requestCount = AtomicLong(0)

        class WindowReader(
            val pipeline: PrefetchPipeline,
            val handle: SmbFileHandle?,
            val scope: CoroutineScope?,
            val primary: Boolean,
            val active: AtomicBoolean = AtomicBoolean(true),
        )

        private val windowReaderMutex = Mutex()
        private var primaryWindowOwner = 0L
        private var primaryWindowReader: WindowReader? = null
        private val auxiliaryWindowReaders = mutableMapOf<Long, WindowReader>()

        /**
         * 第一条长流使用完整主窗口；同时出现的额外长流（通常是超 40 MiB 的容器索引）
         * 使用独立 4 MiB 窗口和文件句柄，不能再把主播放窗口拉到文件另一端。
         */
        suspend fun acquireWindowReader(sequence: Long, start: Long): WindowReader =
            windowReaderMutex.withLock {
                if (primaryWindowOwner == 0L || primaryWindowOwner == sequence) {
                    primaryWindowOwner = sequence
                    pipeline.prepareAt(start)
                    return@withLock WindowReader(pipeline, null, null, primary = true).also {
                        primaryWindowReader = it
                    }
                }
                auxiliaryWindowReaders[sequence]?.let { return@withLock it }
                // Media3 Seek 会先打开目标 Range，随后才关闭旧 Range。目标块已在
                // 48MB 主窗口时直接移交所有权，旧流下一次 read 会安全返回 EOF；
                // 文件末尾的大索引不在主窗口，仍走下方独立辅助窗口，不会来回拉扯。
                if (pipeline.isBufferedAt(start)) {
                    val previousOwner = primaryWindowOwner
                    primaryWindowReader?.active?.set(false)
                    primaryWindowOwner = sequence
                    return@withLock WindowReader(pipeline, null, null, primary = true).also {
                        primaryWindowReader = it
                        Metric.emit(
                            "range_window_handoff",
                            "from" to previousOwner,
                            "to" to sequence,
                            "start" to start,
                        )
                    }
                }
                check(auxiliaryWindowReaders.size < MAX_AUXILIARY_WINDOW_READERS) {
                    "并行长 Range 过多"
                }
                // 这条 Range 没有复用主预读窗口，实际发生了窗口外读取。同步记到
                // 会话主指标中，PlaybackController 才能正确区分窗口内/外 Seek；
                // 只记在 auxiliaryStats 会让所有辅助读取都被误报成缓存命中。
                stats.onWindowReset()
                val auxiliaryStats = IoStats()
                val auxiliaryHandle = SmbFileHandle.open(
                    connections,
                    target,
                    relativePath,
                    channel,
                    stats::onReconnect,
                )
                val auxiliaryScope = CoroutineScope(SupervisorJob())
                var candidatePipeline: PrefetchPipeline? = null
                try {
                    val blocks = maxOf(1, LIGHTWEIGHT_WINDOW_BYTES / blockSize)
                    val auxiliaryPipeline = PrefetchPipeline(
                        handle = auxiliaryHandle,
                        blockSize = blockSize,
                        windowBlocks = blocks,
                        concurrency = minOf(concurrency, LIGHTWEIGHT_MAX_CONCURRENCY),
                        stats = auxiliaryStats,
                        scope = auxiliaryScope,
                    ).also {
                        candidatePipeline = it
                        it.start()
                    }
                    auxiliaryPipeline.prepareAt(start)
                    WindowReader(
                        auxiliaryPipeline,
                        auxiliaryHandle,
                        auxiliaryScope,
                        primary = false,
                    ).also { auxiliaryWindowReaders[sequence] = it }
                } catch (t: Throwable) {
                    runCatching { candidatePipeline?.close() }
                    if (candidatePipeline == null) runCatching { auxiliaryHandle.close() }
                    auxiliaryScope.cancel()
                    throw t
                }
            }

        suspend fun releaseWindowReader(sequence: Long, reader: WindowReader) =
            withContext(NonCancellable) {
                val auxiliary = windowReaderMutex.withLock {
                    reader.active.set(false)
                    if (reader.primary) {
                        if (primaryWindowOwner == sequence) {
                            primaryWindowOwner = 0L
                            primaryWindowReader = null
                        }
                        null
                    } else {
                        auxiliaryWindowReaders.remove(sequence)
                    }
                }
                if (auxiliary != null) {
                    var failure: Throwable? = null
                    runCatching { auxiliary.pipeline.close() }.onFailure { failure = it }
                    runCatching { auxiliary.handle?.close() }.onFailure {
                        failure?.addSuppressed(it) ?: run { failure = it }
                    }
                    auxiliary.scope?.cancel()
                    failure?.let { throw it }
                }
            }

        suspend fun closeAuxiliaryWindowReaders() = withContext(NonCancellable) {
            val readers = windowReaderMutex.withLock {
                primaryWindowOwner = 0L
                primaryWindowReader?.active?.set(false)
                primaryWindowReader = null
                auxiliaryWindowReaders.values.toList().also { auxiliaryWindowReaders.clear() }
            }
            readers.forEach { reader ->
                runCatching { reader.pipeline.close() }
                runCatching { reader.handle?.close() }
                reader.scope?.cancel()
            }
        }

        /**
         * 旁路读：绕过预读流水线直接向 SMB 要数据，不触碰预读窗口。
         *
         * 长度已知且不大（容器索引），所以一次并发把整段拉进内存最快——
         * 串行读 8.3 MB 的 moov 实测要 6 秒，并发打满带宽后不到 1 秒。
         */
        data class BypassBuffer(val data: ByteArray, val permits: Int)

        suspend fun loadBypassRange(offset: Long, length: Int): BypassBuffer {
            val permits = ((length.toLong() + BUDGET_UNIT_BYTES - 1) / BUDGET_UNIT_BYTES)
                .toInt().coerceAtLeast(1)
            runInterruptible(Dispatchers.IO) { bypassBudget.acquire(permits) }
            try {
                return coroutineScope {
                    val data = ByteArray(length)
                    // 比播放通道用更高的并发：这段读取在首帧关键路径上，且是一次性的。
                    val parts = maxOf(concurrency, BYPASS_PARTS)
                        .coerceAtMost(maxOf(1, length / (512 * 1024)))
                        .coerceAtLeast(1)
                    val per = (length + parts - 1) / parts
                    (0 until parts).map { i ->
                        async(Dispatchers.IO) {
                            val off = i * per
                            val take = minOf(per, length - off)
                            if (take > 0) {
                                val read = handle.readFully(offset + off, data, off, take)
                                if (read != take) {
                                    throw IOException("SMB 短读：期望 $take 字节，实际 $read 字节")
                                }
                            }
                        }
                    }.awaitAll()
                    BypassBuffer(data, permits)
                }
            } catch (t: Throwable) {
                bypassBudget.release(permits)
                throw t
            }
        }

        fun releaseBypassBudget(permits: Int) {
            if (permits > 0) bypassBudget.release(permits)
        }

        fun takePreloadedHead(position: Long): ByteArray? {
            while (true) {
                val head = preloadedHeadRef.get() ?: return null
                if (position !in 0 until head.size.toLong()) return null
                if (preloadedHeadRef.compareAndSet(head, null)) return head
            }
        }
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val preloadedHeads = ConcurrentHashMap<String, ByteArray>()
    private val random = SecureRandom()
    private val mutex = Mutex()
    private val preloadMutex = Mutex()
    private val bypassBudget = JavaSemaphore(BYPASS_BUDGET_MIB, true)
    private val sessionSlots = Semaphore(MAX_ACTIVE_SESSIONS)
    private var server: ProxyServer? = null

    val activeStats: IoStats?
        get() = sessions.values
            .firstOrNull { it.channel == SmbConnectionManager.Channel.PLAYBACK }
            ?.stats

    /** @return 供播放内核使用的回环 URL */
    suspend fun publish(
        target: SmbTarget,
        relativePath: String,
        io: IoSettings,
        channel: SmbConnectionManager.Channel = SmbConnectionManager.Channel.PLAYBACK,
    ): String {
        try {
            withTimeout(SESSION_WAIT_TIMEOUT_MS) { sessionSlots.acquire() }
        } catch (t: TimeoutCancellationException) {
            throw IllegalStateException("本地媒体通道繁忙，请稍候重试", t)
        }
        var slotRetainedBySession = false
        try {
            return mutex.withLock {
                val startedServer = server == null
                val srv = server ?: ProxyServer().also {
                    it.start(SOCKET_READ_TIMEOUT_MS, false)
                    server = it
                    Metric.emit("proxy", "state" to "started", "port" to it.listeningPort)
                }

                var candidateHandle: SmbFileHandle? = null
                var candidatePipeline: PrefetchPipeline? = null
                var candidateScope: CoroutineScope? = null
                var published = false
                try {
                    val stats = IoStats()
                    val handle = SmbFileHandle.open(
                        connections,
                        target,
                        relativePath,
                        channel,
                        stats::onReconnect,
                    ).also { candidateHandle = it }
                    val preloadedHead = if (channel == SmbConnectionManager.Channel.PLAYBACK) {
                        preloadMutex.withLock {
                            preloadedHeads.remove(preloadKey(target, relativePath))
                        }
                    } else {
                        null
                    }
                    val scope = CoroutineScope(SupervisorJob()).also { candidateScope = it }
                    val windowBlocks = maxOf(1, (io.prefetchBytes / io.readBlockBytes).toInt())
                    val pipeline = PrefetchPipeline(
                        handle = handle,
                        blockSize = io.readBlockBytes,
                        windowBlocks = windowBlocks,
                        concurrency = io.concurrentReads,
                        stats = stats,
                        scope = scope,
                    ).also {
                        candidatePipeline = it
                        it.start()
                    }

                    // 规格 §3.3「代理预热」：点击文件的瞬间就开始预读，不等播放器初始化
                    pipeline.prepareAt(0)

                    val token = newToken()
                    sessions[token] = Session(
                        token = token,
                        relativePath = relativePath,
                        channel = channel,
                        handle = handle,
                        pipeline = pipeline,
                        stats = stats,
                        scope = scope,
                        mimeType = mimeTypeOf(relativePath),
                        concurrency = io.concurrentReads,
                        blockSize = io.readBlockBytes,
                        target = target,
                        connections = connections,
                        preloadedHead = preloadedHead,
                        bypassBudget = bypassBudget,
                    )
                    published = true
                    slotRetainedBySession = true

                    Metric.emit(
                        "proxy_publish",
                        "size" to handle.size,
                        "block_kb" to io.readBlockKb,
                        "conc" to io.concurrentReads,
                        "prefetch_mb" to io.prefetchMb,
                        "window_blocks" to windowBlocks,
                    )
                    "http://127.0.0.1:${srv.listeningPort}/s/$token"
                } finally {
                    if (!published) {
                        try {
                            candidatePipeline?.close()
                        } catch (_: Throwable) {
                            try { candidateHandle?.close() } catch (_: Throwable) { }
                        }
                        candidateScope?.cancel()
                        if (candidatePipeline == null) {
                            try { candidateHandle?.close() } catch (_: Throwable) { }
                        }
                        if (startedServer && sessions.isEmpty()) stopServerLocked()
                    }
                }
            }
        } finally {
            if (!slotRetainedBySession) sessionSlots.release()
        }
    }

    /**
     * A-06：在仍播放本集时先把下一集开头拉进内存。真正 publish 下一集时会
     * 原子取走这段数据，首个 Range 直接从内存返回，不再等第一次 SMB 往返。
     */
    suspend fun preloadHead(
        target: SmbTarget,
        relativePath: String,
        maxBytes: Int = PRELOAD_HEAD_BYTES,
    ): Int {
        val key = preloadKey(target, relativePath)
        preloadMutex.withLock {
            preloadedHeads[key]?.let { return it.size }
        }
        val handle = SmbFileHandle.open(
            connections,
            target,
            relativePath,
            SmbConnectionManager.Channel.AUX,
        )
        return try {
            val length = minOf(handle.size, maxBytes.toLong()).toInt()
            if (length <= 0) return 0
            val data = ByteArray(length)
            coroutineScope {
                val parts = 4.coerceAtMost(maxOf(1, length / (512 * 1024)))
                val each = (length + parts - 1) / parts
                (0 until parts).map { index ->
                    async(Dispatchers.IO) {
                        val offset = index * each
                        val take = minOf(each, length - offset)
                        if (take > 0) {
                            val read = handle.readFully(offset.toLong(), data, offset, take)
                            if (read != take) {
                                throw IOException("下一集预载短读：期望 $take 字节，实际 $read 字节")
                            }
                        }
                    }
                }.awaitAll()
            }
            preloadMutex.withLock {
                if (preloadedHeads.size >= 2) preloadedHeads.clear()
                preloadedHeads[key] = data
            }
            Metric.emit("next_preloaded", "file" to relativePath, "bytes" to length)
            length
        } finally {
            handle.close()
        }
    }

    suspend fun release(token: String) = withContext(NonCancellable) {
        val removed = mutex.withLock { sessions.remove(token) }
        removed?.let {
            try {
                closeSession(it)
            } finally {
                sessionSlots.release()
            }
        }
        mutex.withLock {
            if (sessions.isEmpty()) stopServerLocked()
        }
    }

    suspend fun releaseAll(clearPreloadedHeads: Boolean = true) = withContext(NonCancellable) {
        val removed = mutex.withLock {
            sessions.values.toList().also { sessions.clear() }
        }
        var failure: Throwable? = null
        removed.forEach { session ->
            try {
                runCatching { closeSession(session) }.onFailure {
                    failure?.addSuppressed(it) ?: run { failure = it }
                }
            } finally {
                sessionSlots.release()
            }
        }
        if (clearPreloadedHeads) {
            preloadMutex.withLock { preloadedHeads.clear() }
        }
        mutex.withLock {
            if (sessions.isEmpty()) stopServerLocked()
        }
        failure?.let { throw it }
    }

    private suspend fun closeSession(s: Session) = withContext(NonCancellable) {
        var failure: Throwable? = null
        suspend fun attempt(block: suspend () -> Unit) {
            try {
                block()
            } catch (t: Throwable) {
                failure?.addSuppressed(t) ?: run { failure = t }
            }
        }
        attempt { s.closeAuxiliaryWindowReaders() }
        attempt { s.pipeline.close() }
        attempt { s.handle.close() }
        s.scope.cancel()
        failure?.let { throw it }
    }

    private fun stopServerLocked() {
        server?.let {
            runCatching { it.stop() }
            Metric.emit("proxy", "state" to "stopped")
        }
        server = null
    }

    private fun newToken(): String {
        val bytes = ByteArray(18)
        random.nextBytes(bytes)
        return android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
        )
    }

    private fun preloadKey(target: SmbTarget, path: String): String =
        "${target.identity}|${target.password.hashCode().toUInt().toString(16)}|" +
            SmbFileRepository.normalizeRelativePath(path)

    private fun mimeTypeOf(path: String): String =
        when (path.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "ts", "m2ts", "mts" -> "video/mp2t"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            else -> "application/octet-stream"
        }

    // ── HTTP 服务器 ──────────────────────────────────────────

    private inner class ProxyServer : NanoHTTPD(LOOPBACK, 0) {
        init {
            setAsyncRunner(BoundedAsyncRunner())
        }

        override fun serve(httpSession: IHTTPSession): Response {
            val uri = httpSession.uri.orEmpty()
            if (!uri.startsWith("/s/")) return forbidden()
            val token = uri.removePrefix("/s/").substringBefore('/')
            val session = sessions[token] ?: return forbidden()

            val total = session.handle.size
            if (total <= 0) {
                return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, "text/plain", "size unavailable",
                )
            }

            // NanoHTTPD 把请求头 key 统一转成小写
            val rangeHeader = httpSession.headers["range"]
            val range = parseRange(rangeHeader, total)
                ?: return newFixedLengthResponse(
                    Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "bad range",
                ).apply { addHeader("Content-Range", "bytes */$total") }

            val (start, endInclusive) = range
            val length = endInclusive - start + 1
            val seq = session.requestCount.incrementAndGet()

            /*
             * 只有「长请求」才驱动预读窗口。
             *
             * Media3 解析容器时会同时持有两条连接：一条从头顺序读媒体数据，另一条跳到
             * 文件末尾读索引（mkv 的 Cues / mp4 的 moov，通常只有几十到几百 KB）。
             * 若两条流都去推预读窗口，就会把窗口互相拉到对方那一头，谁都读不到数据，
             * 一直卡到 HTTP readTimeout（20 s）才恢复——实测首帧稳定落在 20.9 s 就是这个原因。
             *
             * 短请求改走旁路直读：几个 SMB 往返就取完了，也不会打断顺序预读。
             */
            val drivesWindow = length > BYPASS_MAX_BYTES
            Metric.emit(
                "range_req",
                "seq" to seq,
                "start" to start,
                "len" to length,
                "mode" to if (drivesWindow) "window" else "bypass",
            )

            val body: InputStream = PipelineInputStream(
                session,
                start,
                endInclusive,
                seq,
                drivesWindow,
                session.takePreloadedHead(start),
            )
            val status = if (rangeHeader != null) Response.Status.PARTIAL_CONTENT else Response.Status.OK
            val response = newFixedLengthResponse(status, session.mimeType, body, length)
            response.addHeader("Accept-Ranges", "bytes")
            if (rangeHeader != null) {
                response.addHeader("Content-Range", "bytes $start-$endInclusive/$total")
            }
            // 回环传输不需要 gzip，压缩只会白白吃 CPU
            response.addHeader("Cache-Control", "no-store")
            response.addHeader("Connection", "close")
            return response
        }

        private fun forbidden(): Response =
            newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "forbidden")
    }

    private class BoundedAsyncRunner : NanoHTTPD.AsyncRunner {
        private val sequence = AtomicInteger()
        private val running = ConcurrentHashMap.newKeySet<NanoHTTPD.ClientHandler>()
        private val executor = ThreadPoolExecutor(
            HTTP_CORE_THREADS,
            HTTP_MAX_THREADS,
            30L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(HTTP_QUEUE_CAPACITY),
            { task ->
                Thread(task, "LanPlay-proxy-${sequence.incrementAndGet()}").apply {
                    isDaemon = true
                }
            },
        )

        override fun exec(handler: NanoHTTPD.ClientHandler) {
            running += handler
            try {
                executor.execute(handler)
            } catch (_: RejectedExecutionException) {
                running -= handler
                handler.close()
            }
        }

        override fun closed(handler: NanoHTTPD.ClientHandler) {
            running -= handler
        }

        override fun closeAll() {
            running.toList().forEach { it.close() }
            running.clear()
            executor.shutdownNow()
        }
    }

    /**
     * 解析 `Range: bytes=start-end`。全部用 [Long]——12.3 GB 的素材用 Int 必在 4 GB 处溢出（风险 R-5）。
     * @return start 与 endInclusive；不合法返回 null
     */
    private fun parseRange(header: String?, total: Long): Pair<Long, Long>? {
        if (header.isNullOrBlank()) return 0L to (total - 1)
        val spec = header.trim().removePrefix("bytes=").substringBefore(',').trim()
        if (spec.isEmpty()) return null

        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val startText = spec.substring(0, dash).trim()
        val endText = spec.substring(dash + 1).trim()

        return try {
            if (startText.isEmpty()) {
                // suffix range: bytes=-N 表示最后 N 字节
                val suffix = endText.toLong()
                if (suffix <= 0) return null
                val start = maxOf(0L, total - suffix)
                start to (total - 1)
            } else {
                val start = startText.toLong()
                if (start < 0 || start >= total) return null
                val end = if (endText.isEmpty()) total - 1 else minOf(endText.toLong(), total - 1)
                if (end < start) return null
                start to end
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    /**
     * 把预读流水线包装成阻塞 InputStream 交给 NanoHTTPD 写 socket。
     *
     * 注意不要自作主张终止「旧」的流：Media3 解析容器时会并行开第二条连接读文件末尾的
     * moov box，两条流都是有效的。seek 时 Media3 会自己关闭不再需要的连接，socket 一断
     * 这里的写入就会抛异常自然结束，无需额外的取代逻辑。
     */
    private class PipelineInputStream(
        private val session: Session,
        private val start: Long,
        private val endInclusive: Long,
        private val seq: Long,
        private val drivesWindow: Boolean,
        private val preloadedHead: ByteArray?,
    ) : InputStream() {

        private var position = start
        private val single = ByteArray(1)
        private val createdNs = System.nanoTime()
        private var firstByteReported = false
        private var windowReader: Session.WindowReader? = null
        private val closed = AtomicBoolean(false)
        private val lastActivityNs = AtomicLong(System.nanoTime())
        private val idleWatchdog: Job = session.scope.launch(Dispatchers.IO) {
            while (isActive && !closed.get()) {
                delay(STREAM_WATCHDOG_INTERVAL_MS)
                val idleMs = (System.nanoTime() - lastActivityNs.get()) / 1_000_000
                if (idleMs >= STREAM_IDLE_CLOSE_MS) {
                    runCatching { close() }
                    break
                }
            }
        }

        /*
         * 中转缓冲。Media3 是按 16 KB 一次向代理要数据的，直接把每次调用透传下去代价很大：
         *   · bypass 路径 → 每 16 KB 一个独立 SMB 往返（8.3 MB 的 moov 要 519 次，实测拖到 9 秒）
         *   · window 路径 → 每 16 KB 抢一次预读流水线的 mutex，和 6 个 worker 正面竞争
         * 聚合成 256 KB 一取，两条路径的调用次数都降到 1/16。
         */
        private val chunk = ByteArray(CHUNK_SIZE)
        private var chunkStart = -1L
        private var chunkLen = 0

        override fun read(): Int {
            val n = read(single, 0, 1)
            return if (n <= 0) -1 else single[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed.get()) return -1
            lastActivityNs.set(System.nanoTime())
            if (position > endInclusive) return -1

            preloadedHead?.let { head ->
                if (position >= 0L && position < head.size) {
                    val copied = minOf(len, head.size - position.toInt())
                    System.arraycopy(head, position.toInt(), b, off, copied)
                    position += copied
                    lastActivityNs.set(System.nanoTime())
                    session.stats.onBytesRead(copied)
                    session.stats.onBlockRequest(hit = true)
                    if (!firstByteReported) {
                        firstByteReported = true
                        Metric.emit(
                            "range_first_byte",
                            "seq" to seq,
                            "start" to start,
                            "ms" to ((System.nanoTime() - createdNs) / 1_000_000),
                            "n" to copied,
                            "preloaded" to true,
                        )
                    }
                    return copied
                }
            }

            if (!drivesWindow) return readFromBypass(b, off, len)

            if (chunkStart < 0 || position < chunkStart || position >= chunkStart + chunkLen) {
                val want = minOf(CHUNK_SIZE.toLong(), endInclusive - position + 1).toInt()
                if (want <= 0) return -1

                val startedNs = System.nanoTime()
                val n = try {
                    runBlocking {
                        withTimeout(HTTP_BODY_READ_TIMEOUT_MS) {
                            val reader = windowReader ?: session
                                .acquireWindowReader(seq, position)
                                .also { windowReader = it }
                            reader.pipeline.read(position, chunk, 0, want) { reader.active.get() }
                        }
                    }
                } catch (t: TimeoutCancellationException) {
                    throw IOException("本地代理等待 SMB 数据超时", t)
                }
                val waitedMs = (System.nanoTime() - startedNs) / 1_000_000

                if (!firstByteReported) {
                    firstByteReported = true
                    Metric.emit(
                        "range_first_byte",
                        "seq" to seq,
                        "start" to start,
                        "ms" to ((System.nanoTime() - createdNs) / 1_000_000),
                        "n" to n,
                    )
                }
                if (waitedMs >= 500) {
                    Metric.emit("range_slow_read", "seq" to seq, "at" to position, "waited_ms" to waitedMs, "n" to n)
                }
                if (n <= 0) {
                    Metric.emit("range_eof", "seq" to seq, "at" to position, "end" to endInclusive)
                    return -1
                }
                chunkStart = position
                chunkLen = n
            }

            val inChunk = (position - chunkStart).toInt()
            val copied = minOf(chunkLen - inChunk, len)
            System.arraycopy(chunk, inChunk, b, off, copied)
            position += copied
            lastActivityNs.set(System.nanoTime())
            return copied
        }

        /** 整段索引一次并发拉进内存，之后全是内存拷贝——容器解析来回跳读也不再产生 SMB 往返 */
        private fun readFromBypass(b: ByteArray, off: Int, len: Int): Int {
            val data = bypassData ?: run {
                val total = (endInclusive - start + 1).toInt()
                val startedNs = System.nanoTime()
                val loaded = try {
                    runBlocking {
                        withTimeout(HTTP_BODY_READ_TIMEOUT_MS) {
                            session.loadBypassRange(start, total)
                        }
                    }
                } catch (t: TimeoutCancellationException) {
                    throw IOException("本地代理加载容器索引超时", t)
                }
                Metric.emit(
                    "range_bypass_load",
                    "seq" to seq,
                    "bytes" to total,
                    "ms" to ((System.nanoTime() - startedNs) / 1_000_000),
                )
                if (!firstByteReported) {
                    firstByteReported = true
                    Metric.emit(
                        "range_first_byte",
                        "seq" to seq,
                        "start" to start,
                        "ms" to ((System.nanoTime() - createdNs) / 1_000_000),
                        "n" to total,
                    )
                }
                bypassPermits = loaded.permits
                bypassData = loaded.data
                loaded.data
            }
            val inBuf = (position - start).toInt()
            if (inBuf >= data.size) return -1
            val copied = minOf(data.size - inBuf, len)
            System.arraycopy(data, inBuf, b, off, copied)
            position += copied
            lastActivityNs.set(System.nanoTime())
            return copied
        }

        private var bypassData: ByteArray? = null
        private var bypassPermits: Int = 0

        override fun available(): Int =
            (endInclusive - position + 1).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

        /** 索引缓冲用完就放掉：长片的 moov 可以到几十 MB，留着会白占堆 */
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            idleWatchdog.cancel()
            val reader = windowReader
            windowReader = null
            if (reader != null) {
                runBlocking { session.releaseWindowReader(seq, reader) }
            }
            bypassData = null
            val permits = bypassPermits
            bypassPermits = 0
            session.releaseBypassBudget(permits)
            super.close()
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val SOCKET_READ_TIMEOUT_MS = 30_000
        /**
         * 小于此长度的 Range 视为容器解析用的随机读，走旁路一次性并发加载进内存。
         *
         * 上限要够大：长片 1080p60 的 mp4 moov 可以到几十 MB（每帧一条 sample 记录），
         * 历史样本中最大索引约 33.3 MB。但也不能无限大——旁路缓冲是实打实的
         * Java 堆分配，叠加 48MB 预读窗口和 48MB 播放器缓冲后离 256MB 上限已经不远
         * （实测堆峰值 236MB）。超过这个阈值的索引退回窗口模式，慢一些但不会 OOM。
         */
        const val BYPASS_MAX_BYTES = 40L * 1024 * 1024

        /** 每个 HTTP 连接的中转缓冲大小，把 Media3 的 16 KB 碎读聚合起来 */
        const val CHUNK_SIZE = 256 * 1024

        /** 索引旁路加载的并发路数 */
        const val BYPASS_PARTS = 16
        const val LIGHTWEIGHT_WINDOW_BYTES = 4 * 1024 * 1024
        const val LIGHTWEIGHT_MAX_CONCURRENCY = 4
        const val MAX_AUXILIARY_WINDOW_READERS = 2
        const val PRELOAD_HEAD_BYTES = 16 * 1024 * 1024
        const val BUDGET_UNIT_BYTES = 1024L * 1024L
        const val BYPASS_BUDGET_MIB = 64
        const val MAX_ACTIVE_SESSIONS = 4
        const val SESSION_WAIT_TIMEOUT_MS = 10_000L
        const val HTTP_CORE_THREADS = 2
        const val HTTP_MAX_THREADS = 8
        const val HTTP_QUEUE_CAPACITY = 16
        // 必须覆盖 SmbFileHandle 的 60 秒断线恢复窗，并给协程调度留出余量。
        const val HTTP_BODY_READ_TIMEOUT_MS = 65_000L
        const val STREAM_WATCHDOG_INTERVAL_MS = 15_000L
        const val STREAM_IDLE_CLOSE_MS = 90_000L
    }
}
