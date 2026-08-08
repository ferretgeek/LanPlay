package com.lanplay.player.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 需求 §7.4：未看 / 看了一半 / 已看完 */
enum class WatchState { UNWATCHED, IN_PROGRESS, COMPLETED }

enum class ItemType { VIDEO, SUBTITLE }

/**
 * 服务器配置（需求 C-05 多服务器）。
 * 密码经 Keystore AES-256-GCM 加密后存密文与 IV，明文不落盘（C-06）。
 */
@Entity(tableName = "smb_server")
data class SmbServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val host: String,
    val hostName: String? = null,
    val port: Int = 445,
    val shareName: String,
    val domain: String? = null,
    val username: String = "",
    val encryptedPassword: ByteArray = ByteArray(0),
    val passwordIv: ByteArray = ByteArray(0),
    val authMode: String = "ACCOUNT",
    val defaultPath: String = "",
    val sortOrder: Int = 0,
    val createdAt: Long = 0,
) {
    // ByteArray 字段让 data class 的自动 equals/hashCode 变成引用比较，这里按内容比
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SmbServerEntity) return false
        return id == other.id &&
            displayName == other.displayName &&
            host == other.host &&
            hostName == other.hostName &&
            port == other.port &&
            shareName == other.shareName &&
            domain == other.domain &&
            username == other.username &&
            encryptedPassword.contentEquals(other.encryptedPassword) &&
            passwordIv.contentEquals(other.passwordIv) &&
            authMode == other.authMode &&
            defaultPath == other.defaultPath &&
            sortOrder == other.sortOrder &&
            createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + host.hashCode()
        result = 31 * result + port
        result = 31 * result + shareName.hashCode()
        result = 31 * result + username.hashCode()
        result = 31 * result + encryptedPassword.contentHashCode()
        result = 31 * result + passwordIv.contentHashCode()
        return result
    }
}

/**
 * 观看记录（需求 W 组核心）。
 *
 * 主键用自增 id，但 (serverId, fullPath) 上有唯一索引——这是记录的业务标识。
 * (fileSize, lastModified) 索引供 W-07 的重命名探测使用：PC 上改了文件名后，
 * 靠大小与修改时间把旧记录迁移过去，不让进度丢失。
 */
@Entity(
    tableName = "watch_record",
    indices = [
        Index(value = ["serverId", "fullPath"], unique = true),
        Index(value = ["fileSize", "lastModified"]),
        Index(value = ["lastWatchedAt"]),
        Index(value = ["watchState"]),
    ],
)
data class WatchRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long,
    val fullPath: String,
    val fileName: String,
    val fileSize: Long,
    val lastModified: Long,

    // 进度与状态
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val progressPercent: Float = 0f,
    val watchState: WatchState = WatchState.UNWATCHED,
    /** 用户手动标记过状态，此后不再按进度自动推算（W-08） */
    val manuallyMarked: Boolean = false,

    // 统计
    val firstWatchedAt: Long = 0,
    val lastWatchedAt: Long = 0,
    val playCount: Int = 0,
    val totalWatchedMs: Long = 0,

    // 播放参数记忆（W-12）
    val playbackSpeed: Float = 1.0f,
    val subtitlePath: String? = null,
    val subtitleCharset: String? = null,
    val subtitleEnabled: Boolean = true,
    val subtitleOffsetMs: Long = 0,
    val audioTrackIndex: Int = -1,
    val aspectRatioMode: Int = 0,
    val zoomScale: Float = 1f,
    val zoomOffsetX: Float = 0f,
    val zoomOffsetY: Float = 0f,
    val rotationDegrees: Int = 0,
    val mirrorH: Boolean = false,
    val mirrorV: Boolean = false,
    val skipIntroMs: Long = 0,
    val skipOutroMs: Long = 0,

    // 个人整理（O 组，第 4 阶段用，字段先留好避免再次迁移）
    val isFavorite: Boolean = false,
    val rating: Int = 0,
    val note: String? = null,
)

