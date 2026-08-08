package com.lanplay.player.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase as PlatformSQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherSQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 把旧版明文 Room 数据库原地升级为 SQLCipher。
 *
 * 文件替换由持久 journal 驱动；进程在任一阶段被杀后，下次启动会先恢复或完成迁移，
 * 绝不会在存在备份/临时库的歧义状态下让 Room 创建一份空库。
 */
object EncryptedDatabaseMigration {
    private const val SUFFIX_ENCRYPTED = ".encrypted.part"
    private const val SUFFIX_BACKUP = ".plaintext-backup"

    fun prepare(context: Context, databaseName: String, passphrase: ByteArray) {
        System.loadLibrary("sqlcipher")
        val lockFile = File(context.noBackupFilesDir, "$databaseName.migration.lock")
        lockFile.parentFile?.mkdirs()
        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            channel.lock().use {
                prepareLocked(context, databaseName, passphrase)
            }
        }
    }

    private fun prepareLocked(context: Context, databaseName: String, passphrase: ByteArray) {
        val source = context.getDatabasePath(databaseName)
        source.parentFile?.mkdirs()
        val encrypted = File(source.parentFile, "$databaseName$SUFFIX_ENCRYPTED")
        val backup = File(source.parentFile, "$databaseName$SUFFIX_BACKUP")
        val journal = File(context.noBackupFilesDir, "$databaseName.migration.state")

        recoverInterruptedMigration(source, encrypted, backup, journal, passphrase)
        if (!source.isFile) {
            check(!encrypted.exists() && !backup.exists() && !journal.exists()) {
                "数据库迁移状态不明确，已阻止创建空数据库"
            }
            return
        }
        if (!hasPlaintextHeader(source)) {
            // 正常已加密库由随后 Room 的真实打开验证口令与 schema。完整性双扫描只用于
            // 存在迁移残留、即将替换主库等异常恢复路径，不能阻塞每次冷启动。
            check(!encrypted.exists() && !backup.exists() && !journal.exists()) {
                "数据库迁移残留未能安全清理"
            }
            return
        }

        writeStage(journal, Stage.PREPARING)
        checkpointWal(source)
        deleteDatabaseSidecars(encrypted)
        check(!encrypted.exists() || encrypted.delete()) { "无法清理旧的数据库迁移临时文件" }

        val expected = exportEncrypted(source, encrypted, passphrase)
        syncFile(encrypted)
        writeStage(journal, Stage.EXPORTED)
        validateEncrypted(encrypted, passphrase, expected)
        writeStage(journal, Stage.VERIFIED)

        if (backup.exists()) {
            check(secureDelete(backup)) { "无法清理旧的明文数据库备份" }
        }
        moveAtomically(source, backup)
        writeStage(journal, Stage.SOURCE_BACKED_UP)
        try {
            moveAtomically(encrypted, source)
        } catch (t: Throwable) {
            if (!source.exists() && backup.exists()) moveAtomically(backup, source)
            throw t
        }
        writeStage(journal, Stage.INSTALLED)
        deleteDatabaseSidecars(source)
        validateEncrypted(source, passphrase, expected)

        // 闪存无法承诺物理擦除；这里保证逻辑覆写、fsync 和删除。失败时保留 journal，
        // 下次启动会继续尝试清理，而不会再次迁移或创建空库。
        val backupRemoved = secureDelete(backup)
        if (encrypted.exists()) encrypted.delete()
        if (backupRemoved) journal.delete()
    }

    private fun recoverInterruptedMigration(
        source: File,
        encrypted: File,
        backup: File,
        journal: File,
        passphrase: ByteArray,
    ) {
        val hasArtifacts = encrypted.exists() || backup.exists() || journal.exists()
        if (!hasArtifacts) return
        val stage = readStage(journal)

        if (source.isFile && !hasPlaintextHeader(source)) {
            validateEncrypted(source, passphrase, null)
            cleanupArtifacts(encrypted, backup, journal)
            return
        }

        if (!source.exists()) {
            val installable = stage in setOf(
                Stage.VERIFIED,
                Stage.SOURCE_BACKED_UP,
                Stage.INSTALLED,
            ) && encrypted.isFile && isValidEncrypted(encrypted, passphrase)
            if (installable) {
                moveAtomically(encrypted, source)
                validateEncrypted(source, passphrase, null)
                cleanupArtifacts(encrypted, backup, journal)
                return
            }
            if (backup.isFile && hasPlaintextHeader(backup)) {
                moveAtomically(backup, source)
                if (encrypted.exists()) encrypted.delete()
                journal.delete()
                return
            }
            error("检测到未完成的数据库迁移，但没有可安全恢复的数据库")
        }

        // 主库仍是明文：说明尚未进入替换阶段。丢弃临时导出，重新从主库迁移。
        if (hasPlaintextHeader(source)) {
            if (backup.exists()) {
                check(secureDelete(backup)) { "无法清理冲突的明文数据库备份" }
            }
            if (encrypted.exists()) check(encrypted.delete()) { "无法清理迁移临时库" }
            journal.delete()
        }
    }

    private fun exportEncrypted(
        source: File,
        encrypted: File,
        passphrase: ByteArray,
    ): DatabaseSnapshot {
        val passphraseText = passphrase.toString(Charsets.UTF_8)
        CipherSQLiteDatabase.openOrCreateDatabase(encrypted, passphraseText, null, null).use {
            // 创建目标文件和 SQLCipher header；真正 schema 由 sqlcipher_export 写入。
        }
        val plain = CipherSQLiteDatabase.openDatabase(
            source.absolutePath,
            "",
            null,
            CipherSQLiteDatabase.OPEN_READWRITE,
            null,
        )
        try {
            val expected = snapshot(plain)
            var attached = false
            try {
                plain.rawExecSQL(
                    "ATTACH DATABASE ? AS encrypted KEY ?",
                    encrypted.absolutePath,
                    passphraseText,
                )
                attached = true
                plain.rawQuery("SELECT sqlcipher_export('encrypted')", emptyArray<String>()).use {
                    check(it.moveToFirst()) { "数据库加密导出失败" }
                }
                plain.rawExecSQL("PRAGMA encrypted.user_version = ${expected.userVersion}")
            } finally {
                if (attached) {
                    try {
                        plain.rawExecSQL("DETACH DATABASE encrypted")
                    } catch (_: Throwable) {
                        // 后续独立打开和完整性检查仍是最终判定，不在这里掩盖原异常。
                    }
                }
            }
            return expected
        } finally {
            plain.close()
        }
    }

    private fun checkpointWal(file: File) {
        PlatformSQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            PlatformSQLiteDatabase.OPEN_READWRITE,
        ).use { database ->
            database.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
                check(cursor.moveToFirst()) { "无法读取 WAL checkpoint 结果" }
                val busy = cursor.getInt(0)
                val logFrames = cursor.getInt(1)
                val checkpointed = cursor.getInt(2)
                check(busy == 0 && logFrames == checkpointed) {
                    "数据库仍有活动连接，WAL 尚未完整合并"
                }
            }
        }
        deleteDatabaseSidecars(file)
    }

    private fun validateEncrypted(
        file: File,
        passphrase: ByteArray,
        expected: DatabaseSnapshot?,
    ) {
        check(file.isFile && !hasPlaintextHeader(file)) { "加密数据库文件无效" }
        CipherSQLiteDatabase.openDatabase(
            file.absolutePath,
            passphrase.copyOf(),
            null,
            CipherSQLiteDatabase.OPEN_READONLY,
            null,
        ).use { database ->
            database.rawQuery("PRAGMA integrity_check", emptyArray<String>()).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0).equals("ok", true)) {
                    "数据库完整性检查失败"
                }
            }
            database.rawQuery("PRAGMA cipher_integrity_check", emptyArray<String>()).use { cursor ->
                val errors = buildList {
                    while (cursor.moveToNext()) {
                        cursor.getString(0)?.takeUnless { it.equals("ok", true) }?.let(::add)
                    }
                }
                check(errors.isEmpty()) { "SQLCipher 完整性检查失败" }
            }
            if (expected != null) {
                val actual = snapshot(database)
                check(actual == expected) { "加密数据库验证失败：schema 或记录数不一致" }
            }
        }
    }

    private fun snapshot(database: CipherSQLiteDatabase): DatabaseSnapshot {
        val version = database.rawQuery("PRAGMA user_version", emptyArray<String>()).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
        val tables = database.rawQuery(
            "SELECT name FROM sqlite_master " +
                "WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
            emptyArray<String>(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        val schema = database.rawQuery(
            "SELECT type, name, sql FROM sqlite_master " +
                "WHERE type IN ('table','index','trigger') " +
                "AND name NOT LIKE 'sqlite_%' AND sql IS NOT NULL " +
                "ORDER BY type, name",
            emptyArray<String>(),
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val key = "${cursor.getString(0)}:${cursor.getString(1)}"
                    val normalizedSql = cursor.getString(2)
                        .trim()
                        .replace(Regex("\\s+"), " ")
                    put(key, normalizedSql)
                }
            }
        }
        val rows = tables.associateWith { table ->
            val quoted = table.replace("\"", "\"\"")
            database.rawQuery("SELECT count(*) FROM \"$quoted\"", emptyArray<String>()).use {
                check(it.moveToFirst()) { "无法统计表 $table" }
                it.getLong(0)
            }
        }
        return DatabaseSnapshot(version, schema, rows)
    }

    private fun isValidEncrypted(file: File, passphrase: ByteArray): Boolean = try {
        validateEncrypted(file, passphrase, null)
        true
    } catch (_: Throwable) {
        false
    }

    private fun hasPlaintextHeader(file: File): Boolean = try {
        file.inputStream().use { input ->
            val header = ByteArray(16)
            input.read(header) == header.size &&
                header.toString(Charsets.US_ASCII).startsWith("SQLite format 3")
        }
    } catch (_: Throwable) {
        false
    }

    private fun deleteDatabaseSidecars(file: File) {
        listOf(File("${file.absolutePath}-wal"), File("${file.absolutePath}-shm")).forEach {
            check(!it.exists() || it.delete()) { "无法删除数据库 sidecar：${it.name}" }
        }
    }

    private fun moveAtomically(source: File, target: File) {
        check(source.exists()) { "待移动数据库不存在：${source.name}" }
        check(!target.exists()) { "目标数据库已存在：${target.name}" }
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
        }
        check(target.isFile && !source.exists()) { "数据库文件替换失败" }
        syncFile(target)
    }

    private fun syncFile(file: File) {
        FileOutputStream(file, true).use { it.fd.sync() }
    }

    private fun writeStage(journal: File, stage: Stage) {
        journal.parentFile?.mkdirs()
        FileOutputStream(journal, false).use { output ->
            output.write(stage.name.toByteArray(Charsets.US_ASCII))
            output.fd.sync()
        }
    }

    private fun readStage(journal: File): Stage? = try {
        Stage.valueOf(journal.readText(Charsets.US_ASCII).trim())
    } catch (_: Throwable) {
        null
    }

    private fun cleanupArtifacts(encrypted: File, backup: File, journal: File) {
        if (encrypted.exists()) encrypted.delete()
        val backupRemoved = secureDelete(backup)
        if (backupRemoved) journal.delete()
    }

    private fun secureDelete(file: File): Boolean {
        if (!file.exists()) return true
        if (!file.isFile) return false
        return try {
            RandomAccessFile(file, "rw").use { output ->
                val zeros = ByteArray(64 * 1024)
                var remaining = output.length()
                output.seek(0)
                while (remaining > 0) {
                    val count = minOf(remaining, zeros.size.toLong()).toInt()
                    output.write(zeros, 0, count)
                    remaining -= count
                }
                output.fd.sync()
            }
            file.delete()
        } catch (_: Throwable) {
            false
        }
    }

    private data class DatabaseSnapshot(
        val userVersion: Int,
        val schema: Map<String, String>,
        val tableRows: Map<String, Long>,
    )

    private enum class Stage {
        PREPARING,
        EXPORTED,
        VERIFIED,
        SOURCE_BACKED_UP,
        INSTALLED,
    }
}
