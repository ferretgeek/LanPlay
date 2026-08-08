package com.lanplay.player.player

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.os.Build
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.SurfaceView
import android.view.PixelCopy
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.text.Cue
import com.lanplay.player.core.log.Metric
import com.lanplay.player.data.ServerRepository
import com.lanplay.player.data.SubtitleRepository
import com.lanplay.player.data.PreparedSubtitle
import com.lanplay.player.data.SubtitleSearchHit
import com.lanplay.player.data.TimedSubtitleCue
import com.lanplay.player.data.SpritePreviewRepository
import com.lanplay.player.data.TrashRepository
import com.lanplay.player.data.WatchRepository
import com.lanplay.player.data.db.WatchRecordEntity
import com.lanplay.player.data.db.BookmarkDao
import com.lanplay.player.data.db.BookmarkEntity
import com.lanplay.player.data.db.TagDao
import com.lanplay.player.data.db.TagEntity
import com.lanplay.player.data.db.RecordTagEntity
import com.lanplay.player.data.db.AudioDeviceProfileDao
import com.lanplay.player.data.db.AudioDeviceProfileEntity
import com.lanplay.player.data.prefs.ResumePolicy
import com.lanplay.player.data.prefs.PlayerKernel
import com.lanplay.player.data.prefs.SettingsRepository
import com.lanplay.player.smb.SmbErrorCode
import com.lanplay.player.smb.SmbException
import com.lanplay.player.smb.SmbConnectionManager
import com.lanplay.player.smb.SmbFileRepository
import com.lanplay.player.smb.VIDEO_EXTENSIONS
import com.lanplay.player.smb.proxy.LocalMediaProxy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.resume
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

data class PlaybackSession(
    val recordId: Long,
    val serverId: Long,
    val relativePath: String,
    val fileName: String,
    val sizeBytes: Long,
    val url: String,
    val resumedFromMs: Long = 0L,
    val askBeforeResume: Boolean = false,
    val subtitlePath: String? = null,
    val subtitleCharset: String? = null,
    val subtitleEnabled: Boolean = true,
    val kernel: PlayerKernel = PlayerKernel.MEDIA3,
    val playbackSpeed: Float = 1f,
    val aspectRatioMode: Int = 0,
    val zoomScale: Float = 1f,
    val zoomOffsetX: Float = 0f,
    val zoomOffsetY: Float = 0f,
    val rotationDegrees: Int = 0,
    val mirrorH: Boolean = false,
    val mirrorV: Boolean = false,
    val subtitleOffsetMs: Long = 0L,
    val audioTrackIndex: Int = -1,
    val isFavorite: Boolean = false,
    val rating: Int = 0,
    val note: String = "",
    val skipIntroMs: Long = 0,
    val skipOutroMs: Long = 0,
    val externalAudioPath: String? = null,
)

data class DebugSnapshot(
    val throughputMbps: Double = 0.0,
    val bufferSeconds: Double = 0.0,
    val proxyMb: Double = 0.0,
    val inflight: Int = 0,
    val hitRate: Double = 0.0,
    val reconnects: Int = 0,
    val dialect: String = "-",
    val refreshRate: Float = 0f,
    val heapUsedMb: Double = 0.0,
    val heapMaxMb: Double = 0.0,
    val outputDevice: String = "系统默认",
)

data class AudioCalibration(
    val deviceKey: String = "default",
    val displayName: String = "系统默认",
    val outputType: String = "内置扬声器",
    val delayMs: Long = 0,
    val delaySupported: Boolean = false,
)

enum class QueueMode { NORMAL, SINGLE, LIST_LOOP, RANDOM }
data class AbLoop(val startMs: Long, val endMs: Long)
data class QueueItem(val path: String, val name: String, val current: Boolean)

internal fun shouldCountPlayback(
    previous: PlaybackSession?,
    serverId: Long,
    relativePath: String,
): Boolean = previous == null ||
    previous.serverId != serverId ||
    previous.relativePath != relativePath

internal fun shouldAutoAdvanceAtOutro(
    enabled: Boolean,
    positionMs: Long,
    durationMs: Long,
    skipOutroMs: Long,
    alreadyTriggered: Boolean,
): Boolean = enabled &&
    !alreadyTriggered &&
    skipOutroMs > 0L &&
    durationMs > 0L &&
    positionMs >= durationMs - skipOutroMs

internal fun subtitleTextsAt(
    timeline: List<TimedSubtitleCue>,
    playbackPositionMs: Long,
    offsetMs: Long,
): List<String> {
    val sourcePosition = playbackPositionMs - offsetMs
    return timeline.asSequence()
        .filter { sourcePosition >= it.startMs && sourcePosition < it.endMs }
        .map { it.text }
        .toList()
}

internal fun <T> chooseAudioOutput(
    routed: List<T>,
    connected: List<T>,
    typeOf: (T) -> Int,
): T? {
    routed.firstOrNull()?.let { return it }
    val preferredTypes = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
    )
    return connected.firstOrNull { typeOf(it) in preferredTypes }
        ?: connected.firstOrNull { typeOf(it) == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        ?: connected.firstOrNull()
}

internal class PlaybackWatchClock(
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private var playingSinceMs: Long? = null
    private var pendingMs: Long = 0L

    fun onStateChanged(previous: PlaybackState, next: PlaybackState) {
        val now = nowMs()
        if (previous == PlaybackState.PLAYING && next != PlaybackState.PLAYING) {
            settle(now, keepPlaying = false)
        } else if (previous != PlaybackState.PLAYING && next == PlaybackState.PLAYING) {
            playingSinceMs = now
        }
    }

    fun snapshot(isPlaying: Boolean): Long {
        settle(nowMs(), keepPlaying = isPlaying)
        return pendingMs
    }

    fun commit(savedMs: Long) {
        pendingMs = (pendingMs - savedMs.coerceAtLeast(0L)).coerceAtLeast(0L)
    }

    fun reset() {
        playingSinceMs = null
        pendingMs = 0L
    }

    private fun settle(now: Long, keepPlaying: Boolean) {
        playingSinceMs?.let { since ->
            pendingMs += (now - since).coerceAtLeast(0L)
        }
        playingSinceMs = now.takeIf { keepPlaying }
    }
}

/**
 * 播放会话编排：SMB 代理 → 播放内核 → 指标上报。
 *
 * 做成 @Singleton 是因为 debug 测试钩子（BroadcastReceiver，进程级）和播放器 UI
 * 需要驱动同一个播放实例。UI 只负责 attach SurfaceView 和画控件。
 */
