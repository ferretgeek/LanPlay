package com.lanplay.player.data

import android.content.Context
import android.net.Uri
import com.lanplay.player.smb.SUBTITLE_EXTENSIONS
import com.lanplay.player.smb.SmbConnectionManager
import com.lanplay.player.smb.SmbFileRepository
import com.lanplay.player.smb.SmbTarget
import com.lanplay.player.smb.io.SmbFileHandle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class PreparedSubtitle(
    val sourcePath: String,
    val localUri: Uri,
    val charset: String,
    val mimeType: String,
    val firstTextLine: String,
    val sessionDirectory: File,
    val timeline: List<TimedSubtitleCue> = emptyList(),
)

data class TimedSubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

data class SubtitleSearchHit(val positionMs: Long, val text: String)

internal fun chooseSubtitleName(base: String, names: List<String>): String? {
    val byLower = names.associateBy { it.lowercase() }
    // .idx 必须排在 .sub 前，确保 VobSub 成对加载；没有 .idx 时仍允许 MicroDVD .sub。
    val orderedExtensions = listOf("srt", "ass", "ssa", "vtt", "idx", "sub", "ttml", "smi")
    for (ext in orderedExtensions) {
        byLower["${base.lowercase()}.$ext"]?.let { return it }
    }
    val languages = listOf(
        "chs", "sc", "zh-cn", "zh-hans", "zh", "cn", "简体", "中文", "chi",
        "cht", "tc", "zh-tw", "zh-hant", "繁体", "eng", "en", "jpn", "ja", "jp",
    )
    for (lang in languages) {
        for (ext in orderedExtensions) {
            listOf("$base.$lang.$ext", "${base}_$lang.$ext", "$base.$lang.forced.$ext")
                .firstNotNullOfOrNull { byLower[it.lowercase()] }
                ?.let { return it }
        }
    }
    return names
        .filter { subtitleStemMatches(base, it.substringBeforeLast('.')) }
        .sortedWith(compareBy<String> { subtitleLanguageRank(it) }.thenBy { it.length })
        .firstOrNull()
}

internal fun subtitleStemMatches(base: String, candidateStem: String): Boolean {
    if (!candidateStem.startsWith(base, ignoreCase = true)) return false
    if (candidateStem.length == base.length) return true
    return candidateStem[base.length] in charArrayOf('.', '_', '-', ' ', '(', '[', '【')
}

private fun subtitleLanguageRank(name: String): Int {
    val normalized = name.lowercase()
    return when {
        listOf("chs", "sc", "zh-cn", "zh-hans", "简体", "中文").any {
            it in normalized
        } -> 0
        listOf("cht", "tc", "zh-tw", "zh-hant", "繁体").any { it in normalized } -> 1
        listOf(".eng", ".en.", "_en.").any { it in normalized } -> 2
        listOf(".jpn", ".ja.", "_ja.", ".jp.").any { it in normalized } -> 3
        else -> 4
    }
}

internal fun searchAssSubtitleLines(
    lines: Sequence<String>,
    needle: String,
    limit: Int,
): List<SubtitleSearchHit> {
    val hits = mutableListOf<SubtitleSearchHit>()
    for (line in lines) {
        if (!line.startsWith("Dialogue:", true)) continue
        val parts = line.substringAfter(':').split(',', limit = 10)
        if (parts.size >= 10) {
            val body = parts[9].replace("\\N", " ")
                .replace(Regex("""\{[^}]+\}"""), "")
                .trim()
            if (body.contains(needle, true)) {
                parseSubtitleTimeValue(parts[1].trim())?.let {
                    hits += SubtitleSearchHit(it, body)
                }
            }
        }
        if (hits.size >= limit) break
    }
    return hits
}

private fun parseSubtitleTimeValue(value: String): Long? {
    // WebVTT 结束时间后可跟 `align:start` 等带冒号的 cue settings；
    // 必须先截出时间 token，再按冒号拆时分秒。
    val normalized = value.trim().substringBefore(' ').replace(',', '.')
    val parts = normalized.split(':')
    if (parts.size !in 2..3) return null
    val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
    val minutes = parts[parts.lastIndex - 1].toLongOrNull() ?: return null
    val seconds = parts.last().toDoubleOrNull() ?: return null
    return ((hours * 3_600 + minutes * 60) * 1_000 + seconds * 1_000).toLong()
}

