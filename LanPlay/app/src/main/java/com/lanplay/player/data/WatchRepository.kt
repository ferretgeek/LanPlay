package com.lanplay.player.data

import com.lanplay.player.data.db.WatchRecordDao
import com.lanplay.player.data.db.WatchRecordEntity
import com.lanplay.player.data.db.WatchState
import com.lanplay.player.smb.SmbEntry
import com.lanplay.player.smb.SmbFileRepository
import com.lanplay.player.smb.SmbTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 观看记录的唯一写入口。路径命中失败时用大小 + 修改时间迁移旧记录（W-07），
 * 高频进度写只更新必要列，避免每三秒整行重写。
 */
@Singleton
class WatchRepository @Inject constructor(
    private val dao: WatchRecordDao,
    private val files: SmbFileRepository,
) {
    suspend fun setSubtitlePreference(
        recordId: Long,
        path: String?,
        charset: String?,
        enabled: Boolean,
    ) = dao.setSubtitlePreference(recordId, path, charset, enabled)

    suspend fun setPlaybackSpeed(recordId: Long, speed: Float) =
        dao.setPlaybackSpeed(recordId, speed)

    suspend fun setAspectRatioMode(recordId: Long, mode: Int) =
        dao.setAspectRatioMode(recordId, mode)

    suspend fun setVideoTransform(
        recordId: Long,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
        rotation: Int,
        mirrorH: Boolean,
        mirrorV: Boolean,
    ) = dao.setVideoTransform(
        recordId, scale, offsetX, offsetY, rotation, mirrorH, mirrorV
    )

    suspend fun setAudioTrack(recordId: Long, trackIndex: Int) =
        dao.setAudioTrack(recordId, trackIndex)

    suspend fun setSubtitleOffset(recordId: Long, offsetMs: Long) =
        dao.setSubtitleOffset(recordId, offsetMs.coerceIn(-60_000L, 60_000L))

    suspend fun setFavorite(recordId: Long, value: Boolean) =
        dao.setFavorite(recordId, value)

    suspend fun setRating(recordId: Long, rating: Int) =
        dao.setRating(recordId, rating.coerceIn(0, 5))

    suspend fun setNote(recordId: Long, note: String?) =
        dao.setNote(recordId, note?.take(2_000))

    suspend fun setSkipPoints(recordId: Long, introMs: Long, outroMs: Long) =
        dao.setSkipPoints(recordId, introMs.coerceAtLeast(0L), outroMs.coerceAtLeast(0L))

    suspend fun begin(
        serverId: Long,
        target: SmbTarget,
        entry: SmbEntry,
        countPlay: Boolean = true,
    ): WatchRecordEntity {
        var record = findForPlayback(serverId, target, entry)
        val now = System.currentTimeMillis()
        if (record == null) {
            val fresh = WatchRecordEntity(
                serverId = serverId,
                fullPath = entry.relativePath,
                fileName = entry.name,
                fileSize = entry.size,
                lastModified = entry.lastModified,
                firstWatchedAt = if (countPlay) now else 0L,
                lastWatchedAt = if (countPlay) now else 0L,
                playCount = if (countPlay) 1 else 0,
            )
            val insertedId = dao.insertIfAbsent(fresh)
            if (insertedId != -1L) return fresh.copy(id = insertedId)
            record = dao.get(serverId, entry.relativePath)
                ?: error("观看记录并发创建失败")
        }

        val existing = requireNotNull(record)
        dao.touchForPlayback(
            id = existing.id,
            fileName = entry.name,
            fileSize = entry.size,
            lastModified = entry.lastModified,
            now = now,
            countPlay = countPlay,
        )
        return dao.getById(existing.id) ?: error("观看记录更新失败")
    }

    /**
     * 只读取既有播放偏好并处理可确认的 rename，不创建历史记录、不增加播放次数。
     * 播放内核成功打开后再调用 [begin]，失败打开不会出现在历史页。
     */
    suspend fun findForPlayback(
        serverId: Long,
        target: SmbTarget,
        entry: SmbEntry,
    ): WatchRecordEntity? {
        var record = dao.get(serverId, entry.relativePath)
        if (record == null && entry.lastModified > 0L) {
            val candidates = dao.findByFingerprint(serverId, entry.size, entry.lastModified)
            val renamed = candidates.singleOrNull()
            val oldPathGone = if (renamed != null && renamed.fullPath != entry.relativePath) {
                try {
                    !files.exists(target, renamed.fullPath)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    false
                }
            } else {
                false
            }
            if (renamed != null && oldPathGone) {
                dao.migratePath(renamed.id, entry.relativePath, entry.name)
                record = renamed.copy(fullPath = entry.relativePath, fileName = entry.name)
            }
        }
        return record
    }

    suspend fun saveProgress(
        recordId: Long,
        positionMs: Long,
        durationMs: Long,
        watchedDeltaMs: Long,
        forceCompleted: Boolean = false,
    ) {
        if (recordId <= 0L || durationMs <= 0L) return
        val safePosition = positionMs.coerceIn(0L, durationMs)
        val percent = (safePosition.toDouble() / durationMs).toFloat().coerceIn(0f, 1f)
        val state = when {
            forceCompleted || percent >= 0.95f -> WatchState.COMPLETED
            percent >= 0.02f -> WatchState.IN_PROGRESS
            else -> WatchState.UNWATCHED
        }
        dao.updateProgress(
            id = recordId,
            positionMs = safePosition,
            durationMs = durationMs,
            percent = percent,
            state = state,
            now = System.currentTimeMillis(),
            deltaMs = watchedDeltaMs.coerceIn(0L, 10_000L),
        )
    }

    suspend fun getMany(serverId: Long, paths: List<String>): Map<String, WatchRecordEntity> =
        paths.chunked(SQLITE_IN_BATCH_SIZE)
            .flatMap { dao.getMany(serverId, it) }
            .associateBy { it.fullPath }

    fun observeServer(serverId: Long): Flow<List<WatchRecordEntity>> = dao.observeServer(serverId)

    suspend fun mark(serverId: Long, target: SmbTarget, entry: SmbEntry, state: WatchState) {
        val record = begin(serverId, target, entry, countPlay = false)
        dao.markState(record.id, state)
    }

    suspend fun reset(serverId: Long, fullPath: String) {
        dao.get(serverId, fullPath)?.let { dao.resetProgress(it.id) }
    }

    private companion object {
        const val SQLITE_IN_BATCH_SIZE = 900
    }
}
