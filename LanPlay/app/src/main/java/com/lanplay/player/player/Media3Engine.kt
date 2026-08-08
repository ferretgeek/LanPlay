package com.lanplay.player.player

import android.content.Context
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.lanplay.player.core.log.Metric
import com.lanplay.player.data.PreparedSubtitle
import com.lanplay.player.data.prefs.DecoderMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Media3 (ExoPlayer) 内核。规格 §3.1「必须做对的六件事」在此落地：
 *
 *  1. [MediaCodecSelector.DEFAULT] 硬解优先，**不手动指定解码器名**
 *  2. SurfaceView 而非 TextureView —— 解码器输出零拷贝直达显示层
 *  3. 异步 MediaCodec 队列（Media3 1.2+ 默认启用，此处不关闭）
 *  4. [DefaultRenderersFactory.setEnableDecoderFallback] 打开，硬解初始化失败时降级而非直接报错
 *  5. 播放界面零 GPU 特效（UI 层保证）
 *  6. ColorInfo 由容器解析后交给解码器（Media3 默认行为）
 *
 * 所有方法必须在主线程调用。
 */
@OptIn(UnstableApi::class)
class Media3Engine(
    private val context: Context,
    private val decoderMode: DecoderMode = DecoderMode.HW,
) : InstrumentedPlayerEngine {

    private var player: ExoPlayer? = null
    private var surfaceView: SurfaceView? = null
    private val frameRateController = VideoFrameRateController()

    private val _state = MutableStateFlow(PlaybackState.IDLE)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _decoderInfo = MutableStateFlow(DecoderInfo())
    override val decoderInfo: StateFlow<DecoderInfo> = _decoderInfo.asStateFlow()
    private val _audioTracks = MutableStateFlow<List<AudioTrackInfo>>(emptyList())
    override val audioTracks: StateFlow<List<AudioTrackInfo>> = _audioTracks.asStateFlow()
    private val audioTrackMappings = mutableListOf<Pair<TrackGroup, Int>>()
    private val _cues = MutableStateFlow<List<Cue>>(emptyList())
    override val cues: StateFlow<List<Cue>> = _cues.asStateFlow()

    override val positionMs: Long get() = player?.currentPosition ?: 0L
    override val durationMs: Long
        get() = player?.duration?.takeIf { it != C.TIME_UNSET } ?: 0L
    override val bufferedPositionMs: Long get() = player?.bufferedPosition ?: 0L

    /** 供 PlaybackController 计算首帧耗时与 seek 恢复耗时 */
    @Volatile
    var openStartedAtNs: Long = 0L
        private set

    @Volatile
    var seekStartedAtNs: Long = 0L
        private set

    override var onFirstFrame: ((elapsedMs: Long) -> Unit)? = null
    override var onSeekCompleted: ((elapsedMs: Long) -> Unit)? = null
    override var onError: ((code: String, message: String) -> Unit)? = null

    private fun ensurePlayer(): ExoPlayer = player ?: buildPlayer().also { player = it }

    /**
     * 规格 §3.2 的解码器三档。
     * 硬解档不筛选，交给系统按优先级挑——高通硬解器本来就排在前面，
     * 手动指定解码器名反而会在换机型时踩坑（§3.1 第 1 条）。
     */
    private fun selectorFor(mode: DecoderMode): MediaCodecSelector = when (mode) {
        DecoderMode.HW -> MediaCodecSelector.DEFAULT
        DecoderMode.HW_PLUS -> MediaCodecSelector { mime, secure, tunneling ->
            MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                .filter { !it.softwareOnly }
        }
        DecoderMode.SW -> MediaCodecSelector { mime, secure, tunneling ->
            MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                .filter { it.softwareOnly }
        }
    }

    private fun buildPlayer(): ExoPlayer {
        val renderersFactory = DefaultRenderersFactory(context)
            // 规格 §3.2：硬解档失败自动降级；「硬解+」档不回退，直接报错以便定位发烫原因
            .setEnableDecoderFallback(decoderMode == DecoderMode.HW)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setMediaCodecSelector(selectorFor(decoderMode))

        val httpFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(false)

        // 规格 §1.4 的 LoadControl 参数，外加一道字节上限。
        //
        // 只按时间缓冲会在高码率素材上失控：4K 10.74 Mbps × 120 秒 ≈ 161 MB，
        // 叠加代理的 48 MB 预读窗口与 smbj 在途缓冲后撑爆 256 MB 的 Java 堆，
        // 实测表现为 smbj 收包线程抛 OutOfMemoryError 并让进程整个崩掉。
        // prioritizeTimeOverSizeThresholds 必须为 false，否则字节上限根本不参与判定。
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .setTargetBufferBytes(TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        // 本地 HTTP 只是 SMB 代理；加载失败应覆盖 SmbFileHandle 的 60 秒恢复窗，
        // 不能沿用 Media3 对公网源偏保守的少量重试后直接结束播放。
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(LOAD_RETRY_COUNT))

        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    false,
                )
                // WAKE_MODE_NETWORK 同时持有 PARTIAL WakeLock 与 FULL_HIGH_PERF WifiLock，
                // 这是息屏不断流的关键（规格 §1.4）
                setWakeMode(C.WAKE_MODE_NETWORK)
                // 视频拖动优先落到离目标最近的关键帧，避免从前一关键帧逐帧追赶。
                // 手势计算的目标时间不变；实际画面只会在一个 GOP 内取最近可立即解码的位置。
                setSeekParameters(SeekParameters.CLOSEST_SYNC)
                addListener(playerListener)
                addAnalyticsListener(analyticsListener)
                surfaceView?.let { setVideoSurfaceView(it) }
                pendingVolume?.let { volume = it }
            }
    }

    override fun attach(surfaceView: SurfaceView, subtitleSurfaceView: SurfaceView?) {
        this.surfaceView = surfaceView
        frameRateController.attach(surfaceView)
        player?.setVideoSurfaceView(surfaceView)
    }

    override fun detach() {
        player?.clearVideoSurface()
        frameRateController.detach()
        surfaceView = null
    }

    override fun open(
        url: String,
        startPositionMs: Long,
        frameRateHint: Float,
        subtitle: PreparedSubtitle?,
        externalAudioUrl: String?,
    ) {
        openStartedAtNs = System.nanoTime()
        val p = ensurePlayer()
        Metric.emit(
            "player_state",
            "state" to "ENGINE_CREATED",
            "since_open_ms" to ((System.nanoTime() - openStartedAtNs) / 1_000_000),
        )
        val item = MediaItem.Builder().setUri(url).apply {
            // SRT/VTT 由 PlaybackController 按基础时间轴实时选取，字幕偏移无需重开视频。
            // 时间轴为空时保留 Media3 原生解析，作为异常文本的兼容兜底。
            if (subtitle != null && subtitle.timeline.isEmpty()) {
                setSubtitleConfigurations(
                    listOf(
                        MediaItem.SubtitleConfiguration.Builder(subtitle.localUri)
                            .setMimeType(subtitle.mimeType)
                            .setLanguage("zh")
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build()
                    )
                )
            }
        }.build()
        p.setMediaItem(item)
        if (startPositionMs > 0) p.seekTo(startPositionMs)
        p.prepare()
        p.playWhenReady = true
        Metric.emit("player_state", "state" to "PREPARE_CALLED", "since_open_ms" to ((System.nanoTime() - openStartedAtNs) / 1_000_000))
    }

    override fun play() {
        player?.playWhenReady = true
    }

    override fun pause() {
        player?.playWhenReady = false
    }

    override fun seekTo(positionMs: Long) {
        seekStartedAtNs = System.nanoTime()
        player?.seekTo(positionMs)
    }

    override fun setSpeed(speed: Float) {
        player?.setPlaybackSpeed(speed)
    }

    override fun selectAudioTrack(index: Int) {
        val mapping = audioTrackMappings.getOrNull(index) ?: return
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(mapping.first, mapping.second))
            .build()
    }

    /** Media3 无公开音频时延 API；非零补偿由控制器切换至 VLC 后应用。 */
    override fun setAudioDelayMs(delayMs: Long) = Unit
    override fun setSubtitleDelayMs(delayMs: Long) = Unit

    override fun setVolume(volume: Float) {
        pendingVolume = volume
        player?.volume = volume
    }

    /** open() 时机早于外部调用 setVolume，这里记住待应用值，播放器建好立即生效 */
    private var pendingVolume: Float? = null

    override fun release() {
        // stop()/切换内核不一定先触发 Compose 的 onDispose；在这里也清除帧率请求，
        // 保证离开视频后系统能立即恢复界面的 120Hz 策略。
        runCatching { detach() }
        player?.let {
            it.removeListener(playerListener)
            it.removeAnalyticsListener(analyticsListener)
            it.release()
        }
        player = null
        _state.value = PlaybackState.IDLE
        _decoderInfo.value = DecoderInfo()
        _cues.value = emptyList()
    }

    // ── 监听 ──────────────────────────────────────────────────

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = syncState()
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = syncState()

        override fun onTracksChanged(tracks: Tracks) {
            audioTrackMappings.clear()
            val result = mutableListOf<AudioTrackInfo>()
            tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEach { group ->
                for (trackIndex in 0 until group.length) {
                    val format = group.getTrackFormat(trackIndex)
                    audioTrackMappings += group.mediaTrackGroup to trackIndex
                    result += AudioTrackInfo(
                        index = result.size,
                        name = format.label ?: format.language ?: "音轨 ${result.size + 1}",
                        language = format.language,
                        channels = format.channelCount.takeIf { it > 0 } ?: 0,
                        selected = group.isTrackSelected(trackIndex),
                    )
                }
            }
            _audioTracks.value = result
        }

        override fun onCues(cueGroup: CueGroup) {
            _cues.value = cueGroup.cues
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            _decoderInfo.value = _decoderInfo.value.copy(
                width = videoSize.width,
                height = videoSize.height,
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.value = PlaybackState.ERROR
            Metric.error("PLAYBACK_ERROR", error.errorCodeName, "detail" to (error.message ?: ""))
            onError?.invoke(error.errorCodeName, error.message ?: "播放失败")
        }
    }

    private val analyticsListener = object : AnalyticsListener {

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            val hw = DecoderInfo.isHardwareDecoder(decoderName)
            _decoderInfo.value = _decoderInfo.value.copy(
                videoDecoder = decoderName,
                isHardware = hw,
            )
            Metric.emit(
                "decoder",
                "video" to decoderName,
                "hw" to hw,
                "audio" to _decoderInfo.value.audioDecoder,
                "init_ms" to initializationDurationMs,
                "since_open_ms" to ((System.nanoTime() - openStartedAtNs) / 1_000_000),
            )
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            _decoderInfo.value = _decoderInfo.value.copy(audioDecoder = decoderName)
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            if (format.frameRate > 0) frameRateController.updateSourceFps(format.frameRate)
            _decoderInfo.value = _decoderInfo.value.copy(
                width = if (format.width > 0) format.width else _decoderInfo.value.width,
                height = if (format.height > 0) format.height else _decoderInfo.value.height,
                frameRate = if (format.frameRate > 0) format.frameRate else _decoderInfo.value.frameRate,
            )
        }

        override fun onRenderedFirstFrame(
            eventTime: AnalyticsListener.EventTime,
            output: Any,
            renderTimeMs: Long,
        ) {
            val elapsed = (System.nanoTime() - openStartedAtNs) / 1_000_000
            onFirstFrame?.invoke(elapsed)
        }

        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long,
        ) {
            val counters = player?.videoDecoderCounters
            _decoderInfo.value = _decoderInfo.value.copy(
                droppedFrames = counters?.droppedBufferCount ?: droppedFrames,
                maxConsecutiveDropped = counters?.maxConsecutiveDroppedBufferCount ?: 0,
                renderedFrames = (counters?.renderedOutputBufferCount ?: 0).toLong(),
            )
        }

        override fun onPositionDiscontinuity(
            eventTime: AnalyticsListener.EventTime,
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason != Player.DISCONTINUITY_REASON_SEEK || seekStartedAtNs <= 0L) return
            // 命中预读窗口时播放器根本不会进入 BUFFERING，若只等「缓冲结束」这一跃迁，
            // 窗口内 seek 的耗时就永远采不到。这里直接结算瞬时完成的情形。
            if (player?.playbackState == Player.STATE_READY) {
                val elapsed = (System.nanoTime() - seekStartedAtNs) / 1_000_000
                seekStartedAtNs = 0
                onSeekCompleted?.invoke(elapsed)
            }
        }
    }

    private fun syncState() {
        val p = player ?: return
        val previous = _state.value
        val next = when (p.playbackState) {
            Player.STATE_IDLE -> PlaybackState.IDLE
            Player.STATE_BUFFERING -> PlaybackState.BUFFERING
            Player.STATE_ENDED -> PlaybackState.ENDED
            Player.STATE_READY -> if (p.playWhenReady) PlaybackState.PLAYING else PlaybackState.PAUSED
            else -> PlaybackState.IDLE
        }
        // 首帧耗时的拆解：从 open() 起算，看时间究竟花在容器解析还是解码器起步上
        if (next != previous && openStartedAtNs > 0) {
            Metric.emit(
                "player_state",
                "state" to next.name,
                "since_open_ms" to ((System.nanoTime() - openStartedAtNs) / 1_000_000),
            )
        }
        _state.value = when (p.playbackState) {
            Player.STATE_IDLE -> PlaybackState.IDLE
            Player.STATE_BUFFERING -> PlaybackState.BUFFERING
            Player.STATE_ENDED -> PlaybackState.ENDED
            Player.STATE_READY -> if (p.playWhenReady) PlaybackState.PLAYING else PlaybackState.PAUSED
            else -> PlaybackState.IDLE
        }
        // 缓冲结束回到可播状态时结算 seek 恢复耗时
        if (previous == PlaybackState.BUFFERING &&
            (_state.value == PlaybackState.PLAYING || _state.value == PlaybackState.READY ||
                _state.value == PlaybackState.PAUSED) &&
            seekStartedAtNs > 0
        ) {
            val elapsed = (System.nanoTime() - seekStartedAtNs) / 1_000_000
            seekStartedAtNs = 0
            onSeekCompleted?.invoke(elapsed)
        }
    }

    /** 拿最新的解码计数器快照，供指标周期上报 */
    override fun refreshCounters() {
        val counters = player?.videoDecoderCounters ?: return
        counters.ensureUpdated()
        _decoderInfo.value = _decoderInfo.value.copy(
            renderedFrames = counters.renderedOutputBufferCount.toLong(),
            droppedFrames = counters.droppedBufferCount,
            maxConsecutiveDropped = counters.maxConsecutiveDroppedBufferCount,
        )
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 65_000
        const val LOAD_RETRY_COUNT = 10
        const val MIN_BUFFER_MS = 30_000
        const val MAX_BUFFER_MS = 120_000
        const val BUFFER_FOR_PLAYBACK_MS = 2_500
        const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000

        /**
         * 真机长播曲线中 52 MB 会在一次补块前短暂降到 29 秒；提高 4 MB
         * 可多保留约 3 秒内容，让正常波动也不穿过 30 秒门禁。代理仍固定 48 MB，
         * 这里只增加 Media3 已解析的压缩码流，内存增量可控。
         */
        const val TARGET_BUFFER_BYTES = 56 * 1024 * 1024
    }
}