internal fun parseTextSubtitleTimeline(
    text: String,
    extension: String,
): List<TimedSubtitleCue> {
    if (extension !in setOf("srt", "vtt")) return emptyList()
    return text.replace("\r\n", "\n")
        .replace('\r', '\n')
        .split(Regex("\n{2,}"))
        .mapNotNull { block ->
            val lines = block.lines()
            val timeIndex = lines.indexOfFirst { "-->" in it }
            if (timeIndex < 0) return@mapNotNull null
            val timeLine = lines[timeIndex]
            val start = parseSubtitleTimeValue(timeLine.substringBefore("-->").trim())
                ?: return@mapNotNull null
            val end = parseSubtitleTimeValue(timeLine.substringAfter("-->").trim())
                ?: return@mapNotNull null
            if (end <= start) return@mapNotNull null
            val body = lines.drop(timeIndex + 1)
                .joinToString("\n")
                .replace(Regex("<[^>]+>"), "")
                .trim()
            if (body.isEmpty()) null else TimedSubtitleCue(start, end, body)
        }
        .sortedBy { it.startMs }
}

/**
 * 同目录字幕匹配、编码识别与 UTF-8 本地化（S-01~S-06）。
 * VLC 字幕只交给原生字幕 Surface，以保留 ASS/libass 特效和 VobSub 图形；不会用纯文本覆盖。
 */
