package com.lanplay.player.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.graphics.Bitmap
import androidx.room.withTransaction
import com.lanplay.player.data.db.ActorDao
import com.lanplay.player.data.db.ActorEntity
import com.lanplay.player.data.db.MovieActorDao
import com.lanplay.player.data.db.MovieActorEntity
import com.lanplay.player.data.db.MovieInfoDao
import com.lanplay.player.data.db.MovieInfoEntity
import com.lanplay.player.data.db.MediaMetaDao
import com.lanplay.player.data.db.MediaMetaEntity
import com.lanplay.player.data.db.LanPlayDatabase
import com.lanplay.player.smb.SmbConnectionManager
import com.lanplay.player.smb.SmbFileRepository
import com.lanplay.player.smb.SmbEntry
import com.lanplay.player.smb.VIDEO_EXTENSIONS
import com.lanplay.player.smb.proxy.LocalMediaProxy
import com.lanplay.player.data.prefs.SettingsRepository
import com.lanplay.player.data.crypto.CacheCipher
import com.lanplay.player.smb.io.SmbFileHandle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.ByteArrayOutputStream
import java.time.OffsetDateTime
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

internal fun failedProbeMetadata(
    old: MediaMetaEntity?,
    serverId: Long,
    entry: SmbEntry,
    probedAt: Long,
): MediaMetaEntity {
    val reusable = old?.takeIf {
        it.fileSize == entry.size && it.lastModified == entry.lastModified
    }
    return (reusable ?: MediaMetaEntity(
        id = old?.id ?: 0,
        serverId = serverId,
        fullPath = entry.relativePath,
        fileName = entry.name,
        fileSize = entry.size,
        lastModified = entry.lastModified,
    )).copy(
        fileName = entry.name,
        probedAt = probedAt,
        probeFailed = true,
    )
}

@Serializable
private data class MetadataIndex(
    val version: Int = 1,
    val generatedAt: String = "",
    val scanRoot: String = "",
    // 旧版索引没有这个字段。为避免把无法确认完整性的旧文件当成权威快照，
    // 默认 false，只允许新版刮削器的最终写入执行破坏性清理。
    val complete: Boolean = false,
    val items: Map<String, MetadataItem> = emptyMap(),
)

internal fun metadataIndexAllowsPruning(complete: Boolean): Boolean = complete

@Serializable
private data class MetadataItem(
    val code: String,
    val title: String? = null,
    val titleZh: String? = null,
    val releaseDate: String? = null,
    val runtimeMin: Int? = null,
    val studio: String? = null,
    val label: String? = null,
    val series: String? = null,
    val genres: List<String> = emptyList(),
    val actors: List<MetadataActor> = emptyList(),
    val poster: String? = null,
    val cover: String? = null,
    val source: String? = null,
    val scrapedAt: String = "",
)

@Serializable
private data class MetadataActor(
    val name: String,
    val nameZh: String? = null,
    val avatar: String? = null,
    val isMain: Boolean = false,
)

data class MovieDisplay(
    val code: String,
    val title: String?,
    val releaseDate: String?,
    val runtimeMin: Int?,
    val studio: String?,
    val label: String?,
    val series: String?,
    val genres: List<String>,
    val actorNames: List<String>,
    val actorAvatarFiles: List<File?>,
    val posterFile: File?,
)

