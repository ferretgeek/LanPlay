package com.lanplay.player.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.os.Build
import com.lanplay.player.core.concurrent.KeyedMutexRegistry
import com.lanplay.player.core.log.Metric
import com.lanplay.player.data.prefs.IoSettings
import com.lanplay.player.data.crypto.CacheCipher
import com.lanplay.player.smb.SmbConnectionManager
import com.lanplay.player.smb.SmbEntry
import com.lanplay.player.smb.proxy.LocalMediaProxy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil

/**
 * A-01：每 10 秒一帧、100 帧一张 JPEG 雪碧图。
 *
 * 生成走 AUX SMB 通道且只有一个读取并发，不会替换播放通道的指标或预读窗口。
 * manifest 最后写入；中断或失败时不会把半成品误认成有效缓存。
 */
@Singleton
class SpritePreviewRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val proxy: LocalMediaProxy,
) {
    private data class Manifest(
        val fileSize: Long,
        val lastModified: Long,
        val durationMs: Long,
        val frameCount: Int,
    )

    private val generationLocks = KeyedMutexRegistry<String>()
    private val frameCache = object : LinkedHashMap<String, Bitmap>(24, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Bitmap>?,
        ): Boolean = size > 24
    }

    suspend fun ensureGenerated(server: SavedServer, entry: SmbEntry): Boolean =
        withContext(Dispatchers.IO) {
            val key = cacheKey(server.id, entry.relativePath)
            generationLocks.withLock(key) {
                val directory = File(context.cacheDir, "sprites/$key").apply { mkdirs() }
                val existing = readManifest(directory)
                if (
                    existing != null &&
                    existing.fileSize == entry.size &&
                    existing.lastModified == entry.lastModified &&
                    existing.frameCount > 0
                ) {
                    return@withLock true
                }

                evictMemory(directory.name)
                directory.listFiles()
                    ?.filter { it.name.startsWith("sheet-") || it.name == MANIFEST_NAME }
                    ?.forEach { it.delete() }

                val io = IoSettings(
                    prefetchMb = 4,
                    readBlockKb = 512,
                    concurrentReads = 1,
                )
                var token: String? = null
                val started = System.nanoTime()
                try {
                    val url = proxy.publish(
                        server.target,
                        entry.relativePath,
                        io,
                        SmbConnectionManager.Channel.AUX,
                    )
                    token = url.substringAfterLast('/')
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(url, emptyMap())
                        val duration = retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_DURATION
                        )?.toLongOrNull()?.coerceAtLeast(1L) ?: return@withLock false
                        val count = ceil(duration / INTERVAL_MS.toDouble()).toInt()
                            .coerceIn(1, MAX_FRAMES)
                        var generated = 0
                        while (generated < count) {
                            coroutineContext.ensureActive()
                            val inSheet = minOf(FRAMES_PER_SHEET, count - generated)
                            val sheet = Bitmap.createBitmap(
                                COLUMNS * FRAME_WIDTH,
                                ROWS * FRAME_HEIGHT,
                                Bitmap.Config.RGB_565,
                            )
                            val canvas = Canvas(sheet)
                            canvas.drawColor(Color.BLACK)
                            repeat(inSheet) { offset ->
                                coroutineContext.ensureActive()
                                val frameIndex = generated + offset
                                val timeUs = frameIndex * INTERVAL_MS * 1_000L
                                val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                                    retriever.getScaledFrameAtTime(
                                        timeUs,
                                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                        FRAME_WIDTH,
                                        FRAME_HEIGHT,
                                    )
                                } else {
                                    retriever.getFrameAtTime(
                                        timeUs,
                                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                    )
                                }
                                if (frame != null) {
                                    val column = offset % COLUMNS
                                    val row = offset / COLUMNS
                                    canvas.drawBitmap(
                                        frame,
                                        null,
                                        Rect(
                                            column * FRAME_WIDTH,
                                            row * FRAME_HEIGHT,
                                            (column + 1) * FRAME_WIDTH,
                                            (row + 1) * FRAME_HEIGHT,
                                        ),
                                        null,
                                    )
                                    frame.recycle()
                                }
                            }
                            val encoded = ByteArrayOutputStream()
                            check(sheet.compress(Bitmap.CompressFormat.JPEG, 60, encoded)) {
                                "雪碧图写入失败"
                            }
                            sheet.recycle()
                            val final = File(
                                directory,
                                "sheet-${generated / FRAMES_PER_SHEET}.jpg",
                            )
                            val bytes = encoded.toByteArray()
                            CacheCipher.writeEncrypted(final, bytes)
                            bytes.fill(0)
                            generated += inSheet
                        }
                        writeManifest(
                            directory,
                            Manifest(entry.size, entry.lastModified, duration, count),
                        )
                        Metric.emit(
                            "sprite_ready",
                            "file" to entry.name,
                            "frames" to count,
                            "ms" to (System.nanoTime() - started) / 1_000_000,
                        )
                        true
                    } finally {
                        retriever.release()
                    }
                } catch (throwable: Throwable) {
                    if (throwable is kotlinx.coroutines.CancellationException) throw throwable
                    Metric.error(
                        "SPRITE_GENERATE",
                        throwable.message ?: "Seek 预览生成失败",
                        "file" to entry.name,
                    )
                    false
                } finally {
                    token?.let { proxy.release(it) }
                    directory.listFiles()
                        ?.filter { it.extension == "part" }
                        ?.forEach { it.delete() }
                }
            }
        }

    suspend fun frame(serverId: Long, relativePath: String, positionMs: Long): Bitmap? =
        withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "sprites/${cacheKey(serverId, relativePath)}")
            val manifest = readManifest(directory) ?: return@withContext null
            val frameIndex = (positionMs.coerceAtLeast(0L) / INTERVAL_MS)
                .toInt()
                .coerceIn(0, manifest.frameCount - 1)
            val cacheKey = "${directory.name}:$frameIndex"
            synchronized(frameCache) {
                frameCache[cacheKey]?.let { return@withContext it }
            }
            val sheetIndex = frameIndex / FRAMES_PER_SHEET
            val inSheet = frameIndex % FRAMES_PER_SHEET
            val sheetFile = File(directory, "sheet-$sheetIndex.jpg")
            val sheetBytes = CacheCipher.read(sheetFile) ?: return@withContext null
            val sheet = BitmapFactory.decodeByteArray(sheetBytes, 0, sheetBytes.size)
                ?: return@withContext null
            val column = inSheet % COLUMNS
            val row = inSheet / COLUMNS
            val result = runCatching {
                Bitmap.createBitmap(
                    sheet,
                    column * FRAME_WIDTH,
                    row * FRAME_HEIGHT,
                    FRAME_WIDTH,
                    FRAME_HEIGHT,
                )
            }.getOrNull()
            sheet.recycle()
            if (result != null) {
                synchronized(frameCache) { frameCache[cacheKey] = result }
            }
            result
        }

    private fun cacheKey(serverId: Long, path: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$serverId|$path".toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun evictMemory(directoryName: String) {
        synchronized(frameCache) {
            frameCache.keys.removeAll { it.startsWith("$directoryName:") }
        }
    }

    private fun readManifest(directory: File): Manifest? {
        val parts = runCatching {
            CacheCipher.read(File(directory, MANIFEST_NAME))
                ?.toString(Charsets.UTF_8)
                ?.trim()
                ?.split('|')
        }.getOrNull() ?: return null
        if (parts.size != 4) return null
        return Manifest(
            parts[0].toLongOrNull() ?: return null,
            parts[1].toLongOrNull() ?: return null,
            parts[2].toLongOrNull() ?: return null,
            parts[3].toIntOrNull() ?: return null,
        )
    }

    private fun writeManifest(directory: File, value: Manifest) {
        val final = File(directory, MANIFEST_NAME)
        CacheCipher.writeEncrypted(
            final,
            "${value.fileSize}|${value.lastModified}|${value.durationMs}|${value.frameCount}"
                .toByteArray(Charsets.UTF_8),
        )
    }

    private companion object {
        const val MANIFEST_NAME = "manifest.txt"
        const val INTERVAL_MS = 10_000L
        const val FRAME_WIDTH = 160
        const val FRAME_HEIGHT = 90
        const val COLUMNS = 10
        const val ROWS = 10
        const val FRAMES_PER_SHEET = COLUMNS * ROWS
        const val MAX_FRAMES = 2_160 // 最长支持 6 小时
    }
}
