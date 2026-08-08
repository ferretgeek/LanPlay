package com.lanplay.player.core.log

import android.content.Context
import android.util.Log
import com.lanplay.player.BuildConfig
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.FileOutputStream
import java.io.Writer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 结构化指标输出。每条一行 JSON，tag 固定 [TAG]，供 tools/verify.ps1 逐行解析。
 *
 * release 构建下 [ENABLED] 是编译期常量 false，整个 emit 体会被 R8 消除，
 * 不会有任何字符串拼接或日志调用残留。
 *
 * 注意：指标的**采集**（IoStats 等）在 release 下照常进行，调试面板（P-18）要用；
 * 这里剔除的只是 logcat 输出。
 */
object Metric {

    const val TAG = "LANPLAY_METRIC"

    // 必须是真正的编译期常量：BuildConfig.DEBUG 被 AGP 生成为 Boolean.parseBoolean(...)，
    // 不满足 const 要求；METRICS_ENABLED 由 buildConfigField 生成为 static final boolean，
    // release 下为 false，Kotlin 编译器会直接消除下面每个 emit 的函数体。
    private const val ENABLED = BuildConfig.METRICS_ENABLED

    /** 落盘文件名，用 `adb exec-out run-as <pkg> cat files/<此文件>` 取回 */
    const val SINK_NAME = "metrics.jsonl"