/** 浏览状态：滚动位置、排序、视图模式，按目录分别记忆（B-01 / C-11） */
@Entity(
    tableName = "browse_state",
    indices = [Index(value = ["serverId", "dirPath"], unique = true)],
)
data class BrowseStateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long,
    val dirPath: String,
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
    val sortField: String = "LAST_MODIFIED",
    val sortAscending: Boolean = false,
    val viewMode: String = "LIST",
    val updatedAt: Long = 0,
)

/**
 * 目录快照（C-12）：先从本地显示上次结果，再在后台用 SMB 刷新。
 * 仅缓存文件属性，不缓存文件内容；主键保证同一目录内可安全重复刷新。
 */
@Entity(
    tableName = "directory_entry_cache",
    primaryKeys = ["serverId", "parentPath", "relativePath"],
    indices = [Index(value = ["serverId", "parentPath"])],
)
data class DirectoryEntryCacheEntity(
    val serverId: Long,
    val parentPath: String,
    val relativePath: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
)

/**
 * 回收站条目（D-02~D-05）。
 * groupId 把同一次删除的视频与字幕绑在一起，还原时整组回去。
 */
@Entity(tableName = "trash_item", indices = [Index("groupId"), Index("serverId")])
data class TrashItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long,
    val originalPath: String,
    val trashPath: String,
    val fileName: String,
    val fileSize: Long,
    val deletedAt: Long,
    val groupId: String,
    val itemType: ItemType,
)

/** 文件元数据缓存（C-15），避免每次进目录都重新探测 */
@Entity(
    tableName = "media_meta",
    indices = [Index(value = ["serverId", "fullPath"], unique = true)],
)
data class MediaMetaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long,
    val fullPath: String,
    val fileName: String,
    val fileSize: Long,
    val lastModified: Long,
    val isDirectory: Boolean = false,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val bitrate: Long? = null,
    /** 供刷新率适配（P-15）判断该切到多少 Hz */
    val frameRate: Float? = null,
    val thumbnailPath: String? = null,
    val spriteSheetPath: String? = null,
    val probedAt: Long = 0,
    val probeFailed: Boolean = false,
)

@Entity(tableName = "tag", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorHex: String,
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "record_tag",
    primaryKeys = ["recordId", "tagId"],
    indices = [Index("tagId")],
)
data class RecordTagEntity(val recordId: Long, val tagId: Long)

@Entity(tableName = "bookmark", indices = [Index("recordId")])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordId: Long,
    val positionMs: Long,
    val label: String? = null,
    val thumbnailPath: String? = null,
    val createdAt: Long,
)

@Entity(
    tableName = "movie_info",
    indices = [
        Index(value = ["serverId", "fullPath"], unique = true),
        Index(value = ["code"]),
    ],
)
data class MovieInfoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long,
    val fullPath: String,
    val code: String,
    val title: String? = null,
    val titleZh: String? = null,
    val releaseDate: String? = null,
    val runtimeMin: Int? = null,
    val studio: String? = null,
    val label: String? = null,
    val series: String? = null,
    val genresJson: String = "[]",
    val posterRelPath: String? = null,
    val coverRelPath: String? = null,
    val source: String? = null,
    val scrapedAt: Long = 0,
    val userOverride: Boolean = false,
)

@Entity(
    tableName = "actor",
    indices = [
        Index(value = ["serverId", "name"], unique = true),
        Index("serverId"),
    ],
)
data class ActorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long,
    val name: String,
    val nameZh: String? = null,
    val avatarRelPath: String? = null,
    val isFollowed: Boolean = false,
)

@Entity(
    tableName = "movie_actor",
    primaryKeys = ["movieId", "actorId"],
    indices = [Index("actorId")],
)
data class MovieActorEntity(
    val movieId: Long,
    val actorId: Long,
    val isMain: Boolean = false,
    val orderIndex: Int = 0,
)

@Entity(tableName = "audio_device_profile")
data class AudioDeviceProfileEntity(
    @PrimaryKey val deviceKey: String,
    val displayName: String,
    val outputType: String,
    val codec: String? = null,
    val audioDelayMs: Long = 0,
    val lastUsedAt: Long = 0,
)
