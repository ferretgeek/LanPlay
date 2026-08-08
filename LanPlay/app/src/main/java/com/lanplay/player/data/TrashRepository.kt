package com.lanplay.player.data

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.lanplay.player.data.db.ItemType
import com.lanplay.player.data.db.MediaMetaDao
import com.lanplay.player.data.db.TrashItemDao
import com.lanplay.player.data.db.TrashItemEntity
import com.lanplay.player.data.db.WatchRecordDao
import com.lanplay.player.data.db.BookmarkDao
import com.lanplay.player.data.db.TagDao
import com.lanplay.player.smb.SUBTITLE_EXTENSIONS
import com.lanplay.player.smb.SmbConnectionManager
import com.lanplay.player.smb.SmbFileRepository
import com.lanplay.player.smb.SmbTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.EnumSet
import java.util.UUID
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class TrashMoveFailure(
    val fileName: String,
    val originalPath: String,
    val reason: String,
)

data class TrashMoveResult(
    val videoMoved: Boolean,
    val movedItems: List<TrashItemEntity>,
    val failures: List<TrashMoveFailure>,
) {
    val isPartial: Boolean get() = videoMoved && failures.isNotEmpty()
    val primaryFailure: TrashMoveFailure? get() = failures.firstOrNull()
}

/**
 * 回收站编排（D-01~D-06）：只做同共享内 rename，不复制文件。
 * 视频与字幕用 groupId 绑定，还原和彻底删除始终整组执行。
 */
