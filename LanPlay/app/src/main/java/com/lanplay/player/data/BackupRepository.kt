package com.lanplay.player.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.lanplay.player.data.db.BookmarkDao
import com.lanplay.player.data.db.LanPlayDatabase
import com.lanplay.player.data.db.BookmarkEntity
import com.lanplay.player.data.db.RecordTagEntity
import com.lanplay.player.data.db.TagDao
import com.lanplay.player.data.db.TagEntity
import com.lanplay.player.data.db.WatchRecordDao
import com.lanplay.player.data.db.WatchRecordEntity
import com.lanplay.player.data.db.WatchState
import com.lanplay.player.data.prefs.SettingsRepository
import com.lanplay.player.smb.AuthMode
import com.lanplay.player.smb.SmbTarget
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal fun hasUniqueBackupTargets(targets: List<Pair<Long, String>>): Boolean =
    targets.size == targets.toSet().size

internal fun isSupportedBackupPlaybackState(
    subtitleOffsetMs: Long,
    aspectRatioMode: Int,
): Boolean = subtitleOffsetMs in -60_000L..60_000L && aspectRatioMode in 0..5

internal fun createBackupJsonCodec(): Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    explicitNulls = false
    // 备份格式版本本身有默认值；若沿用 kotlinx.serialization 的
    // encodeDefaults=false，导出的 JSON 会省略 version，未来版本便无法
    // 在读取前可靠判断兼容性。备份文件优先保证契约明确，少量体积不重要。
    encodeDefaults = true
}

@Serializable
private data class BackupBundle(
    val version: Int = 2,
    val generatedAt: String,
    val settings: BackupSettings,
    val servers: List<BackupServer>,
    val records: List<BackupRecord>,
    val tags: List<BackupTag>,
    val links: List<BackupLink>,
    val bookmarks: List<BackupBookmark>,
)

@Serializable
internal data class BackupSettings(
    val theme: String,
    val darkMode: String,
    val homeLayout: String = "GALLERY",
    val resumePolicy: String,
    val seekSeconds: Int,
    val doubleTapSeconds: Int,
    val longPressSpeed: Float,
    val autoPlayNext: Boolean,
    val fadePlayback: Boolean,
    val seekPreviewEnabled: Boolean = false,
    val handedness: String,
    val playerKernel: String,
    val orientation: String,
    val prefetchMb: Int,
    val readBlockKb: Int,
    val concurrentReads: Int,
    val decoderMode: String,
    val subtitleSize: Int,
    val subtitleTextColor: String,
    val subtitleEdgeColor: String,
    val subtitleEdgeWidth: Int,
    val subtitleBackground: Boolean,
    val subtitleBottom: Int,
    val subtitleFont: String,
    val audioBoostPercent: Int = 100,
    val loudnessNormalization: Boolean = false,
    val equalizerPreset: String = "flat",
    val equalizerBands: List<Float> = List(10) { 0f },
)

@Serializable
private data class BackupServer(
    val id: Long,
    val host: String,
    val share: String,
    val port: Int = 445,
    val domain: String? = null,
    val username: String = "",
    val authMode: String = "ACCOUNT",
)

@Serializable
private data class BackupRecord(
    val oldId: Long,
    val serverId: Long,
    val fullPath: String,
    val fileName: String,
    val fileSize: Long,
    val lastModified: Long,
    val positionMs: Long,
    val durationMs: Long,
    val progressPercent: Float,
    val watchState: String,
    val manuallyMarked: Boolean,
    val firstWatchedAt: Long,
    val lastWatchedAt: Long,
    val playCount: Int,
    val totalWatchedMs: Long,
    val playbackSpeed: Float,
    val subtitlePath: String?,
    val subtitleCharset: String?,
    val subtitleEnabled: Boolean,
    val subtitleOffsetMs: Long,
    val audioTrackIndex: Int,
    val aspectRatioMode: Int,
    val zoomScale: Float,
    val zoomOffsetX: Float,
    val zoomOffsetY: Float,
    val rotationDegrees: Int,
    val mirrorH: Boolean,
    val mirrorV: Boolean,
    val skipIntroMs: Long,
    val skipOutroMs: Long,
    val isFavorite: Boolean,
    val rating: Int,
    val note: String?,
)

