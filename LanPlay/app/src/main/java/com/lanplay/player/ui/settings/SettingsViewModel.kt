package com.lanplay.player.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanplay.player.data.prefs.AppearanceSettings
import com.lanplay.player.data.prefs.DarkMode
import com.lanplay.player.data.prefs.IoSettings
import com.lanplay.player.data.prefs.OrientationMode
import com.lanplay.player.data.prefs.PlayerSettings
import com.lanplay.player.data.prefs.PrivacySettings
import com.lanplay.player.data.prefs.ResumePolicy
import com.lanplay.player.data.prefs.PlayerKernel
import com.lanplay.player.data.prefs.SettingsRepository
import com.lanplay.player.data.prefs.CacheSettings
import com.lanplay.player.data.prefs.SubtitleFont
import com.lanplay.player.data.prefs.SubtitleStyleSettings
import com.lanplay.player.data.prefs.GestureRegion
import com.lanplay.player.data.prefs.Handedness
import com.lanplay.player.data.prefs.HomeLayout
import com.lanplay.player.data.prefs.AudioEnhancementSettings
import com.lanplay.player.data.CacheRepository
import com.lanplay.player.data.CacheStats
import com.lanplay.player.data.ServerRepository
import com.lanplay.player.smb.SmbConnectionManager
import com.lanplay.player.smb.SmbFileRepository
import com.lanplay.player.smb.VIDEO_EXTENSIONS
import com.lanplay.player.smb.io.SmbFileHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import com.lanplay.player.data.db.TagDao
import com.lanplay.player.data.db.TagEntity
import com.lanplay.player.data.db.LanPlayDatabase
import com.lanplay.player.data.BackupRepository
import android.net.Uri
import androidx.room.withTransaction
import com.lanplay.player.core.log.Metric
import javax.inject.Inject

