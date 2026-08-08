package com.lanplay.player.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.content.Context
import com.lanplay.player.core.concurrent.KeyedLockRegistry
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * V-08：图片与元数据文件缓存使用 Android Keystore 的 AES-256-GCM 加密落盘。
 *
 * 密钥不可导出；每个文件使用随机 96-bit IV，并由 GCM 校验完整性。缓存损坏或设备密钥
 * 失效时返回 null，让上层从 SMB 重新生成，绝不把损坏内容当作有效图片。
 */
object CacheCipher {
    private const val KEY_ALIAS = "lanplay_cache_aes_v1"
    private const val DATABASE_KEY_ALIAS = "lanplay_database_aes_v1"
    private val MAGIC = byteArrayOf(0x4c, 0x50, 0x43, 0x45, 0x01) // LPCE + version
    private val writeLocks = KeyedLockRegistry<String>()

    fun isEncrypted(file: File): Boolean {
        if (!file.isFile || file.length() < MAGIC.size + 13) return false
        return runCatching {
            file.inputStream().use { input ->
                val head = ByteArray(MAGIC.size)
                input.read(head) == head.size && head.contentEquals(MAGIC)
            }
        }.getOrDefault(false)
    }

    fun writeEncrypted(
        file: File,
        plain: ByteArray,
        keyAlias: String = KEY_ALIAS,
    ) {
        require(plain.size <= MAX_PLAIN_BYTES) { "缓存文件过大" }
        file.parentFile?.mkdirs()
        val lockKey = file.absoluteFile.normalize().path
        writeLocks.withLock(lockKey) {
            writeEncryptedLocked(file, plain, keyAlias)
        }
    }

    private fun writeEncryptedLocked(file: File, plain: ByteArray, keyAlias: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(keyAlias))
        val encrypted = cipher.doFinal(plain)
        val part = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.part")
        try {
            FileOutputStream(part).use { stream ->
                val output = BufferedOutputStream(stream)
                output.write(MAGIC)
                output.write(cipher.iv.size)
                output.write(cipher.iv)
                output.write(encrypted)
                output.flush()
                stream.fd.sync()
            }
            try {
                Files.move(
                    part.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (part.exists()) part.delete()
            encrypted.fill(0)
        }
    }

    fun read(file: File, keyAlias: String = KEY_ALIAS): ByteArray? {
        if (!file.isFile || file.length() !in 1..MAX_ENCRYPTED_BYTES) return null
        if (!isEncrypted(file)) return runCatching { file.readBytes() }.getOrNull()
        return runCatching {
            file.inputStream().buffered().use { input ->
                val magic = ByteArray(MAGIC.size)
                require(input.read(magic) == magic.size && magic.contentEquals(MAGIC))
                val ivLength = input.read()
                require(ivLength in 12..32)
                val iv = ByteArray(ivLength)
                require(input.read(iv) == iv.size)
                val encrypted = input.readBytes()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(keyAlias), GCMParameterSpec(128, iv))
                cipher.doFinal(encrypted)
            }
        }.getOrNull()
    }

    /** 原位升级旧版明文缓存，保留文件名，Room 中已有路径无需迁移。 */
    fun encryptLegacyFile(file: File): Boolean {
        val lockKey = file.absoluteFile.normalize().path
        return writeLocks.withLock(lockKey) {
            if (!file.isFile || isEncrypted(file)) return@withLock false
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@withLock false
            try {
                runCatching {
                    writeEncryptedLocked(file, bytes, KEY_ALIAS)
                    true
                }.getOrDefault(false)
            } finally {
                bytes.fill(0)
            }
        }
    }

    fun migrateKnownCaches(context: Context): Int {
        val roots = listOf(
            File(context.cacheDir, "metadata-images"),
            File(context.cacheDir, "thumbnails"),
            File(context.cacheDir, "sprites"),
            File(context.cacheDir, "metadata"),
        )
        var migrated = 0
        roots.forEach { root ->
            try {
                if (!root.exists()) return@forEach
                root.walkTopDown()
                    .filter { it.isFile && it.extension != "part" }
                    .forEach { file ->
                        try {
                            if (encryptLegacyFile(file)) migrated++
                        } catch (_: Throwable) {
                            // 单个可再生缓存损坏不应终止整个迁移线程或应用进程。
                        }
                    }
            } catch (_: Throwable) {
                // 目录不可读时跳过，后续缓存仍可按需重建。
            }
        }
        return migrated
    }

    @Synchronized
    fun databasePassphrase(context: Context): ByteArray {
        val keyFile = File(context.noBackupFilesDir, DATABASE_KEY_FILE)
        if (keyFile.exists()) {
            check(isEncrypted(keyFile)) { "数据库包装密钥文件格式无效" }
            read(keyFile, DATABASE_KEY_ALIAS)?.let { return it }
            // 从旧版共用 cache alias 无损迁移到数据库专用 alias。
            read(keyFile, KEY_ALIAS)?.let { legacyPassphrase ->
                writeEncrypted(keyFile, legacyPassphrase, DATABASE_KEY_ALIAS)
                return legacyPassphrase
            }
            throw IllegalStateException(
                "数据库密钥暂时不可用。为保护原有数据，应用不会重置密钥或新建空数据库。",
            )
        }
        val database = context.getDatabasePath(DATABASE_NAME)
        check(!database.exists() || hasPlaintextDatabaseHeader(database)) {
            "数据库密钥文件缺失。为保护原有加密数据库，应用不会覆盖现有数据。"
        }
        val random = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val passphrase = Base64.encode(random, Base64.NO_WRAP)
        random.fill(0)
        writeEncrypted(keyFile, passphrase, DATABASE_KEY_ALIAS)
        return passphrase
    }

    fun writeDatabaseRecoveryNotice(context: Context, recoveryId: String) {
        val marker = File(context.noBackupFilesDir, RECOVERY_MARKER)
        marker.writeText(recoveryId.take(80), Charsets.UTF_8)
    }

    fun consumeDatabaseRecoveryNotice(context: Context): Boolean {
        val marker = File(context.noBackupFilesDir, RECOVERY_MARKER)
        return marker.exists() && marker.delete()
    }

    private fun hasPlaintextDatabaseHeader(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(16)
            input.read(header) == header.size &&
                header.toString(Charsets.US_ASCII).startsWith("SQLite format 3")
        }
    }.getOrDefault(false)

    @Synchronized
    private fun key(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private const val MAX_PLAIN_BYTES = 64 * 1024 * 1024
    private const val MAX_ENCRYPTED_BYTES = MAX_PLAIN_BYTES.toLong() + 1024L
    const val DATABASE_NAME = "lanplay.db"
    const val DATABASE_KEY_FILE = "lanplay-db-key.lpc"
    private const val RECOVERY_MARKER = "database-recovery.pending"
}
