package com.lanplay.player.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lanplay.player.StartupActivity
import com.lanplay.player.core.log.Metric
import com.lanplay.player.data.ServerRepository
import com.lanplay.player.data.TrashRepository
import com.lanplay.player.data.db.LanPlayDatabase
import com.lanplay.player.data.prefs.SettingsRepository
import com.lanplay.player.player.PlaybackController
import com.lanplay.player.smb.AuthMode
import com.lanplay.player.smb.SmbConnectionManager
import com.lanplay.player.smb.SmbException
import com.lanplay.player.smb.SmbFileRepository
import com.lanplay.player.smb.SmbTarget
import com.lanplay.player.smb.LanScanner
import com.lanplay.player.smb.SmbShareDiscovery
import com.lanplay.player.smb.io.SmbFileHandle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * adb 驱动的自动化测试入口。**仅 debug 构建存在**。
 *
 * 用法（务必用 -n 指定组件，Android 8+ 的隐式广播限制会拦掉不带目标的自定义广播）：
 *
 *   adb shell am broadcast -n com.lanplay.player/.debug.TestHookReceiver \
 *     -a com.lanplay.player.TEST --es cmd play \
 *     --es path "<VIDEO_SUBDIR>/sample-4k.mkv"
 *
 * 所有结果都以 LANPLAY_METRIC 单行 JSON 打进 logcat，由 tools/verify.ps1 解析。
 * 密码只用于建立连接，绝不进入任何指标或日志。
 */
class TestHookReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun playback(): PlaybackController
        fun servers(): ServerRepository
        fun settings(): SettingsRepository
        fun files(): SmbFileRepository
        fun connections(): SmbConnectionManager
        fun trash(): TrashRepository
        fun database(): LanPlayDatabase
        fun scanner(): LanScanner
        fun shareDiscovery(): SmbShareDiscovery
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TEST_ACTION) return
        val cmd = intent.getStringExtra("cmd") ?: run {
            Metric.error("TEST_HOOK", "missing cmd")
            return
        }
        val app = context.applicationContext
        val deps = EntryPointAccessors.fromApplication(app, Deps::class.java)
        val pending = goAsync()

        scope.launch {
            try {
                commandMutex.withLock { dispatch(app, cmd, intent, deps) }
            } catch (e: SmbException) {
                Metric.error(e.code, e.message, "cmd" to cmd)
            } catch (t: Throwable) {
                Metric.error(
                    "TEST_HOOK_FAILED",
                    t.message ?: t::class.java.simpleName,
                    "cmd" to cmd,
                    "type" to t::class.java.name,
                    "at" to t.stackTrace.take(4).joinToString(" <- "),
                )
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun dispatch(
        app: Context,
        cmd: String,
        intent: Intent,
        deps: Deps,
    ) {
        when (cmd) {
            "clear" -> { Metric.clear(); ack("clear") }
            "mute" -> {
                val on = intent.getBooleanExtra("on", true)
                deps.playback().forceMute = on
                deps.playback().setVolume(if (on) 0f else 1f)
                ack("mute", "on" to on)
            }
            "configure" -> configure(intent, deps)
            "list" -> list(intent, deps)
            "play" -> play(app, intent, deps)
            "pause" -> deps.playback().pause().also { ack(cmd) }
            "resume" -> deps.playback().play().also { ack(cmd) }
            "stop" -> deps.playback().stop().also { ack(cmd) }
            "seek" -> seek(intent, deps)
            "set" -> set(intent, deps)
            "get" -> get(intent, deps)
            "verify_pin" -> verifyPin(intent, deps)
            "metrics_dump" -> metricsDump(deps)
            "gc" -> collectGarbageForMeasurement()
            "speedtest" -> speedTest(intent, deps)
            "probe_offset" -> probeOffset(intent, deps)
            "trash_move" -> trashMove(intent, deps)
            "trash_restore" -> trashRestore(intent, deps)
            "trash_delete" -> trashDelete(intent, deps)
            "trash_list" -> trashList(deps)
            "db_status" -> dbStatus(deps)
            "scan" -> scan(deps)
            "discover_shares" -> discoverShares(intent, deps)
            "ui_gallery" -> uiGallery(intent, deps)
            "capture_frame" -> captureFrame(deps)
            "record_crash_smoke" -> {
                Metric.recordCrash(
                    Thread.currentThread(),
                    IllegalStateException("真机崩溃日志通道验收（未终止进程）"),
                )
                ack("record_crash_smoke")
            }
            "clear_errors" -> {
                Metric.clearErrors()
                ack("clear_errors")
            }
            "organize_smoke" -> organizeSmoke(intent, deps)
            "organize_cleanup" -> organizeCleanup(intent, deps)
            else -> Metric.error("TEST_HOOK", "unknown cmd: $cmd")
        }
    }

    // ── 命令实现 ─────────────────────────────────────────────

    private suspend fun configure(intent: Intent, deps: Deps) {
        val host = intent.getStringExtra("host") ?: return Metric.error("TEST_HOOK", "missing host")
        val share = intent.getStringExtra("share") ?: return Metric.error("TEST_HOOK", "missing share")
        val user = intent.getStringExtra("user").orEmpty()
        val pass = intent.getStringExtra("pass").orEmpty()
        val domain = intent.getStringExtra("domain")?.takeIf { it.isNotEmpty() }
        val port = intent.getIntExtra("port", 445)
        val mode = intent.getStringExtra("mode")?.uppercase()?.let {
            runCatching { AuthMode.valueOf(it) }.getOrNull()
        } ?: if (user.isEmpty()) AuthMode.GUEST else AuthMode.ACCOUNT
        val defaultPath = intent.getStringExtra("path").orEmpty()

        val target = SmbTarget(
            host = host, port = port, share = share,
            domain = domain, username = user, password = pass, authMode = mode,
        )
        deps.servers().save(
            target,
            displayName = intent.getStringExtra("name").orEmpty(),
            defaultPath = defaultPath,
        )

        // 立刻验证一次连通性，configure 失败要当场知道，而不是等到 play 才报错
        deps.connections().share(target, SmbConnectionManager.Channel.AUX)
        Metric.emit(
            "configured",
            "mode" to mode.name,
            "has_user" to user.isNotEmpty(),
            "has_password" to pass.isNotEmpty(),
            "has_default_path" to defaultPath.isNotEmpty(),
            // 连接可能是复用的，那样不会再有握手事件。方言要随时可查，
            // 调试面板（P-18）显示的也是这个值。
            "dialect" to deps.connections().negotiatedDialect,
        )
    }

    private suspend fun list(intent: Intent, deps: Deps) {
        val target = requireTarget(deps) ?: return
        val path = intent.getStringExtra("path") ?: deps.servers().current()?.defaultPath.orEmpty()
        val entries = deps.files().list(target, path)
        // list 汇总指标已由 SmbFileRepository 输出，这里补一条便于核对具体内容
        val verbose = intent.getBooleanExtra("verbose", false)
        if (verbose) {
            entries.take(intent.getIntExtra("limit", 50)).forEach {
                Metric.emit(
                    "list_item",
                    "dir" to it.isDirectory,
                    "size" to it.size,
                )
            }
        }
    }

    private suspend fun play(app: Context, intent: Intent, deps: Deps) {
        val path = intent.getStringExtra("path") ?: return Metric.error("TEST_HOOK", "missing path")
        val startMs = intent.longArg("startMs") ?: 0L

        // 自动化测试默认静音——素材不适合外放。要出声必须显式传 --ez sound true。
        deps.playback().forceMute = !intent.getBooleanExtra("sound", false)

        // 全新安装仍停在首次引导页时，PlaybackController 虽会在后台推进播放，
        // 但 MainActivity 不会组合 PlayerScreen，也就没有 Surface 和首帧事件。
        // debug 验收明确跳过已经由 DeviceUiSmokeTest 单独覆盖的引导流程。
        deps.settings().setOnboardingCompleted()

        // 没有可见的 Activity 就没有 Surface，也就不会有首帧。确保 APP 在前台。
        withContext(Dispatchers.Main) {
            app.startActivity(
                Intent(app, StartupActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
        val subtitleOverride = intent.getStringExtra("subtitle")
        Metric.emit("play_request", "has_subtitle" to (subtitleOverride != null))
        deps.playback().open(
            relativePath = path,
            startPositionMs = startMs,
            // 显式传字幕就是自动化要求启用它，不能被该文件已有的“字幕关闭”观看记录吞掉。
            subtitlesEnabled = subtitleOverride?.let { true },
            subtitleOverride = subtitleOverride,
            charsetOverride = intent.getStringExtra("charset"),
            externalAudioPath = intent.getStringExtra("externalAudio"),
        )
    }

    private fun seek(intent: Intent, deps: Deps) {
        val ms = intent.longArg("ms") ?: return Metric.error("TEST_HOOK", "missing ms")
        deps.playback().seekTo(ms)
        ack("seek", "ms" to ms)
    }

    private suspend fun set(intent: Intent, deps: Deps) {
        val key = intent.getStringExtra("key") ?: return Metric.error("TEST_HOOK", "missing key")
        val intValue = if (intent.hasExtra("value")) {
            runCatching { intent.getIntExtra("value", Int.MIN_VALUE) }.getOrNull()
                ?.takeIf { it != Int.MIN_VALUE }
        } else null
        val stringValue = intent.getStringExtra("value")
        val ok = deps.settings().setByKey(key, intValue, stringValue)
        // set 支持应用锁 PIN；指标不能记录任何设置值，避免动态 key + 固定 value 绕过字段脱敏。
        Metric.emit("set", "key" to key, "has_value" to (stringValue != null || intValue != null), "ok" to ok)
    }

    private suspend fun get(intent: Intent, deps: Deps) {
        val key = intent.getStringExtra("key") ?: return Metric.error("TEST_HOOK", "missing key")
        Metric.emit("get", "key" to key, "value" to deps.settings().getByKey(key))
    }

    private suspend fun verifyPin(intent: Intent, deps: Deps) {
        val value = intent.getStringExtra("value") ?: return Metric.error("TEST_HOOK", "missing value")
        Metric.emit("pin_verified", "ok" to deps.settings().verifyAppLockPin(value))
    }

    private suspend fun collectGarbageForMeasurement() {
        repeat(2) {
            Runtime.getRuntime().gc()
            delay(400)
            System.runFinalization()
        }
        Metric.emit(
            "gc",
            "java_used_mb" to (Runtime.getRuntime().run { (totalMemory() - freeMemory()) / (1024 * 1024) }),
        )
    }

    private suspend fun metricsDump(deps: Deps) {
        val io = deps.settings().currentIoSettings()
        Metric.emit(
            "settings",
            "prefetchMb" to io.prefetchMb,
            "readBlockKb" to io.readBlockKb,
            "concurrentReads" to io.concurrentReads,
            "decoderMode" to io.decoderMode.name,
            "dialect" to deps.connections().negotiatedDialect,
            "reconnects" to deps.connections().reconnectCount,
        )
        val playback = deps.playback()
        val info = playback.decoderInfo.value
        Metric.emit(
            "decoder",
            "video" to info.videoDecoder,
            "hw" to info.isHardware,
            "audio" to info.audioDecoder,
            "w" to info.width,
            "h" to info.height,
            "fps" to info.frameRate,
        )
        Metric.emit(
            "state",
            "state" to playback.state.value.name,
            "pos_ms" to playback.positionMs,
            "dur_ms" to playback.durationMs,
            "has_file" to (playback.session.value != null),
            "resume_ms" to (playback.session.value?.resumedFromMs ?: 0L),
            "kernel" to (playback.session.value?.kernel?.name ?: ""),
            "has_subtitle" to !playback.session.value?.subtitlePath.isNullOrEmpty(),
            "subtitle_charset" to (playback.session.value?.subtitleCharset ?: ""),
            "has_external_audio" to !playback.session.value?.externalAudioPath.isNullOrEmpty(),
        )
        playback.emitIoMetrics()
        playback.emitDisplay()
    }

    /** 需求 O-05：对当前共享做纯读测速，走 AUX 通道不与播放抢带宽 */
    private suspend fun speedTest(intent: Intent, deps: Deps) {
        val target = requireTarget(deps) ?: return
        val megabytes = intent.getIntExtra("mb", 100).coerceIn(1, 1024)
        val path = intent.getStringExtra("path")
            ?: return Metric.error("TEST_HOOK", "missing path")

        // 必须并发测：串行读只有 ~6.8 MB/s，而播放通道 6 路并发能到 11.5 MB/s。
        // 用串行数字回报「你的网络有多快」会严重低估实际可用带宽。
        val parts = intent.getIntExtra("parts", 6).coerceIn(1, 16)

        withContext(Dispatchers.IO) {
            val handle = SmbFileHandle.open(
                deps.connections(), target, path, SmbConnectionManager.Channel.AUX,
            )
            try {
                val totalBytes = megabytes.toLong() * 1024L * 1024L
                val per = totalBytes / parts
                val startNs = System.nanoTime()
                val read = coroutineScope {
                    (0 until parts).map { i ->
                        async {
                            val block = ByteArray(1024 * 1024)
                            var got = 0L
                            val base = i * per
                            while (got < per) {
                                val want = minOf(block.size.toLong(), per - got).toInt()
                                val n = handle.readFully(base + got, block, 0, want)
                                if (n <= 0) break
                                got += n
                            }
                            got
                        }
                    }.awaitAll().sum()
                }
                val elapsedSec = (System.nanoTime() - startNs) / 1_000_000_000.0
                Metric.emit(
                    "speedtest",
                    "mb" to Math.round(read / (1024.0 * 1024.0) * 100.0) / 100.0,
                    "sec" to Math.round(elapsedSec * 100.0) / 100.0,
                    "mbps" to Math.round((read / elapsedSec / (1024.0 * 1024.0)) * 100.0) / 100.0,
                    "parts" to parts,
                )
            } finally {
                handle.close()
            }
        }
    }

    /**
     * 风险 R-5 专项：直接读指定偏移一块，验证 >4GB 偏移不溢出。
     * 输出前 16 字节十六进制，可与 PC 端同偏移的字节逐一比对。
     */
    private suspend fun probeOffset(intent: Intent, deps: Deps) {
        val target = requireTarget(deps) ?: return
        val path = intent.getStringExtra("path") ?: return Metric.error("TEST_HOOK", "missing path")
        val offset = intent.longArg("offset") ?: return Metric.error("TEST_HOOK", "missing offset")
        val length = intent.getIntExtra("len", 4096).coerceIn(16, 1024 * 1024)

        withContext(Dispatchers.IO) {
            val handle = SmbFileHandle.open(
                deps.connections(), target, path, SmbConnectionManager.Channel.AUX,
            )
            try {
                val buf = ByteArray(length)
                val startNs = System.nanoTime()
                val n = handle.readFully(offset, buf, 0, length)
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
                Metric.emit(
                    "probe_offset",
                    "offset" to offset,
                    "want" to length,
                    "got" to n,
                    "ms" to elapsedMs,
                    "size" to handle.size,
                    "head16" to buf.take(16).joinToString("") { "%02x".format(it) },
                )
            } finally {
                handle.close()
            }
        }
    }

    private suspend fun trashMove(intent: Intent, deps: Deps) {
        val server = deps.servers().current()
            ?: return Metric.error("SMB_NOT_CONFIGURED", "先执行 configure")
        val path = intent.getStringExtra("path")
            ?: return Metric.error("TEST_HOOK", "missing path")
        val result = deps.trash().moveToTrash(server.id, server.target, path)
        val items = result.movedItems
        Metric.emit(
            "trash_move",
            "group" to items.firstOrNull()?.groupId,
            "items" to items.size,
            "bytes" to items.sumOf { it.fileSize },
            "ms" to System.currentTimeMillis() - (items.firstOrNull()?.deletedAt ?: 0L),
            "video_moved" to result.videoMoved,
            "failures" to result.failures.size,
        )
    }

    private suspend fun trashRestore(intent: Intent, deps: Deps) {
        val server = deps.servers().current()
            ?: return Metric.error("SMB_NOT_CONFIGURED", "先执行 configure")
        val group = intent.getStringExtra("group")
            ?: return Metric.error("TEST_HOOK", "missing group")
        deps.trash().restore(server.id, server.target, group)
        Metric.emit("trash_restore", "group" to group)
    }

    private suspend fun trashDelete(intent: Intent, deps: Deps) {
        val server = deps.servers().current()
            ?: return Metric.error("SMB_NOT_CONFIGURED", "先执行 configure")
        val group = intent.getStringExtra("group")
            ?: return Metric.error("TEST_HOOK", "missing group")
        deps.trash().permanentlyDelete(server.id, server.target, group)
        Metric.emit("trash_delete", "group" to group)
    }

    private suspend fun trashList(deps: Deps) {
        deps.trash().listAll().forEach {
            Metric.emit(
                "trash_item",
                "group" to it.groupId,
                "type" to it.itemType.name,
                "size" to it.fileSize,
            )
        }
    }

    private suspend fun dbStatus(deps: Deps) {
        val db = deps.database()
        Metric.emit(
            "db_status",
            "version" to db.openHelper.readableDatabase.version,
            "servers" to db.smbServerDao().listAll().size,
            "watch_records" to db.watchRecordDao().count(),
            "trash_items" to db.trashItemDao().count(),
            "movies" to db.movieInfoDao().count(),
            "directory_cache" to db.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM directory_entry_cache"
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 },
            "media_meta" to db.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM media_meta"
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 },
            "tags" to db.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM tag"
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 },
            "bookmarks" to db.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM bookmark"
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 },
        )
    }

    private suspend fun captureFrame(deps: Deps) {
        runCatching { deps.playback().captureFrame() }
            .onSuccess { Metric.emit("capture_frame", "bytes" to it.length()) }
            .onFailure { Metric.error("CAPTURE_FRAME", it.message) }
    }

    private suspend fun organizeSmoke(intent: Intent, deps: Deps) {
        // 与正式入口使用完全相同的裁剪规则，cleanup 才能精确删除由 smoke
        // 创建的行；否则长随机标识会被正式方法截断，却按原文清理而留下脏数据。
        val label = (intent.getStringExtra("label") ?: "验收书签").trim().take(80)
        val tag = (intent.getStringExtra("tag") ?: "验收标签").trim().take(30)
        deps.playback().addBookmark(label)
        deps.playback().addTag(tag)
        deps.playback().setNote("书签、标签与备注真机验收")
        delay(500)
        dbStatus(deps)
    }

    private suspend fun organizeCleanup(intent: Intent, deps: Deps) {
        val label = (intent.getStringExtra("label") ?: "验收书签").trim().take(80)
        val tag = (intent.getStringExtra("tag") ?: "验收标签").trim().take(30)
        val db = deps.database().openHelper.writableDatabase
        db.execSQL("DELETE FROM bookmark WHERE label = ?", arrayOf(label))
        db.execSQL(
            "DELETE FROM record_tag WHERE tagId IN (SELECT id FROM tag WHERE name = ?)",
            arrayOf(tag),
        )
        db.execSQL("DELETE FROM tag WHERE name = ?", arrayOf(tag))
        deps.playback().setNote("")
        delay(300)
        dbStatus(deps)
    }

    private suspend fun scan(deps: Deps) {
        val started = System.currentTimeMillis()
        val hosts = deps.scanner().scan()
        Metric.emit(
            "lan_scan",
            "ms" to (System.currentTimeMillis() - started),
            "hosts" to hosts.size,
        )
    }

    private suspend fun discoverShares(intent: Intent, deps: Deps) {
        val host = intent.getStringExtra("host")
            ?: return Metric.error("TEST_HOOK", "missing host")
        val user = intent.getStringExtra("user").orEmpty()
        val pass = intent.getStringExtra("pass").orEmpty()
        val started = System.currentTimeMillis()
        val shares = deps.shareDiscovery().list(host, user, pass)
        Metric.emit(
            "share_discovery",
            "ms" to (System.currentTimeMillis() - started),
            "count" to shares.size,
        )
    }

    private fun uiGallery(intent: Intent, deps: Deps) {
        val path = intent.getStringExtra("path").orEmpty()
        deps.database().openHelper.writableDatabase.execSQL(
            """
            INSERT INTO browse_state
                (serverId, dirPath, scrollIndex, scrollOffset, sortField, sortAscending, viewMode, updatedAt)
            VALUES
                ((SELECT id FROM smb_server ORDER BY sortOrder, id LIMIT 1), ?, 0, 0, 'LAST_MODIFIED', 0, 'GALLERY', ?)
            ON CONFLICT(serverId, dirPath) DO UPDATE SET viewMode = 'GALLERY'
            """.trimIndent(),
            arrayOf<Any>(path, System.currentTimeMillis()),
        )
        ack("ui_gallery")
    }

    // ── 工具 ─────────────────────────────────────────────────

    private suspend fun requireTarget(deps: Deps): SmbTarget? {
        val t = deps.servers().current()?.target
        if (t == null) Metric.error("SMB_NOT_CONFIGURED", "先执行 configure")
        return t
    }

    private fun ack(cmd: String, vararg extra: Pair<String, Any?>) {
        Metric.emit("ack", *(arrayOf<Pair<String, Any?>>("cmd" to cmd) + extra))
    }

    /** 同时接受 --el（long）与 --ei（int），脚本两种写法都能用 */
    private fun Intent.longArg(name: String): Long? {
        val asLong = getLongExtra(name, Long.MIN_VALUE)
        if (asLong != Long.MIN_VALUE) return asLong
        val asInt = getIntExtra(name, Int.MIN_VALUE)
        if (asInt != Int.MIN_VALUE) return asInt.toLong()
        return getStringExtra(name)?.toLongOrNull()
    }

    private companion object {
        const val TEST_ACTION = "com.lanplay.player.TEST"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val commandMutex = Mutex()
    }
}