@Singleton
class TrashRepository @Inject constructor(
    private val connections: SmbConnectionManager,
    private val files: SmbFileRepository,
    private val trashDao: TrashItemDao,
    private val watchDao: WatchRecordDao,
    private val mediaMetaDao: MediaMetaDao,
    private val bookmarkDao: BookmarkDao,
    private val tagDao: TagDao,
) {
    fun observeAll(): Flow<List<TrashItemEntity>> = trashDao.observeAll()
    suspend fun listAll(): List<TrashItemEntity> = trashDao.listAll()

    suspend fun moveToTrash(
        serverId: Long,
        target: SmbTarget,
        videoPath: String,
    ): TrashMoveResult = withContext(Dispatchers.IO) {
        val stat = files.stat(target, videoPath) ?: error("文件已不存在")
        val dir = videoPath.substringBeforeLast('/', "")
        val base = stat.name.substringBeforeLast('.')
        val subtitles = files.list(target, dir)
            .filter {
                !it.isDirectory &&
                    it.extension in SUBTITLE_EXTENSIONS &&
                    subtitleMatches(base, it.name.substringBeforeLast('.'))
            }
        val sources = listOf(stat) + subtitles
        val groupId = UUID.randomUUID().toString()
        val deletedAt = System.currentTimeMillis()

        connections.withShare(target, SmbConnectionManager.Channel.AUX) { share ->
            if (!share.folderExists(TRASH_DIR)) share.mkdir(TRASH_DIR)
        }

        val moved = mutableListOf<TrashItemEntity>()
        val failures = mutableListOf<TrashMoveFailure>()
        for (entry in sources) {
            val prefix = "${deletedAt}_${groupId.take(8)}_"
            val trashPath = "$TRASH_DIR/$prefix${entry.name}"
            val movedItem = TrashItemEntity(
                serverId = serverId,
                originalPath = entry.relativePath,
                trashPath = trashPath,
                fileName = entry.name,
                fileSize = entry.size,
                deletedAt = deletedAt,
                groupId = groupId,
                itemType = if (entry.relativePath == videoPath) ItemType.VIDEO else ItemType.SUBTITLE,
            )
            // 先写意图再做远端 rename：进程在 rename 后被杀时，恢复入口已经存在。
            val insertedId = trashDao.insert(movedItem)
            val tracked = movedItem.copy(id = insertedId)
            try {
                rename(target, entry.relativePath, trashPath)
                moved += tracked
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // 只有能明确确认“源仍在且目标不在”时才撤销意图；断网等歧义状态
                // 保留记录，后续 reconcile 会继续判定，绝不制造无索引孤儿。
                val sourceExists = existsOrNull(target, entry.relativePath)
                val trashExists = existsOrNull(target, trashPath)
                when {
                    trashExists == true && sourceExists != true -> moved += tracked
                    sourceExists == true && trashExists == false -> trashDao.deleteById(insertedId)
                    else -> Unit
                }
                if (tracked !in moved) {
                    failures += TrashMoveFailure(
                        fileName = entry.name,
                        originalPath = entry.relativePath,
                        reason = t.message ?: "移动结果无法确认，请检查网络后重试",
                    )
                    // 视频没有确认移走时，不再处理字幕，也不能提前清理本机观看数据。
                    if (tracked.itemType == ItemType.VIDEO) {
                        return@withContext TrashMoveResult(
                            videoMoved = false,
                            movedItems = moved,
                            failures = failures,
                        )
                    }
                }
            }
        }
        val videoMoved = moved.any { it.itemType == ItemType.VIDEO }
        if (videoMoved) cleanupLocalData(serverId, videoPath)
        TrashMoveResult(videoMoved, moved, failures)
    }

    private suspend fun cleanupLocalData(serverId: Long, videoPath: String) {
        val record = watchDao.get(serverId, videoPath)
        if (record != null) {
            bookmarkDao.deleteForRecord(record.id)
            tagDao.removeRecordLinks(record.id)
        }
        mediaMetaDao.get(serverId, videoPath)?.let { meta ->
            listOfNotNull(meta.thumbnailPath, meta.spriteSheetPath).forEach { path ->
                runCatching { File(path).takeIf { it.isFile }?.delete() }
            }
        }
        watchDao.deleteByPath(serverId, videoPath)
        mediaMetaDao.deleteByPath(serverId, videoPath)
    }

    private suspend fun existsOrNull(target: SmbTarget, path: String): Boolean? = try {
        files.exists(target, path)
    } catch (check: Throwable) {
        if (check is CancellationException) throw check
        null
    }

    suspend fun hasRestoreConflict(
        serverId: Long,
        target: SmbTarget,
        groupId: String,
    ): Boolean = withContext(Dispatchers.IO) {
        reconcileGroup(serverId, target, groupId)
        trashDao.listGroup(serverId, groupId).any { files.exists(target, it.originalPath) }
    }

    suspend fun restore(
        serverId: Long,
        target: SmbTarget,
        groupId: String,
        renameOnConflict: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        reconcileGroup(serverId, target, groupId)
        var items = trashDao.listGroup(serverId, groupId)
        require(items.isNotEmpty()) { "回收站记录已不存在" }
        var destinations = items.associateWith { it.originalPath }
        val hasConflict = items.any { files.exists(target, it.originalPath) }
        if (hasConflict && !renameOnConflict) {
            error("原位置已有同名文件，请选择改名后还原")
        }
        if (hasConflict) {
            val video = items.firstOrNull { it.itemType == ItemType.VIDEO } ?: items.first()
            val dir = video.originalPath.substringBeforeLast('/', "")
            val base = video.fileName.substringBeforeLast('.')
            var number = 1
            while (true) {
                val suffix = if (number == 1) "（已还原）" else "（已还原 $number）"
                val candidate = items.associateWith { item ->
                    val ext = item.fileName.substringAfterLast('.', "")
                    val stem = item.fileName.substringBeforeLast('.')
                    val renamedStem = if (stem.startsWith(base, ignoreCase = true)) {
                        base + suffix + stem.substring(base.length)
                    } else {
                        stem + suffix
                    }
                    val name = renamedStem + if (ext.isEmpty()) "" else ".$ext"
                    if (dir.isEmpty()) name else "$dir/$name"
                }
                if (
                    candidate.values.toSet().size == candidate.size &&
                    candidate.values.none { files.exists(target, it) }
                ) {
                    destinations = candidate
                    // 先持久化最终目的地，半还原重启后仍使用同一组文件名。
                    candidate.forEach { (item, destination) ->
                        trashDao.updateOriginalPath(item.id, destination)
                    }
                    items = items.map { item ->
                        item.copy(originalPath = candidate.getValue(item))
                    }
                    destinations = items.associateWith { it.originalPath }
                    break
                }
                number++
            }
        }
        for (item in items) {
            val destination = destinations.getValue(item)
            if (files.exists(target, item.trashPath)) {
                rename(target, item.trashPath, destination)
            }
            check(files.exists(target, destination)) { "还原结果无法确认，请检查网络后重试" }
            // 每完成一项立即提交；强杀后剩余项可继续，不会重复移动已完成文件。
            trashDao.deleteById(item.id)
        }
    }

    suspend fun permanentlyDelete(serverId: Long, target: SmbTarget, groupId: String) =
        withContext(Dispatchers.IO) {
            reconcileGroup(serverId, target, groupId)
            val items = trashDao.listGroup(serverId, groupId)
            for (item in items) {
                connections.withShare(target, SmbConnectionManager.Channel.AUX) { share ->
                    val path = SmbFileRepository.toSmbPath(item.trashPath)
                    if (share.fileExists(path)) share.rm(path)
                }
                trashDao.deleteById(item.id)
            }
        }

    suspend fun purgeExpired(serverId: Long, target: SmbTarget, retentionDays: Int): Int {
        if (retentionDays <= 0) return 0
        reconcileServer(serverId, target)
        val before = System.currentTimeMillis() - retentionDays * 24L * 60L * 60L * 1_000L
        val groups = trashDao.listOlderThan(serverId, before).map { it.groupId }.distinct()
        groups.forEach { permanentlyDelete(serverId, target, it) }
        return groups.size
    }

    private suspend fun reconcileServer(serverId: Long, target: SmbTarget) {
        trashDao.listAll().asSequence()
            .filter { it.serverId == serverId }
            .map { it.groupId }
            .distinct()
            .forEach { reconcileGroup(serverId, target, it) }
    }

    private suspend fun reconcileGroup(serverId: Long, target: SmbTarget, groupId: String) {
        trashDao.listGroup(serverId, groupId).forEach { item ->
            val trashExists = files.exists(target, item.trashPath)
            val sourceExists = files.exists(target, item.originalPath)
            when {
                trashExists -> Unit
                sourceExists -> trashDao.deleteById(item.id) // 意图已写，但 rename 尚未发生
                else -> Unit // 两边都不存在时保留证据，不擅自抹掉恢复索引
            }
        }
    }

    private suspend fun rename(target: SmbTarget, source: String, destination: String) {
        try {
            connections.withShare(target, SmbConnectionManager.Channel.AUX) { share ->
                val handle = share.openFile(
                    SmbFileRepository.toSmbPath(source),
                    EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null,
                )
                try {
                    handle.rename(SmbFileRepository.toSmbPath(destination), false)
                } finally {
                    handle.close()
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val raw = generateSequence(error) { it.cause }
                .mapNotNull { it.message }
                .joinToString(" ")
                .uppercase()
            val message = when {
                "ACCESS_DENIED" in raw || "STATUS_ACCESS_DENIED" in raw ->
                    "权限不足：电脑上的共享账号没有删除或改名权限"
                "SHARING_VIOLATION" in raw || "LOCK" in raw ->
                    "文件正被占用：请关闭电脑上正在使用它的程序后重试"
                "CONNECTION" in raw || "TIMEOUT" in raw || "NETWORK" in raw ->
                    "网络已断开：请确认手机仍连接同一局域网后重试"
                else -> "移动文件失败：${error.message ?: "电脑未返回具体原因"}"
            }
            throw IllegalStateException(message, error)
        }
    }

    private fun subtitleMatches(videoBase: String, subtitleStem: String): Boolean {
        if (subtitleStem.equals(videoBase, ignoreCase = true)) return true
        if (!subtitleStem.startsWith(videoBase, ignoreCase = true)) return false
        val suffix = subtitleStem.substring(videoBase.length)
        val separator = suffix.firstOrNull() ?: return false
        if (separator != '.' && separator != '_' && separator != '-') return false
        val tags = suffix.trimStart('.', '_', '-')
            .split('.', '_', '-')
            .filter { it.isNotBlank() }
            .map { it.lowercase() }
        return tags.isNotEmpty() && tags.all { tag ->
            tag in SUBTITLE_NAME_TAGS ||
                tag.matches(Regex("(?:cd|part|disc)\\d{1,2}"))
        }
    }

    private companion object {
        const val TRASH_DIR = ".lanplay_trash"
        val SUBTITLE_NAME_TAGS = setOf(
            "chs", "sc", "zh-cn", "zh-hans", "zh", "cn", "简体", "中文", "chi",
            "cht", "tc", "zh-tw", "zh-hant", "繁体", "eng", "en", "jpn", "ja", "jp",
            "forced", "sdh", "cc", "default", "signs",
        )
    }
}
