package com.lanplay.player.smb

import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.lanplay.player.core.log.Metric
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 需求 C-09：默认只显示视频文件 */
val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "avi", "mov", "ts", "m2ts", "mts", "flv",
    "wmv", "webm", "m4v", "mpg", "mpeg", "rmvb", "rm", "3gp", "vob", "iso",
)

/** 需求 S-03：字幕格式 */
val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "sub", "idx", "ttml", "smi")

@Singleton
class SmbFileRepository @Inject constructor(
    private val connections: SmbConnectionManager,
) {

    /**
     * 列目录。
     *
     * 需求 C-10：'.' 开头的目录（.lanplay_meta / .lanplay_trash）**始终隐藏**，
     * 不受「显示全部文件」开关影响，所以过滤放在这一层而不是 UI 层。
     *
     * @param relativePath 相对共享根，'/' 分隔，空串表示共享根
     */
    suspend fun list(
        target: SmbTarget,
        relativePath: String,
        channel: SmbConnectionManager.Channel = SmbConnectionManager.Channel.AUX,
    ): List<SmbEntry> = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val smbPath = toSmbPath(relativePath)

        val raw = try {
            connections.withShare(target, channel) { it.list(smbPath) }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val code = connections.classify(t)
            Metric.error(code, t.message, "path_hash" to relativePath.hashCode())
            throw SmbException(code, "无法打开目录「${relativePath.ifEmpty { "/" }}」", t)
        }
        if (raw.size > MAX_DIRECTORY_ENTRIES) {
            throw SmbException(
                SmbErrorCode.READ_FAILED,
                "这个目录包含超过 $MAX_DIRECTORY_ENTRIES 个项目，请拆分后再浏览",
            )
        }
        val nameBytes = raw.sumOf { it.fileName.toByteArray(Charsets.UTF_8).size.toLong() }
        if (nameBytes > MAX_DIRECTORY_NAME_BYTES) {
            throw SmbException(SmbErrorCode.READ_FAILED, "目录项目名称总量过大，已停止读取")
        }

        val entries = raw.asSequence()
            .filter { it.fileName != "." && it.fileName != ".." }
            // 隐藏 APP 自己的元数据与回收站目录（C-10）
            .filter { !it.fileName.startsWith(".") }
            .map { it.toEntry(relativePath) }
            .sortedWith(compareByDescending<SmbEntry> { it.isDirectory }.thenBy { it.name })
            .toList()

        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        Metric.emit(
            "list",
            "path" to relativePath,
            "n" to entries.size,
            "videos" to entries.count { !it.isDirectory && it.extension in VIDEO_EXTENSIONS },
            "ms" to elapsedMs,
        )
        entries
    }

    /** 单文件信息，播放前拿大小用（>4GB 必须是 Long） */
    suspend fun stat(
        target: SmbTarget,
        relativePath: String,
        channel: SmbConnectionManager.Channel = SmbConnectionManager.Channel.AUX,
    ): SmbEntry? = withContext(Dispatchers.IO) {
        val smbPath = toSmbPath(relativePath)
        try {
            val info = connections.withShare(target, channel) { it.getFileInformation(smbPath) }
            SmbEntry(
                name = relativePath.substringAfterLast('/'),
                relativePath = relativePath,
                isDirectory = info.standardInformation.isDirectory,
                size = info.standardInformation.endOfFile,
                lastModified = info.basicInformation.lastWriteTime.toEpochMillis(),
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val code = connections.classify(t)
            if (code == SmbErrorCode.FILE_NOT_FOUND) null
            else throw SmbException(code, "无法读取文件信息", t)
        }
    }

    /** 存在性检查，供删除/字幕匹配用 */
    suspend fun exists(
        target: SmbTarget,
        relativePath: String,
        channel: SmbConnectionManager.Channel = SmbConnectionManager.Channel.AUX,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            connections.withShare(target, channel) { it.fileExists(toSmbPath(relativePath)) }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (connections.classify(t) == SmbErrorCode.FILE_NOT_FOUND) false else throw t
        }
    }

    private fun FileIdBothDirectoryInformation.toEntry(parent: String): SmbEntry {
        val isDir = (fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
        return SmbEntry(
            name = fileName,
            relativePath = if (parent.isEmpty()) fileName else "$parent/$fileName",
            isDirectory = isDir,
            size = if (isDir) 0L else endOfFile,
            lastModified = lastWriteTime.toEpochMillis(),
        )
    }

    companion object {
        private const val MAX_DIRECTORY_ENTRIES = 50_000
        private const val MAX_DIRECTORY_NAME_BYTES = 16L * 1024 * 1024

        /**
         * 规范化共享内相对路径。为兼容既有 `/目录` 配置，首尾 `/` 会被收敛；
         * 路径段本身不得改变父级语义或夹带 SMB/URL 控制字符。
         */
        fun normalizeRelativePath(relativePath: String): String {
            require('\\' !in relativePath) { "路径不能包含反斜杠" }
            require(relativePath.none { it.code < 0x20 || it.code == 0x7f }) {
                "路径不能包含控制字符"
            }
            val normalized = relativePath.trim().trim('/')
            if (normalized.isEmpty()) return ""
            val segments = normalized.split('/')
            require(segments.none { it.isEmpty() || it == "." || it == ".." }) {
                "路径包含不安全的目录段"
            }
            return segments.joinToString("/")
        }

        /** 对外统一 '/'，SMB 协议侧用 '\\'。共享名含中文由 smbj 处理。 */
        fun toSmbPath(relativePath: String): String =
            normalizeRelativePath(relativePath).replace('/', '\\')
    }
}
