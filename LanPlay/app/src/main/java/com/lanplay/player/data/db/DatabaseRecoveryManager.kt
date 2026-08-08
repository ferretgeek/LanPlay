package com.lanplay.player.data.db

import android.content.Context
import android.net.Uri
import com.lanplay.player.data.crypto.CacheCipher
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 不依赖 Room 的数据库恢复文件编排。 */
object DatabaseRecoveryManager {
    suspend fun validateBackup(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取所选备份" }
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_BACKUP_BYTES) { "备份文件超过 16 MB 上限" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        try {
            val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            require(root["version"]?.jsonPrimitive?.intOrNull in 1..2) { "不支持这个备份版本" }
            root.getValue("settings").jsonObject
            listOf("servers", "records", "tags", "links", "bookmarks").forEach { key ->
                root.getValue(key).jsonArray
            }
        } catch (t: Throwable) {
            throw IllegalArgumentException("所选文件不是有效的 LanPlay 备份", t)
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * 把旧数据库、sidecar、迁移状态和包装密钥作为一组移入私有恢复目录，再创建新库。
     * 任一步失败都会删除新建文件并把已移动的旧文件放回原位。
     */
    suspend fun rebuild(context: Context): String = withContext(Dispatchers.IO) {
        val lockFile = File(context.noBackupFilesDir, RECOVERY_LOCK)
        lockFile.parentFile?.mkdirs()
        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            channel.lock().use {
                rebuildLocked(context)
            }
        }
    }

    private fun rebuildLocked(context: Context): String {
        DatabaseBootstrap.clearPreparedDatabase()
        DatabaseBootstrap.clearFailure()
        val recoveryId = Instant.now().toString()
            .replace(Regex("[^0-9A-Za-z._-]"), "-")
        val root = File(context.filesDir, "database-recovery").apply {
            check(mkdirs() || isDirectory) { "无法创建数据库恢复目录" }
        }
        val staging = File(root, ".$recoveryId.part")
        check(!staging.exists() && staging.mkdirs()) { "无法创建数据库恢复暂存目录" }

        val moved = mutableListOf<Pair<File, File>>()
        return try {
            recoveryFiles(context).forEach { source ->
                if (!source.exists()) return@forEach
                val category = if (source.parentFile == context.noBackupFilesDir) "no-backup" else "database"
                val archived = File(staging, "$category/${source.name}")
                move(source, archived)
                moved += source to archived
            }

            try {
                DatabaseBootstrap.preflight(context)
            } catch (t: Throwable) {
                freshDatabaseFiles(context).forEach { file ->
                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                }
                moved.asReversed().forEach { (original, archived) ->
                    if (archived.exists()) move(archived, original)
                }
                staging.deleteRecursively()
                DatabaseBootstrap.recordFailure(t)
                throw t
            }

            val archive = File(root, recoveryId)
            move(staging, archive)
            CacheCipher.writeDatabaseRecoveryNotice(context, recoveryId)
            recoveryId
        } catch (t: Throwable) {
            // preflight 前的移动失败也必须回滚。
            moved.asReversed().forEach { (original, archived) ->
                if (!original.exists() && archived.exists()) runCatching { move(archived, original) }
            }
            if (staging.exists()) staging.deleteRecursively()
            throw t
        }
    }

    private fun recoveryFiles(context: Context): List<File> {
        val database = context.getDatabasePath(CacheCipher.DATABASE_NAME)
        return listOf(
            database,
            File("${database.absolutePath}-wal"),
            File("${database.absolutePath}-shm"),
            File("${database.absolutePath}-journal"),
            File(database.parentFile, "${CacheCipher.DATABASE_NAME}.encrypted.part"),
            File(database.parentFile, "${CacheCipher.DATABASE_NAME}.plaintext-backup"),
            File(context.noBackupFilesDir, CacheCipher.DATABASE_KEY_FILE),
            File(context.noBackupFilesDir, "${CacheCipher.DATABASE_NAME}.migration.state"),
            File(context.noBackupFilesDir, "${CacheCipher.DATABASE_NAME}.migration.lock"),
            File(context.noBackupFilesDir, "database-recovery.pending"),
        )
    }

    private fun freshDatabaseFiles(context: Context): List<File> = recoveryFiles(context)

    private fun move(source: File, target: File) {
        target.parentFile?.let { check(it.mkdirs() || it.isDirectory) { "无法创建恢复目录" } }
        check(!target.exists()) { "恢复目标已存在" }
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
        }
        check(target.exists() && !source.exists()) { "恢复文件移动失败" }
    }

    private const val MAX_BACKUP_BYTES = 16 * 1024 * 1024
    private const val RECOVERY_LOCK = "database-recovery.lock"
}
