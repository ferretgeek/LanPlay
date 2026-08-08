package com.lanplay.player.player

import android.content.Context
import android.net.Uri
import android.view.SurfaceView
import androidx.media3.common.text.Cue
import com.lanplay.player.data.PreparedSubtitle
import com.lanplay.player.data.prefs.DecoderMode
import com.lanplay.player.data.prefs.AudioEnhancementSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.interfaces.IVLCVout

/** 兼容性兜底内核：Media3 无法处理的容器、音轨或复杂 ASS 可一键切到 VLC。 */
class VlcEngine(
    context: Context,
    private val decoderMode: DecoderMode,
    subtitleSizePercent: Int,
    private val audioEnhancements: AudioEnhancementSettings = AudioEnhancementSettings(),
) : InstrumentedPlayerEngine {
    /**
     * VLC 的 freetype-rel-fontsize 是“画面高度除数”，数值越大字越小。
     * 以 100%=20 为基准换算，75% 得到 27，和 Media3 的 16.5sp 默认观感接近。
     */
    private val subtitleHeightDivisor =
        (2_000f / subtitleSizePercent.coerceIn(50, 250)).toInt().coerceIn(8, 40)
    private val libVlc = LibVLC(
        context,
        arrayListOf(
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--network-caching=1500",
            "--freetype-rel-fontsize=$subtitleHeightDivisor",
            "--sub-text-scale=${subtitleSizePercent.coerceIn(50, 250)}",
        ).apply {
            if (audioEnhancements.loudnessNormalization) {
                add("--audio-filter=compressor")
                add("--compressor-threshold=-11")
                add("--compressor-ratio=8")
                add("--compressor-attack=25")
                add("--compressor-release=100")
                add("--compressor-makeup-gain=6")
            }
        },
    )
    private val player = MediaPlayer(libVlc)
    private var surface: SurfaceView? = null
    private var subtitleSurface: SurfaceView? = null
    private val frameRateController = VideoFrameRateController()
    @Volatile private var released = false
    private var openStartedNs = 0L
    private var seekStartedNs = 0L
    private var firstFrameSent = false
    private var pendingStartPositionMs = 0L
    private data class PendingOpen(
        val url: String,
        val startPositionMs: Long,
        val subtitle: PreparedSubtitle?,
        val externalAudioUrl: String?,
    )
    private var pendingOpen: PendingOpen? = null
    private val _state = MutableStateFlow(PlaybackState.IDLE)
    private val _decoder = MutableStateFlow(
        DecoderInfo(
            videoDecoder = if (decoderMode == DecoderMode.SW) "libvlc software" else "libvlc hardware",
            isHardware = decoderMode != DecoderMode.SW,
        )
    )
    private val _audioTracks = MutableStateFlow<List<AudioTrackInfo>>(emptyList())

    override val state: StateFlow<PlaybackState> = _state.asStateFlow()
    override val decoderInfo: StateFlow<DecoderInfo> = _decoder.asStateFlow()
    override val audioTracks: StateFlow<List<AudioTrackInfo>> = _audioTracks.asStateFlow()
    private val _cues = MutableStateFlow<List<Cue>>(emptyList())
    override val cues: StateFlow<List<Cue>> = _cues.asStateFlow()
    override val positionMs: Long get() = if (released) 0L else player.time.coerceAtLeast(0L)
    override val durationMs: Long get() = if (released) 0L else player.length.coerceAtLeast(0L)
    override val bufferedPositionMs: Long get() = positionMs
    override var onFirstFrame: ((Long) -> Unit)? = null
    override var onSeekCompleted: ((Long) -> Unit)? = null
    override var onError: ((String, String) -> Unit)? = null

    init {
        applyEqualizer()
        player.setEventListener { event ->
            if (released) return@setEventListener
            when (event.type) {
                MediaPlayer.Event.Opening -> _state.value = PlaybackState.BUFFERING
                MediaPlayer.Event.Buffering -> {
                    if (event.buffering < 100f && !player.isPlaying) {
                        _state.value = PlaybackState.BUFFERING
                    }
                }
                MediaPlayer.Event.Playing -> {
                    if (pendingStartPositionMs > 0L) {
                        val target = pendingStartPositionMs
                        pendingStartPositionMs = 0L
                        if (kotlin.math.abs(player.time - target) > 500L) {
                            player.time = target
                        }
                    }
                    _state.value = PlaybackState.PLAYING
                    _audioTracks.value = player.audioTracks.orEmpty().mapIndexed { index, track ->
                        AudioTrackInfo(
                            index = index,
                            name = track.name ?: "音轨 ${index + 1}",
                            selected = track.id == player.audioTrack,
                        )
                    }
                    if (seekStartedNs > 0) {
                        onSeekCompleted?.invoke((System.nanoTime() - seekStartedNs) / 1_000_000)
                        seekStartedNs = 0L
                    }
                }
                MediaPlayer.Event.Paused -> _state.value = PlaybackState.PAUSED
                MediaPlayer.Event.EndReached -> _state.value = PlaybackState.ENDED
                MediaPlayer.Event.Stopped -> _state.value = PlaybackState.IDLE
                MediaPlayer.Event.EncounteredError -> {
                    _state.value = PlaybackState.ERROR
                    onError?.invoke("VLC_ERROR", "VLC 内核无法播放这个文件")
                }
                MediaPlayer.Event.Vout -> if (!firstFrameSent && event.voutCount > 0) {
                    firstFrameSent = true
                    player.currentVideoTrack?.let { track ->
                        if (track.frameRateDen > 0) {
                            frameRateController.updateSourceFps(
                                track.frameRateNum.toFloat() / track.frameRateDen
                            )
                        }
                        _decoder.value = _decoder.value.copy(
                            width = track.width,
                            height = track.height,
                            frameRate = if (track.frameRateDen > 0) {
                                track.frameRateNum.toFloat() / track.frameRateDen
                            } else 0f,
                        )
                    }
                    onFirstFrame?.invoke((System.nanoTime() - openStartedNs) / 1_000_000)
                }
            }
        }
    }

    override fun attach(surfaceView: SurfaceView, subtitleSurfaceView: SurfaceView?) {
        if (released) return
        if (
            surface === surfaceView &&
            subtitleSurface === subtitleSurfaceView &&
            player.vlcVout.areViewsAttached()
        ) return
        if (player.vlcVout.areViewsAttached()) player.vlcVout.detachViews()
        surface = surfaceView
        subtitleSurface = subtitleSurfaceView
        frameRateController.attach(surfaceView)
        player.vlcVout.setVideoView(surfaceView)
        subtitleSurfaceView?.let { player.vlcVout.setSubtitlesView(it) }
        player.vlcVout.attachViews(
            IVLCVout.OnNewVideoLayoutListener { _, width, height, _, _, _, _ ->
                _decoder.value = _decoder.value.copy(width = width, height = height)
            }
        )
        pendingOpen?.let {
            pendingOpen = null
            startMedia(it)
        }
    }

    override fun detach() {
        if (released) return
        frameRateController.detach()
        if (player.vlcVout.areViewsAttached()) player.vlcVout.detachViews()
        surface = null
        subtitleSurface = null
    }

    override fun open(
        url: String,
        startPositionMs: Long,
        frameRateHint: Float,
        subtitle: PreparedSubtitle?,
        externalAudioUrl: String?,
    ) {
        if (released) return
        openStartedNs = System.nanoTime()
        firstFrameSent = false
        val request = PendingOpen(url, startPositionMs, subtitle, externalAudioUrl)
        if (surface == null || !player.vlcVout.areViewsAttached()) {
            pendingOpen = request
        } else {
            startMedia(request)
        }
    }

    private fun startMedia(request: PendingOpen) {
        if (released) return
        val media = Media(libVlc, Uri.parse(request.url)).apply {
            setHWDecoderEnabled(decoderMode != DecoderMode.SW, decoderMode == DecoderMode.HW_PLUS)
            addOption(":network-caching=1500")
            addOption(":freetype-rel-fontsize=$subtitleHeightDivisor")
            addOption(":sub-text-scale=${(2_000f / subtitleHeightDivisor).toInt().coerceIn(50, 250)}")
            if (request.subtitle != null) {
                addSlave(
                    IMedia.Slave(
                        IMedia.Slave.Type.Subtitle,
                        4,
                        request.subtitle.localUri.toString(),
                    )
                )
            }
            if (!request.externalAudioUrl.isNullOrBlank()) {
                addSlave(
                    IMedia.Slave(
                        IMedia.Slave.Type.Audio,
                        4,
                        request.externalAudioUrl,
                    )
                )
            }
        }
        player.media = media
        media.release()
        pendingStartPositionMs = request.startPositionMs.coerceAtLeast(0L)
        player.play()
    }

    override fun play() {
        if (!released) player.play()
    }
    override fun pause() {
        if (!released) player.pause()
    }
    override fun seekTo(positionMs: Long) {
        if (released) return
        seekStartedNs = System.nanoTime()
        player.time = positionMs.coerceIn(0L, durationMs.coerceAtLeast(positionMs))
    }
    override fun setSpeed(speed: Float) {
        if (!released) player.setRate(speed)
    }
    override fun selectAudioTrack(index: Int) {
        if (released) return
        player.audioTracks?.getOrNull(index)?.let { player.audioTrack = it.id }
    }
    override fun setAudioDelayMs(delayMs: Long) {
        if (!released) player.audioDelay = delayMs.coerceIn(-500L, 500L) * 1_000L
    }
    override fun setSubtitleDelayMs(delayMs: Long) {
        if (!released) player.spuDelay = delayMs.coerceIn(-60_000L, 60_000L) * 1_000L
    }
    override fun setVolume(volume: Float) {
        if (!released) player.volume = (volume.coerceIn(0f, 2f) * 100).toInt()
    }

    private fun applyEqualizer() {
        val bands = audioEnhancements.equalizerBands
        if (audioEnhancements.equalizerPreset == "flat" && bands.all { it == 0f }) {
            player.setEqualizer(null)
            return
        }
        val equalizer = MediaPlayer.Equalizer.create()
        val count = minOf(MediaPlayer.Equalizer.getBandCount(), bands.size)
        for (index in 0 until count) {
            equalizer.setAmp(index, bands[index].coerceIn(-12f, 12f))
        }
        equalizer.setPreAmp(-(bands.maxOrNull() ?: 0f).coerceAtLeast(0f))
        player.setEqualizer(equalizer)
    }
    override fun refreshCounters() = Unit
    override fun release() {
        if (released) return
        released = true
        pendingStartPositionMs = 0L
        pendingOpen = null
        runCatching {
            frameRateController.detach()
            if (player.vlcVout.areViewsAttached()) player.vlcVout.detachViews()
        }
        surface = null
        subtitleSurface = null
        player.release()
        libVlc.release()
        _state.value = PlaybackState.IDLE
    }
}
