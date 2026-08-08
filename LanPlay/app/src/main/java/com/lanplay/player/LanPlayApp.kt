package com.lanplay.player

import android.app.Application
import com.lanplay.player.core.log.Metric
import dagger.hilt.android.HiltAndroidApp
import com.lanplay.player.data.crypto.CacheCipher

/**
 * 需求 §5.5：Application.onCreate 保持为空，不做任何阻塞初始化。
 * SMB Session、HTTP 代理、播放内核全部延迟到真要播放时才创建。
 */
@HiltAndroidApp
class LanPlayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 指标只在 debug 写入；最近 200 条错误与崩溃在 release 也保存在本机。
        Metric.init(this)
        val systemHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Metric.recordCrash(thread, throwable)
            } finally {
                systemHandler?.uncaughtException(thread, throwable)
            }
        }
        Thread(
            {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                try {
                    // 字幕是播放器会话所需的临时明文；进程重启后不再复用，立即清理。
                    java.io.File(cacheDir, "subtitles").listFiles()?.forEach {
                        if (it.isDirectory) it.deleteRecursively() else it.delete()
                    }
                    val migrated = CacheCipher.migrateKnownCaches(this)
                    if (migrated > 0) {
                        Metric.emit("cache_encrypted", "files" to migrated)
                    }
                } catch (t: Throwable) {
                    Metric.error("CACHE_MIGRATION", t.message ?: "缓存迁移失败")
                }
            },
            "LanPlay-cache-encryption",
        ).start()
    }
}