@Serializable
private data class BackupTag(
    val oldId: Long,
    val name: String,
    val colorHex: String,
    val sortOrder: Int,
)

@Serializable
private data class BackupLink(val recordId: Long, val tagId: Long)

@Serializable
private data class BackupBookmark(
    val recordId: Long,
    val positionMs: Long,
    val label: String?,
    val createdAt: Long,
)

data class BackupResult(
    val records: Int,
    val tags: Int,
    val bookmarks: Int,
    val skipped: Int,
    val serversCreated: Int = 0,
    val settingsImported: Boolean = true,
)

private data class BackupRoomSnapshot(
    val servers: List<SavedServer>,
    val records: List<WatchRecordEntity>,
    val tags: List<TagEntity>,
    val links: List<RecordTagEntity>,
    val bookmarks: List<BookmarkEntity>,
)

@Singleton
class BackupRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val servers: ServerRepository,
    private val watchDao: WatchRecordDao,
    private val tagDao: TagDao,
    private val bookmarkDao: BookmarkDao,
    private val settings: SettingsRepository,
    private val database: LanPlayDatabase,
) {
    private val json = createBackupJsonCodec()

    suspend fun exportTo(uri: Uri): Int = withContext(Dispatchers.IO) {
        val player = settings.playerSettings.first()
        val appearance = settings.appearanceSettings.first()
        val io = settings.ioSettings.first()
        val subtitle = settings.subtitleStyleSettings.first()
        val audio = settings.audioEnhancementSettings.first()
        val snapshot = database.withTransaction {
            BackupRoomSnapshot(
                servers = servers.listAll(),
                records = watchDao.listAll(),
                tags = tagDao.listAll(),
                links = tagDao.listAllLinks(),
                bookmarks = bookmarkDao.listAll(),
            )
        }
        val bundle = BackupBundle(
            generatedAt = Instant.now().toString(),
            settings = BackupSettings(
                theme = appearance.themeId,
                darkMode = appearance.darkMode.name,
                homeLayout = appearance.homeLayout.name,
                resumePolicy = player.resumePolicy.name,
                seekSeconds = player.seekSensitivitySeconds,
                doubleTapSeconds = player.doubleTapSeconds,
                longPressSpeed = player.longPressSpeed,
                autoPlayNext = player.autoPlayNext,
                fadePlayback = player.fadePlayback,
                seekPreviewEnabled = player.seekPreviewEnabled,
                handedness = player.handedness.name,
                playerKernel = player.playerKernel.name,
                orientation = player.orientationMode.name,
                prefetchMb = io.prefetchMb,
                readBlockKb = io.readBlockKb,
                concurrentReads = io.concurrentReads,
                decoderMode = io.decoderMode.name,
                subtitleSize = subtitle.sizePercent,
                subtitleTextColor = subtitle.textColor,
                subtitleEdgeColor = subtitle.edgeColor,
                subtitleEdgeWidth = subtitle.edgeWidth,
                subtitleBackground = subtitle.backgroundEnabled,
                subtitleBottom = subtitle.bottomPaddingPercent,
                subtitleFont = subtitle.font.name,
                audioBoostPercent = audio.volumeBoostPercent,
                loudnessNormalization = audio.loudnessNormalization,
                equalizerPreset = audio.equalizerPreset,
                equalizerBands = audio.equalizerBands,
            ),
            servers = snapshot.servers.map {
                BackupServer(
                    it.id,
                    it.target.host,
                    it.target.share,
                    it.target.port,
                    it.target.domain,
                    it.target.username,
                    it.target.authMode.name,
                )
            },
            records = snapshot.records.map(::toBackup),
            tags = snapshot.tags.map {
                BackupTag(it.id, it.name, it.colorHex, it.sortOrder)
            },
            links = snapshot.links.map { BackupLink(it.recordId, it.tagId) },
            bookmarks = snapshot.bookmarks.map {
                BackupBookmark(it.recordId, it.positionMs, it.label, it.createdAt)
            },
        )
        context.contentResolver.openOutputStream(uri, "wt").use { output ->
            requireNotNull(output) { "无法写入所选文件" }
            output.writer(Charsets.UTF_8).use { it.write(json.encodeToString(bundle)) }
        }
        snapshot.records.size
    }

    suspend fun importFrom(uri: Uri): BackupResult = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取所选文件" }
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_BACKUP_BYTES) { "备份文件超过 ${MAX_BACKUP_BYTES / 1024 / 1024} MB 上限" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        val bundle = json.decodeFromString<BackupBundle>(bytes.toString(Charsets.UTF_8))
        bytes.fill(0)
        require(bundle.version in 1..2) { "不支持这个备份版本" }
        validateBundle(bundle)

        fun SavedServer.matches(old: BackupServer): Boolean =
            target.host.equals(old.host, true) &&
                target.share.equals(old.share, true) &&
                (bundle.version == 1 || (
                    target.port == old.port &&
                        target.domain.orEmpty().equals(old.domain.orEmpty(), true) &&
                        target.username.equals(old.username, true) &&
                        target.authMode.name == old.authMode
                    ))

        val createdServerIds = mutableListOf<Long>()
        try {
        var localServers = servers.listAll()
        var serversCreated = 0
        // 恢复到空库时不能把全部记录跳过。备份不含密码，因此只创建无密码占位，
        // 用户恢复后在连接设置中重新填写凭据。
        bundle.servers.forEach { old ->
            if (localServers.none { it.matches(old) }) {
                val createdId = servers.save(
                    target = SmbTarget(
                        host = old.host,
                        port = old.port,
                        share = old.share,
                        domain = old.domain,
                        username = old.username,
                        password = "",
                        authMode = AuthMode.valueOf(old.authMode),
                    ),
                    displayName = old.host,
                )
                createdServerIds += createdId
                serversCreated++
                localServers = servers.listAll()
            }
        }
        val serverMap = bundle.servers.mapNotNull { old ->
            localServers.filter { it.matches(old) }.singleOrNull()?.let { old.id to it.id }
        }.toMap()

        val result = database.withTransaction {
            val recordMap = mutableMapOf<Long, Long>()
            var skipped = 0
            for (record in bundle.records) {
                val serverId = serverMap[record.serverId]
                if (serverId == null) {
                    skipped++
                    continue
                }
                val local = watchDao.get(serverId, record.fullPath)
                if (local != null && local.lastWatchedAt > record.lastWatchedAt) {
                    recordMap[record.oldId] = local.id
                    continue
                }
                val entity = record.toEntity(serverId, local?.id ?: 0)
                val resultId = watchDao.upsert(entity)
                recordMap[record.oldId] = if (entity.id == 0L) resultId else entity.id
            }

            val existingTags = tagDao.listAll().associateBy { it.name.lowercase() }.toMutableMap()
            val tagMap = mutableMapOf<Long, Long>()
            for (tag in bundle.tags) {
                val key = tag.name.lowercase()
                val existing = existingTags[key]
                val id = existing?.id ?: tagDao.insert(
                    TagEntity(name = tag.name, colorHex = tag.colorHex, sortOrder = tag.sortOrder)
                ).also { inserted ->
                    existingTags[key] = TagEntity(
                        id = inserted,
                        name = tag.name,
                        colorHex = tag.colorHex,
                        sortOrder = tag.sortOrder,
                    )
                }
                tagMap[tag.oldId] = id
            }
            bundle.links.forEach {
                val recordId = recordMap[it.recordId]
                val tagId = tagMap[it.tagId]
                if (recordId != null && tagId != null) {
                    tagDao.addToRecord(RecordTagEntity(recordId, tagId))
                }
            }
            var importedBookmarks = 0
            val bookmarkKeys = bookmarkDao.listAll()
                .mapTo(hashSetOf()) { Triple(it.recordId, it.positionMs, it.label) }
            bundle.bookmarks.forEach {
                val recordId = recordMap[it.recordId] ?: return@forEach
                val key = Triple(recordId, it.positionMs, it.label)
                if (bookmarkKeys.add(key)) {
                    bookmarkDao.insert(
                        BookmarkEntity(
                            recordId = recordId,
                            positionMs = it.positionMs,
                            label = it.label,
                            createdAt = it.createdAt,
                        )
                    )
                    importedBookmarks++
                }
            }
            BackupResult(
                records = recordMap.size,
                tags = tagMap.size,
                bookmarks = importedBookmarks,
                skipped = skipped,
                serversCreated = serversCreated,
            )
        }
        // DataStore 与 Room 没有共同事务；所有设置已在 validateBundle 中验证，
        // 因此放在 Room 成功提交后，避免坏数据留下“设置已改、记录半导入”。
        val settingsImported = runCatching { importSettings(bundle.settings) }.isSuccess
        result.copy(settingsImported = settingsImported)
        } catch (t: Throwable) {
            withContext(NonCancellable) {
                createdServerIds.asReversed().forEach { id ->
                    try {
                        servers.delete(id)
                    } catch (cleanup: Throwable) {
                        t.addSuppressed(cleanup)
                    }
                }
            }
            throw t
        }
    }

    private fun validateBundle(bundle: BackupBundle) {
        require(bundle.servers.size <= MAX_SERVERS)
        require(bundle.records.size <= MAX_RECORDS)
        require(bundle.tags.size <= MAX_TAGS)
        require(bundle.links.size <= MAX_LINKS)
        require(bundle.bookmarks.size <= MAX_BOOKMARKS)
        val serverIds = bundle.servers.map { it.id }
        require(serverIds.size == serverIds.toSet().size)
        bundle.servers.forEach {
            require(it.host.isNotBlank() && it.host.length <= 255)
            require(it.share.isNotBlank() && it.share.length <= 255)
            require(it.port in 1..65535)
            require(it.username.length <= 256 && (it.domain?.length ?: 0) <= 256)
            com.lanplay.player.smb.AuthMode.valueOf(it.authMode)
        }
        val recordIds = bundle.records.map { it.oldId }
        require(recordIds.size == recordIds.toSet().size)
        val serverIdSet = serverIds.toSet()
        require(hasUniqueBackupTargets(bundle.records.map { it.serverId to it.fullPath }))
        bundle.records.forEach {
            require(it.serverId in serverIdSet)
            require(it.fullPath.isNotBlank())
            require(it.fullPath.length <= MAX_PATH_LENGTH && it.fileName.length <= MAX_NAME_LENGTH)
            require(it.positionMs >= 0 && it.durationMs >= 0 && it.fileSize >= 0)
            require(it.lastModified >= 0 && it.firstWatchedAt >= 0 && it.lastWatchedAt >= 0)
            require(it.totalWatchedMs >= 0)
            require(it.skipIntroMs >= 0 && it.skipOutroMs >= 0)
            require(it.progressPercent.isFinite() && it.progressPercent in 0f..1f)
            require(it.playbackSpeed.isFinite() && it.playbackSpeed in 0.5f..3f)
            require(it.zoomScale.isFinite() && it.zoomScale in 1f..5f)
            require(it.zoomOffsetX.isFinite() && it.zoomOffsetY.isFinite())
            require(it.rating in 0..5 && it.playCount >= 0)
            require(it.audioTrackIndex >= -1)
            require(isSupportedBackupPlaybackState(it.subtitleOffsetMs, it.aspectRatioMode))
            require(it.rotationDegrees in setOf(0, 90, 180, 270))
            require((it.subtitlePath?.length ?: 0) <= MAX_PATH_LENGTH)
            require((it.subtitleCharset?.length ?: 0) <= 80)
            require(it.note == null || it.note.length <= 2_000)
            WatchState.valueOf(it.watchState)
        }
        val tagIds = bundle.tags.map { it.oldId }
        require(tagIds.size == tagIds.toSet().size)
        bundle.tags.forEach {
            require(it.name.isNotBlank() && it.name.length <= 30)
            require(it.colorHex.matches(Regex("#[0-9A-Fa-f]{6}")))
        }
        val recordIdSet = recordIds.toSet()
        val tagIdSet = tagIds.toSet()
        bundle.links.forEach {
            require(it.recordId in recordIdSet && it.tagId in tagIdSet)
        }
        bundle.bookmarks.forEach {
            require(it.recordId in recordIdSet)
            require(it.positionMs >= 0 && it.createdAt >= 0 && (it.label?.length ?: 0) <= 80)
        }
        val value = bundle.settings
        com.lanplay.player.data.prefs.DarkMode.valueOf(value.darkMode)
        com.lanplay.player.data.prefs.HomeLayout.valueOf(value.homeLayout)
        com.lanplay.player.data.prefs.ResumePolicy.valueOf(value.resumePolicy)
        com.lanplay.player.data.prefs.Handedness.valueOf(value.handedness)
        com.lanplay.player.data.prefs.PlayerKernel.valueOf(value.playerKernel)
        com.lanplay.player.data.prefs.OrientationMode.valueOf(value.orientation)
        com.lanplay.player.data.prefs.SubtitleFont.valueOf(value.subtitleFont)
        com.lanplay.player.data.prefs.DecoderMode.valueOf(value.decoderMode)
        require(value.theme.length <= 80)
        require(value.seekSeconds in 10..14_400)
        require(value.doubleTapSeconds in intArrayOf(5, 10, 15, 30))
        require(value.longPressSpeed.isFinite() && value.longPressSpeed in listOf(1.5f, 2f, 2.5f, 3f))
        require(value.prefetchMb in intArrayOf(16, 32, 48, 64))
        require(value.readBlockKb in intArrayOf(512, 1024, 2048))
        require(value.concurrentReads in intArrayOf(2, 4, 6, 8))
        require(value.subtitleSize in 50..250)
        val colorPattern = Regex("#[0-9A-Fa-f]{6}(?:[0-9A-Fa-f]{2})?")
        require(value.subtitleTextColor.matches(colorPattern))
        require(value.subtitleEdgeColor.matches(colorPattern))
        require(value.subtitleEdgeWidth in 0..4 && value.subtitleBottom in 0..35)
        require(value.audioBoostPercent in 100..200)
        require(value.equalizerPreset.length <= 40)
        require(value.equalizerBands.size == 10)
        require(value.equalizerBands.all { it.isFinite() && it in -12f..12f })
    }

    private suspend fun importSettings(value: BackupSettings) {
        settings.importValidatedBackupSettings(value)
    }

    private fun toBackup(r: WatchRecordEntity) = BackupRecord(
        r.id, r.serverId, r.fullPath, r.fileName, r.fileSize, r.lastModified,
        r.positionMs, r.durationMs, r.progressPercent, r.watchState.name,
        r.manuallyMarked, r.firstWatchedAt, r.lastWatchedAt, r.playCount,
        r.totalWatchedMs, r.playbackSpeed, r.subtitlePath, r.subtitleCharset,
        r.subtitleEnabled, r.subtitleOffsetMs, r.audioTrackIndex, r.aspectRatioMode,
        r.zoomScale, r.zoomOffsetX, r.zoomOffsetY, r.rotationDegrees, r.mirrorH,
        r.mirrorV, r.skipIntroMs, r.skipOutroMs, r.isFavorite, r.rating, r.note,
    )

    private fun BackupRecord.toEntity(mappedServerId: Long, id: Long) = WatchRecordEntity(
        id = id,
        serverId = mappedServerId,
        fullPath = fullPath,
        fileName = fileName,
        fileSize = fileSize,
        lastModified = lastModified,
        positionMs = positionMs,
        durationMs = durationMs,
        progressPercent = progressPercent,
        watchState = WatchState.valueOf(watchState),
        manuallyMarked = manuallyMarked,
        firstWatchedAt = firstWatchedAt,
        lastWatchedAt = lastWatchedAt,
        playCount = playCount,
        totalWatchedMs = totalWatchedMs,
        playbackSpeed = playbackSpeed,
        subtitlePath = subtitlePath,
        subtitleCharset = subtitleCharset,
        subtitleEnabled = subtitleEnabled,
        subtitleOffsetMs = subtitleOffsetMs,
        audioTrackIndex = audioTrackIndex,
        aspectRatioMode = aspectRatioMode,
        zoomScale = zoomScale,
        zoomOffsetX = zoomOffsetX,
        zoomOffsetY = zoomOffsetY,
        rotationDegrees = rotationDegrees,
        mirrorH = mirrorH,
        mirrorV = mirrorV,
        skipIntroMs = skipIntroMs,
        skipOutroMs = skipOutroMs,
        isFavorite = isFavorite,
        rating = rating,
        note = note,
    )

    private companion object {
        const val MAX_BACKUP_BYTES = 16 * 1024 * 1024
        const val MAX_SERVERS = 100
        const val MAX_RECORDS = 100_000
        const val MAX_TAGS = 10_000
        const val MAX_LINKS = 500_000
        const val MAX_BOOKMARKS = 500_000
        const val MAX_PATH_LENGTH = 4_096
        const val MAX_NAME_LENGTH = 512
    }
}