    private val executor: ExecutorService? =
        if (ENABLED) Executors.newSingleThreadExecutor { r -> Thread(r, "metric-sink").apply { isDaemon = true } } else null
    private val errorExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "error-sink").apply { isDaemon = true } }
    private val errorLock = Any()

    @Volatile
    private var sink: File? = null

    @Volatile
    private var writer: Writer? = null
    @Volatile
    private var errorSink: File? = null
    private var errorLineCount: Int = 0

    /**
     * 指标除了打 logcat，还落一份 JSONL 到应用私有目录。
     *
     * 起因是测试机（荣耀 MagicOS）对 logcat 全量加密，输出形如 `(HKS)…(HKE)`，
     * 一行明文都取不到，采集链路整个失效。文件通道不受这个机制影响，
     * 且 debug 构建可以用 run-as 直接读。
     */
    fun init(context: Context) {
        errorSink = File(context.filesDir, ERROR_SINK_NAME)
        // 旧版本曾先写明文、导出时才脱敏。升级后一次性清除历史文件，避免旧内容残留。
        val privacyMarker = File(context.noBackupFilesDir, ERROR_PRIVACY_MARKER)
        if (!privacyMarker.isFile) {
            val legacySink = checkNotNull(errorSink)
            val cleared = runCatching {
                if (!legacySink.exists() || legacySink.delete()) {
                    true
                } else {
                    FileOutputStream(legacySink, false).use { it.fd.sync() }
                    legacySink.length() == 0L
                }
            }.getOrDefault(false)
            // 删除和安全截断都失败时不写 marker，下次冷启动继续重试，不能永久保留旧明文。
            if (cleared) {
                runCatching {
                    privacyMarker.parentFile?.mkdirs()
                    privacyMarker.writeText("2", Charsets.US_ASCII)
                }
            }
        }
        synchronized(errorLock) {
            errorLineCount = errorSink
                ?.takeIf { it.isFile }
                ?.useLines(Charsets.UTF_8) { it.count() }
                ?: 0
        }
        if (!ENABLED) return
        val f = File(context.filesDir, SINK_NAME)
        sink = f
        executor?.execute { openWriter(f, append = true) }
    }

    /** 相当于 logcat -c：每轮验收开始前清空，避免读到上一轮的残留 */
    fun clear() {
        if (!ENABLED) return
        executor?.execute { sink?.let { openWriter(it, append = false) } }
    }

    private fun openWriter(f: File, append: Boolean) {
        runCatching {
            writer?.close()
            writer = BufferedWriter(FileWriter(f, append))
        }
    }

    fun emit(event: String, vararg fields: Pair<String, Any?>) {
        if (!ENABLED) return
        // debug 指标也会同时进入 logcat 与私有 JSONL，不能因为不进 release 就放宽隐私口径。
        val redacted = fields.map { (key, value) -> LogRedaction.field(key, value) }.toTypedArray()
        write(buildLine(event, redacted))
    }

    /**
     * 错误事件统一入口，code 用大写下划线常量便于脚本比对。
     * 底层异常消息可能夹带无法可靠识别的裸主机名、账号或目录，因此只记录“有消息”，
     * 不把原文写入 debug logcat 或 release errors.jsonl。
     */
    fun error(code: String, message: String?, vararg extra: Pair<String, Any?>) {
        val fields = arrayOf<Pair<String, Any?>>(
            "code" to code,
            "has_msg" to !message.isNullOrBlank(),
        ) + extra.map { (key, value) -> LogRedaction.field(key, value) }
        val line = buildLine("error", fields)
        errorExecutor.execute {
            errorSink?.let { file ->
                synchronized(errorLock) {
                    runCatching { appendErrorLine(file, line, sync = false) }
                }
            }
        }
        if (ENABLED) write(line)
    }

    fun recentErrors(): List<String> =
        errorSink?.takeIf { it.isFile }
            ?.readLines(Charsets.UTF_8)
            ?.takeLast(200)
            ?.map(::redactForExport)
            ?: emptyList()

    private fun redactForExport(line: String): String = LogRedaction.text(line)
        .replace(
            Regex("\\\"(?:path|relative_path|file|host|ip|address|share|user|username|domain|endpoint|url|uri|title|actor|name|server_name|password|pass|pin|secret|token|first|msg|message|detail)\\\"\\s*:\\s*\\\"[^\\\"]*\\\""),
            "\\\"private\\\":\\\"<redacted>\\\"",
        )
        .replace(Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"), "<ip>")
        .replace(Regex("[A-Za-z]:\\\\\\\\[^\\\"]+"), "<local-path>")

    fun clearErrors() {
        errorExecutor.execute {
            synchronized(errorLock) {
                runCatching {
                    errorSink?.let { FileOutputStream(it, false).use { output -> output.fd.sync() } }
                    errorLineCount = 0
                }
            }
        }
    }

    /**
     * 未捕获异常后进程马上会退出，不能排队到后台线程；这里同步写完再交还系统处理。
     */
    fun recordCrash(thread: Thread, throwable: Throwable) {
        val fields = arrayOf<Pair<String, Any?>>(
            "code" to "UNCAUGHT_EXCEPTION",
            "has_msg" to !throwable.message.isNullOrBlank(),
            "thread" to LogRedaction.text(thread.name),
            "type" to throwable::class.java.name,
            "at" to LogRedaction.text(throwable.stackTrace.take(12).joinToString(" <- ")),
        )
        val line = buildLine("error", fields)
        errorSink?.let { file ->
            synchronized(errorLock) {
                runCatching { appendErrorLine(file, line, sync = true) }
            }
        }
    }

    private fun appendErrorLine(file: File, line: String, sync: Boolean) {
        FileOutputStream(file, true).use { output ->
            output.write((line + "\n").toByteArray(Charsets.UTF_8))
            if (sync) output.fd.sync()
        }
        errorLineCount++
        if (errorLineCount > ERROR_COMPACT_THRESHOLD) {
            val kept = file.readLines(Charsets.UTF_8).takeLast(ERROR_RETAIN_LINES)
            FileOutputStream(file, false).use { output ->
                output.write(kept.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8))
                if (sync) output.fd.sync()
            }
            errorLineCount = kept.size
        }
    }

    private fun write(line: String) {
        Log.i(TAG, line)
        executor?.execute {
            runCatching {
                writer?.apply {
                    write(line)
                    write("\n")
                    // 每行 flush：验收脚本随时可能来读，指标频率低，开销可忽略
                    flush()
                }
            }
        }
    }

    private fun buildLine(event: String, fields: Array<out Pair<String, Any?>>): String {
        val sb = StringBuilder(64 + fields.size * 24)
        sb.append("{\"e\":")
        appendJsonString(sb, event)
        for ((k, v) in fields) {
            sb.append(',')
            appendJsonString(sb, k)
            sb.append(':')
            appendJsonValue(sb, v)
        }
        sb.append('}')
        return sb.toString()
    }

    private fun appendJsonValue(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Float -> appendFinite(sb, v.toDouble())
            is Double -> appendFinite(sb, v)
            else -> appendJsonString(sb, v.toString())
        }
    }

    private fun appendFinite(sb: StringBuilder, d: Double) {
        // JSON 没有 NaN/Infinity，落到 null 而不是输出非法字面量把整行解析弄坏
        if (d.isNaN() || d.isInfinite()) sb.append("null") else sb.append(d.toString())
    }

    private fun appendJsonString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    private const val ERROR_SINK_NAME = "errors.jsonl"
    private const val ERROR_PRIVACY_MARKER = "errors-redacted-v2"
    private const val ERROR_COMPACT_THRESHOLD = 400
    private const val ERROR_RETAIN_LINES = 200
}