data class DiagnosticStep(val name: String, val status: String, val success: Boolean?)
data class DiagnosticState(
    val running: Boolean = false,
    val steps: List<DiagnosticStep> = emptyList(),
    val speedMbps: Double? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val cacheRepository: CacheRepository,
    private val servers: ServerRepository,
    private val files: SmbFileRepository,
    private val connections: SmbConnectionManager,
    private val tagDao: TagDao,
    private val backupRepository: BackupRepository,
    private val database: LanPlayDatabase,
) : ViewModel() {
    data class UiState(
        val appearance: AppearanceSettings = AppearanceSettings(),
        val player: PlayerSettings = PlayerSettings(),
        val io: IoSettings = IoSettings(),
        val privacy: PrivacySettings = PrivacySettings(),
        val cacheSettings: CacheSettings = CacheSettings(),
        val cacheStats: CacheStats = CacheStats(),
        val subtitle: SubtitleStyleSettings = SubtitleStyleSettings(),
        val audio: AudioEnhancementSettings = AudioEnhancementSettings(),
    )

    private val cacheStats = kotlinx.coroutines.flow.MutableStateFlow(CacheStats())
    private val _diagnostics = MutableStateFlow(DiagnosticState())
    val diagnostics = _diagnostics.asStateFlow()
    val tags = tagDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _backupMessage = MutableStateFlow<String?>(null)
    val backupMessage = _backupMessage.asStateFlow()
    private val _errorLogs = MutableStateFlow<List<String>>(emptyList())
    val errorLogs = _errorLogs.asStateFlow()

    val state = combine(
        repository.appearanceSettings,
        repository.playerSettings,
        repository.ioSettings,
        repository.privacySettings,
        combine(
            repository.cacheSettings,
            cacheStats,
            repository.subtitleStyleSettings,
            repository.audioEnhancementSettings,
        ) { settings, stats, subtitle, audio ->
            Pair(Triple(settings, stats, subtitle), audio)
        },
    ) { appearance, player, io, privacy, cache ->
        UiState(
            appearance,
            player,
            io,
            privacy,
            cache.first.first,
            cache.first.second,
            cache.first.third,
            cache.second,
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        refreshCacheStats()
    }

    fun setTheme(id: String) = viewModelScope.launch { repository.setThemeId(id) }
    fun setDarkMode(mode: DarkMode) = viewModelScope.launch { repository.setDarkMode(mode) }
    fun setHomeLayout(layout: HomeLayout) =
        viewModelScope.launch { repository.setHomeLayout(layout) }
    fun setOrientation(mode: OrientationMode) =
        viewModelScope.launch { repository.setOrientationMode(mode) }

    fun setSeekSensitivity(value: Int) =
        viewModelScope.launch { repository.setSeekSensitivitySeconds(value) }

    fun setResumePolicy(value: ResumePolicy) =
        viewModelScope.launch { repository.setResumePolicy(value) }

    fun setVolumeLimit(value: Float) =
        viewModelScope.launch { repository.setVolumeSoftLimitPercent(value) }

    fun setBrightnessLimit(value: Float) =
        viewModelScope.launch { repository.setBrightnessSoftLimitPercent(value) }

    fun setVolumeSensitivity(value: Int) =
        viewModelScope.launch { repository.setVolumeSensitivityPercent(value) }

    fun setBrightnessSensitivity(value: Int) =
        viewModelScope.launch { repository.setBrightnessSensitivityPercent(value) }

    fun setHaptic(value: Boolean) =
        viewModelScope.launch { repository.setHapticEnabled(value) }

    fun setPlayerKernel(value: PlayerKernel) =
        viewModelScope.launch { repository.setPlayerKernel(value) }

    fun setDoubleTapSeconds(value: Int) =
        viewModelScope.launch { repository.setDoubleTapSeconds(value) }

    fun setDoubleTapCenterPause(value: Boolean) =
        viewModelScope.launch { repository.setDoubleTapCenterPause(value) }

    fun setLongPressSpeed(value: Float) =
        viewModelScope.launch { repository.setLongPressSpeed(value) }

    fun setHorizontalSeek(value: Boolean) =
        viewModelScope.launch { repository.setHorizontalSeekEnabled(value) }

    fun setVerticalAdjust(value: Boolean) =
        viewModelScope.launch { repository.setVerticalAdjustEnabled(value) }

    fun setPrecisionSeek(value: Boolean) =
        viewModelScope.launch { repository.setPrecisionSeekEnabled(value) }

    fun setTransformGesture(value: Boolean) =
        viewModelScope.launch { repository.setTransformGestureEnabled(value) }

    fun setSubtitleOffsetGesture(value: Boolean) =
        viewModelScope.launch { repository.setSubtitleOffsetGestureEnabled(value) }

    fun setGestureRegion(value: GestureRegion) =
        viewModelScope.launch { repository.setGestureRegion(value) }

    fun setSystemEdgeExclusion(value: Boolean) =
        viewModelScope.launch { repository.setSystemEdgeExclusion(value) }

    fun setSeekPreview(value: Boolean) =
        viewModelScope.launch { repository.setSeekPreviewEnabled(value) }

    fun setAutoPlayNext(value: Boolean) =
        viewModelScope.launch { repository.setAutoPlayNext(value) }

    fun setFadePlayback(value: Boolean) =
        viewModelScope.launch { repository.setFadePlayback(value) }

    fun setHandedness(value: Handedness) =
        viewModelScope.launch { repository.setHandedness(value) }

    fun resetGestures() = viewModelScope.launch { repository.resetGestureSettings() }
    fun resetPlayback() = viewModelScope.launch { repository.resetPlaybackSettings() }
    fun resetAll() = viewModelScope.launch { repository.resetAllSettings() }
    fun replayOnboarding() =
        viewModelScope.launch { repository.setOnboardingCompleted(false) }

    fun setSubtitleSize(value: Int) =
        viewModelScope.launch { repository.setSubtitleSizePercent(value) }

    fun setSubtitleEdge(value: Int) =
        viewModelScope.launch { repository.setSubtitleEdgeWidth(value) }

    fun setSubtitleBackground(value: Boolean) =
        viewModelScope.launch { repository.setSubtitleBackground(value) }

    fun setSubtitleBottom(value: Int) =
        viewModelScope.launch { repository.setSubtitleBottomPadding(value) }

    fun setSubtitleFont(value: SubtitleFont) =
        viewModelScope.launch { repository.setSubtitleFont(value) }

    fun setSubtitleColors(text: String, edge: String) =
        viewModelScope.launch { repository.setSubtitleColors(text, edge) }

    fun setAudioBoost(value: Int) =
        viewModelScope.launch { repository.setAudioBoostPercent(value) }

    fun setLoudnessNormalization(value: Boolean) =
        viewModelScope.launch { repository.setLoudnessNormalization(value) }

    fun setEqualizerPreset(value: String) = viewModelScope.launch {
        repository.setEqualizerPreset(value, equalizerBandsForPreset(value))
    }

    fun setEqualizerBand(index: Int, value: Float) =
        viewModelScope.launch { repository.setEqualizerBand(index, value) }

    fun configureAppLock(pin: String) =
        viewModelScope.launch { repository.configureAppLock(pin) }

    fun disableAppLock(pin: String, onResult: (Boolean, Long) -> Unit) =
        viewModelScope.launch {
            val verified = repository.verifyAppLockPin(pin)
            if (verified) repository.setAppLockEnabled(false)
            onResult(verified, repository.appLockRetryAfterMs())
        }

    fun setLockGrace(value: Int) =
        viewModelScope.launch { repository.setLockGraceSeconds(value) }

    fun setBlurArtwork(value: Boolean) =
        viewModelScope.launch { repository.setBlurArtwork(value) }

    fun setDisguiseEnabled(value: Boolean) =
        viewModelScope.launch { repository.setDisguiseEnabled(value) }

    fun setImageCacheLimit(value: Int) = viewModelScope.launch {
        repository.setImageCacheLimitMb(value)
        cacheRepository.pruneArtwork()
        refreshCacheStats()
    }

    private fun equalizerBandsForPreset(value: String): List<Float> = when (value) {
        "voice" -> listOf(-3f, -2f, -1f, 1f, 3f, 4f, 3f, 1f, -1f, -2f)
        "cinema" -> listOf(3f, 2f, 0f, -1f, 0f, 2f, 3f, 4f, 3f, 2f)
        "bass" -> listOf(7f, 6f, 4f, 2f, 0f, -1f, -2f, -2f, -1f, 0f)
        "treble" -> listOf(-2f, -2f, -1f, 0f, 1f, 2f, 4f, 6f, 7f, 7f)
        else -> List(10) { 0f }
    }

    fun setTrashRetention(value: Int) =
        viewModelScope.launch { repository.setTrashRetentionDays(value) }

    fun clearArtworkCache() = viewModelScope.launch {
        cacheRepository.clearArtwork()
        refreshCacheStats()
    }

    fun clearThumbnailCache() = viewModelScope.launch {
        cacheRepository.clearThumbnails()
        refreshCacheStats()
    }

    fun clearSpriteCache() = viewModelScope.launch {
        cacheRepository.clearSprites()
        refreshCacheStats()
    }

    fun clearSubtitleCache() = viewModelScope.launch {
        cacheRepository.clearSubtitles()
        refreshCacheStats()
    }

    fun clearScreenshots() = viewModelScope.launch {
        cacheRepository.clearScreenshots()
        refreshCacheStats()
    }

    private fun refreshCacheStats() = viewModelScope.launch {
        cacheStats.value = cacheRepository.stats()
    }

    fun setIo(key: String, value: Int) =
        viewModelScope.launch { repository.setByKey(key, value, null) }

    fun setDecoder(value: String) =
        viewModelScope.launch { repository.setByKey("decoderMode", null, value) }

    fun runDiagnostics() {
        if (_diagnostics.value.running) return
        viewModelScope.launch {
            val server = servers.current()
            if (server == null) {
                _diagnostics.value = DiagnosticState(
                    steps = listOf(DiagnosticStep("服务器配置", "尚未添加服务器", false))
                )
                return@launch
            }
            val results = mutableListOf<DiagnosticStep>()
            fun report(name: String, status: String, success: Boolean?) {
                results += DiagnosticStep(name, status, success)
                _diagnostics.value = DiagnosticState(true, results.toList())
            }
            _diagnostics.value = DiagnosticState(running = true)
            val ping = withContext(Dispatchers.IO) {
                runCatching { InetAddress.getByName(server.target.host).isReachable(1_000) }
                    .getOrDefault(false)
            }
            report(
                "主机响应",
                if (ping) "地址可达" else "未响应 ICMP，继续检查 SMB",
                if (ping) true else null,
            )
            val portOpen = withContext(Dispatchers.IO) {
                runCatching {
                    Socket().use {
                        it.connect(
                            InetSocketAddress(server.target.host, server.target.port),
                            1_500,
                        )
                    }
                    true
                }.getOrDefault(false)
            }
            report(
                "TCP ${server.target.port}",
                if (portOpen) "端口已开放" else "端口不可连接",
                portOpen,
            )
            if (!portOpen) {
                _diagnostics.value = DiagnosticState(false, results)
                return@launch
            }
            val smb = runCatching {
                connections.share(server.target, SmbConnectionManager.Channel.AUX)
            }
            report(
                "SMB 协商与认证",
                smb.fold(
                    onSuccess = { "成功 · ${connections.negotiatedDialect}" },
                    onFailure = { it.message ?: "认证失败" },
                ),
                smb.isSuccess,
            )
            if (smb.isFailure) {
                _diagnostics.value = DiagnosticState(false, results)
                return@launch
            }
            val listing = runCatching { files.list(server.target, server.defaultPath) }
            report(
                "列出目录",
                listing.fold(
                    onSuccess = { "成功 · ${it.size} 项" },
                    onFailure = { it.message ?: "读取目录失败" },
                ),
                listing.isSuccess,
            )
            val video = listing.getOrNull()
                ?.firstOrNull { !it.isDirectory && it.extension in VIDEO_EXTENSIONS }
            if (video == null) {
                report("100 MB 读取测速", "当前默认目录没有可测速的视频", null)
                _diagnostics.value = DiagnosticState(false, results)
                return@launch
            }
            val speed = withContext(Dispatchers.IO) {
                runCatching {
                    val handle = SmbFileHandle.open(
                        connections,
                        server.target,
                        video.relativePath,
                        SmbConnectionManager.Channel.AUX,
                    )
                    try {
                        val targetBytes = minOf(100L * 1024 * 1024, handle.size)
                        val buffer = ByteArray(1024 * 1024)
                        var offset = 0L
                        val started = System.nanoTime()
                        while (offset < targetBytes) {
                            val want = minOf(buffer.size.toLong(), targetBytes - offset).toInt()
                            val read = handle.readFully(offset, buffer, 0, want)
                            if (read <= 0) break
                            offset += read
                        }
                        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
                        check(offset > 0 && seconds > 0) { "未读到数据" }
                        offset / 1024.0 / 1024.0 / seconds
                    } finally {
                        handle.close()
                    }
                }
            }
            report(
                "100 MB 读取测速",
                speed.fold(
                    onSuccess = { "%.1f MB/s".format(it) },
                    onFailure = { it.message ?: "测速失败" },
                ),
                speed.isSuccess,
            )
            _diagnostics.value = DiagnosticState(
                running = false,
                steps = results,
                speedMbps = speed.getOrNull(),
            )
        }
    }

    fun createTag(name: String, color: String) = viewModelScope.launch {
        val clean = name.trim().take(30)
        if (clean.isNotBlank()) runCatching {
            tagDao.insert(TagEntity(name = clean, colorHex = color))
        }
    }

    fun renameTag(tag: TagEntity, name: String) = viewModelScope.launch {
        val clean = name.trim().take(30)
        if (clean.isNotBlank()) runCatching { tagDao.update(tag.copy(name = clean)) }
    }

    fun recolorTag(tag: TagEntity, color: String) = viewModelScope.launch {
        tagDao.update(tag.copy(colorHex = color))
    }

    fun deleteTag(tag: TagEntity) = viewModelScope.launch {
        tagDao.removeAllLinks(tag.id)
        tagDao.delete(tag)
    }

    fun exportBackup(uri: Uri) = viewModelScope.launch {
        runCatching { backupRepository.exportTo(uri) }
            .onSuccess { _backupMessage.value = "备份完成：$it 条观看记录" }
            .onFailure { _backupMessage.value = it.message ?: "备份失败" }
    }

    fun importBackup(uri: Uri) = viewModelScope.launch {
        runCatching { backupRepository.importFrom(uri) }
            .onSuccess {
                _backupMessage.value =
                    "恢复完成：${it.records} 条记录、${it.tags} 个标签、${it.bookmarks} 个书签" +
                        (if (it.serversCreated > 0) {
                            "；已创建 ${it.serversCreated} 个服务器占位，请重新填写密码"
                        } else "") +
                        (if (it.skipped > 0) {
                            "；${it.skipped} 条因本机未配置对应服务器而跳过"
                        } else "") +
                        if (!it.settingsImported) "；设置写入失败，原设置保持不变" else ""
            }
            .onFailure { _backupMessage.value = it.message ?: "恢复失败" }
    }

    fun clearBackupMessage() {
        _backupMessage.value = null
    }

    fun loadErrorLogs() = viewModelScope.launch(Dispatchers.IO) {
        _errorLogs.value = Metric.recentErrors()
    }

    fun clearErrorLogs() {
        Metric.clearErrors()
        _errorLogs.value = emptyList()
    }

    /**
     * V-07：只清本机可识别使用痕迹，保留服务器凭据和 SMB 回收站记录，
     * 因而不会影响共享原文件，也不会让已经移入回收站的内容失去还原入口。
     */
    fun clearAllLocalTraces() = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            database.withTransaction {
                database.bookmarkDao().clearAll()
                database.tagDao().clearAllLinks()
                database.watchRecordDao().clearAll()
                database.movieActorDao().clearAll()
                database.movieInfoDao().clearAll()
                database.actorDao().clearAll()
                database.mediaMetaDao().clearAll()
                database.directoryEntryCacheDao().clearAll()
                database.browseStateDao().clearAll()
            }
            cacheRepository.clearArtwork()
            cacheRepository.clearThumbnails()
            cacheRepository.clearSprites()
            cacheRepository.clearSubtitles()
            cacheRepository.clearScreenshots()
            Metric.clear()
            Metric.clearErrors()
        }.onSuccess {
            _backupMessage.value = "本机观看痕迹、图片、字幕、截图与元数据缓存已清空"
            refreshCacheStats()
        }.onFailure {
            _backupMessage.value = it.message ?: "清空本机痕迹失败"
        }
    }
}
