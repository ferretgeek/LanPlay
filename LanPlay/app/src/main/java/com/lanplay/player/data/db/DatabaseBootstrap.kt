package com.lanplay.player.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.lanplay.player.data.crypto.CacheCipher
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * 数据库启动闸门。桌面入口先在 IO 线程调用 [preflight]，成功后 Hilt 直接接管已经真实
 * 打开的同一个 Room 实例；失败时 UI 转入完全不依赖 Room 的恢复页。
 */
object DatabaseBootstrap {
    private val lock = Any()
    private var preparedDatabase: LanPlayDatabase? = null

    @Volatile
    private var failure: String? = null
    @Volatile
    private var ready = false

    fun preflight(context: Context) {
        clearPreparedDatabase()
        val passphrase = CacheCipher.databasePassphrase(context)
        var database: LanPlayDatabase? = null
        try {
            EncryptedDatabaseMigration.prepare(context, CacheCipher.DATABASE_NAME, passphrase)
            database = buildDatabase(context, passphrase)
            // 强制真实打开，密钥、SQLCipher、Room schema/migration 的错误都在恢复页前截获。
            database.openHelper.writableDatabase
            synchronized(lock) {
                preparedDatabase?.close()
                preparedDatabase = database
                database = null
            }
            ready = true
            failure = null
        } catch (t: Throwable) {
            database?.close()
            recordFailure(t)
            throw t
        } finally {
            passphrase.fill(0)
        }
    }

    /** Hilt provider 的唯一入口。正常桌面启动不再做任何主线程密钥或全库工作。 */
    fun provideDatabase(context: Context): LanPlayDatabase {
        takePreparedDatabase()?.let { return it }
        var passphrase: ByteArray? = null
        return try {
            // 仅服务/系统恢复等绕过 StartupActivity 的异常入口会到这里。
            passphrase = CacheCipher.databasePassphrase(context)
            EncryptedDatabaseMigration.prepare(context, CacheCipher.DATABASE_NAME, passphrase)
            buildDatabase(context, passphrase).also {
                it.openHelper.writableDatabase
                ready = true
                failure = null
            }
        } catch (t: Throwable) {
            ready = false
            recordFailure(t)
            // 只让异常入口完成 Hilt 构图并立即退出；绝不触碰旧数据库文件。
            Room.inMemoryDatabaseBuilder(context, LanPlayDatabase::class.java).build()
        } finally {
            passphrase?.fill(0)
        }
    }

    fun isPrepared(): Boolean = ready

    fun failureMessage(): String? = failure

    fun recordFailure(throwable: Throwable) {
        failure = when {
            throwable.message?.contains("密钥", ignoreCase = true) == true ->
                "本机数据库密钥当前不可用。旧数据库未被覆盖。"
            throwable.message?.contains("迁移", ignoreCase = true) == true ->
                "本机数据库迁移未能安全完成。旧数据库和迁移文件已保留。"
            else -> "无法打开本机加密数据库。旧数据未被覆盖。"
        }
    }

    fun clearFailure() {
        failure = null
    }

    fun clearPreparedDatabase() {
        ready = false
        synchronized(lock) {
            preparedDatabase?.close()
            preparedDatabase = null
        }
    }

    private fun takePreparedDatabase(): LanPlayDatabase? = synchronized(lock) {
        preparedDatabase.also { preparedDatabase = null }
    }

    internal fun buildDatabase(context: Context, passphrase: ByteArray): LanPlayDatabase {
        val factory = SupportOpenHelperFactory(passphrase.copyOf())
        return Room.databaseBuilder(context, LanPlayDatabase::class.java, CacheCipher.DATABASE_NAME)
            .openHelperFactory(factory)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(
                DatabaseModule.MIGRATION_1_2,
                DatabaseModule.MIGRATION_2_3,
                DatabaseModule.MIGRATION_3_4,
            )
            .build()
    }
}
