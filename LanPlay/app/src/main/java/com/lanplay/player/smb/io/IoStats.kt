package com.lanplay.player.smb.io

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 播放器规格 §1.2 第 15 条 / §8.1 调试面板：实时暴露吞吐、缓冲水位、在途请求、命中率、重连次数。
 *
 * 采集在 release 构建下同样进行（调试面板 P-18 要读），被剔除的只是 logcat 输出。
 */
class IoStats {

    private val totalBytes = AtomicLong(0)
    private val blockRequests = AtomicLong(0)
    private val blockHits = AtomicLong(0)
    private val inflightCount = AtomicInteger(0)
    private val windowResets = AtomicLong(0)
    private val reconnectCount = AtomicInteger(0)

    /** 上次采样点，用于算区间平均吞吐而非累计平均 */
    private var lastSampleNs = System.nanoTime()
    private var lastSampleBytes = 0L

    @Volatile
    var bufferedBytes: Long = 0

    /** 由播放会话写入，用于把字节水位换算成秒数水位 */
    @Volatile
    var bitrateBytesPerSec: Double = 0.0

    val bufferedSeconds: Double
        get() = if (bitrateBytesPerSec > 0) bufferedBytes / bitrateBytesPerSec else 0.0

    val inflight: Int get() = inflightCount.get()
    val reconnects: Int get() = reconnectCount.get()
    val resets: Long get() = windowResets.get()

    val hitRate: Double
        get() {
            val req = blockRequests.get()
            return if (req == 0L) 0.0 else blockHits.get().toDouble() / req
        }

    fun onBytesRead(n: Int) {
        if (n > 0) totalBytes.addAndGet(n.toLong())
    }

    fun onBlockRequest(hit: Boolean) {
        blockRequests.incrementAndGet()
        if (hit) blockHits.incrementAndGet()
    }

    fun onWindowReset() = windowResets.incrementAndGet()
    fun onReconnect() = reconnectCount.incrementAndGet()
    fun onInflightStart() = inflightCount.incrementAndGet()
    fun onInflightEnd() = inflightCount.decrementAndGet()

    /** 区间平均吞吐（MB/s）。每次调用会重置采样窗口，因此只应由指标上报处周期调用。 */
    @Synchronized
    fun sampleThroughputMbps(): Double {
        val now = System.nanoTime()
        val bytes = totalBytes.get()
        val elapsedSec = (now - lastSampleNs) / 1_000_000_000.0
        val delta = bytes - lastSampleBytes
        lastSampleNs = now
        lastSampleBytes = bytes
        if (elapsedSec <= 0.0) return 0.0
        return (delta / elapsedSec) / (1024.0 * 1024.0)
    }

    fun totalBytesRead(): Long = totalBytes.get()

    @Synchronized
    fun reset() {
        totalBytes.set(0)
        blockRequests.set(0)
        blockHits.set(0)
        inflightCount.set(0)
        windowResets.set(0)
        reconnectCount.set(0)
        bufferedBytes = 0
        lastSampleNs = System.nanoTime()
        lastSampleBytes = 0
    }
}
