package com.lanplay.player.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

class Converters {
    @TypeConverter
    fun watchStateToString(value: WatchState): String = value.name

    @TypeConverter
    fun stringToWatchState(value: String): WatchState =
        runCatching { WatchState.valueOf(value) }.getOrDefault(WatchState.UNWATCHED)

    @TypeConverter
    fun itemTypeToString(value: ItemType): String = value.name

    @TypeConverter
    fun stringToItemType(value: String): ItemType =
        runCatching { ItemType.valueOf(value) }.getOrDefault(ItemType.VIDEO)
}

@Database(
    entities = [
        SmbServerEntity::class,
        WatchRecordEntity::class,
        BrowseStateEntity::class,
        DirectoryEntryCacheEntity::class,
        TrashItemEntity::class,
        MediaMetaEntity::class,
        TagEntity::class,
        RecordTagEntity::class,
        BookmarkEntity::class,
        MovieInfoEntity::class,
        ActorEntity::class,
        MovieActorEntity::class,
        AudioDeviceProfileEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class LanPlayDatabase : RoomDatabase() {
    abstract fun smbServerDao(): SmbServerDao
    abstract fun watchRecordDao(): WatchRecordDao
    abstract fun browseStateDao(): BrowseStateDao
    abstract fun directoryEntryCacheDao(): DirectoryEntryCacheDao
    abstract fun trashItemDao(): TrashItemDao
    abstract fun mediaMetaDao(): MediaMetaDao
    abstract fun movieInfoDao(): MovieInfoDao
    abstract fun actorDao(): ActorDao
    abstract fun movieActorDao(): MovieActorDao
    abstract fun tagDao(): TagDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun audioDeviceProfileDao(): AudioDeviceProfileDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LanPlayDatabase =
        DatabaseBootstrap.provideDatabase(context)

    @Provides fun provideSmbServerDao(db: LanPlayDatabase): SmbServerDao = db.smbServerDao()
    @Provides fun provideWatchRecordDao(db: LanPlayDatabase): WatchRecordDao = db.watchRecordDao()
    @Provides fun provideBrowseStateDao(db: LanPlayDatabase): BrowseStateDao = db.browseStateDao()
    @Provides
    fun provideDirectoryEntryCacheDao(db: LanPlayDatabase): DirectoryEntryCacheDao =
        db.directoryEntryCacheDao()
    @Provides fun provideTrashItemDao(db: LanPlayDatabase): TrashItemDao = db.trashItemDao()
    @Provides fun provideMediaMetaDao(db: LanPlayDatabase): MediaMetaDao = db.mediaMetaDao()
    @Provides fun provideMovieInfoDao(db: LanPlayDatabase): MovieInfoDao = db.movieInfoDao()
    @Provides fun provideActorDao(db: LanPlayDatabase): ActorDao = db.actorDao()
    @Provides fun provideMovieActorDao(db: LanPlayDatabase): MovieActorDao = db.movieActorDao()
    @Provides fun provideTagDao(db: LanPlayDatabase): TagDao = db.tagDao()
    @Provides fun provideBookmarkDao(db: LanPlayDatabase): BookmarkDao = db.bookmarkDao()
    @Provides
    fun provideAudioDeviceProfileDao(db: LanPlayDatabase): AudioDeviceProfileDao =
        db.audioDeviceProfileDao()

    /**
     * v1 → v2 只新增表，不改动 smb_server / watch_record 等既有数据。
     * 每条 SQL 与实体一一对应，绝不使用 destructive migration。
     */
    internal val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `tag` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `colorHex` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL)"
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tag_name` ON `tag` (`name`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `record_tag` (`recordId` INTEGER NOT NULL, `tagId` INTEGER NOT NULL, PRIMARY KEY(`recordId`, `tagId`))"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_record_tag_tagId` ON `record_tag` (`tagId`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `bookmark` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `recordId` INTEGER NOT NULL, `positionMs` INTEGER NOT NULL, `label` TEXT, `thumbnailPath` TEXT, `createdAt` INTEGER NOT NULL)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmark_recordId` ON `bookmark` (`recordId`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `movie_info` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `serverId` INTEGER NOT NULL, `fullPath` TEXT NOT NULL, `code` TEXT NOT NULL, `title` TEXT, `titleZh` TEXT, `releaseDate` TEXT, `runtimeMin` INTEGER, `studio` TEXT, `label` TEXT, `series` TEXT, `genresJson` TEXT NOT NULL, `posterRelPath` TEXT, `coverRelPath` TEXT, `source` TEXT, `scrapedAt` INTEGER NOT NULL, `userOverride` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_movie_info_serverId_fullPath` ON `movie_info` (`serverId`, `fullPath`)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_movie_info_code` ON `movie_info` (`code`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `actor` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `nameZh` TEXT, `avatarRelPath` TEXT, `isFollowed` INTEGER NOT NULL)"
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_actor_name` ON `actor` (`name`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `movie_actor` (`movieId` INTEGER NOT NULL, `actorId` INTEGER NOT NULL, `isMain` INTEGER NOT NULL, `orderIndex` INTEGER NOT NULL, PRIMARY KEY(`movieId`, `actorId`))"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_movie_actor_actorId` ON `movie_actor` (`actorId`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `audio_device_profile` (`deviceKey` TEXT NOT NULL, `displayName` TEXT NOT NULL, `outputType` TEXT NOT NULL, `codec` TEXT, `audioDelayMs` INTEGER NOT NULL, `lastUsedAt` INTEGER NOT NULL, PRIMARY KEY(`deviceKey`))"
            )
        }
    }

    /** v2 → v3 仅新增可丢弃的目录快照表，既有服务器、观看记录与元数据保持原样。 */
    internal val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `directory_entry_cache` (" +
                    "`serverId` INTEGER NOT NULL, `parentPath` TEXT NOT NULL, " +
                    "`relativePath` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                    "`isDirectory` INTEGER NOT NULL, `size` INTEGER NOT NULL, " +
                    "`lastModified` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`serverId`, `parentPath`, `relativePath`))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_directory_entry_cache_serverId_parentPath` " +
                    "ON `directory_entry_cache` (`serverId`, `parentPath`)"
            )
        }
    }

    /**
     * v3 → v4：演员资料改为服务器内唯一。
     *
     * 旧表没有 serverId；只能依据 movie_actor → movie_info 的现存关联为每台服务器复制。
     * 没有任何影片关联的孤立行无法可靠归属，迁移时丢弃，避免泄漏到任意服务器。
     */
    internal val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `actor_new` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`serverId` INTEGER NOT NULL, `name` TEXT NOT NULL, " +
                    "`nameZh` TEXT, `avatarRelPath` TEXT, `isFollowed` INTEGER NOT NULL)"
            )
            db.execSQL(
                """
                INSERT INTO actor_new (serverId, name, nameZh, avatarRelPath, isFollowed)
                SELECT movie_info.serverId, actor.name, actor.nameZh,
                       actor.avatarRelPath, actor.isFollowed
                FROM actor
                INNER JOIN movie_actor ON movie_actor.actorId = actor.id
                INNER JOIN movie_info ON movie_info.id = movie_actor.movieId
                GROUP BY movie_info.serverId, actor.name
                """.trimIndent()
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `movie_actor_new` (" +
                    "`movieId` INTEGER NOT NULL, `actorId` INTEGER NOT NULL, " +
                    "`isMain` INTEGER NOT NULL, `orderIndex` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`movieId`, `actorId`))"
            )
            db.execSQL(
                """
                INSERT INTO movie_actor_new (movieId, actorId, isMain, orderIndex)
                SELECT movie_actor.movieId, actor_new.id,
                       movie_actor.isMain, movie_actor.orderIndex
                FROM movie_actor
                INNER JOIN movie_info ON movie_info.id = movie_actor.movieId
                INNER JOIN actor ON actor.id = movie_actor.actorId
                INNER JOIN actor_new
                  ON actor_new.serverId = movie_info.serverId
                 AND actor_new.name = actor.name
                """.trimIndent()
            )
            db.execSQL("DROP TABLE movie_actor")
            db.execSQL("ALTER TABLE movie_actor_new RENAME TO movie_actor")
            db.execSQL("CREATE INDEX `index_movie_actor_actorId` ON `movie_actor` (`actorId`)")
            db.execSQL("DROP TABLE actor")
            db.execSQL("ALTER TABLE actor_new RENAME TO actor")
            db.execSQL(
                "CREATE UNIQUE INDEX `index_actor_serverId_name` " +
                    "ON `actor` (`serverId`, `name`)"
            )
            db.execSQL("CREATE INDEX `index_actor_serverId` ON `actor` (`serverId`)")
        }
    }
}
