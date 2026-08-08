package com.lanplay.player.data

import android.content.Context
import com.lanplay.player.data.prefs.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class CacheStats(
    val artworkBytes: Long = 0,
    val thumbnailBytes: Long = 0,
    val spriteBytes: Long = 0,
    val subtitleBytes: Long = 0,
    val screenshotBytes: Long = 0,
    val availableBytes: Long = 0,
)

@Singleton
class CacheRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    private val artworkDir get() = File(context.cacheDir, "metadata-images")
    private val thumbnailDir get() = File(context.cacheDir, "thumbnails")
    private val spriteDir get() = File(context.cacheDir, "sprites")
    private val subtitleDir get() = File(context.cacheDir, "subtitles")
    private val screenshotDir get() = File(context.filesDir, "screenshots")

    suspend fun stats(): CacheStats = withContext(Dispatchers.IO) {
        CacheStats(
            artworkBytes = directorySize(artworkDir),
            thumbnailBytes = directorySize(thumbnailDir),
            spriteBytes = directorySize(spriteDir),
            subtitleBytes = directorySize(subtitleDir),
            screenshotBytes = directorySize(screenshotDir),
            availableBytes = context.filesDir.usableSpace.coerceAtLeast(0L),
        )
    }

    suspend fun touch(file: File) = withContext(Dispatchers.IO) {
        if (file.isFile) file.setLastModified(System.currentTimeMillis())
    }

    suspend fun pruneArtwork() = withContext(Dispatchers.IO) {
        val limit = settings.currentCacheSettings().imageCacheLimitMb.toLong() * 1024L * 1024L
        val files = artworkDir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }
            ?: return@withContext
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= limit) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    suspend fun clearArtwork() = withContext(Dispatchers.IO) {
        artworkDir.listFiles()?.forEach { if (it.isFile) it.delete() }
    }

    suspend fun clearThumbnails() = withContext(Dispatchers.IO) {
        thumbnailDir.listFiles()?.forEach { if (it.isFile) it.delete() }
    }

    suspend fun clearSprites() = withContext(Dispatchers.IO) {
        spriteDir.listFiles()?.forEach { file ->
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        }
    }

    suspend fun clearSubtitles() = withContext(Dispatchers.IO) {
        subtitleDir.listFiles()?.forEach { file ->
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        }
    }

    suspend fun clearScreenshots() = withContext(Dispatchers.IO) {
        screenshotDir.listFiles()?.forEach { if (it.isFile) it.delete() }
    }

    private fun directorySize(dir: File): Long =
        if (!dir.exists()) 0L
        else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