@Singleton
class PlaybackController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val proxy: LocalMediaProxy,
    private val servers: ServerRepository,
    private val settings: SettingsRepository,
    private val fileRepository: SmbFileRepository,
    private val watchRepository: WatchRepository,
    private val subtitleRepository: SubtitleRepository,
    private val trashRepository: TrashRepository,
    private val connections: SmbConnectionManager,
    private val bookmarkDao: BookmarkDao,
    private val tagDao: TagDao,
    private val audioProfileDao: AudioDeviceProfileDao,
    private val spritePreviews: SpritePreviewRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessionMutex = Mutex()
    private val progressMutex = Mutex()
    private val commandGeneration = AtomicLong(0L)
    private var sessionGeneration = 0L

    private var engine: InstrumentedPlayerEngine? = null
    private var currentToken: String? = null
    private var currentAudioToken: String? = null
    private var metricsJob: Job? = null
    private var transformPersistJob: Job? = null
    private var subtitleOffsetPersistJob: Job? = null
    private var subtitleCueJob: Job? = null
    private var gestureSeekJob: Job? = null
    private var nextPreloadJob: Job? = null
    private var externalAudioScanJob: Job? = null
    private var spriteGenerationJob: Job? = null
    private val activeOpenJob = AtomicReference<Job?>(null)
    private val collectionJobs = mutableListOf<Job>()
    private var resetsAtSeekStart: Long = 0
    private var currentRecord: WatchRecordEntity? = null
    private var preparedSubtitle: PreparedSubtitle? = null
    private var lastPersistedAtMs: Long = 0L
    private val watchClock = PlaybackWatchClock()
    private var autoPlayNextEnabled = false

    private val _session = MutableStateFlow<PlaybackSession?>(null)
    val session: StateFlow<PlaybackSession?> = _session.asStateFlow()

    private val _state = MutableStateFlow(PlaybackState.IDLE)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _decoderInfo = MutableStateFlow(DecoderInfo())
    val decoderInfo: StateFlow<DecoderInfo> = _decoderInfo.asStateFlow()

    private val _audioTracks = MutableStateFlow<List<AudioTrackInfo>>(emptyList())
    val audioTracks: StateFlow<List<AudioTrackInfo>> = _audioTracks.asStateFlow()
    private val _externalAudioFiles = MutableStateFlow<List<String>>(emptyList())
    val externalAudioFiles: StateFlow<List<String>> = _externalAudioFiles.asStateFlow()
    private val _cues = MutableStateFlow<List<Cue>>(emptyList())
    val cues: StateFlow<List<Cue>> = _cues.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _debugSnapshot = MutableStateFlow(DebugSnapshot())
    val debugSnapshot: StateFlow<DebugSnapshot> = _debugSnapshot.asStateFlow()
    private val _bookmarks = MutableStateFlow<List<BookmarkEntity>>(emptyList())
    val bookmarks: StateFlow<List<BookmarkEntity>> = _bookmarks.asStateFlow()
    private val _tags = MutableStateFlow<List<TagEntity>>(emptyList())
    val tags: StateFlow<List<TagEntity>> = _tags.asStateFlow()
    private val _audioCalibration = MutableStateFlow(AudioCalibration())
    val audioCalibration: StateFlow<AudioCalibration> = _audioCalibration.asStateFlow()
    private val _queueMode = MutableStateFlow(QueueMode.NORMAL)
    val queueMode: StateFlow<QueueMode> = _queueMode.asStateFlow()
    private val _abLoop = MutableStateFlow<AbLoop?>(null)
    val abLoop: StateFlow<AbLoop?> = _abLoop.asStateFlow()
    private var outroTriggeredForPath: String? = null

    /**
     * Media3 只允许在创建它的线程上访问，而测试钩子跑在 BroadcastReceiver 的后台协程上。
     * 因此位置/时长走主线程 tick 刷新的缓存，任何线程读都安全。
     */
    @Volatile
    private var positionCache: Long = 0L

    @Volatile
    private var durationCache: Long = 0L

    val positionMs: Long get() = positionCache
    val durationMs: Long get() = durationCache

    /**
     * 打开并播放共享内的一个文件。
     * @param relativePath 相对共享根，'/' 分隔，例如 "电影/示例.mkv"
     */
    /**
     * 自动化测试期间强制静音：素材内容不适合外放，而且靠系统音量不可靠
     * （其他应用或系统策略随时会改回来）。这里直接压播放器自身的音量。
     */
    @Volatile
    var forceMute: Boolean = false

    fun setVolume(volume: Float) = onMain { it.setVolume(volume) }

    fun clearNotice() {
        _notice.value = null
    }

    suspend fun open(
        relativePath: String,
        startPositionMs: Long = 0L,
        subtitleOverride: String? = null,
        charsetOverride: String? = null,
        subtitlesEnabled: Boolean? = null,
        externalAudioPath: String? = null,
        serverId: Long? = null,
    ) {
        val openJob = currentCoroutineContext()[Job]
        if (openJob != null) {
            activeOpenJob.getAndSet(openJob)
                ?.takeIf { it !== openJob }
                ?.cancel(CancellationException("播放请求已被新请求取代"))
        }
        // 请求代际在等待互斥锁之前递增；后来请求可立即让较早 open 失效。
        val generation = commandGeneration.incrementAndGet()
        try {
            sessionMutex.withLock {
                if (generation != commandGeneration.get()) return
                sessionGeneration = generation
                try {
                    openLocked(
                        relativePath,
                        startPositionMs,
                        subtitleOverride,
                        charsetOverride,
                        subtitlesEnabled,
                        externalAudioPath,
                        serverId,
                        generation,
                    )
                } catch (t: Throwable) {
                    try {
                        stopInternal(clearPreloadedHeads = true)
                    } catch (cleanup: Throwable) {
                        t.addSuppressed(cleanup)
                    }
                    _session.value = null
                    _state.value = if (t is CancellationException) {
                        PlaybackState.IDLE
                    } else {
                        PlaybackState.ERROR
                    }
                    if (t !is CancellationException) {
                        _lastError.value = t.message ?: "无法开始播放"
                    }
                    context.stopService(Intent(context, PlaybackService::class.java))
                    throw t
                }
            }
        } finally {
            if (openJob != null) activeOpenJob.compareAndSet(openJob, null)
        }
    }

    private suspend fun openLocked(
        relativePath: String,
        startPositionMs: Long,
        subtitleOverride: String?,
        charsetOverride: String?,
        subtitlesEnabled: Boolean?,
        externalAudioPath: String?,
        serverId: Long?,
        generation: Long,
    ) {
        val savedServer = serverId?.let { servers.getById(it) } ?: servers.current()
            ?: throw SmbException(SmbErrorCode.NOT_CONFIGURED, "还没有配置共享，请先添加服务器")
        val target = savedServer.target

        val previousSession = _session.value
        ensureCurrent(generation)
        stopInternal(clearPreloadedHeads = false)
        watchClock.reset()
        positionCache = 0L
        durationCache = 0L
        _lastError.value = null
        _notice.value = null

        val entry = withContext(Dispatchers.IO) {
            fileRepository.stat(target, relativePath)
                ?: throw SmbException(SmbErrorCode.FILE_NOT_FOUND, "这个文件已经不在了")
        }
        ensureCurrent(generation)
        val shouldCountPlay = shouldCountPlayback(
            previousSession,
            savedServer.id,
            relativePath,
        )
        val record = watchRepository.findForPlayback(savedServer.id, target, entry)
            ?: WatchRecordEntity(
                serverId = savedServer.id,
                fullPath = entry.relativePath,
                fileName = entry.name,
                fileSize = entry.size,
                lastModified = entry.lastModified,
            )
        ensureCurrent(generation)
        val playerSettings = settings.currentPlayerSettings()
        autoPlayNextEnabled = playerSettings.autoPlayNext
        val audioEnhancements = settings.currentAudioEnhancementSettings()
        val resumeMs = when {
            startPositionMs > 0L -> startPositionMs
            playerSettings.resumePolicy == ResumePolicy.NEVER -> 0L
            record.positionMs >= 5_000L &&
                (record.durationMs <= 0L || record.progressPercent < 0.95f) -> record.positionMs
            else -> record.skipIntroMs.coerceAtLeast(0L)
        }
        outroTriggeredForPath = null
        _abLoop.value = null
        val resolvedSubtitleEnabled = subtitlesEnabled ?: record.subtitleEnabled
        val resolvedSubtitlePath = subtitleOverride ?: record.subtitlePath
        val resolvedCharset = charsetOverride ?: record.subtitleCharset
        var subtitle = if (resolvedSubtitleEnabled) {
            runCatching {
                subtitleRepository.prepare(
                    target,
                    relativePath,
                    resolvedSubtitlePath,
                    resolvedCharset,
                    0L,
                )
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                Metric.error("SUBTITLE_PREPARE", it.message ?: "字幕加载失败")
                _notice.value = "字幕加载失败，已继续无字幕播放"
            }.getOrNull()
        } else null
        val nativeSubtitleExtensions = setOf("ass", "ssa", "idx", "sub", "smi", "ttml")
        val useNativeSubtitleRenderer = subtitle?.sourcePath
            ?.substringAfterLast('.', "")
            ?.lowercase() in nativeSubtitleExtensions ||
            (subtitle != null && subtitle.timeline.isEmpty())
        val audioEnhancementsEnabled =
            audioEnhancements.volumeBoostPercent > 100 ||
                audioEnhancements.loudnessNormalization ||
                audioEnhancements.equalizerPreset != "flat" ||
                audioEnhancements.equalizerBands.any { it != 0f }
        val effectiveKernel = if (
            useNativeSubtitleRenderer ||
            externalAudioPath != null ||
            audioEnhancementsEnabled
        ) {
            PlayerKernel.VLC
        } else {
            playerSettings.playerKernel
        }
        if (subtitle != null) {
            Metric.emit(
                "subtitle",
                "path" to subtitle.sourcePath,
                "charset" to subtitle.charset,
                // 字幕正文属于私人媒体内容，指标只记录是否存在可解析文本。
                "has_text" to subtitle.firstTextLine.isNotEmpty(),
            )
        }
        preparedSubtitle = subtitle
        // VLC 走独立透明 Surface，由 libass / VLC 原生字幕解码器绘制。
        // 这样 ASS 定位、动画、字体与 VobSub 图形字幕不会被降级成纯文本。
        _cues.value = emptyList()

        val io = settings.currentIoSettings()
        val subtitleStyle = settings.subtitleStyleSettings.first()
        val url = proxy.publish(target, relativePath, io)
        currentToken = url.substringAfterLast('/')
        ensureCurrent(generation)
        val externalAudioUrl = externalAudioPath?.let { path ->
            val audioUrl = proxy.publish(
                target,
                path,
                io.copy(prefetchMb = 8, concurrentReads = 2),
                SmbConnectionManager.Channel.AUX,
            )
            currentAudioToken = audioUrl.substringAfterLast('/')
            audioUrl
        }

        val fileName = relativePath.substringAfterLast('/')
        val pendingSession = PlaybackSession(
            recordId = record.id,
            serverId = savedServer.id,
            relativePath = relativePath,
            fileName = fileName,
            sizeBytes = entry.size,
            url = url,
            resumedFromMs = resumeMs,
            askBeforeResume = playerSettings.resumePolicy == ResumePolicy.ASK && resumeMs > 0L,
            subtitlePath = subtitle?.sourcePath,
            subtitleCharset = subtitle?.charset,
            subtitleEnabled = resolvedSubtitleEnabled,
            kernel = effectiveKernel,
            playbackSpeed = record.playbackSpeed.coerceIn(0.5f, 3f),
            aspectRatioMode = record.aspectRatioMode.coerceIn(0, 5),
            zoomScale = record.zoomScale.coerceIn(1f, 5f),
            zoomOffsetX = record.zoomOffsetX,
            zoomOffsetY = record.zoomOffsetY,
            rotationDegrees = record.rotationDegrees,
            mirrorH = record.mirrorH,
            mirrorV = record.mirrorV,
            subtitleOffsetMs = record.subtitleOffsetMs,
            audioTrackIndex = record.audioTrackIndex,
            isFavorite = record.isFavorite,
            rating = record.rating,
            note = record.note.orEmpty(),
            skipIntroMs = record.skipIntroMs,
            skipOutroMs = record.skipOutroMs,
            externalAudioPath = externalAudioPath,
        )
        // 正常的 UI 播放会由 MainActivity 在可见状态启动前台服务。调试钩子可能从
        // 后台 BroadcastReceiver 打开视频，Android 12+ 会拒绝该来源启动前台服务；
        // 这里绝不能让系统策略异常中断真正的播放。
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, PlaybackService::class.java)
                    .putExtra(PlaybackService.EXTRA_TITLE, fileName),
            )
        }

        check(acquireAudioFocus()) { "另一个应用正在占用音频，暂时无法播放" }
        withContext(Dispatchers.Main.immediate) {
            val e: InstrumentedPlayerEngine = when (effectiveKernel) {
                PlayerKernel.MEDIA3 -> Media3Engine(context, io.decoderMode)
                PlayerKernel.VLC -> VlcEngine(
                    context,
                    io.decoderMode,
                    subtitleStyle.sizePercent,
                    audioEnhancements,
                )
            }.also { engine = it }
            wireEngine(e, fileName, entry.size, generation)
            attachedSurface?.let { e.attach(it, attachedSubtitleSurface) }
            e.setVolume(
                if (forceMute) 0f
                else audioEnhancements.volumeBoostPercent.coerceIn(100, 200) / 100f
            )
            e.open(
                url,
                resumeMs,
                subtitle = subtitle,
                externalAudioUrl = externalAudioUrl,
            )
            e.setSpeed(record.playbackSpeed.coerceIn(0.5f, 3f))
            if (effectiveKernel == PlayerKernel.VLC) {
                e.setSubtitleDelayMs(record.subtitleOffsetMs)
            }
            if (pendingSession.askBeforeResume) {
                // 续播选择出现前不允许音频或视频先走一帧；UI 同时保持黑场覆盖。
                e.pause()
            }
            refreshAudioDevice(e)
        }
        ensureCurrent(generation)
        val startedRecord = watchRepository.begin(
            savedServer.id,
            target,
            entry,
            countPlay = shouldCountPlay || record.id == 0L,
        )
        currentRecord = startedRecord
        _session.value = pendingSession.copy(recordId = startedRecord.id)
        collectionJobs += scope.launch {
            bookmarkDao.observe(startedRecord.id).collect { _bookmarks.value = it }
        }
        collectionJobs += scope.launch {
            tagDao.observeForRecord(startedRecord.id).collect { _tags.value = it }
        }
        if (
            subtitlesEnabled != null ||
            subtitleOverride != null ||
            charsetOverride != null
        ) {
            watchRepository.setSubtitlePreference(
                startedRecord.id,
                subtitle?.sourcePath ?: resolvedSubtitlePath,
                subtitle?.charset ?: resolvedCharset,
                resolvedSubtitleEnabled,
            )
        }
        ensureCurrent(generation)
        registerAudioDeviceCallback()
        registerNoisyReceiver()
        lastPersistedAtMs = System.currentTimeMillis()
        startMetricsLoop()
        startSubtitleCueLoop()
        if (playerSettings.seekPreviewEnabled) {
            spriteGenerationJob = scope.launch {
                delay(SPRITE_GENERATION_DELAY_MS)
                if (
                    generation == commandGeneration.get() &&
                    _session.value?.recordId == startedRecord.id
                ) {
                    spritePreviews.ensureGenerated(savedServer, entry)
                }
            }
        }
        externalAudioScanJob = scope.launch {
            val parent = relativePath.substringBeforeLast('/', "")
            val audioFiles = withContext(Dispatchers.IO) {
                val entries = try {
                    fileRepository.list(target, parent)
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    emptyList()
                }
                entries
                    .filter {
                        !it.isDirectory &&
                            it.relativePath != relativePath &&
                            it.extension in EXTERNAL_AUDIO_EXTENSIONS
                    }
                    .sortedBy { it.name.lowercase() }
                    .map { it.relativePath }
            }
            val active = _session.value
            if (
                generation == commandGeneration.get() &&
                active?.recordId == startedRecord.id &&
                active.serverId == savedServer.id
            ) {
                _externalAudioFiles.value = audioFiles
            }
        }
        nextPreloadJob = scope.launch {
            delay(1_500)
            val parent = relativePath.substringBeforeLast('/', "")
            val videos = withContext(Dispatchers.IO) {
                val entries = try {
                    fileRepository.list(target, parent)
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    emptyList()
                }
                entries
                    .filter { !it.isDirectory && it.extension in VIDEO_EXTENSIONS }
                    .sortedBy { it.name.lowercase() }
            }
            val currentIndex = videos.indexOfFirst { it.relativePath == relativePath }
            val next = videos.getOrNull(currentIndex + 1)
            if (
                next != null &&
                generation == commandGeneration.get() &&
                _session.value?.recordId == startedRecord.id &&
                _session.value?.serverId == savedServer.id
            ) {
                try {
                    proxy.preloadHead(target, next.relativePath)
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    Metric.error(
                        "NEXT_PRELOAD",
                        t.message ?: "下一集预缓存失败",
                        "file" to next.name,
                    )
                }
            }
        }
    }

    private fun wireEngine(
        e: InstrumentedPlayerEngine,
        fileName: String,
        sizeBytes: Long,
        generation: Long,
    ) {
        fun isCurrent(): Boolean = sessionGeneration == generation &&
            commandGeneration.get() == generation && engine === e
        e.onFirstFrame = firstFrame@{ elapsedMs ->
            if (!isCurrent()) return@firstFrame
            val info = e.decoderInfo.value
            Metric.emit(
                "first_frame",
                "ms" to elapsedMs,
                "file" to fileName,
                "w" to info.width,
                "h" to info.height,
            )
            emitDisplay()
        }
        e.onSeekCompleted = seekCompleted@{ elapsedMs ->
            if (!isCurrent()) return@seekCompleted
            val stats = proxy.activeStats
            val inWindow = stats != null && stats.resets == resetsAtSeekStart
            Metric.emit(
                "seek",
                "target_ms" to e.positionMs,
                "resume_ms" to elapsedMs,
                "in_window" to inWindow,
            )
        }
        e.onError = engineError@{ code, message ->
            if (!isCurrent()) return@engineError
            _lastError.value = message
            Metric.error("PLAYBACK_ERROR", message, "code" to code)
        }
        collectionJobs += scope.launch {
            e.state.collect { s ->
                if (!isCurrent()) return@collect
                val previous = _state.value
                watchClock.onStateChanged(previous, s)
                _state.value = s
                if (s == PlaybackState.PLAYING || s == PlaybackState.READY) {
                    // 拿到真实时长后才能把字节水位换算成秒数水位
                    val dur = e.durationMs
                    if (dur > 0 && sizeBytes > 0) {
                        proxy.activeStats?.bitrateBytesPerSec = sizeBytes.toDouble() / (dur / 1000.0)
                    }
                }
                when (s) {
                    PlaybackState.PAUSED -> persistProgress()
                    PlaybackState.ENDED -> persistProgress(forceCompleted = true)
                    else -> Unit
                }
            }
        }
        collectionJobs += scope.launch {
            e.decoderInfo.collect { if (isCurrent()) _decoderInfo.value = it }
        }
        collectionJobs += scope.launch {
            e.audioTracks.collect { tracks ->
                if (!isCurrent()) return@collect
                _audioTracks.value = tracks
                val saved = _session.value?.audioTrackIndex ?: -1
                if (saved >= 0 && tracks.getOrNull(saved)?.selected != true) {
                    e.selectAudioTrack(saved)
                }
            }
        }
        if (e !is VlcEngine) {
            collectionJobs += scope.launch {
                e.cues.collect { if (isCurrent()) _cues.value = it }
            }
        }
    }

    /**
     * 记住当前的显示表面。
     *
     * 每次 open() 都会重建播放内核，而 SurfaceView 由 UI 持有、只在进入播放器时创建一次。
     * 不把它记下来，连播下一个文件时新内核就没有 Surface——表现为进度在走、声音也有，
     * 但画面全黑且 renderedFrames 恒为 0。
     */
    private var attachedSurface: SurfaceView? = null
    private var attachedSubtitleSurface: SurfaceView? = null

    fun attachSurface(
        surfaceView: SurfaceView,
        subtitleSurfaceView: SurfaceView? = null,
    ) {
        attachedSurface = surfaceView
        attachedSubtitleSurface = subtitleSurfaceView
        engine?.attach(surfaceView, subtitleSurfaceView)
    }

    fun detachSurface() {
        attachedSurface = null
        attachedSubtitleSurface = null
        engine?.detach()
    }

    fun play() {
        if (acquireAudioFocus()) {
            onMain { it.play() }
        } else {
            _lastError.value = "另一个应用正在占用音频，暂时无法播放"
        }
    }

    fun pause() {
        onMain { it.pause() }
        scope.launch { persistProgress() }
    }

    fun seekTo(positionMs: Long) {
        resetsAtSeekStart = proxy.activeStats?.resets ?: 0
        scope.launch { persistProgress() }
        onMain { it.seekTo(positionMs) }
    }

    /**
     * 手势连续拖动只执行最后一次落点。短暂合并快速重复操作，避免解码器和 SMB
     * 预读窗口在多个尚未稳定的目标之间来回重置。
     */
    fun seekToFromGesture(positionMs: Long) {
        gestureSeekJob?.cancel()
        gestureSeekJob = scope.launch {
            delay(90)
            persistProgress()
            resetsAtSeekStart = proxy.activeStats?.resets ?: 0
            withContext(Dispatchers.Main.immediate) {
                engine?.seekTo(positionMs)
            }
        }
    }

    fun setSpeed(speed: Float) {
        val value = ((speed.coerceIn(0.5f, 3f) * 4).toInt() / 4f)
        _session.value = _session.value?.copy(playbackSpeed = value)
        onMain { it.setSpeed(value) }
        currentRecord?.let { record ->
            scope.launch { watchRepository.setPlaybackSpeed(record.id, value) }
        }
    }

    /** 手势临时倍速：只影响当前 engine，不改会话偏好和数据库。 */
    fun setTransientSpeed(speed: Float) {
        val value = ((speed.coerceIn(0.5f, 3f) * 4).toInt() / 4f)
        onMain { it.setSpeed(value) }
    }

    fun setAspectRatioMode(mode: Int) {
        val value = mode.coerceIn(0, 5)
        _session.value = _session.value?.copy(aspectRatioMode = value)
        currentRecord?.let { record ->
            scope.launch { watchRepository.setAspectRatioMode(record.id, value) }
        }
    }

    fun setVideoTransform(
        scale: Float = _session.value?.zoomScale ?: 1f,
        offsetX: Float = _session.value?.zoomOffsetX ?: 0f,
        offsetY: Float = _session.value?.zoomOffsetY ?: 0f,
        rotation: Int = _session.value?.rotationDegrees ?: 0,
        mirrorH: Boolean = _session.value?.mirrorH ?: false,
        mirrorV: Boolean = _session.value?.mirrorV ?: false,
    ) {
        val normalizedRotation = ((rotation % 360) + 360) % 360
        val value = _session.value?.copy(
            zoomScale = scale.coerceIn(1f, 5f),
            zoomOffsetX = offsetX,
            zoomOffsetY = offsetY,
            rotationDegrees = normalizedRotation,
            mirrorH = mirrorH,
            mirrorV = mirrorV,
        ) ?: return
        _session.value = value
        currentRecord?.let { record ->
            transformPersistJob?.cancel()
            transformPersistJob = scope.launch {
                delay(300)
                watchRepository.setVideoTransform(
                    record.id,
                    value.zoomScale,
                    value.zoomOffsetX,
                    value.zoomOffsetY,
                    value.rotationDegrees,
                    value.mirrorH,
                    value.mirrorV,
                )
            }
        }
    }

    fun setSubtitleOffset(offsetMs: Long) {
        val value = offsetMs.coerceIn(-60_000L, 60_000L)
        _session.value = _session.value?.copy(subtitleOffsetMs = value)
        currentRecord?.let { record ->
            subtitleOffsetPersistJob?.cancel()
            subtitleOffsetPersistJob = scope.launch {
                delay(300)
                watchRepository.setSubtitleOffset(record.id, value)
            }
        }
        val current = _session.value ?: return
        if (current.kernel == PlayerKernel.VLC) {
            onMain { it.setSubtitleDelayMs(value) }
        } else {
            updateTextSubtitleCues()
        }
    }

    fun selectAudioTrack(index: Int) {
        if (index !in _audioTracks.value.indices) return
        _session.value = _session.value?.copy(audioTrackIndex = index)
        onMain { it.selectAudioTrack(index) }
        currentRecord?.let { record ->
            scope.launch { watchRepository.setAudioTrack(record.id, index) }
        }
    }

    fun toggleFavorite() {
        val session = _session.value ?: return
        val value = !session.isFavorite
        _session.value = session.copy(isFavorite = value)
        scope.launch { watchRepository.setFavorite(session.recordId, value) }
    }

    fun setRating(rating: Int) {
        val session = _session.value ?: return
        val value = rating.coerceIn(0, 5)
        _session.value = session.copy(rating = value)
        scope.launch { watchRepository.setRating(session.recordId, value) }
    }

    fun setNote(note: String) {
        val session = _session.value ?: return
        val value = note.trim().take(2_000)
        _session.value = session.copy(note = value)
        scope.launch { watchRepository.setNote(session.recordId, value.ifBlank { null }) }
    }

    fun addBookmark(label: String? = null) {
        val session = _session.value ?: return
        val position = positionMs.coerceAtLeast(0L)
        scope.launch {
            bookmarkDao.insert(
                BookmarkEntity(
                    recordId = session.recordId,
                    positionMs = position,
                    label = label?.trim()?.take(80)?.ifBlank { null },
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    fun deleteBookmark(bookmark: BookmarkEntity) {
        scope.launch { bookmarkDao.delete(bookmark) }
    }

    fun addTag(name: String, colorHex: String = "#6E93D6") {
        val session = _session.value ?: return
        val clean = name.trim().take(30)
        if (clean.isBlank()) return
        scope.launch(Dispatchers.IO) {
            val existing = tagDao.listAll().firstOrNull {
                it.name.equals(clean, ignoreCase = true)
            }
            val tagId = existing?.id ?: runCatching {
                tagDao.insert(TagEntity(name = clean, colorHex = colorHex))
            }.getOrElse {
                tagDao.listAll().firstOrNull {
                    it.name.equals(clean, ignoreCase = true)
                }?.id ?: return@launch
            }
            tagDao.addToRecord(RecordTagEntity(session.recordId, tagId))
        }
    }

    fun addToCollection(name: String) {
        val session = _session.value ?: return
        val clean = name.trim().take(24)
        if (clean.isBlank()) return
        val value = "$COLLECTION_PREFIX$clean"
        _session.value = session.copy(isFavorite = true)
        scope.launch(Dispatchers.IO) {
            watchRepository.setFavorite(session.recordId, true)
            val existing = tagDao.listAll().firstOrNull {
                it.name.equals(value, ignoreCase = true)
            }
            val tagId = existing?.id ?: runCatching {
                tagDao.insert(TagEntity(name = value, colorHex = "#D47760"))
            }.getOrElse {
                tagDao.listAll().firstOrNull {
                    it.name.equals(value, ignoreCase = true)
                }?.id ?: return@launch
            }
            tagDao.addToRecord(RecordTagEntity(session.recordId, tagId))
        }
    }

    fun removeTag(tag: TagEntity) {
        val session = _session.value ?: return
        scope.launch { tagDao.removeFromRecord(session.recordId, tag.id) }
    }

    fun setAudioDelay(delayMs: Long) {
        val calibration = _audioCalibration.value
        val value = delayMs.coerceIn(-500L, 500L)
        if (!calibration.delaySupported && value != 0L) {
            _lastError.value = "音频延迟补偿需要切换到 VLC 兼容内核"
            return
        }
        _audioCalibration.value = calibration.copy(delayMs = value)
        onMain { it.setAudioDelayMs(value) }
        scope.launch(Dispatchers.IO) {
            audioProfileDao.upsert(
                AudioDeviceProfileEntity(
                    deviceKey = calibration.deviceKey,
                    displayName = calibration.displayName,
                    outputType = calibration.outputType,
                    audioDelayMs = value,
                    lastUsedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    fun setQueueMode(mode: QueueMode) {
        _queueMode.value = mode
    }

    fun setAbStart() {
        val start = positionMs
        val existing = _abLoop.value
        _abLoop.value = AbLoop(start, existing?.endMs?.takeIf { it > start } ?: start)
    }

    fun setAbEnd() {
        val end = positionMs
        val start = _abLoop.value?.startMs ?: return
        if (end - start < 1_000L) {
            _lastError.value = "A-B 区间至少需要 1 秒"
            return
        }
        _abLoop.value = AbLoop(start, end)
    }

    fun clearAbLoop() {
        _abLoop.value = null
    }

    fun setSkipIntroHere() {
        val session = _session.value ?: return
        val intro = positionMs.coerceAtLeast(0L)
        _session.value = session.copy(skipIntroMs = intro)
        scope.launch {
            watchRepository.setSkipPoints(session.recordId, intro, session.skipOutroMs)
        }
    }

    fun setSkipOutroHere() {
        val session = _session.value ?: return
        val remaining = (durationMs - positionMs).coerceAtLeast(0L)
        _session.value = session.copy(skipOutroMs = remaining)
        scope.launch {
            watchRepository.setSkipPoints(session.recordId, session.skipIntroMs, remaining)
        }
    }

    fun clearSkipPoints() {
        val session = _session.value ?: return
        _session.value = session.copy(skipIntroMs = 0, skipOutroMs = 0)
        scope.launch { watchRepository.setSkipPoints(session.recordId, 0, 0) }
    }

    suspend fun listQueue(): List<QueueItem> {
        val current = _session.value ?: return emptyList()
        val server = servers.getById(current.serverId) ?: return emptyList()
        val parent = current.relativePath.substringBeforeLast('/', "")
        return fileRepository.list(server.target, parent)
            .filter { !it.isDirectory && it.extension in VIDEO_EXTENSIONS }
            .sortedBy { it.name.lowercase() }
            .map { QueueItem(it.relativePath, it.name, it.relativePath == current.relativePath) }
    }

    fun playQueueItem(path: String) {
        val serverId = _session.value?.serverId ?: return
        scope.launch { open(path, serverId = serverId) }
    }

    fun replay() {
        seekTo(0L)
        play()
    }

    fun retryCurrent() {
        val current = _session.value ?: return
        val position = positionMs
        scope.launch {
            open(
                current.relativePath,
                position,
                current.subtitlePath,
                current.subtitleCharset,
                current.subtitleEnabled,
                current.externalAudioPath,
                serverId = current.serverId,
            )
        }
    }

    fun deleteCurrent() {
        val current = _session.value ?: return
        scope.launch {
            val generation = commandGeneration.incrementAndGet()
            sessionMutex.withLock {
                sessionGeneration = generation
                val server = servers.getById(current.serverId) ?: return@withLock
                try {
                    activeOpenJob.getAndSet(null)?.cancel(
                        CancellationException("删除当前视频")
                    )
                    stopInternal(clearPreloadedHeads = true)
                    _session.value = null
                    _state.value = PlaybackState.IDLE
                    val result = trashRepository.moveToTrash(
                        server.id,
                        server.target,
                        current.relativePath,
                    )
                    if (!result.videoMoved) {
                        _lastError.value = result.primaryFailure?.reason ?: "视频未能移入回收站"
                    } else if (result.isPartial) {
                        _notice.value = "视频已移入回收站；部分关联字幕未能移动"
                    }
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    _lastError.value = t.message ?: "删除失败"
                } finally {
                    context.stopService(Intent(context, PlaybackService::class.java))
                }
            }
        }
    }

    /** A-05：直接复制应用自己的 Surface，保存到私有目录，不进入系统相册。 */
    suspend fun captureFrame(): File {
        val surface = attachedSurface ?: error("播放器画面尚未就绪")
        val bitmap = withContext(Dispatchers.Main.immediate) {
            if (surface.width <= 0 || surface.height <= 0) error("播放器画面尚未就绪")
            val target = Bitmap.createBitmap(
                surface.width,
                surface.height,
                Bitmap.Config.ARGB_8888,
            )
            val result = suspendCancellableCoroutine { continuation ->
                PixelCopy.request(
                    surface,
                    target,
                    { code -> if (continuation.isActive) continuation.resume(code) },
                    Handler(Looper.getMainLooper()),
                )
            }
            if (result != PixelCopy.SUCCESS) {
                target.recycle()
                error("当前画面截图失败（代码 $result）")
            }
            target
        }
        return withContext(Dispatchers.IO) {
            val directory = File(context.filesDir, "screenshots").apply { mkdirs() }
            val safeName = _session.value?.fileName
                ?.substringBeforeLast('.')
                ?.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
                ?.take(48)
                .orEmpty()
                .ifBlank { "LanPlay" }
            val file = File(directory, "${safeName}_${System.currentTimeMillis()}.jpg")
            val part = File(directory, ".${file.name}.part")
            try {
                FileOutputStream(part).use {
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it)) {
                        "截图写入失败"
                    }
                    it.fd.sync()
                }
                check(part.renameTo(file)) { "截图提交失败" }
                file
            } finally {
                bitmap.recycle()
                if (part.exists()) part.delete()
            }
        }
    }

    fun switchKernel() {
        val current = _session.value ?: return
        val position = positionMs
        scope.launch {
            val next = if (current.kernel == PlayerKernel.MEDIA3) PlayerKernel.VLC else PlayerKernel.MEDIA3
            settings.setPlayerKernel(next)
            open(
                current.relativePath,
                position,
                current.subtitlePath,
                current.subtitleCharset,
                current.subtitleEnabled,
                current.externalAudioPath,
                serverId = current.serverId,
            )
        }
    }

    suspend fun listSubtitles(): List<String> {
        val current = _session.value ?: return emptyList()
        val server = servers.getById(current.serverId) ?: return emptyList()
        return subtitleRepository.listCandidates(server.target, current.relativePath)
    }

    suspend fun searchSubtitle(query: String): List<SubtitleSearchHit> {
        val subtitle = preparedSubtitle ?: return emptyList()
        return subtitleRepository.searchLocal(subtitle, query)
    }

    suspend fun seekPreview(positionMs: Long): Bitmap? {
        val current = _session.value ?: return null
        return spritePreviews.frame(current.serverId, current.relativePath, positionMs)
    }

    fun selectSubtitle(path: String, charset: String?) {
        val current = _session.value ?: return
        val position = positionMs
        scope.launch {
            open(
                current.relativePath,
                position,
                path,
                charset,
                true,
                current.externalAudioPath,
                serverId = current.serverId,
            )
        }
    }

    fun disableSubtitles() {
        val current = _session.value ?: return
        val position = positionMs
        scope.launch {
            open(
                current.relativePath,
                position,
                current.subtitlePath,
                current.subtitleCharset,
                false,
                current.externalAudioPath,
                serverId = current.serverId,
            )
        }
    }

    fun selectExternalAudio(path: String?) {
        val current = _session.value ?: return
        val position = positionMs
        scope.launch {
            open(
                current.relativePath,
                position,
                current.subtitlePath,
                current.subtitleCharset,
                current.subtitleEnabled,
                path,
                serverId = current.serverId,
            )
        }
    }

    fun playAdjacent(direction: Int) {
        val current = _session.value ?: return
        scope.launch {
            val server = servers.getById(current.serverId) ?: return@launch
            val parent = current.relativePath.substringBeforeLast('/', "")
            val files = runCatching { fileRepository.list(server.target, parent) }
                .getOrElse {
                    _lastError.value = "无法读取同目录视频"
                    return@launch
                }
                .filter { !it.isDirectory && it.extension in VIDEO_EXTENSIONS }
                .sortedBy { it.name.lowercase() }
            if (files.isEmpty()) return@launch
            val index = files.indexOfFirst { it.relativePath == current.relativePath }
            if (index < 0) return@launch
            val target = when {
                _queueMode.value == QueueMode.SINGLE -> index
                _queueMode.value == QueueMode.RANDOM ->
                    files.indices.filter { it != index }.randomOrNull() ?: index
                _queueMode.value == QueueMode.LIST_LOOP ->
                    (index + direction).mod(files.size)
                else -> (index + direction).coerceIn(0, files.lastIndex)
            }
            if (_session.value?.recordId != current.recordId) return@launch
            if (target == index && _queueMode.value == QueueMode.SINGLE) replay()
            else if (target != index) open(files[target].relativePath, serverId = current.serverId)
        }
    }

    private fun onMain(block: (InstrumentedPlayerEngine) -> Unit) {
        val target = engine ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (engine === target) block(target)
        } else {
            scope.launch {
                if (engine === target) block(target)
            }
        }
    }

    private fun ensureCurrent(generation: Long) {
        if (generation != commandGeneration.get()) {
            throw kotlinx.coroutines.CancellationException("播放请求已被更新请求取代")
        }
    }

    fun requestStop(fromService: Boolean = false) {
        scope.launch {
            try {
                stop(fromService)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _lastError.value = t.message ?: "播放资源清理失败"
            }
        }
    }

    fun requestPersist() {
        scope.launch { persistNow() }
    }

    suspend fun stop(fromService: Boolean = false) {
        activeOpenJob.getAndSet(null)?.cancel(CancellationException("停止播放"))
        val generation = commandGeneration.incrementAndGet()
        sessionMutex.withLock {
            sessionGeneration = generation
            var failure: Throwable? = null
            try {
                stopInternal(clearPreloadedHeads = true)
            } catch (t: Throwable) {
                failure = t
                if (t !is kotlinx.coroutines.CancellationException) {
                    _lastError.value = t.message ?: "播放资源清理失败"
                }
            } finally {
                _session.value = null
                _state.value = PlaybackState.IDLE
                if (!fromService) {
                    context.stopService(Intent(context, PlaybackService::class.java))
                }
            }
            failure?.let { throw it }
        }
    }

    /** Activity.onStop 的同步点（W-01）：不暂停，只把当前位置立即落盘。 */
    suspend fun persistNow() = persistProgress()

    private suspend fun stopInternal(
        clearPreloadedHeads: Boolean,
    ) = withContext(NonCancellable) {
        var failure: Throwable? = null
        suspend fun attempt(block: suspend () -> Unit) {
            try {
                block()
            } catch (t: Throwable) {
                failure?.addSuppressed(t) ?: run { failure = t }
            }
        }

        attempt { persistProgress() }
        val finalSession = _session.value
        _session.value = null
        val record = currentRecord
        if (record != null && finalSession?.recordId == record.id) {
            attempt {
                watchRepository.setVideoTransform(
                    record.id,
                    finalSession.zoomScale,
                    finalSession.zoomOffsetX,
                    finalSession.zoomOffsetY,
                    finalSession.rotationDegrees,
                    finalSession.mirrorH,
                    finalSession.mirrorV,
                )
            }
            attempt { watchRepository.setSubtitleOffset(record.id, finalSession.subtitleOffsetMs) }
        }

        metricsJob?.cancel()
        metricsJob = null
        transformPersistJob?.cancel()
        transformPersistJob = null
        subtitleOffsetPersistJob?.cancel()
        subtitleOffsetPersistJob = null
        subtitleCueJob?.cancel()
        subtitleCueJob = null
        gestureSeekJob?.cancel()
        gestureSeekJob = null
        nextPreloadJob?.cancel()
        nextPreloadJob = null
        externalAudioScanJob?.cancel()
        externalAudioScanJob = null
        spriteGenerationJob?.cancel()
        spriteGenerationJob = null
        collectionJobs.forEach { it.cancel() }
        collectionJobs.clear()

        val oldEngine = engine
        engine = null
        attempt {
            withContext(Dispatchers.Main.immediate) {
                oldEngine?.let {
                    positionCache = it.positionMs
                    durationCache = it.durationMs
                    it.release()
                }
            }
        }
        val videoToken = currentToken
        currentToken = null
        val audioToken = currentAudioToken
        currentAudioToken = null
        attempt {
            withContext(Dispatchers.IO) { videoToken?.let { proxy.release(it) } }
        }
        attempt {
            withContext(Dispatchers.IO) { audioToken?.let { proxy.release(it) } }
        }
        // 兜底关闭被取消的预览、外部音轨或失败重开遗留的代理会话。
        attempt {
            withContext(Dispatchers.IO) {
                proxy.releaseAll(clearPreloadedHeads = clearPreloadedHeads)
            }
        }

        currentRecord = null
        watchClock.reset()
        autoPlayNextEnabled = false
        val subtitleToDelete = preparedSubtitle
        preparedSubtitle = null
        attempt {
            withContext(Dispatchers.IO) { subtitleToDelete?.sessionDirectory?.deleteRecursively() }
        }
        _cues.value = emptyList()
        _bookmarks.value = emptyList()
        _tags.value = emptyList()
        _externalAudioFiles.value = emptyList()
        attempt { abandonAudioFocus() }
        attempt { unregisterNoisyReceiver() }
        attempt { unregisterAudioDeviceCallback() }
        failure?.let { throw it }
    }

    /**
     * 主线程 tick：每 [TICK_MS] 刷新位置缓存，每 [METRIC_INTERVAL_MS] 输出一条 io 与 frames。
     * 长跑采集靠这条连续曲线判断有没有卡顿。
     */
    private fun startMetricsLoop() {
        metricsJob?.cancel()
        metricsJob = scope.launch {
            var sinceMetric = 0L
            while (isActive) {
                delay(TICK_MS)
                engine?.let {
                    positionCache = it.positionMs
                    durationCache = it.durationMs
                }
                if (_state.value == PlaybackState.PLAYING) {
                    val loop = _abLoop.value
                    if (loop != null && loop.endMs > loop.startMs &&
                        positionCache >= loop.endMs
                    ) {
                        seekTo(loop.startMs)
                    } else {
                        val current = _session.value
                        if (current != null && shouldAutoAdvanceAtOutro(
                                enabled = autoPlayNextEnabled,
                                positionMs = positionCache,
                                durationMs = durationCache,
                                skipOutroMs = current.skipOutroMs,
                                alreadyTriggered = outroTriggeredForPath == current.relativePath,
                            )
                        ) {
                            outroTriggeredForPath = current.relativePath
                            playAdjacent(1)
                        }
                    }
                }
                sinceMetric += TICK_MS
                if (sinceMetric >= METRIC_INTERVAL_MS) {
                    sinceMetric = 0
                    emitIoMetrics()
                }
                if (_state.value == PlaybackState.PLAYING &&
                    System.currentTimeMillis() - lastPersistedAtMs >= WATCH_WRITE_INTERVAL_MS
                ) {
                    persistProgress()
                }
            }
        }
    }

    private fun startSubtitleCueLoop() {
        subtitleCueJob?.cancel()
        val subtitle = preparedSubtitle ?: return
        if (subtitle.timeline.isEmpty() || _session.value?.kernel != PlayerKernel.MEDIA3) return
        subtitleCueJob = scope.launch {
            while (isActive) {
                updateTextSubtitleCues()
                delay(SUBTITLE_CUE_TICK_MS)
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun updateTextSubtitleCues() {
        val current = _session.value
        val subtitle = preparedSubtitle
        if (
            current == null ||
            !current.subtitleEnabled ||
            current.kernel != PlayerKernel.MEDIA3 ||
            subtitle == null ||
            subtitle.timeline.isEmpty()
        ) {
            if (_cues.value.isNotEmpty()) _cues.value = emptyList()
            return
        }
        engine?.let { positionCache = it.positionMs }
        val texts = subtitleTextsAt(
            subtitle.timeline,
            positionCache,
            current.subtitleOffsetMs,
        )
        val currentTexts = _cues.value.map { it.text?.toString().orEmpty() }
        if (texts != currentTexts) {
            _cues.value = texts.map { text -> Cue.Builder().setText(text).build() }
        }
    }

    private suspend fun persistProgress(forceCompleted: Boolean = false) =
        progressMutex.withLock {
            val recordId = currentRecord?.id ?: return@withLock
            val activeSession = _session.value ?: return@withLock
            if (activeSession.recordId != recordId) return@withLock
            val generation = sessionGeneration
            if (generation != commandGeneration.get()) return@withLock
            val dur = durationCache.takeIf { it > 0 } ?: engine?.durationMs ?: 0L
            val pos = positionCache.takeIf { it >= 0 } ?: 0L
            if (dur <= 0L) return@withLock
            val now = System.currentTimeMillis()
            val delta = watchClock.snapshot(_state.value == PlaybackState.PLAYING)
            watchRepository.saveProgress(recordId, pos, dur, delta, forceCompleted)
            watchClock.commit(delta)
            if (generation != sessionGeneration || activeSession.recordId != currentRecord?.id) {
                return@withLock
            }
            lastPersistedAtMs = now
            Metric.emit(
                "watch_saved",
                "record_id" to recordId,
                "pos_ms" to pos,
                "dur_ms" to dur,
                "completed" to forceCompleted,
            )
        }

    // ── 音频焦点与耳机断开（U-01~U-03）────────────────────

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mediaAudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
        .build()
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
        ) {
            pause()
            Metric.emit("audio_focus", "change" to change, "action" to "pause")
        }
    }
    private val focusRequest: AudioFocusRequest? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(mediaAudioAttributes)
                .setOnAudioFocusChangeListener(focusListener)
                // 告诉系统由上面的 listener 处理 duck；系统会回调 CAN_DUCK，应用统一暂停。
                .setWillPauseWhenDucked(true)
                .build()
        } else null

    private fun acquireAudioFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.requestAudioFocus(it) }
                ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }

    private var noisyRegistered = false
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pause()
                Metric.emit("audio_route", "event" to "becoming_noisy", "action" to "pause")
            }
        }
    }

    private fun registerNoisyReceiver() {
        if (noisyRegistered) return
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(noisyReceiver, filter)
        }
        noisyRegistered = true
    }

    private fun unregisterNoisyReceiver() {
        if (!noisyRegistered) return
        runCatching { context.unregisterReceiver(noisyReceiver) }
        noisyRegistered = false
    }

    private var audioDeviceCallbackRegistered = false
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            scope.launch {
                delay(300)
                engine?.let { refreshAudioDevice(it) }
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            scope.launch {
                delay(300)
                engine?.let { refreshAudioDevice(it) }
            }
        }
    }

    private fun registerAudioDeviceCallback() {
        if (audioDeviceCallbackRegistered) return
        audioManager.registerAudioDeviceCallback(
            audioDeviceCallback,
            Handler(Looper.getMainLooper()),
        )
        audioDeviceCallbackRegistered = true
    }

    private fun unregisterAudioDeviceCallback() {
        if (!audioDeviceCallbackRegistered) return
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        audioDeviceCallbackRegistered = false
    }

    private fun refreshAudioDevice(targetEngine: InstrumentedPlayerEngine) {
        scope.launch {
            val device = currentMediaOutputDevice()
            val type = audioDeviceTypeName(device?.type)
            val name = device?.productName?.toString()?.ifBlank { null } ?: type
            val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                device?.address.orEmpty()
            } else ""
            val key = "${device?.type ?: 0}:${address.ifBlank { name }}"
            val profile = withContext(Dispatchers.IO) { audioProfileDao.get(key) }
            // DAO 查询期间 stop/open 可能已经释放旧 VLC；必须重新确认对象仍是活动 engine。
            if (engine !== targetEngine || _session.value == null) return@launch
            val delay = profile?.audioDelayMs?.coerceIn(-500L, 500L) ?: 0L
            val supported = _session.value?.kernel == PlayerKernel.VLC
            _audioCalibration.value = AudioCalibration(key, name, type, delay, supported)
            if (supported) targetEngine.setAudioDelayMs(delay)
            Metric.emit(
                "audio_device",
                "name" to name,
                "type" to type,
                "delay_ms" to delay,
                "supported" to supported,
            )
        }
    }

    private fun currentMediaOutputDevice(): AudioDeviceInfo? {
        val routed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                audioManager.getAudioDevicesForAttributes(mediaAudioAttributes)
                    .filter { it.isSink && it.type != AudioDeviceInfo.TYPE_TELEPHONY }
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val connected = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isSink && it.type != AudioDeviceInfo.TYPE_TELEPHONY }
        return chooseAudioOutput(routed, connected, AudioDeviceInfo::getType)
    }

    private fun audioDeviceTypeName(type: Int?): String = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙耳机"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙通话设备"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE -> "有线 / USB 耳机"
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "内置扬声器"
        else -> "系统音频输出"
    }

    /** 必须在主线程调用（内部会碰 ExoPlayer 的解码计数器） */
    suspend fun emitIoMetrics() = withContext(Dispatchers.Main.immediate) {
        val stats = proxy.activeStats ?: return@withContext
        val e = engine
        e?.refreshCounters()
        val info = e?.decoderInfo?.value ?: DecoderInfo()

        // 代理预读窗口与 Media3 已解析缓冲前后串联、内容不重叠，两者之和才是断网后
        // 仍可连续播放的真实水位。只看 Media3 会把仍在代理中的可播数据错误漏掉。
        val playerBufSec = if (e != null) {
            ((e.bufferedPositionMs - e.positionMs).coerceAtLeast(0L)) / 1000.0
        } else 0.0
        val proxyBufSec = stats.bufferedSeconds
        val effectiveBufSec = playerBufSec + proxyBufSec

        // Java 堆用量必须持续监控：预读窗口 + 播放器缓冲 + smbj 在途缓冲三者叠加，
        // 撑爆 256MB 堆时是 smbj 收包线程抛 OOM 直接崩进程，事后很难还原现场
        val rt = Runtime.getRuntime()
        val heapUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0)
        val heapMaxMb = rt.maxMemory() / (1024.0 * 1024.0)
        val throughput = stats.sampleThroughputMbps()
        val currentDisplay = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        val outputDevice = runCatching {
            currentMediaOutputDevice()?.productName?.toString()
        }.getOrNull() ?: "系统默认"
        _debugSnapshot.value = DebugSnapshot(
            throughputMbps = round2(throughput),
            bufferSeconds = round1(effectiveBufSec),
            proxyMb = round2(stats.bufferedBytes / (1024.0 * 1024.0)),
            inflight = stats.inflight,
            hitRate = round3(stats.hitRate),
            reconnects = stats.reconnects,
            dialect = connections.negotiatedDialect,
            refreshRate = currentDisplay?.refreshRate ?: 0f,
            heapUsedMb = round1(heapUsedMb),
            heapMaxMb = round1(heapMaxMb),
            outputDevice = outputDevice,
        )

        Metric.emit(
            "io",
            "mbps" to round2(throughput),
            "buf_sec" to round1(effectiveBufSec),
            "player_buf_sec" to round1(playerBufSec),
            "proxy_buf_sec" to round1(proxyBufSec),
            "proxy_mb" to round2(stats.bufferedBytes / (1024.0 * 1024.0)),
            "heap_mb" to round1(heapUsedMb),
            "heap_max" to round1(heapMaxMb),
            "inflight" to stats.inflight,
            "hit" to round3(stats.hitRate),
            "reconnect" to stats.reconnects,
            "resets" to stats.resets,
        )
        Metric.emit(
            "frames",
            "rendered" to info.renderedFrames,
            "dropped" to info.droppedFrames,
            "max_consec" to info.maxConsecutiveDropped,
            "pos_ms" to (e?.positionMs ?: 0L),
        )
    }

    fun emitDisplay() {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        Metric.emit(
            "display",
            "hz" to round1(display.refreshRate.toDouble()),
            "surface" to "SurfaceView",
        )
    }

    private fun round1(v: Double) = Math.round(v * 10.0) / 10.0
    private fun round2(v: Double) = Math.round(v * 100.0) / 100.0
    private fun round3(v: Double) = Math.round(v * 1000.0) / 1000.0

    private companion object {
        /** 位置缓存刷新周期，UI 进度显示的精度由它决定 */
        const val TICK_MS = 250L
        const val SUBTITLE_CUE_TICK_MS = 100L
        const val SPRITE_GENERATION_DELAY_MS = 3_000L
        const val METRIC_INTERVAL_MS = 2_000L
        const val WATCH_WRITE_INTERVAL_MS = 3_000L
        const val COLLECTION_PREFIX = "收藏夹 · "
        val EXTERNAL_AUDIO_EXTENSIONS = setOf(
            "aac", "ac3", "eac3", "dts", "flac", "m4a", "mp3", "ogg", "opus", "wav",
        )
    }
}
