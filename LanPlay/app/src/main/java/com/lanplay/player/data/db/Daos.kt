package com.lanplay.player.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SmbServerDao {
    @Query("SELECT * FROM smb_server ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<SmbServerEntity>>

    @Query("SELECT * FROM smb_server ORDER BY sortOrder, id")
    suspend fun listAll(): List<SmbServerEntity>

    @Query("SELECT * FROM smb_server WHERE id = :id")
    suspend fun getById(id: Long): SmbServerEntity?

    @Query("SELECT * FROM smb_server ORDER BY sortOrder, id LIMIT 1")
    suspend fun first(): SmbServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(server: SmbServerEntity): Long

    @Update
    suspend fun update(server: SmbServerEntity)

    @Delete
    suspend fun delete(server: SmbServerEntity)

    @Query("UPDATE smb_server SET sortOrder = CASE WHEN id = :id THEN 0 ELSE sortOrder + 1 END")
    suspend fun makeFirst(id: Long)

    @Query("UPDATE smb_server SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: Long, sortOrder: Int)
}

@Dao
interface WatchRecordDao {

    @Query("SELECT * FROM watch_record ORDER BY id")
    suspend fun listAll(): List<WatchRecordEntity>

    @Query("SELECT * FROM watch_record WHERE serverId = :serverId AND fullPath = :fullPath")
    suspend fun get(serverId: Long, fullPath: String): WatchRecordEntity?

    @Query("SELECT * FROM watch_record WHERE id = :id")
    suspend fun getById(id: Long): WatchRecordEntity?

    @Query("SELECT * FROM watch_record WHERE serverId = :serverId AND fullPath IN (:paths)")
    suspend fun getMany(serverId: Long, paths: List<String>): List<WatchRecordEntity>

    @Query("SELECT * FROM watch_record WHERE serverId = :serverId AND fullPath = :fullPath")
    fun observe(serverId: Long, fullPath: String): Flow<WatchRecordEntity?>

    /**
     * W-07：PC 上重命名后路径对不上了，靠大小 + 修改时间找回旧记录。
     * 两者同时相等的概率极低，足以认定是同一个文件。
     */
    @Query(
        """
        SELECT * FROM watch_record
        WHERE serverId = :serverId AND fileSize = :fileSize AND lastModified = :lastModified
        ORDER BY id
        """
    )
    suspend fun findByFingerprint(
        serverId: Long,
        fileSize: Long,
        lastModified: Long,
    ): List<WatchRecordEntity>

    @Upsert
    suspend fun upsert(record: WatchRecordEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(record: WatchRecordEntity): Long

    /** 开始播放只更新会话身份与计数，绝不覆盖并发写入的收藏、评分、备注或进度。 */
    @Query(
        """
        UPDATE watch_record
        SET fileName = :fileName, fileSize = :fileSize, lastModified = :lastModified,
            firstWatchedAt = CASE
                WHEN :countPlay = 1 AND firstWatchedAt = 0 THEN :now ELSE firstWatchedAt END,
            lastWatchedAt = CASE WHEN :countPlay = 1 THEN :now ELSE lastWatchedAt END,
            playCount = playCount + CASE WHEN :countPlay = 1 THEN 1 ELSE 0 END
        WHERE id = :id
        """
    )
    suspend fun touchForPlayback(
        id: Long,
        fileName: String,
        fileSize: Long,
        lastModified: Long,
        now: Long,
        countPlay: Boolean,
    )

    /** 高频写入路径（每 3 秒一次），只更新进度相关列，避免整行重写 */
    @Query(
        """
        UPDATE watch_record
        SET positionMs = :positionMs, durationMs = :durationMs, progressPercent = :percent,
            watchState = CASE WHEN manuallyMarked = 1 THEN watchState ELSE :state END,
            lastWatchedAt = :now, totalWatchedMs = totalWatchedMs + :deltaMs
        WHERE id = :id
        """
    )
    suspend fun updateProgress(
        id: Long,
        positionMs: Long,
        durationMs: Long,
        percent: Float,
        state: WatchState,
        now: Long,
        deltaMs: Long,
    )

    @Query("UPDATE watch_record SET fullPath = :newPath, fileName = :newName WHERE id = :id")
    suspend fun migratePath(id: Long, newPath: String, newName: String)

    @Query("UPDATE watch_record SET watchState = :state, manuallyMarked = 1 WHERE id = :id")
    suspend fun markState(id: Long, state: WatchState)

    /** 继续观看（W-09）：最近未看完的，倒序 */
    @Query(
        """
        SELECT * FROM watch_record
        WHERE watchState = 'IN_PROGRESS' ORDER BY lastWatchedAt DESC LIMIT :limit
        """
    )
    suspend fun recentInProgress(limit: Int = 20): List<WatchRecordEntity>

    @Query(
        """
        SELECT * FROM watch_record
        WHERE serverId = :serverId AND watchState = 'IN_PROGRESS'
        ORDER BY lastWatchedAt DESC LIMIT 1
        """
    )
    suspend fun mostRecentInProgress(serverId: Long): WatchRecordEntity?

    /** 批量清理（D-08~D-10）：按状态挑，可排除收藏、可限定最后观看早于某时刻 */
    @Query(
        """
        SELECT * FROM watch_record
        WHERE serverId = :serverId
          AND watchState IN (:states)
          AND (:excludeFavorite = 0 OR isFavorite = 0)
          AND (:beforeMs = 0 OR lastWatchedAt < :beforeMs)
        ORDER BY lastWatchedAt
        """
    )
    suspend fun listByStates(
        serverId: Long,
        states: List<WatchState>,
        excludeFavorite: Boolean,
        beforeMs: Long,
    ): List<WatchRecordEntity>

    @Query("DELETE FROM watch_record WHERE serverId = :serverId AND fullPath = :fullPath")
    suspend fun deleteByPath(serverId: Long, fullPath: String)

    @Query("DELETE FROM bookmark WHERE recordId = :recordId")
    suspend fun deleteBookmarksForRecord(recordId: Long)

    @Query("DELETE FROM record_tag WHERE recordId = :recordId")
    suspend fun deleteTagsForRecord(recordId: Long)

    @Transaction
    suspend fun deleteWithRelations(serverId: Long, fullPath: String) {
        val record = get(serverId, fullPath) ?: return
        deleteBookmarksForRecord(record.id)
        deleteTagsForRecord(record.id)
        deleteByPath(serverId, fullPath)
    }

    @Query(
        "DELETE FROM bookmark WHERE recordId IN " +
            "(SELECT id FROM watch_record WHERE serverId = :serverId)"
    )
    suspend fun deleteBookmarksForServer(serverId: Long)

    @Query(
        "DELETE FROM record_tag WHERE recordId IN " +
            "(SELECT id FROM watch_record WHERE serverId = :serverId)"
    )
    suspend fun deleteTagsForServer(serverId: Long)

    @Query("DELETE FROM watch_record WHERE serverId = :serverId")
    suspend fun clearServer(serverId: Long)

    @Transaction
    suspend fun clearServerWithRelations(serverId: Long) {
        deleteBookmarksForServer(serverId)
        deleteTagsForServer(serverId)
        clearServer(serverId)
    }

    @Query("DELETE FROM watch_record")
    suspend fun clearAll()

    @Query(
        """
        UPDATE watch_record
        SET positionMs = 0, durationMs = 0, progressPercent = 0,
            watchState = 'UNWATCHED', manuallyMarked = 0, totalWatchedMs = 0
        WHERE id = :id
        """
    )
    suspend fun resetProgress(id: Long)

    @Query("UPDATE watch_record SET isFavorite = :value WHERE id = :id")
    suspend fun setFavorite(id: Long, value: Boolean)

    @Query("UPDATE watch_record SET rating = :rating WHERE id = :id")
    suspend fun setRating(id: Long, rating: Int)

    @Query(
        """
        UPDATE watch_record
        SET subtitlePath = :path, subtitleCharset = :charset, subtitleEnabled = :enabled
        WHERE id = :id
        """
    )
    suspend fun setSubtitlePreference(
        id: Long,
        path: String?,
        charset: String?,
        enabled: Boolean,
    )

    @Query("UPDATE watch_record SET playbackSpeed = :speed WHERE id = :id")
    suspend fun setPlaybackSpeed(id: Long, speed: Float)

    @Query("UPDATE watch_record SET aspectRatioMode = :mode WHERE id = :id")
    suspend fun setAspectRatioMode(id: Long, mode: Int)

    @Query(
        """
        UPDATE watch_record
        SET zoomScale = :scale, zoomOffsetX = :offsetX, zoomOffsetY = :offsetY,
            rotationDegrees = :rotation, mirrorH = :mirrorH, mirrorV = :mirrorV
        WHERE id = :id
        """
    )
    suspend fun setVideoTransform(
        id: Long,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
        rotation: Int,
        mirrorH: Boolean,
        mirrorV: Boolean,
    )

    @Query("UPDATE watch_record SET audioTrackIndex = :trackIndex WHERE id = :id")
    suspend fun setAudioTrack(id: Long, trackIndex: Int)

    @Query("UPDATE watch_record SET subtitleOffsetMs = :offsetMs WHERE id = :id")
    suspend fun setSubtitleOffset(id: Long, offsetMs: Long)

    @Query("UPDATE watch_record SET note = :note WHERE id = :id")
    suspend fun setNote(id: Long, note: String?)

    @Query("UPDATE watch_record SET skipIntroMs = :introMs, skipOutroMs = :outroMs WHERE id = :id")
    suspend fun setSkipPoints(id: Long, introMs: Long, outroMs: Long)

    @Query("SELECT COUNT(*) FROM watch_record")
    suspend fun count(): Int

    @Query("SELECT * FROM watch_record WHERE serverId = :serverId ORDER BY lastWatchedAt DESC")
    fun observeServer(serverId: Long): Flow<List<WatchRecordEntity>>
}

@Dao
interface BrowseStateDao {
    @Query("SELECT * FROM browse_state WHERE serverId = :serverId AND dirPath = :dirPath")
    suspend fun get(serverId: Long, dirPath: String): BrowseStateEntity?

    @Upsert
    suspend fun upsert(state: BrowseStateEntity)

    @Query("DELETE FROM browse_state WHERE serverId = :serverId")
    suspend fun clearServer(serverId: Long)

    @Query("DELETE FROM browse_state")
    suspend fun clearAll()
}

@Dao
interface DirectoryEntryCacheDao {
    @Query(
        "SELECT * FROM directory_entry_cache " +
            "WHERE serverId = :serverId AND parentPath = :parentPath " +
            "ORDER BY isDirectory DESC, name COLLATE NOCASE"
    )
    suspend fun list(serverId: Long, parentPath: String): List<DirectoryEntryCacheEntity>

    @Query("DELETE FROM directory_entry_cache WHERE serverId = :serverId AND parentPath = :parentPath")
    suspend fun deleteDirectory(serverId: Long, parentPath: String)

    @Query("DELETE FROM directory_entry_cache WHERE serverId = :serverId")
    suspend fun clearServer(serverId: Long)

    @Query("DELETE FROM directory_entry_cache")
    suspend fun clearAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<DirectoryEntryCacheEntity>)

    @androidx.room.Transaction
    suspend fun replace(
        serverId: Long,
        parentPath: String,
        items: List<DirectoryEntryCacheEntity>,
    ) {
        deleteDirectory(serverId, parentPath)
        if (items.isNotEmpty()) insertAll(items)
    }
}

@Dao
interface TrashItemDao {
    @Query("SELECT * FROM trash_item ORDER BY deletedAt DESC")
    fun observeAll(): Flow<List<TrashItemEntity>>

    @Query("SELECT * FROM trash_item ORDER BY deletedAt DESC")
    suspend fun listAll(): List<TrashItemEntity>

    @Query("SELECT * FROM trash_item WHERE serverId = :serverId AND groupId = :groupId")
    suspend fun listGroup(serverId: Long, groupId: String): List<TrashItemEntity>

    /** 回收站自动清理（D-12），严格限制在单一服务器。 */
    @Query("SELECT * FROM trash_item WHERE serverId = :serverId AND deletedAt < :beforeMs")
    suspend fun listOlderThan(serverId: Long, beforeMs: Long): List<TrashItemEntity>

    @Insert
    suspend fun insert(item: TrashItemEntity): Long

    @Insert
    suspend fun insertAll(items: List<TrashItemEntity>)

    @Query("DELETE FROM trash_item WHERE serverId = :serverId AND groupId = :groupId")
    suspend fun deleteGroup(serverId: Long, groupId: String)

    @Query("UPDATE trash_item SET originalPath = :path WHERE id = :id")
    suspend fun updateOriginalPath(id: Long, path: String)

    @Query("DELETE FROM trash_item WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM trash_item")
    suspend fun count(): Int
}

@Dao
interface MediaMetaDao {
    @Query("SELECT * FROM media_meta WHERE serverId = :serverId AND fullPath = :fullPath")
    suspend fun get(serverId: Long, fullPath: String): MediaMetaEntity?

    @Query("SELECT * FROM media_meta WHERE serverId = :serverId AND fullPath IN (:paths)")
    suspend fun getMany(serverId: Long, paths: List<String>): List<MediaMetaEntity>

    @Upsert
    suspend fun upsert(meta: MediaMetaEntity)

    @Upsert
    suspend fun upsertAll(items: List<MediaMetaEntity>)

    @Query("DELETE FROM media_meta WHERE serverId = :serverId AND fullPath = :fullPath")
    suspend fun deleteByPath(serverId: Long, fullPath: String)

    @Query("DELETE FROM media_meta")
    suspend fun clearAll()
}

@Dao
interface MovieInfoDao {
    @Query("SELECT * FROM movie_info WHERE serverId = :serverId AND fullPath = :fullPath")
    suspend fun get(serverId: Long, fullPath: String): MovieInfoEntity?

    @Query("SELECT * FROM movie_info WHERE serverId = :serverId AND fullPath IN (:paths)")
    suspend fun getMany(serverId: Long, paths: List<String>): List<MovieInfoEntity>

    @Query("SELECT * FROM movie_info WHERE serverId = :serverId")
    suspend fun listServer(serverId: Long): List<MovieInfoEntity>

    @Upsert
    suspend fun upsert(item: MovieInfoEntity): Long

    @Query("DELETE FROM movie_info WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM movie_info WHERE serverId = :serverId")
    suspend fun clearServer(serverId: Long)

    @Query("DELETE FROM movie_info")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM movie_info")
    suspend fun count(): Int

    @Query(
        """
        SELECT movie_info.* FROM movie_info
        INNER JOIN movie_actor ON movie_actor.movieId = movie_info.id
        WHERE movie_actor.actorId = :actorId AND movie_info.serverId = :serverId
        ORDER BY movie_info.releaseDate DESC, movie_info.code
        """
    )
    suspend fun listForActor(serverId: Long, actorId: Long): List<MovieInfoEntity>

    @Query(
        """
        SELECT DISTINCT movie_info.* FROM movie_info
        INNER JOIN movie_actor ON movie_actor.movieId = movie_info.id
        INNER JOIN actor ON actor.id = movie_actor.actorId
        WHERE movie_info.serverId = :serverId AND actor.isFollowed = 1
        ORDER BY movie_info.scrapedAt DESC, movie_info.releaseDate DESC
        LIMIT :limit
        """
    )
    suspend fun listForFollowedActors(serverId: Long, limit: Int = 20): List<MovieInfoEntity>
}

data class ActorStatsRow(
    val id: Long,
    val name: String,
    val nameZh: String?,
    val avatarRelPath: String?,
    val isFollowed: Boolean,
    val movieCount: Int,
    val watchedCount: Int,
)

@Dao
interface ActorDao {
    @Query("SELECT * FROM actor WHERE serverId = :serverId AND name = :name LIMIT 1")
    suspend fun findByName(serverId: Long, name: String): ActorEntity?

    @Query("SELECT * FROM actor WHERE id IN (:ids)")
    suspend fun getMany(ids: List<Long>): List<ActorEntity>

    @Upsert
    suspend fun upsert(actor: ActorEntity): Long

    @Query(
        """
        SELECT actor.id, actor.name, actor.nameZh, actor.avatarRelPath, actor.isFollowed,
               COUNT(DISTINCT movie_actor.movieId) AS movieCount,
               COUNT(DISTINCT CASE WHEN watch_record.watchState = 'COMPLETED'
                                   THEN movie_info.id END) AS watchedCount
        FROM actor
        LEFT JOIN movie_actor ON movie_actor.actorId = actor.id
        LEFT JOIN movie_info ON movie_info.id = movie_actor.movieId
        LEFT JOIN watch_record ON watch_record.serverId = movie_info.serverId
                              AND watch_record.fullPath = movie_info.fullPath
        WHERE actor.serverId = :serverId
        GROUP BY actor.id
        ORDER BY movieCount DESC, actor.name COLLATE NOCASE
        """
    )
    fun observeStats(serverId: Long): Flow<List<ActorStatsRow>>

    @Query("UPDATE actor SET isFollowed = :followed WHERE id = :actorId")
    suspend fun setFollowed(actorId: Long, followed: Boolean)

    @Query(
        """
        DELETE FROM actor
        WHERE serverId = :serverId
          AND id NOT IN (SELECT actorId FROM movie_actor)
        """
    )
    suspend fun deleteOrphans(serverId: Long)

    @Query("DELETE FROM actor")
    suspend fun clearAll()
}

@Dao
interface MovieActorDao {
    @Query("SELECT * FROM movie_actor WHERE movieId IN (:movieIds) ORDER BY orderIndex")
    suspend fun listForMovies(movieIds: List<Long>): List<MovieActorEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MovieActorEntity>)

    @Query("DELETE FROM movie_actor WHERE movieId = :movieId")
    suspend fun deleteForMovie(movieId: Long)

    @Query("DELETE FROM movie_actor")
    suspend fun clearAll()
}

@Dao
interface TagDao {
    @Query("SELECT * FROM tag ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tag ORDER BY sortOrder, name COLLATE NOCASE")
    suspend fun listAll(): List<TagEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(tag: TagEntity): Long

    @Update
    suspend fun update(tag: TagEntity)

    @Delete
    suspend fun delete(tag: TagEntity)

    @Query(
        """
        SELECT tag.* FROM tag
        INNER JOIN record_tag ON record_tag.tagId = tag.id
        WHERE record_tag.recordId = :recordId
        ORDER BY tag.sortOrder, tag.name COLLATE NOCASE
        """
    )
    fun observeForRecord(recordId: Long): Flow<List<TagEntity>>

    @Query(
        """
        SELECT tag.* FROM tag
        INNER JOIN record_tag ON record_tag.tagId = tag.id
        WHERE record_tag.recordId = :recordId
        ORDER BY tag.sortOrder, tag.name COLLATE NOCASE
        """
    )
    suspend fun listForRecord(recordId: Long): List<TagEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addToRecord(link: RecordTagEntity)

    @Query("DELETE FROM record_tag WHERE recordId = :recordId AND tagId = :tagId")
    suspend fun removeFromRecord(recordId: Long, tagId: Long)

    @Query("DELETE FROM record_tag WHERE recordId = :recordId")
    suspend fun removeRecordLinks(recordId: Long)

    @Query("DELETE FROM record_tag WHERE tagId = :tagId")
    suspend fun removeAllLinks(tagId: Long)

    @Query("SELECT recordId FROM record_tag WHERE tagId = :tagId")
    suspend fun recordIdsForTag(tagId: Long): List<Long>

    @Query("SELECT * FROM record_tag")
    suspend fun listAllLinks(): List<RecordTagEntity>

    @Query("DELETE FROM record_tag")
    suspend fun clearAllLinks()
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmark WHERE recordId = :recordId ORDER BY positionMs")
    fun observe(recordId: Long): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmark WHERE recordId = :recordId ORDER BY positionMs")
    suspend fun list(recordId: Long): List<BookmarkEntity>

    @Query("SELECT * FROM bookmark ORDER BY id")
    suspend fun listAll(): List<BookmarkEntity>

    @Query("DELETE FROM bookmark")
    suspend fun clearAll()

    @Insert
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Delete
    suspend fun delete(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmark WHERE recordId = :recordId")
    suspend fun deleteForRecord(recordId: Long)
}

@Dao
interface AudioDeviceProfileDao {
    @Query("SELECT * FROM audio_device_profile WHERE deviceKey = :deviceKey")
    suspend fun get(deviceKey: String): AudioDeviceProfileEntity?

    @Query("SELECT * FROM audio_device_profile ORDER BY lastUsedAt DESC")
    fun observeAll(): Flow<List<AudioDeviceProfileEntity>>

    @Upsert
    suspend fun upsert(profile: AudioDeviceProfileEntity)

    @Query("DELETE FROM audio_device_profile WHERE deviceKey = :deviceKey")
    suspend fun delete(deviceKey: String)
}
