package com.lanplay.player.player

import android.view.SurfaceView
import androidx.media3.common.text.Cue
import com.lanplay.player.data.PreparedSubtitle
import kotlinx.coroutines.flow.StateFlow

enum class PlaybackState { IDLE, BUFFERING, READY, PLAYING, PAUSED, ENDED, ERROR }

/**
 * 解码信息（播放器规格 §8.1 调试面板 / §3.6 验收标准）。
 * [isHardware] 为 false 意味着掉软解，是第 1 阶段的硬门禁。
 */
data class DecoderInfo(
    val videoDecoder: String = "-",
    val audioDecoder: String = "-",
    val isHardware: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Float = 0f,
    val renderedFrames: Long = 0,
    val droppedFrames: Int = 0,
    val maxConsecutiveDropped: Int = 0,
) {
    companion object {
        /**
         * 规格 §3.1 第 1 条：高通硬解器名形如 c2.qti.* / OMX.qcom.*；
         * 出现 c2.android.* 或 OMX.google.* 即为软解，必须排查。
         */
        fun isHardwareDecoder(name: String): Boolean {
            val n = name.lowercase()
            if (n.startsWith("c2.android.") || n.startsWith("omx.google.")) return false
            return n.isNotEmpty() && n != "-"
        }
    }
}

data class AudioTrackInfo(
    val index: Int,
    val name: String,
    val language: String? = null,
    val channels: Int = 0,
    val selected: Boolean = false,
)

/**
 * 双内核统一抽象（播放器规格 §2.2）。
 *
 * 第 1 阶段只有 Media3 实现；libVLC 兜底内核是第 4 阶段。接口按当前真正用到的能力定义，
 * 字幕 / 音轨 / 画面变换 / 音频延迟等随对应阶段再扩展，避免为尚未实现的功能先造空壳。
 */
interface IPlayerEngine {
    val state: StateFlow<PlaybackState>
    val decoderInfo: StateFlow<DecoderInfo>
    val audioTracks: StateFlow<List<AudioTrackInfo>>
    val cues: StateFlow<List<Cue>>
    val positionMs: Long
    val durationMs: Long
    val bufferedPositionMs: Long

    /**
     * VLC 需要第二块带透明通道的 Surface 才能让 libass / VobSub 原生渲染字幕；
     * Media3 仍由上层 SubtitleView 绘制，直接忽略 [subtitleSurfaceView]。
     */
    fun attach(surfaceView: SurfaceView, subtitleSurfaceView: SurfaceView? = null)
    fun detach()

    /** @param frameRateHint 已知帧率时传入，可省掉一次容器探测（规格 §3.3） */
    fun open(
        url: String,
        startPositionMs: Long = 0L,
        frameRateHint: Float = 0f,
        subtitle: PreparedSubtitle? = null,
        externalAudioUrl: String? = null,
    )
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)
    fun selectAudioTrack(index: Int)
    fun setAudioDelayMs(delayMs: Long)
    fun setSubtitleDelayMs(delayMs: Long)

    /** 0.0 ~ 1.0（第 4 阶段的音量增强 U-11 会放宽到 2.0） */
    fun setVolume(volume: Float)
    fun release()
}

interface InstrumentedPlayerEngine : IPlayerEngine {
    var onFirstFrame: ((elapsedMs: Long) -> Unit)?
    var onSeekCompleted: ((elapsedMs: Long) -> Unit)?
    var onError: ((code: String, message: String) -> Unit)?
    fun refreshCounters()
}
