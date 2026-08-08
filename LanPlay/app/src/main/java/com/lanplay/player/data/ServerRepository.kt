package com.lanplay.player.data

import com.lanplay.player.data.crypto.CredentialCipher
import androidx.room.withTransaction
import com.lanplay.player.data.db.LanPlayDatabase
import com.lanplay.player.data.db.SmbServerDao
import com.lanplay.player.data.db.SmbServerEntity
import com.lanplay.player.smb.AuthMode
import com.lanplay.player.smb.SmbConnectionManager
import com.lanplay.player.smb.SmbFileRepository
import com.lanplay.player.smb.SmbTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** 一台已保存的服务器：连接参数 + 它在库里的主键（观看记录等都按 serverId 归属） */
data class SavedServer(
    val id: Long,
    val displayName: String,
    val target: SmbTarget,
    val defaultPath: String,
)

/**
 * 服务器配置仓库（需求 C-03/C-05/C-06）。
 *
 * 从 DataStore 迁到 Room 是因为观看记录、浏览状态、回收站都要按 serverId 归属，
 * 且 C-05 要求多服务器可编辑、删除、排序。密码仍走 Keystore 加密，库里只有密文。
 */
@Singleton
class ServerRepository @Inject constructor(
    private val dao: SmbServerDao,
    private val cipher: CredentialCipher,
    private val database: LanPlayDatabase,
    private val connections: SmbConnectionManager,
) {
    private val mutationMutex = Mutex()

    fun observeAll(): Flow<List<SavedServer>> = dao.observeAll().map { list -> list.map { it.toSaved() } }

    fun observeCurrent(): Flow<SavedServer?> = dao.observeAll().map { list ->
        list.firstOrNull()?.toSaved()
    }

    suspend fun listAll(): List<SavedServer> = dao.listAll().map { it.toSaved() }

    suspend fun getById(id: Long): SavedServer? = dao.getById(id)?.toSaved()

    /** 当前使用的服务器。第 2 阶段只有单台，取排序最前的一条。 */
    suspend fun current(): SavedServer? = dao.first()?.toSaved()

    /**
     * 保存或更新。已存在同 host+share+user 的记录就更新它，避免每次 configure 都新增一条。
     * @return serverId
     */
    suspend fun save(
        target: SmbTarget,
        displayName: String = "",
        defaultPath: String = "",
        editingId: Long? = null,
    ): Long = mutationMutex.withLock {
        target.requireValid()
        val editingTargetIdentity = editingId
            ?.let { dao.getById(it) }
            ?.toSaved()
            ?.target
            ?.identity
        val normalizedPath = SmbFileRepository.normalizeRelativePath(defaultPath)
        val (cipherText, iv) = cipher.encrypt(target.password)
        val id = database.withTransaction {
            val all = dao.listAll()
            fun SmbServerEntity.matchesTarget(): Boolean =
                host.equals(target.host, true) &&
                    port == target.port &&
                    shareName.equals(target.share, true) &&
                    domain.orEmpty().equals(target.domain.orEmpty(), true) &&
                    username.equals(target.username, true) &&
                    authMode == target.authMode.name
            if (editingId != null) {
                require(all.none { it.id != editingId && it.matchesTarget() }) {
                    "已经存在相同地址、共享和账号的服务器"
                }
            }
            val existing = editingId?.let { dao.getById(it) }
                ?: all.firstOrNull { it.matchesTarget() }
            val name = displayName.trim().ifEmpty { existing?.displayName ?: target.host }
            val entity = SmbServerEntity(
                id = existing?.id ?: 0,
                displayName = name,
                host = target.host.trim(),
                port = target.port,
                shareName = target.share.trim(),
                domain = target.domain?.trim()?.ifBlank { null },
                username = target.username.trim(),
                encryptedPassword = cipherText,
                passwordIv = iv,
                authMode = target.authMode.name,
                // 空字符串是“从共享根目录开始”的有效设置，不能被旧值覆盖。
                defaultPath = normalizedPath,
                sortOrder = existing?.sortOrder ?: all.size,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            )
            if (existing == null) dao.insert(entity) else {
                dao.update(entity)
                existing.id
            }
        }
        // 设置页和目录探测走 AUX。只作废被修改服务器的辅助会话，正在播放的
        // PLAYBACK 通道继续使用其已建立的会话，不波及其他服务器。
        connections.closeIdentity(target.identity, SmbConnectionManager.Channel.AUX)
        if (editingTargetIdentity != null && editingTargetIdentity != target.identity) {
            connections.closeIdentity(editingTargetIdentity, SmbConnectionManager.Channel.AUX)
        }
        id
    }

    suspend fun delete(id: Long) = mutationMutex.withLock {
        var deletedIdentity: String? = null
        database.withTransaction {
            val server = dao.getById(id) ?: return@withTransaction
            deletedIdentity = server.toSaved().target.identity
            val sqlite = database.openHelper.writableDatabase
            val trashCount = sqlite.query(
                "SELECT count(*) FROM trash_item WHERE serverId = ?",
                arrayOf(id),
            ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
            require(trashCount == 0L) { "请先还原或彻底删除这台服务器的回收站项目" }
            sqlite.execSQL(
                "DELETE FROM bookmark WHERE recordId IN " +
                    "(SELECT id FROM watch_record WHERE serverId = ?)",
                arrayOf(id),
            )
            sqlite.execSQL(
                "DELETE FROM record_tag WHERE recordId IN " +
                    "(SELECT id FROM watch_record WHERE serverId = ?)",
                arrayOf(id),
            )
            sqlite.execSQL(
                "DELETE FROM movie_actor WHERE movieId IN " +
                    "(SELECT id FROM movie_info WHERE serverId = ?)",
                arrayOf(id),
            )
            listOf(
                "watch_record",
                "browse_state",
                "directory_entry_cache",
                "media_meta",
                "movie_info",
                "actor",
            ).forEach { table ->
                sqlite.execSQL("DELETE FROM $table WHERE serverId = ?", arrayOf(id))
            }
            dao.delete(server)
        }
        deletedIdentity?.let { connections.closeIdentity(it) }
    }

    suspend fun activate(id: Long) = mutationMutex.withLock {
        database.withTransaction {
            val ordered = dao.listAll()
            val from = ordered.indexOfFirst { it.id == id }
            require(from >= 0) { "服务器不存在" }
            val compacted = ordered.toMutableList().apply {
                add(0, removeAt(from))
            }
            compacted.forEachIndexed { index, entity ->
                if (entity.sortOrder != index) dao.setSortOrder(entity.id, index)
            }
        }
    }

    /** 将配置在列表中上移或下移一步；只改显示顺序，不重连也不触碰观看记录。 */
    suspend fun move(id: Long, delta: Int) = mutationMutex.withLock {
        if (delta == 0) return@withLock
        database.withTransaction {
            val ordered = dao.listAll()
            val from = ordered.indexOfFirst { it.id == id }
            if (from < 0) return@withTransaction
            val to = (from + delta).coerceIn(0, ordered.lastIndex)
            if (from == to) return@withTransaction
            val mutable = ordered.toMutableList()
            val item = mutable.removeAt(from)
            mutable.add(to, item)
            mutable.forEachIndexed { index, entity ->
                if (entity.sortOrder != index) dao.setSortOrder(entity.id, index)
            }
        }
    }

    private fun SmbServerEntity.toSaved(): SavedServer {
        val password = if (encryptedPassword.isEmpty() || passwordIv.isEmpty()) {
            ""
        } else {
            runCatching { cipher.decrypt(encryptedPassword, passwordIv) }.getOrDefault("")
        }
        return SavedServer(
            id = id,
            displayName = displayName,
            defaultPath = defaultPath,
            target = SmbTarget(
                host = host,
                port = port,
                share = shareName,
                domain = domain,
                username = username,
                password = password,
                authMode = runCatching { AuthMode.valueOf(authMode) }.getOrDefault(AuthMode.ACCOUNT),
            ),
        )
    }
}