@Singleton
class SubtitleRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val files: SmbFileRepository,
    private val connections: SmbConnectionManager,
) {
    suspend fun searchLocal(
        subtitle: PreparedSubtitle,
        query: String,
        limit: Int = 50,
    ): List<SubtitleSearchHit> = withContext(Dispatchers.IO) {
        val needle = query.trim()
        if (needle.isEmpty()) return@withContext emptyList()
        val safeLimit = limit.coerceIn(1, 500)
        val file = File(requireNotNull(subtitle.localUri.path))
        if (!file.isFile) return@withContext emptyList()
        val text = file.readText(Charsets.UTF_8)
        val extension = file.extension.lowercase()
        val hits = mutableListOf<SubtitleSearchHit>()
        if (extension == "srt" || extension == "vtt") {
            val blocks = text.replace("\r\n", "\n").split(Regex("\n{2,}"))
            for (block in blocks) {
                val lines = block.lines()
                val timeLine = lines.firstOrNull { "-->" in it } ?: continue
                val body = lines.dropWhile { it != timeLine }.drop(1).joinToString(" ")
                    .replace(Regex("<[^>]+>"), "")
                    .trim()
                if (body.contains(needle, ignoreCase = true)) {
                    parseSubtitleTimeValue(timeLine.substringBefore("-->").trim())?.let {
                        hits += SubtitleSearchHit(it, body)
                    }
                }
                if (hits.size >= safeLimit) break
            }
        } else if (extension == "ass" || extension == "ssa") {
            hits += searchAssSubtitleLines(text.lineSequence(), needle, safeLimit)
        }
        hits.take(safeLimit)
    }

    suspend fun listCandidates(target: SmbTarget, videoPath: String): List<String> =
        withContext(Dispatchers.IO) {
            val dir = videoPath.substringBeforeLast('/', "")
            files.list(target, dir)
                .filter { !it.isDirectory && it.extension in SUBTITLE_EXTENSIONS }
                .map { it.relativePath }
                .sortedBy { it.substringAfterLast('/').lowercase() }
        }

    suspend fun prepare(
        target: SmbTarget,
        videoPath: String,
        sourcePath: String? = null,
        charsetOverride: String? = null,
        offsetMs: Long = 0L,
    ): PreparedSubtitle? =
        withContext(Dispatchers.IO) {
            val dir = videoPath.substringBeforeLast('/', "")
            val base = videoPath.substringAfterLast('/').substringBeforeLast('.')
            val candidates = files.list(target, dir)
                .filter { !it.isDirectory && it.extension in SUBTITLE_EXTENSIONS }
            var path = if (sourcePath != null) {
                candidates.firstOrNull { it.relativePath == sourcePath }?.relativePath
                    ?: return@withContext null
            } else {
                val chosen = chooseSubtitleName(base, candidates.map { it.name })
                    ?: return@withContext null
                if (dir.isEmpty()) chosen else "$dir/$chosen"
            }
            // VobSub 是同名 .idx（时间轴/调色板）+.sub（二进制图形）一对文件。
            // 用户点到 .sub 时也统一以 .idx 为主入口，VLC 才能正确识别整对字幕。
            if (path.substringAfterLast('.', "").equals("sub", true)) {
                val stem = path.substringBeforeLast('.')
                candidates.firstOrNull {
                    it.relativePath.equals("$stem.idx", ignoreCase = true)
                }?.let { path = it.relativePath }
            }
            val bytes = readAll(target, path)
            val decoded = decode(bytes, charsetOverride)
            val extension = path.substringAfterLast('.', "srt").lowercase()
            val shiftedText = shiftTimestamps(decoded.text, extension, offsetMs)
            val timeline = parseTextSubtitleTimeline(decoded.text, extension)

            val outDir = File(
                File(context.cacheDir, "subtitles"),
                UUID.randomUUID().toString(),
            ).apply { check(mkdirs() || isDirectory) { "无法创建字幕临时目录" } }
            val sourceEntry = candidates.firstOrNull { it.relativePath == path }
            val keyMaterial = listOf(
                target.identity,
                videoPath,
                path,
                sourceEntry?.size ?: 0L,
                sourceEntry?.lastModified ?: 0L,
                charsetOverride.orEmpty(),
                offsetMs,
            ).joinToString("|")
            val safeName = MessageDigest.getInstance("SHA-256")
                .digest(keyMaterial.toByteArray(Charsets.UTF_8))
                .take(16)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val output = File(outDir, "$safeName.$extension")
            try {
                writeAtomically(output, shiftedText.toByteArray(Charsets.UTF_8))
                if (extension == "idx") {
                    val stem = path.substringBeforeLast('.')
                    val pair = candidates.firstOrNull {
                        it.relativePath.equals("$stem.sub", ignoreCase = true)
                    } ?: error("VobSub 缺少同名 .sub 文件")
                    copyToLocal(
                        target,
                        pair.relativePath,
                        File(outDir, "$safeName.sub"),
                        MAX_VOBSUB_BYTES,
                    )
                }
                PreparedSubtitle(
                    sourcePath = path,
                    localUri = Uri.fromFile(output),
                    charset = decoded.charset,
                    mimeType = mimeType(extension),
                    firstTextLine = shiftedText.lineSequence()
                        .map { it.trim() }
                        .firstOrNull {
                            it.isNotEmpty() && it.toIntOrNull() == null && !it.contains("-->")
                        }
                        .orEmpty(),
                    sessionDirectory = outDir,
                    // 时间轴始终保留源字幕时间，实时偏移只改变播放端查询位置。
                    timeline = timeline,
                )
            } catch (t: Throwable) {
                outDir.deleteRecursively()
                throw t
            }
        }

    private fun shiftTimestamps(text: String, extension: String, offsetMs: Long): String {
        if (offsetMs == 0L) return text
        return when (extension) {
            "srt", "vtt" -> text.lineSequence().joinToString("\n") { line ->
                if (!line.contains("-->")) line else {
                    TIME_TOKEN.replace(line) { match ->
                        formatTimestamp(
                            (parseTimestamp(match.value) + offsetMs).coerceAtLeast(0L),
                            match.value.contains(','),
                            match.value.count { it == ':' } >= 2,
                        )
                    }
                }
            }
            "ass", "ssa" -> text.lineSequence().joinToString("\n") { line ->
                if (!line.startsWith("Dialogue:", ignoreCase = true)) line else {
                    var seen = 0
                    ASS_TIME_TOKEN.replace(line) { match ->
                        if (seen++ >= 2) match.value else {
                            formatAssTimestamp(
                                (parseAssTimestamp(match.value) + offsetMs).coerceAtLeast(0L)
                            )
                        }
                    }
                }
            }
            else -> text
        }
    }

    private fun parseTimestamp(value: String): Long {
        val normalized = value.replace(',', '.')
        val parts = normalized.split(':')
        val seconds = parts.last().toDoubleOrNull() ?: 0.0
        val minutes = parts.getOrNull(parts.lastIndex - 1)?.toLongOrNull() ?: 0L
        val hours = parts.getOrNull(parts.lastIndex - 2)?.toLongOrNull() ?: 0L
        return ((hours * 3600 + minutes * 60) * 1000 + seconds * 1000).toLong()
    }

    private fun formatTimestamp(ms: Long, comma: Boolean, includeHours: Boolean): String {
        val hours = ms / 3_600_000
        val minutes = ms / 60_000 % 60
        val seconds = ms / 1_000 % 60
        val millis = ms % 1_000
        val separator = if (comma) ',' else '.'
        return if (includeHours) {
            "%02d:%02d:%02d%c%03d".format(hours, minutes, seconds, separator, millis)
        } else {
            "%02d:%02d%c%03d".format(minutes + hours * 60, seconds, separator, millis)
        }
    }

    private fun parseAssTimestamp(value: String): Long {
        val parts = value.split(':', '.')
        if (parts.size != 4) return 0L
        return (parts[0].toLongOrNull() ?: 0L) * 3_600_000 +
            (parts[1].toLongOrNull() ?: 0L) * 60_000 +
            (parts[2].toLongOrNull() ?: 0L) * 1_000 +
            (parts[3].toLongOrNull() ?: 0L) * 10
    }

    private fun formatAssTimestamp(ms: Long): String =
        "%d:%02d:%02d.%02d".format(
            ms / 3_600_000,
            ms / 60_000 % 60,
            ms / 1_000 % 60,
            ms / 10 % 100,
        )

    private suspend fun readAll(target: SmbTarget, path: String): ByteArray {
        val handle = SmbFileHandle.open(connections, target, path, SmbConnectionManager.Channel.AUX)
        try {
            require(handle.size in 1..MAX_SUBTITLE_BYTES) { "文本字幕文件过大" }
            val out = ByteArray(handle.size.toInt())
            val read = handle.readFully(0, out, 0, out.size)
            check(read == out.size) { "字幕读取不完整" }
            return out
        } finally {
            handle.close()
        }
    }

    private suspend fun copyToLocal(
        target: SmbTarget,
        path: String,
        output: File,
        maxBytes: Long,
    ) {
        val handle = SmbFileHandle.open(
            connections,
            target,
            path,
            SmbConnectionManager.Channel.AUX,
        )
        val part = File(output.parentFile, ".${output.name}.${UUID.randomUUID()}.part")
        try {
            require(handle.size in 1..maxBytes) { "VobSub 图形字幕超过磁盘缓存上限" }
            FileOutputStream(part).use { stream ->
                val buffered = BufferedOutputStream(stream)
                val chunk = ByteArray(COPY_BUFFER_BYTES)
                var offset = 0L
                while (offset < handle.size) {
                    val wanted = minOf(chunk.size.toLong(), handle.size - offset).toInt()
                    val read = handle.readFully(offset, chunk, 0, wanted)
                    check(read == wanted) { "VobSub 字幕读取不完整" }
                    buffered.write(chunk, 0, read)
                    offset += read
                }
                buffered.flush()
                stream.fd.sync()
            }
            replaceAtomically(part, output)
        } finally {
            handle.close()
            if (part.exists()) part.delete()
        }
    }

    private fun writeAtomically(output: File, bytes: ByteArray) {
        val part = File(output.parentFile, ".${output.name}.${UUID.randomUUID()}.part")
        try {
            FileOutputStream(part).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            replaceAtomically(part, output)
        } finally {
            if (part.exists()) part.delete()
        }
    }

    private fun replaceAtomically(part: File, output: File) {
        try {
            Files.move(
                part.toPath(),
                output.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(part.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private data class Decoded(val text: String, val charset: String)

    private fun decode(bytes: ByteArray, charsetOverride: String? = null): Decoded {
        if (charsetOverride != null) {
            val charset = runCatching { Charset.forName(charsetOverride) }.getOrNull()
                ?: error("不支持字幕编码 $charsetOverride")
            val offset = when {
                charset.name().equals("UTF-8", true) &&
                    bytes.startsWith(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) -> 3
                charset.name().equals("UTF-16LE", true) &&
                    bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) -> 2
                charset.name().equals("UTF-16BE", true) &&
                    bytes.startsWith(byteArrayOf(0xFE.toByte(), 0xFF.toByte())) -> 2
                else -> 0
            }
            return Decoded(String(bytes, offset, bytes.size - offset, charset), charset.name())
        }
        if (bytes.startsWith(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))) {
            return Decoded(String(bytes, 3, bytes.size - 3, Charsets.UTF_8), "UTF-8")
        }
        if (bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))) {
            return Decoded(String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE), "UTF-16LE")
        }
        if (bytes.startsWith(byteArrayOf(0xFE.toByte(), 0xFF.toByte()))) {
            return Decoded(String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE), "UTF-16BE")
        }
        val utf8 = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
        if (utf8 != null) return Decoded(utf8, "UTF-8")

        val candidates = listOf("GB18030", "GBK", "Big5", "Shift_JIS", "EUC-JP")
        val best = candidates.mapNotNull { name ->
            runCatching {
                val text = String(bytes, charset(name))
                Triple(name, text, score(text))
            }.getOrNull()
        }.maxByOrNull { it.third }
        return if (best != null) Decoded(best.second, best.first)
        else Decoded(String(bytes, Charsets.UTF_8), "UTF-8")
    }

    private fun score(text: String): Int =
        text.count { it in '\u4E00'..'\u9FFF' } * 3 -
            text.count { it == '\uFFFD' } * 50 -
            text.count { it.isISOControl() && it !in "\r\n\t" } * 10

    private fun mimeType(extension: String): String = when (extension) {
        "srt" -> "application/x-subrip"
        "vtt" -> "text/vtt"
        "ttml" -> "application/ttml+xml"
        "ssa", "ass" -> "text/x-ssa"
        "idx", "sub" -> "application/vobsub"
        "smi" -> "application/x-sami"
        else -> "application/x-subrip"
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private companion object {
        const val MAX_SUBTITLE_BYTES = 8L * 1024L * 1024L
        const val MAX_VOBSUB_BYTES = 512L * 1024L * 1024L
        const val COPY_BUFFER_BYTES = 1024 * 1024
        val TIME_TOKEN = Regex("""(?:(?:\d{1,2}):)?\d{2}:\d{2}[,.]\d{3}""")
        val ASS_TIME_TOKEN = Regex("""\d{1,2}:\d{2}:\d{2}\.\d{2}""")
    }
}