/** 读取 PC 刮削工具生成的 .lanplay_meta/index.json，并合并到 Room（M-01~M-07）。 */
@Singleton
class MetadataRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val files: SmbFileRepository,
    private val connections: SmbConnectionManager,
    private val movieDao: MovieInfoDao,
    private val actorDao: ActorDao,
    private val movieActorDao: MovieActorDao,
    private val mediaMetaDao: MediaMetaDao,
    private val cacheRepository: CacheRepository,
    private val localMediaProxy: LocalMediaProxy,
    private val settingsRepository: SettingsRepository,
    private val database: LanPlayDatabase,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    suspend fun refresh(server: SavedServer): Boolean = withContext(Dispatchers.IO) {
        val stat = files.stat(server.target, INDEX_PATH) ?: return@withContext false
        val cached = mediaMetaDao.get(server.id, INDEX_PATH)
        if (
            cached != null &&
            cached.fileSize == stat.size &&
            cached.lastModified == stat.lastModified &&
            !cached.probeFailed
        ) {
            return@withContext true
        }
        val indexBytes = readRemote(server, INDEX_PATH, MAX_INDEX_BYTES) ?: return@withContext false
        val metadataCache = File(context.cacheDir, "metadata").apply { mkdirs() }
        CacheCipher.writeEncrypted(
            File(metadataCache, "server-${server.id}-index.json.lpc"),
            indexBytes,
        )
        val index = try {
            json.decodeFromString<MetadataIndex>(indexBytes.toString(Charsets.UTF_8))
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return@withContext false
        }
        require(index.version == 1) { "不支持的元数据索引版本" }
        require(index.items.size <= MAX_INDEX_ITEMS) { "元数据索引项目过多" }
        val root = SmbFileRepository.normalizeRelativePath(index.scanRoot)
        database.withTransaction {
            val previous = movieDao.listServer(server.id).associateBy { it.fullPath }
            val importedPaths = hashSetOf<String>()
            for ((fileName, item) in index.items) {
                val relative = SmbFileRepository.normalizeRelativePath(fileName)
                val fullPath = SmbFileRepository.normalizeRelativePath(
                    if (root.isEmpty()) relative else "$root/$relative"
                )
                importedPaths += fullPath
                val existing = previous[fullPath]
                val scrapedAt = parseTime(item.scrapedAt.ifEmpty { index.generatedAt })
                val scraped = MovieInfoEntity(
                    id = existing?.id ?: 0,
                    serverId = server.id,
                    fullPath = fullPath,
                    code = item.code.take(80),
                    title = item.title?.take(500),
                    titleZh = item.titleZh?.take(500),
                    releaseDate = item.releaseDate?.take(32),
                    runtimeMin = item.runtimeMin?.coerceIn(0, 24 * 60),
                    studio = item.studio?.take(200),
                    label = item.label?.take(200),
                    series = item.series?.take(200),
                    genresJson = json.encodeToString(item.genres.take(100).map { it.take(100) }),
                    posterRelPath = item.poster,
                    coverRelPath = item.cover,
                    source = item.source?.take(100),
                    scrapedAt = scrapedAt,
                    userOverride = existing?.userOverride ?: false,
                )
                val entity = if (existing?.userOverride == true) existing else scraped
                val movieId = if (entity.id == 0L) movieDao.upsert(entity) else {
                    movieDao.upsert(entity)
                    entity.id
                }
                movieActorDao.deleteForMovie(movieId)
                val links = item.actors.take(200).mapIndexedNotNull { order, actor ->
                    if (actor.name.isBlank()) return@mapIndexedNotNull null
                    val actorName = actor.name.trim().take(200)
                    val old = actorDao.findByName(server.id, actorName)
                    val actorEntity = ActorEntity(
                        id = old?.id ?: 0,
                        serverId = server.id,
                        name = actorName,
                        nameZh = actor.nameZh?.take(200) ?: old?.nameZh,
                        avatarRelPath = actor.avatar ?: old?.avatarRelPath,
                        isFollowed = old?.isFollowed ?: false,
                    )
                    val actorId = if (actorEntity.id == 0L) actorDao.upsert(actorEntity) else {
                        actorDao.upsert(actorEntity)
                        actorEntity.id
                    }
                    MovieActorEntity(movieId, actorId, actor.isMain, order)
                }
                if (links.isNotEmpty()) movieActorDao.insertAll(links)
            }
            if (metadataIndexAllowsPruning(index.complete)) {
                previous.values.filter { it.fullPath !in importedPaths }.forEach { stale ->
                    movieActorDao.deleteForMovie(stale.id)
                    movieDao.deleteById(stale.id)
                }
                actorDao.deleteOrphans(server.id)
            }
            mediaMetaDao.upsert(
                MediaMetaEntity(
                    id = cached?.id ?: 0,
                    serverId = server.id,
                    fullPath = INDEX_PATH,
                    fileName = "index.json",
                    fileSize = stat.size,
                    lastModified = stat.lastModified,
                    probedAt = System.currentTimeMillis(),
                )
            )
        }
        true
    }

    suspend fun displays(
        server: SavedServer,
        paths: List<String>,
    ): Map<String, MovieDisplay> = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext emptyMap()
        val movies = paths.chunked(SQLITE_IN_BATCH_SIZE)
            .flatMap { movieDao.getMany(server.id, it) }
        if (movies.isEmpty()) return@withContext emptyMap()
        val links = movies.map { it.id }.chunked(SQLITE_IN_BATCH_SIZE)
            .flatMap { movieActorDao.listForMovies(it) }
        val actors = links.map { it.actorId }.distinct().chunked(SQLITE_IN_BATCH_SIZE)
            .flatMap { actorDao.getMany(it) }
            .associateBy { it.id }
        val linksByMovie = links.groupBy { it.movieId }
        // 每批最多两部，避免大媒体库为每条记录同时创建协程和图片缓冲。
        movies.chunked(2).flatMap { chunk ->
            coroutineScope {
                chunk.map { movie ->
                    async {
                        val poster = movie.posterRelPath?.let { cacheImageOrNull(server, it) }
                        val names = linksByMovie[movie.id].orEmpty()
                            .sortedBy { it.orderIndex }
                            .mapNotNull { actors[it.actorId] }
                            .map { it.nameZh ?: it.name }
                        val linkedActors = linksByMovie[movie.id].orEmpty()
                            .sortedBy { it.orderIndex }
                            .mapNotNull { actors[it.actorId] }
                        val avatars = linkedActors.map { actor ->
                            actor.avatarRelPath?.let { cacheImageOrNull(server, it) }
                        }
                        movie.fullPath to MovieDisplay(
                            code = movie.code,
                            title = movie.titleZh ?: movie.title,
                            releaseDate = movie.releaseDate,
                            runtimeMin = movie.runtimeMin,
                            studio = movie.studio,
                            label = movie.label,
                            series = movie.series,
                            genres = runCatching {
                                json.decodeFromString<List<String>>(movie.genresJson)
                            }.getOrDefault(emptyList()),
                            actorNames = names,
                            actorAvatarFiles = avatars,
                            posterFile = poster,
                        )
                    }
                }.awaitAll()
            }
        }.toMap()
    }

    /** 演员索引等非影片卡片复用同一套私有图片缓存。 */
    suspend fun artwork(server: SavedServer, relativePath: String?): File? =
        relativePath?.let { cacheImageOrNull(server, it) }

    private suspend fun cacheImageOrNull(server: SavedServer, relativePath: String): File? =
        try {
            cacheImage(server, relativePath)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            null
        }

    /**
     * C-14~C-16：顺序探测目录中的视频并生成 10% 位置缩略图。
     * 以 (size, mtime) 为缓存指纹；未变化的文件绝不重复打开。
     */
    suspend fun probeDirectory(
        server: SavedServer,
        entries: List<SmbEntry>,
        force: Boolean = false,
    ): Int = withContext(Dispatchers.IO) {
        var changed = 0
        val thumbnails = File(context.cacheDir, "thumbnails").apply { mkdirs() }
        val probeIo = settingsRepository.currentIoSettings().copy(
            prefetchMb = 4,
            concurrentReads = 2,
        )
        entries.filter { !it.isDirectory && it.extension in VIDEO_EXTENSIONS }.forEach { entry ->
            val old = mediaMetaDao.get(server.id, entry.relativePath)
            val unchanged = old != null &&
                old.fileSize == entry.size &&
                old.lastModified == entry.lastModified
            val failedRecently = old?.probeFailed == true &&
                System.currentTimeMillis() - old.probedAt < PROBE_RETRY_BACKOFF_MS
            if (!force && unchanged && (!old.probeFailed || failedRecently)) {
                return@forEach
            }
            var token: String? = null
            val result = runCatching {
                val url = localMediaProxy.publish(
                    server.target,
                    entry.relativePath,
                    probeIo,
                    SmbConnectionManager.Channel.AUX,
                )
                token = url.substringAfterLast('/')
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(url, emptyMap())
                    val duration = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull()
                    val width = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                    )?.toIntOrNull()
                    val height = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                    )?.toIntOrNull()
                    val bitrate = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_BITRATE
                    )?.toLongOrNull()
                    val frameRate = if (android.os.Build.VERSION.SDK_INT >= 23) {
                        retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE
                        )?.toFloatOrNull()
                    } else null
                    val thumbnailFile = File(
                        thumbnails,
                        stableCacheKey(
                            "${server.id}|${entry.relativePath}|${entry.size}|${entry.lastModified}"
                        ) + ".jpg",
                    )
                    if (!thumbnailFile.isFile || force) {
                        val frameTimeUs = (duration ?: 0L).coerceAtLeast(0L) * 100L
                        retriever.getFrameAtTime(
                            frameTimeUs,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        )?.let { frame ->
                            val scaled = scaleThumbnail(frame, 480, 270)
                            val encoded = ByteArrayOutputStream()
                            check(scaled.compress(Bitmap.CompressFormat.JPEG, 86, encoded))
                            CacheCipher.writeEncrypted(thumbnailFile, encoded.toByteArray())
                            if (scaled !== frame) scaled.recycle()
                            frame.recycle()
                        }
                    }
                    MediaMetaEntity(
                        id = old?.id ?: 0,
                        serverId = server.id,
                        fullPath = entry.relativePath,
                        fileName = entry.name,
                        fileSize = entry.size,
                        lastModified = entry.lastModified,
                        durationMs = duration,
                        width = width,
                        height = height,
                        bitrate = bitrate,
                        frameRate = frameRate,
                        thumbnailPath = thumbnailFile.takeIf { it.isFile }?.absolutePath,
                        probedAt = System.currentTimeMillis(),
                    )
                } finally {
                    retriever.release()
                }
            }
            withContext(NonCancellable) {
                token?.let { localMediaProxy.release(it) }
            }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            result.onSuccess {
                mediaMetaDao.upsert(it)
                changed++
            }.onFailure {
                mediaMetaDao.upsert(
                    failedProbeMetadata(
                        old = old,
                        serverId = server.id,
                        entry = entry,
                        probedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
        changed
    }

    private fun scaleThumbnail(source: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val factor = minOf(maxWidth / source.width.toFloat(), maxHeight / source.height.toFloat(), 1f)
        if (factor >= 1f) return source
        return Bitmap.createScaledBitmap(
            source,
            (source.width * factor).toInt().coerceAtLeast(1),
            (source.height * factor).toInt().coerceAtLeast(1),
            true,
        )
    }

    private suspend fun cacheImage(server: SavedServer, relative: String): File? {
        val safeRelative = SmbFileRepository.normalizeRelativePath(relative)
        val remote = SmbFileRepository.normalizeRelativePath("$META_ROOT/$safeRelative")
        require(remote.startsWith("$META_ROOT/")) { "元数据图片路径越界" }
        val key = stableCacheKey("${server.id}|$remote")
        val extension = relative.substringAfterLast('.', "jpg").take(5)
        val dir = File(context.cacheDir, "metadata-images").apply { mkdirs() }
        val out = File(dir, "$key.$extension")
        if (out.exists() && out.length() > 0) {
            if (!CacheCipher.isEncrypted(out) && !CacheCipher.encryptLegacyFile(out)) {
                // 可再生缓存加密失败时 fail-closed，不继续暴露明文文件。
                out.delete()
            } else {
                cacheRepository.touch(out)
                return out
            }
        }
        val bytes = readRemote(server, remote, MAX_IMAGE_BYTES) ?: return null
        CacheCipher.writeEncrypted(out, bytes)
        cacheRepository.pruneArtwork()
        return out
    }

    private suspend fun readRemote(server: SavedServer, path: String, maxBytes: Long): ByteArray? {
        val handle = try {
            SmbFileHandle.open(
                connections,
                server.target,
                path,
                SmbConnectionManager.Channel.AUX,
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return null
        }
        try {
            if (handle.size !in 1..maxBytes) return null
            val data = ByteArray(handle.size.toInt())
            val n = handle.readFully(0, data, 0, data.size)
            check(n == data.size) { "远端元数据读取不完整" }
            return data
        } finally {
            handle.close()
        }
    }

    private fun stableCacheKey(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun parseTime(value: String): Long =
        runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrDefault(0L)

    private companion object {
        const val META_ROOT = ".lanplay_meta"
        const val INDEX_PATH = "$META_ROOT/index.json"
        const val MAX_INDEX_BYTES = 32L * 1024L * 1024L
        const val MAX_INDEX_ITEMS = 100_000
        const val MAX_IMAGE_BYTES = 24L * 1024L * 1024L
        const val PROBE_RETRY_BACKOFF_MS = 60L * 60L * 1_000L
        const val SQLITE_IN_BATCH_SIZE = 900
    }
}
