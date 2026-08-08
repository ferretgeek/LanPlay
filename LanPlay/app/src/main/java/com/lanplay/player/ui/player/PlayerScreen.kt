package com.lanplay.player.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.ScreenLockRotation
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import com.lanplay.player.data.prefs.OrientationMode
import com.lanplay.player.data.prefs.SubtitleFont
import com.lanplay.player.data.prefs.GestureRegion
import com.lanplay.player.data.prefs.Handedness
import com.lanplay.player.player.PlaybackController
import com.lanplay.player.player.PlaybackSession
import com.lanplay.player.player.PlaybackState
import com.lanplay.player.player.DebugSnapshot
import com.lanplay.player.player.DecoderInfo
import com.lanplay.player.player.AudioCalibration
import com.lanplay.player.player.AudioTrackInfo
import com.lanplay.player.player.QueueMode
import com.lanplay.player.player.QueueItem
import com.lanplay.player.player.AbLoop
import com.lanplay.player.data.db.BookmarkEntity
import com.lanplay.player.data.db.TagEntity
import com.lanplay.player.data.SubtitleSearchHit
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign
import java.io.File

private enum class DragMode { NONE, SEEK, BRIGHTNESS, VOLUME, SUBTITLE }
private enum class TwoFingerMode { NONE, TRANSFORM, SUBTITLE }

internal fun requestedOrientation(mode: OrientationMode): Int = when (mode) {
    OrientationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
    OrientationMode.FORCE_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    OrientationMode.FORCE_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
}

private data class GestureOverlay(
    val icon: DragMode,
    val title: String,
    val detail: String,
    val progress: Float? = null,
    val warning: Boolean = false,
    val preview: Bitmap? = null,
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(
    controller: PlaybackController,
    session: PlaybackSession,
    isPip: Boolean = false,
    onExit: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val lastError by controller.lastError.collectAsStateWithLifecycle()
    val notice by controller.notice.collectAsStateWithLifecycle()
    val decoder by controller.decoderInfo.collectAsStateWithLifecycle()
    val debug by controller.debugSnapshot.collectAsStateWithLifecycle()
    val audioTracks by controller.audioTracks.collectAsStateWithLifecycle()
    val externalAudioFiles by controller.externalAudioFiles.collectAsStateWithLifecycle()
    val audioCalibration by controller.audioCalibration.collectAsStateWithLifecycle()
    val cues by controller.cues.collectAsStateWithLifecycle()
    val bookmarks by controller.bookmarks.collectAsStateWithLifecycle()
    val tags by controller.tags.collectAsStateWithLifecycle()
    val queueMode by controller.queueMode.collectAsStateWithLifecycle()
    val abLoop by controller.abLoop.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val subtitleStyle by viewModel.subtitleStyle.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context.findActivity()
    val haptic = LocalHapticFeedback.current
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsEpoch by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var brightness by remember { mutableFloatStateOf(context.systemBrightnessPercent()) }
    var volume by remember { mutableFloatStateOf(viewModel.currentVolumePercent()) }
    var overlay by remember { mutableStateOf<GestureOverlay?>(null) }
    var isDragging by remember { mutableStateOf(false) }
    var orientationLocked by remember { mutableStateOf(false) }
    val sessionKey = session.serverId to session.relativePath
    var gestureLocked by remember(sessionKey) { mutableStateOf(false) }
    var askingResume by remember(sessionKey) { mutableStateOf(session.askBeforeResume) }
    var showResumeNotice by remember(sessionKey) { mutableStateOf(false) }
    var showSubtitleDialog by remember { mutableStateOf(false) }
    var subtitleLoading by remember { mutableStateOf(false) }
    var subtitleOptions by remember { mutableStateOf<List<String>>(emptyList()) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showAspectDialog by remember { mutableStateOf(false) }
    var showRatingDialog by remember { mutableStateOf(false) }
    var showDebugPanel by remember { mutableStateOf(false) }
    var showAudioTracks by remember { mutableStateOf(false) }
    var showOrganize by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var capturedScreenshot by remember { mutableStateOf<File?>(null) }
    var queueItems by remember { mutableStateOf<List<QueueItem>>(emptyList()) }
    var queueLoading by remember { mutableStateOf(false) }
    var muted by remember(sessionKey) { mutableStateOf(false) }
    var volumeBeforeMute by remember(sessionKey) { mutableFloatStateOf(1f) }
    var lastAudioDevice by remember { mutableStateOf<String?>(null) }
    val blackFade = remember(sessionKey) { Animatable(1f) }
    val coroutineScope = rememberCoroutineScope()
    val currentPlaybackSpeed by rememberUpdatedState(session.playbackSpeed)
    var exitInProgress by remember { mutableStateOf(false) }
    var twoFingerActive by remember(sessionKey) { mutableStateOf(false) }
    var suppressSingleGesture by remember(sessionKey) { mutableStateOf(false) }
    var volumeArmedUntilMs by remember(sessionKey) { mutableLongStateOf(0L) }
    var brightnessArmedUntilMs by remember(sessionKey) { mutableLongStateOf(0L) }
    val requestExit: () -> Unit = {
        if (!exitInProgress) {
            exitInProgress = true
            coroutineScope.launch {
                if (settings.fadePlayback) {
                    blackFade.animateTo(1f, tween(300))
                }
                onExit()
            }
        }
    }

    BackHandler {
        if (gestureLocked) {
            Toast.makeText(context, "手势已锁定，请长按画面解锁", Toast.LENGTH_SHORT).show()
        } else {
            requestExit()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            positionMs = controller.positionMs
            delay(250)
        }
    }
    LaunchedEffect(notice) {
        notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            controller.clearNotice()
        }
    }
    LaunchedEffect(sessionKey, settings.fadePlayback, askingResume) {
        if (askingResume) {
            blackFade.snapTo(1f)
        } else if (settings.fadePlayback) {
            blackFade.snapTo(1f)
            blackFade.animateTo(0f, tween(300))
        } else {
            blackFade.snapTo(0f)
        }
    }
    LaunchedEffect(state, sessionKey, settings.autoPlayNext) {
        if (state == PlaybackState.ENDED && settings.autoPlayNext) {
            delay(30_000)
            if (controller.state.value == PlaybackState.ENDED &&
                controller.session.value?.let {
                    it.serverId == session.serverId && it.relativePath == session.relativePath
                } == true
            ) {
                controller.playAdjacent(1)
            }
        }
    }
    LaunchedEffect(controlsVisible, controlsEpoch) {
        if (controlsVisible) {
            delay(4_000)
            controlsVisible = false
        }
    }
    LaunchedEffect(sessionKey, session.resumedFromMs) {
        if (session.askBeforeResume) {
            controller.pause()
        } else if (session.resumedFromMs > 0) {
            showResumeNotice = true
            Toast.makeText(
                context,
                "已从 ${formatTime(session.resumedFromMs)} 继续",
                Toast.LENGTH_SHORT,
            ).show()
            delay(6_000)
            showResumeNotice = false
        }
    }
    LaunchedEffect(audioCalibration.deviceKey) {
        val previous = lastAudioDevice
        if (previous != null && previous != audioCalibration.deviceKey) {
            Toast.makeText(
                context,
                "音频已切换到 ${audioCalibration.displayName}，已套用设备校准",
                Toast.LENGTH_SHORT,
            ).show()
        }
        lastAudioDevice = audioCalibration.deviceKey
    }
    DisposableEffect(activity, settings.orientationMode) {
        if (activity != null) {
            activity.requestedOrientation = requestedOrientation(settings.orientationMode)
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    DisposableEffect(activity, sessionKey) {
        // 每次进入默认跟随手机系统/自动亮度；只有本次播放手动纵滑后才临时覆盖。
        activity?.followSystemBrightness()
        brightness = context.systemBrightnessPercent()
        onDispose {
            activity?.followSystemBrightness()
        }
    }
    LaunchedEffect(overlay, isDragging) {
        if (overlay != null && !isDragging) {
            delay(600)
            overlay = null
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(
                sessionKey,
                gestureLocked,
                settings.doubleTapSeconds,
                settings.doubleTapCenterPause,
                settings.longPressSpeed,
                settings.hapticEnabled,
            ) {
                detectTapGestures(
                    onPress = {
                        val pressScope = this
                        if (gestureLocked) {
                            coroutineScope {
                                var unlocked = false
                                val unlockJob = launch {
                                    delay(800)
                                    unlocked = true
                                    gestureLocked = false
                                    controlsVisible = true
                                    controlsEpoch++
                                    if (settings.hapticEnabled) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                    Toast.makeText(context, "手势已解锁", Toast.LENGTH_SHORT).show()
                                }
                                pressScope.tryAwaitRelease()
                                unlockJob.cancel()
                                if (!unlocked) controlsVisible = false
                            }
                        } else {
                            val originalSpeed = currentPlaybackSpeed
                            coroutineScope {
                                var temporarySpeed = false
                                val speedJob = launch {
                                    delay(450)
                                    temporarySpeed = true
                                    controller.setTransientSpeed(settings.longPressSpeed)
                                    overlay = GestureOverlay(
                                        DragMode.SEEK,
                                        "临时 ${settings.longPressSpeed}×",
                                        "松手恢复 ${originalSpeed}×",
                                    )
                                }
                                try {
                                    pressScope.tryAwaitRelease()
                                } finally {
                                    speedJob.cancel()
                                    if (temporarySpeed) {
                                        controller.setTransientSpeed(originalSpeed)
                                        overlay = null
                                    }
                                }
                            }
                        }
                    },
                    onTap = {
                        if (gestureLocked) {
                            Toast.makeText(context, "长按画面解锁", Toast.LENGTH_SHORT).show()
                        } else {
                            controlsVisible = !controlsVisible
                            controlsEpoch++
                        }
                    },
                    onDoubleTap = { point ->
                        if (gestureLocked) return@detectTapGestures
                        when {
                            point.x < size.width * 0.35f ->
                                controller.seekTo(
                                    (controller.positionMs -
                                        settings.doubleTapSeconds * 1_000L).coerceAtLeast(0L)
                                )
                            point.x > size.width * 0.65f ->
                                controller.seekTo(
                                    (controller.positionMs +
                                        settings.doubleTapSeconds * 1_000L)
                                        .coerceAtMost(controller.durationMs)
                                )
                            settings.doubleTapCenterPause &&
                                state == PlaybackState.PLAYING -> controller.pause()
                            settings.doubleTapCenterPause -> controller.play()
                            point.x < size.width / 2f -> controller.seekTo(
                                (controller.positionMs -
                                    settings.doubleTapSeconds * 1_000L).coerceAtLeast(0L)
                            )
                            else -> controller.seekTo(
                                (controller.positionMs +
                                    settings.doubleTapSeconds * 1_000L)
                                    .coerceAtMost(controller.durationMs)
                            )
                        }
                        controlsEpoch++
                    },
                )
            }
            .pointerInput(
                sessionKey,
                gestureLocked,
                settings.seekSensitivitySeconds,
                settings.gestureRegion,
                settings.verticalAdjustEnabled,
                settings.volumeSensitivityPercent,
                settings.brightnessSensitivityPercent,
                settings.volumeSoftLimitPercent,
                settings.brightnessSoftLimitPercent,
                settings.seekPreviewEnabled,
            ) {
                var start = Offset.Zero
                var total = Offset.Zero
                var mode = DragMode.NONE
                var startPosition = 0L
                var startBrightness = 0.5f
                var startVolume = 0.5f
                var targetPosition = 0L
                var gestureStartAllowed = true
                var volumeClamped = false
                var brightnessClamped = false
                var volumeUnlocked = false
                var brightnessUnlocked = false
                var limitHapticSent = false
                var seekPreviewJob: Job? = null

                detectDragGestures(
                    onDragStart = {
                        suppressSingleGesture = false
                        start = it
                        total = Offset.Zero
                        mode = DragMode.NONE
                        startPosition = controller.positionMs
                        targetPosition = startPosition
                        startBrightness = activity?.windowBrightnessPercent()
                            ?: context.systemBrightnessPercent()
                        brightness = startBrightness
                        startVolume = viewModel.currentVolumePercent()
                        isDragging = true
                        controlsVisible = false
                        val edge = 28.dp.toPx()
                        val yAllowed = when (settings.gestureRegion) {
                            GestureRegion.FULL -> true
                            GestureRegion.LOWER_TWO_THIRDS -> it.y >= size.height / 3f
                            GestureRegion.LOWER_HALF -> it.y >= size.height / 2f
                            GestureRegion.MIDDLE_SIXTY ->
                                it.y in size.height * 0.2f..size.height * 0.8f
                        }
                        // 边缘留给系统返回；中心区域才接管播放器横滑，避免两者抢手势。
                        val xAllowed = it.x in edge..(size.width - edge)
                        gestureStartAllowed = !gestureLocked && yAllowed && xAllowed
                        volumeClamped = false
                        brightnessClamped = false
                        volumeUnlocked = false
                        brightnessUnlocked = false
                        limitHapticSent = false
                    },
                    onDrag = { change, drag ->
                        if (!gestureStartAllowed || twoFingerActive || suppressSingleGesture) {
                            return@detectDragGestures
                        }
                        change.consume()
                        total += drag
                        if (mode == DragMode.NONE &&
                            (abs(total.x) >= 16.dp.toPx() || abs(total.y) >= 12.dp.toPx())
                        ) {
                            mode = if (abs(total.x) > abs(total.y) * 1.2f) {
                                DragMode.SEEK
                            } else if (settings.verticalAdjustEnabled &&
                                start.x < size.width / 2f
                            ) {
                                DragMode.BRIGHTNESS
                            } else if (settings.verticalAdjustEnabled) {
                                DragMode.VOLUME
                            } else {
                                DragMode.NONE
                            }
                        }
                        when (mode) {
                            DragMode.SEEK -> {
                                val normalized = (total.x / size.width).coerceIn(-1f, 1f)
                                // 非线性曲线让短距离拖动更细腻，仍保留大范围快速跳转能力。
                                val deltaMs = curvedSeekDeltaMs(
                                    normalized,
                                    settings.seekSensitivitySeconds,
                                )
                                targetPosition = (startPosition + deltaMs)
                                    .coerceIn(0L, controller.durationMs.coerceAtLeast(0L))
                                overlay = GestureOverlay(
                                    DragMode.SEEK,
                                    formatTime(targetPosition),
                                    signedDuration(targetPosition - startPosition),
                                )
                                if (settings.seekPreviewEnabled) {
                                    seekPreviewJob?.cancel()
                                    val previewPosition = targetPosition
                                    seekPreviewJob = coroutineScope.launch {
                                        delay(70)
                                        val bitmap = controller.seekPreview(previewPosition)
                                        if (targetPosition == previewPosition && mode == DragMode.SEEK) {
                                            overlay = overlay?.copy(preview = bitmap)
                                        }
                                    }
                                }
                            }
                            DragMode.BRIGHTNESS -> {
                                val sensitivity = settings.brightnessSensitivityPercent / 100f
                                val desired = (
                                    startBrightness - total.y / size.height * sensitivity
                                    ).coerceIn(0.01f, 1f)
                                val limit = settings.brightnessSoftLimitPercent
                                val upward = total.y < 0f
                                val canUnlock = upward &&
                                    SystemClock.elapsedRealtime() <= brightnessArmedUntilMs
                                val adjusted = when {
                                    limit <= 0f || desired <= limit || !upward -> desired
                                    canUnlock -> {
                                        brightnessUnlocked = true
                                        desired
                                    }
                                    else -> {
                                        brightnessClamped = true
                                        if (!limitHapticSent && settings.hapticEnabled) {
                                            limitHapticSent = true
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                        limit
                                    }
                                }
                                brightness = adjusted
                                activity?.setWindowBrightness(adjusted)
                                overlay = GestureOverlay(
                                    DragMode.BRIGHTNESS,
                                    "亮度 ${(adjusted * 100).toInt()}%",
                                    if (brightnessClamped && !brightnessUnlocked) {
                                        "已到安全上限，松手后再次上滑可继续"
                                    } else "",
                                    adjusted,
                                    warning = brightnessClamped && !brightnessUnlocked,
                                )
                            }
                            DragMode.VOLUME -> {
                                val sensitivity = settings.volumeSensitivityPercent / 100f
                                val desired = (
                                    startVolume - total.y / size.height * sensitivity
                                    ).coerceIn(0f, 1f)
                                val limit = settings.volumeSoftLimitPercent
                                val upward = total.y < 0f
                                val canUnlock = upward &&
                                    SystemClock.elapsedRealtime() <= volumeArmedUntilMs
                                val adjusted = when {
                                    limit <= 0f || desired <= limit || !upward -> desired
                                    canUnlock -> {
                                        volumeUnlocked = true
                                        desired
                                    }
                                    else -> {
                                        volumeClamped = true
                                        if (!limitHapticSent && settings.hapticEnabled) {
                                            limitHapticSent = true
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                        limit
                                    }
                                }
                                volume = viewModel.setVolumePercent(adjusted, persist = false)
                                overlay = GestureOverlay(
                                    DragMode.VOLUME,
                                    "音量 ${(volume * 100).toInt()}%",
                                    if (volumeClamped && !volumeUnlocked) {
                                        "已到安全上限，松手后再次上滑可继续"
                                    } else "",
                                    volume,
                                    warning = volumeClamped && !volumeUnlocked,
                                )
                            }
                            DragMode.NONE, DragMode.SUBTITLE -> Unit
                        }
                    },
                    onDragEnd = {
                        seekPreviewJob?.cancel()
                        if (!suppressSingleGesture &&
                            mode == DragMode.SEEK &&
                            targetPosition != startPosition
                        ) {
                            controller.seekToFromGesture(targetPosition)
                        }
                        if (mode == DragMode.VOLUME) viewModel.saveVolumePercent(volume)
                        if (mode == DragMode.BRIGHTNESS) viewModel.saveBrightness(brightness)
                        val armedForMs = settings.softLimitArmedSeconds.coerceAtLeast(1) * 1_000L
                        if (volumeUnlocked) volumeArmedUntilMs = 0L
                        else if (volumeClamped) {
                            volumeArmedUntilMs = SystemClock.elapsedRealtime() + armedForMs
                        }
                        if (brightnessUnlocked) brightnessArmedUntilMs = 0L
                        else if (brightnessClamped) {
                            brightnessArmedUntilMs = SystemClock.elapsedRealtime() + armedForMs
                        }
                        isDragging = false
                        suppressSingleGesture = false
                    },
                    onDragCancel = {
                        seekPreviewJob?.cancel()
                        overlay = null
                        isDragging = false
                        suppressSingleGesture = false
                    },
                )
            }
            .pointerInput(
                sessionKey,
                gestureLocked,
                settings.transformGestureEnabled,
                settings.subtitleOffsetGestureEnabled,
            ) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var multiTouchStarted = false
                    var gestureMode = TwoFingerMode.NONE
                    var initialDistance = 0f
                    var initialCentroid = Offset.Zero
                    var initialPositions = emptyMap<androidx.compose.ui.input.pointer.PointerId, Offset>()
                    var initialSubtitleOffset = controller.session.value?.subtitleOffsetMs ?: 0L
                    while (true) {
                        val event = awaitPointerEvent()
                        val pointers = event.changes.filter { it.pressed }.take(2)
                        if (!multiTouchStarted && pointers.size == 2) {
                            multiTouchStarted = true
                            twoFingerActive = true
                            suppressSingleGesture = true
                            isDragging = true
                            controlsVisible = false
                            initialDistance =
                                (pointers[0].position - pointers[1].position).getDistance()
                            initialCentroid = (pointers[0].position + pointers[1].position) / 2f
                            initialPositions = pointers.associate { it.id to it.position }
                            initialSubtitleOffset =
                                controller.session.value?.subtitleOffsetMs ?: 0L
                        }
                        if (multiTouchStarted && pointers.size == 2 && !gestureLocked) {
                            val activeSession = controller.session.value
                            if (activeSession != null) {
                                val previousDistance = (pointers[0].previousPosition -
                                    pointers[1].previousPosition).getDistance()
                                val currentDistance =
                                    (pointers[0].position - pointers[1].position).getDistance()
                                val centroid =
                                    (pointers[0].position + pointers[1].position) / 2f
                                val centroidDelta = centroid - initialCentroid
                                val verticalDeltas = pointers.mapNotNull { pointer ->
                                    initialPositions[pointer.id]?.let { pointer.position.y - it.y }
                                }
                                val sameVerticalDirection = verticalDeltas.size == 2 &&
                                    abs(verticalDeltas[0]) >= 16.dp.toPx() &&
                                    abs(verticalDeltas[1]) >= 16.dp.toPx() &&
                                    sign(verticalDeltas[0]) == sign(verticalDeltas[1])
                                if (gestureMode == TwoFingerMode.NONE) {
                                    gestureMode = when {
                                        settings.transformGestureEnabled &&
                                            abs(currentDistance - initialDistance) >= 24.dp.toPx() ->
                                            TwoFingerMode.TRANSFORM
                                        settings.subtitleOffsetGestureEnabled &&
                                            sameVerticalDirection -> TwoFingerMode.SUBTITLE
                                        !settings.subtitleOffsetGestureEnabled &&
                                            settings.transformGestureEnabled &&
                                            centroidDelta.getDistance() >= 16.dp.toPx() ->
                                            TwoFingerMode.TRANSFORM
                                        else -> TwoFingerMode.NONE
                                    }
                                }
                                val zoom = if (previousDistance > 0f && currentDistance > 0f) {
                                    currentDistance / previousDistance
                                } else 1f
                                val pan =
                                    (
                                        (pointers[0].position - pointers[0].previousPosition) +
                                            (pointers[1].position - pointers[1].previousPosition)
                                        ) / 2f
                                when (gestureMode) {
                                    TwoFingerMode.TRANSFORM -> {
                                        controller.setVideoTransform(
                                            scale = activeSession.zoomScale * zoom,
                                            offsetX = activeSession.zoomOffsetX + pan.x,
                                            offsetY = activeSession.zoomOffsetY + pan.y,
                                        )
                                        overlay = GestureOverlay(
                                            DragMode.SEEK,
                                            "画面 ${(activeSession.zoomScale * zoom * 100).toInt()}%",
                                            "双指缩放与移动",
                                        )
                                    }
                                    TwoFingerMode.SUBTITLE -> {
                                        val offset = (
                                            initialSubtitleOffset +
                                                centroidDelta.y / size.height * 5_000L
                                            ).toLong().coerceIn(-60_000L, 60_000L)
                                        controller.setSubtitleOffset(offset)
                                        overlay = GestureOverlay(
                                            DragMode.SUBTITLE,
                                            "字幕 ${signedDuration(offset)}",
                                            "上移提前 · 下移延后",
                                        )
                                    }
                                    TwoFingerMode.NONE -> Unit
                                }
                                if (gestureMode != TwoFingerMode.NONE) {
                                    event.changes
                                        .filter { it.positionChanged() }
                                        .forEach { it.consume() }
                                }
                            }
                        }
                        if (event.changes.none { it.pressed }) {
                            if (multiTouchStarted) {
                                twoFingerActive = false
                                isDragging = false
                                if (gestureMode == TwoFingerMode.NONE) overlay = null
                            }
                            break
                        }
                    }
                }
            },
    ) {
        AndroidView(
            factory = { ctx ->
                AspectRatioFrameLayout(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    val video = SurfaceView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                    val nativeSubtitles = SurfaceView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        setZOrderMediaOverlay(true)
                        holder.setFormat(PixelFormat.TRANSLUCENT)
                    }
                    addView(video)
                    addView(nativeSubtitles)
                    controller.attachSurface(video, nativeSubtitles)
                }
            },
            update = { host ->
                val video = host.getChildAt(0) as SurfaceView
                val sourceRatio = if (decoder.width > 0 && decoder.height > 0) {
                    decoder.width.toFloat() / decoder.height
                } else {
                    16f / 9f
                }
                host.resizeMode = when (session.aspectRatioMode) {
                    1, 4 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    5 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
                host.setAspectRatio(
                    when (session.aspectRatioMode) {
                        2 -> 16f / 9f
                        3 -> 4f / 3f
                        else -> sourceRatio
                    }
                )
                video.scaleX = session.zoomScale * if (session.mirrorH) -1f else 1f
                video.scaleY = session.zoomScale * if (session.mirrorV) -1f else 1f
                video.translationX = session.zoomOffsetX
                video.translationY = session.zoomOffsetY
                video.rotation = session.rotationDegrees.toFloat()
            },
            modifier = Modifier.fillMaxSize(),
        )
        DisposableEffect(Unit) {
            onDispose { controller.detachSurface() }
        }

        if (
            session.subtitleEnabled &&
            session.kernel != com.lanplay.player.data.prefs.PlayerKernel.VLC
        ) {
            AndroidView(
                factory = { ctx ->
                    SubtitleView(ctx).apply {
                        setApplyEmbeddedStyles(true)
                        // 字号始终由 LanPlay 的 50%~250% 设置控制，避免 ASS/SRT
                        // 内嵌字号绕过用户选择，出现一部片特别大的情况。
                        setApplyEmbeddedFontSizes(false)
                        setStyle(
                            CaptionStyleCompat(
                                android.graphics.Color.WHITE,
                                android.graphics.Color.TRANSPARENT,
                                android.graphics.Color.TRANSPARENT,
                                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                                android.graphics.Color.BLACK,
                                null,
                            )
                        )
                        // 不能按 SubtitleView 整体高度取百分比：竖屏播放器的 Overlay
                        // 高达 2400px，即使 75% 也会变成近百像素的大字。固定以 sp
                        // 为基准，横竖屏与不同分辨率都保持相同的可读视觉尺寸。
                        setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f * 0.75f)
                        setBottomPaddingFraction(0.08f)
                    }
                },
                update = {
                    val textColor = runCatching {
                        android.graphics.Color.parseColor(subtitleStyle.textColor)
                    }.getOrDefault(android.graphics.Color.WHITE)
                    val edgeColor = runCatching {
                        android.graphics.Color.parseColor(subtitleStyle.edgeColor)
                    }.getOrDefault(android.graphics.Color.BLACK)
                    val typeface = when (subtitleStyle.font) {
                        SubtitleFont.SANS -> android.graphics.Typeface.SANS_SERIF
                        SubtitleFont.SERIF -> android.graphics.Typeface.SERIF
                        SubtitleFont.MONOSPACE -> android.graphics.Typeface.MONOSPACE
                    }
                    it.setStyle(
                        CaptionStyleCompat(
                            textColor,
                            if (subtitleStyle.backgroundEnabled) 0x99000000.toInt()
                            else android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT,
                            if (subtitleStyle.edgeWidth > 0) CaptionStyleCompat.EDGE_TYPE_OUTLINE
                            else CaptionStyleCompat.EDGE_TYPE_NONE,
                            edgeColor,
                            typeface,
                        )
                    )
                    it.setFixedTextSize(
                        android.util.TypedValue.COMPLEX_UNIT_SP,
                        22f * subtitleStyle.sizePercent / 100f,
                    )
                    it.setBottomPaddingFraction(subtitleStyle.bottomPaddingPercent / 100f)
                    it.setCues(cues)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (state == PlaybackState.BUFFERING) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = Color(0xCC17171A),
                modifier = Modifier.align(Alignment.Center),
            ) {
                Row(
                    Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("正在缓冲", color = Color.White)
                }
            }
        }

        if (state == PlaybackState.ERROR && !isPip) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xEE242428),
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            ) {
                Column(
                    Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("播放遇到问题", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(
                        lastError ?: "网络或解码器暂时不可用",
                        color = Color(0xFFD0D0D5),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = controller::retryCurrent) { Text("重试") }
                        TextButton(onClick = controller::switchKernel) { Text("切换内核") }
                        TextButton(onClick = requestExit) { Text("退出") }
                    }
                }
            }
        }

        if (!isPip) overlay?.let { gesture ->
            GestureBubble(
                gesture,
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 18.dp, end = 18.dp),
            )
        }

        if (gestureLocked && !isPip) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = Color(0xCC17171A),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 18.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Lock, null, tint = Color.White)
                    Text(
                        "手势已锁定 · 长按画面解锁",
                        color = Color.White,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }

        if (showResumeNotice && !isPip) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xE8242428),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
            ) {
                Row(
                    Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "已从 ${formatTime(session.resumedFromMs)} 继续",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(
                        onClick = {
                            controller.seekTo(0L)
                            showResumeNotice = false
                        }
                    ) {
                        Text("从头开始", color = Color(0xFF9FC0F8))
                    }
                }
            }
        }

        if (showDebugPanel && !isPip) {
            DebugPanel(
                session = session,
                decoder = decoder,
                debug = debug,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        if (state == PlaybackState.ENDED && !isPip) {
            EndedPanel(
                onReplay = controller::replay,
                onNext = { controller.playAdjacent(1) },
                onDelete = controller::deleteCurrent,
                autoNext = settings.autoPlayNext,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (controlsVisible && !isPip) {
            PlayerControls(
                session = session,
                state = state,
                decoderName = decoder.videoDecoder,
                hardware = decoder.isHardware,
                positionMs = positionMs,
                durationMs = controller.durationMs,
                seekSensitivity = settings.seekSensitivitySeconds,
                orientationLocked = orientationLocked,
                gestureLocked = gestureLocked,
                playbackSpeed = session.playbackSpeed,
                kernelName = if (session.kernel == com.lanplay.player.data.prefs.PlayerKernel.MEDIA3) "Media3" else "VLC",
                onBack = requestExit,
                onPlayPause = {
                    if (state == PlaybackState.PLAYING) controller.pause() else controller.play()
                    controlsEpoch++
                },
                onSeek = controller::seekTo,
                onCycleSeek = viewModel::cycleSeekSensitivity,
                onPrevious = { controller.playAdjacent(-1) },
                onNext = { controller.playAdjacent(1) },
                onSpeed = { showSpeedDialog = true },
                onSwitchKernel = controller::switchKernel,
                onSubtitles = {
                    showSubtitleDialog = true
                    subtitleLoading = true
                    coroutineScope.launch {
                        subtitleOptions = runCatching { controller.listSubtitles() }
                            .getOrDefault(emptyList())
                        subtitleLoading = false
                    }
                },
                onToggleOrientation = {
                    if (activity != null) {
                        orientationLocked = !orientationLocked
                        activity.requestedOrientation = if (orientationLocked) {
                            ActivityInfo.SCREEN_ORIENTATION_LOCKED
                        } else {
                            requestedOrientation(settings.orientationMode)
                        }
                        Toast.makeText(
                            context,
                            if (orientationLocked) {
                                "已锁定当前方向"
                            } else {
                                when (settings.orientationMode) {
                                    OrientationMode.AUTO -> "已恢复自动旋转"
                                    OrientationMode.FORCE_LANDSCAPE -> "已恢复强制横屏"
                                    OrientationMode.FORCE_PORTRAIT -> "已恢复强制竖屏"
                                }
                            },
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    controlsEpoch++
                },
                onToggleGestureLock = {
                    gestureLocked = true
                    controlsVisible = false
                    if (settings.hapticEnabled) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    Toast.makeText(context, "手势已锁定，长按画面解锁", Toast.LENGTH_SHORT).show()
                },
                onAspect = { showAspectDialog = true },
                onMute = {
                    if (muted) {
                        viewModel.setVolumePercent(volumeBeforeMute)
                        muted = false
                    } else {
                        volumeBeforeMute = viewModel.currentVolumePercent()
                        viewModel.setVolumePercent(0f)
                        muted = true
                    }
                },
                muted = muted,
                onFavorite = controller::toggleFavorite,
                onRating = { showRatingDialog = true },
                onRotate = {
                    controller.setVideoTransform(rotation = session.rotationDegrees + 90)
                },
                onMirror = {
                    controller.setVideoTransform(mirrorH = !session.mirrorH)
                },
                onDebug = { showDebugPanel = !showDebugPanel },
                onAudioTracks = { showAudioTracks = true },
                handedness = settings.handedness,
                quickSeekSeconds = settings.doubleTapSeconds,
                seekPreviewEnabled = settings.seekPreviewEnabled,
                onSeekPreview = controller::seekPreview,
                onOrganize = { showOrganize = true },
                onScreenshot = {
                    coroutineScope.launch {
                        runCatching { controller.captureFrame() }
                            .onSuccess { capturedScreenshot = it }
                            .onFailure {
                                Toast.makeText(
                                    context,
                                    it.message ?: "截图失败",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                    }
                },
                onQueue = {
                    showQueue = true
                    queueLoading = true
                    coroutineScope.launch {
                        queueItems = runCatching { controller.listQueue() }
                            .getOrDefault(emptyList())
                        queueLoading = false
                    }
                },
            )
        }
        if (blackFade.value > 0f && !isPip) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = blackFade.value))
            )
        }
    }
    if (askingResume) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("继续上次观看？") },
            text = { Text("上次看到 ${formatTime(session.resumedFromMs)}。") },
            dismissButton = {
                TextButton(onClick = {
                    askingResume = false
                    controller.seekTo(0)
                    controller.play()
                }) { Text("从头开始") }
            },
            confirmButton = {
                Button(onClick = {
                    askingResume = false
                    controller.play()
                }) { Text("继续观看") }
            },
        )
    }
    if (showSubtitleDialog) {
        SubtitleDialog(
            options = subtitleOptions,
            currentPath = session.subtitlePath,
            currentCharset = session.subtitleCharset,
            enabled = session.subtitleEnabled,
            offsetMs = session.subtitleOffsetMs,
            loading = subtitleLoading,
            onDismiss = { showSubtitleDialog = false },
            onSelect = { path, charset ->
                showSubtitleDialog = false
                controller.selectSubtitle(path, charset)
            },
            onDisable = {
                showSubtitleDialog = false
                controller.disableSubtitles()
            },
            onOffset = controller::setSubtitleOffset,
            onSearch = controller::searchSubtitle,
            onSeekHit = {
                controller.seekTo(it.positionMs)
                showSubtitleDialog = false
            },
        )
    }
    if (showSpeedDialog) {
        ChoiceDialog(
            title = "播放速度",
            options = (2..12).map { it / 4f to "${it / 4f}×" },
            selected = session.playbackSpeed,
            onDismiss = { showSpeedDialog = false },
            onSelect = {
                controller.setSpeed(it)
                showSpeedDialog = false
            },
        )
    }
    if (showAspectDialog) {
        ChoiceDialog(
            title = "画面比例",
            options = listOf(
                0 to "原始",
                1 to "铺满",
                2 to "16:9",
                3 to "4:3",
                4 to "拉伸",
                5 to "裁切",
            ),
            selected = session.aspectRatioMode,
            onDismiss = { showAspectDialog = false },
            onSelect = {
                controller.setAspectRatioMode(it)
                showAspectDialog = false
            },
        )
    }
    if (showRatingDialog) {
        ChoiceDialog(
            title = "个人评分",
            options = (0..5).map { it to if (it == 0) "不评分" else "★".repeat(it) },
            selected = session.rating,
            onDismiss = { showRatingDialog = false },
            onSelect = {
                controller.setRating(it)
                showRatingDialog = false
            },
        )
    }
    if (showAudioTracks) {
        AudioDialog(
            tracks = audioTracks,
            selectedTrack = session.audioTrackIndex.takeIf { it >= 0 }
                ?: audioTracks.indexOfFirst { it.selected },
            calibration = audioCalibration,
            externalAudioFiles = externalAudioFiles,
            selectedExternalAudio = session.externalAudioPath,
            onDismiss = { showAudioTracks = false },
            onSelectTrack = {
                controller.selectAudioTrack(it)
            },
            onDelay = controller::setAudioDelay,
            onSelectExternalAudio = controller::selectExternalAudio,
        )
    }
    if (showOrganize) {
        OrganizeDialog(
            session = session,
            bookmarks = bookmarks,
            tags = tags,
            onDismiss = { showOrganize = false },
            onAddBookmark = controller::addBookmark,
            onSeekBookmark = {
                controller.seekTo(it.positionMs)
                showOrganize = false
            },
            onDeleteBookmark = controller::deleteBookmark,
            onAddTag = controller::addTag,
            onAddCollection = controller::addToCollection,
            onRemoveTag = controller::removeTag,
            onSaveNote = controller::setNote,
        )
    }
    if (showQueue) {
        QueueDialog(
            session = session,
            items = queueItems,
            loading = queueLoading,
            mode = queueMode,
            abLoop = abLoop,
            currentPositionMs = positionMs,
            onDismiss = { showQueue = false },
            onMode = controller::setQueueMode,
            onPlay = {
                showQueue = false
                controller.playQueueItem(it.path)
            },
            onSetA = controller::setAbStart,
            onSetB = controller::setAbEnd,
            onClearAb = controller::clearAbLoop,
            onIntro = controller::setSkipIntroHere,
            onOutro = controller::setSkipOutroHere,
            onClearSkip = controller::clearSkipPoints,
        )
    }
    capturedScreenshot?.let { file ->
        AlertDialog(
            onDismissRequest = { capturedScreenshot = null },
            title = { Text("截图已保存") },
            text = {
                Text(
                    "截图保存在 LanPlay 的私有空间，不会出现在系统相册。需要时可以临时分享给你选择的应用。"
                )
            },
            dismissButton = {
                TextButton(onClick = { capturedScreenshot = null }) { Text("继续播放") }
            },
            confirmButton = {
                Button(
                    onClick = {
                        runCatching {
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.files",
                                file,
                            )
                            val share = Intent(Intent.ACTION_SEND)
                                .setType("image/jpeg")
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(Intent.createChooser(share, "分享播放器截图"))
                        }.onFailure {
                            Toast.makeText(
                                context,
                                "没有可用的分享应用",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                ) { Text("分享截图") }
            },
        )
    }
}

@Composable
private fun QueueDialog(
    session: PlaybackSession,
    items: List<QueueItem>,
    loading: Boolean,
    mode: QueueMode,
    abLoop: AbLoop?,
    currentPositionMs: Long,
    onDismiss: () -> Unit,
    onMode: (QueueMode) -> Unit,
    onPlay: (QueueItem) -> Unit,
    onSetA: () -> Unit,
    onSetB: () -> Unit,
    onClearAb: () -> Unit,
    onIntro: () -> Unit,
    onOutro: () -> Unit,
    onClearSkip: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("播放队列与区间") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("播放顺序", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        QueueMode.NORMAL to "顺序",
                        QueueMode.SINGLE to "单曲",
                        QueueMode.LIST_LOOP to "列表循环",
                        QueueMode.RANDOM to "随机",
                    ).forEach { option ->
                        AssistChip(
                            onClick = { onMode(option.first) },
                            label = { Text(option.second) },
                            leadingIcon = if (mode == option.first) {
                                { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
                            } else null,
                        )
                    }
                }
                Text("A-B 循环", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        abLoop == null -> "尚未设置"
                        abLoop.endMs <= abLoop.startMs ->
                            "A ${formatTime(abLoop.startMs)} · 请播放到结束点再设 B"
                        else -> "A ${formatTime(abLoop.startMs)} — B ${formatTime(abLoop.endMs)}"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(onClick = onSetA, label = { Text("设 A · ${formatTime(currentPositionMs)}") })
                    AssistChip(onClick = onSetB, label = { Text("设 B") }, enabled = abLoop != null)
                    AssistChip(onClick = onClearAb, label = { Text("清除") }, enabled = abLoop != null)
                }
                Text("跳过片头 / 片尾", style = MaterialTheme.typography.titleMedium)
                Text(
                    "片头 ${formatTime(session.skipIntroMs)} · 片尾提前 ${formatTime(session.skipOutroMs)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(onClick = onIntro, label = { Text("当前位置为片头结束") })
                    AssistChip(onClick = onOutro, label = { Text("当前位置为片尾开始") })
                    AssistChip(onClick = onClearSkip, label = { Text("清除") })
                }
                Text("同目录队列", style = MaterialTheme.typography.titleMedium)
                if (loading) {
                    CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                } else {
                    items.forEach { item ->
                        TextButton(
                            onClick = { onPlay(item) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (item.current) {
                                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                            }
                            Text(
                                item.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

@Composable
private fun AudioDialog(
    tracks: List<AudioTrackInfo>,
    selectedTrack: Int,
    calibration: AudioCalibration,
    externalAudioFiles: List<String>,
    selectedExternalAudio: String?,
    onDismiss: () -> Unit,
    onSelectTrack: (Int) -> Unit,
    onDelay: (Long) -> Unit,
    onSelectExternalAudio: (String?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("音轨与音画同步") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "${calibration.displayName} · ${calibration.outputType}",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text("音轨", style = MaterialTheme.typography.titleMedium)
                tracks.forEach { track ->
                    AssistChip(
                        onClick = { onSelectTrack(track.index) },
                        label = {
                            Text(
                                buildString {
                                    append(track.name)
                                    if (!track.language.isNullOrBlank()) append(" · ${track.language}")
                                    if (track.channels > 0) append(" · ${track.channels}声道")
                                }
                            )
                        },
                        leadingIcon = if (selectedTrack == track.index) {
                            { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
                        } else null,
                    )
                }
                Text("同目录外挂音轨", style = MaterialTheme.typography.titleMedium)
                AssistChip(
                    onClick = { onSelectExternalAudio(null) },
                    label = { Text("不使用外挂音轨") },
                    leadingIcon = if (selectedExternalAudio == null) {
                        { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
                    } else null,
                )
                externalAudioFiles.forEach { path ->
                    AssistChip(
                        onClick = { onSelectExternalAudio(path) },
                        label = {
                            Text(
                                path.substringAfterLast('/'),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingIcon = if (selectedExternalAudio == path) {
                            { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
                        } else null,
                    )
                }
                if (externalAudioFiles.isEmpty()) {
                    Text(
                        "当前目录没有 AAC、AC3、DTS、FLAC、M4A、MP3、OGG、Opus 或 WAV 文件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("音频延迟 ${calibration.delayMs} ms", style = MaterialTheme.typography.titleMedium)
                if (!calibration.delaySupported) {
                    Text(
                        "当前 Media3 内核信任系统自动补偿；需要手动校准时请先切换 VLC 兼容内核。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-50L, -10L, 10L, 50L).forEach { delta ->
                        AssistChip(
                            onClick = { onDelay(calibration.delayMs + delta) },
                            enabled = calibration.delaySupported,
                            label = { Text(if (delta > 0) "+$delta" else "$delta") },
                        )
                    }
                }
                TextButton(
                    onClick = { onDelay(0) },
                    enabled = calibration.delaySupported && calibration.delayMs != 0L,
                ) { Text("归零") }
                Text(
                    "校准值会按当前耳机或输出设备记忆，下次连接自动套用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

@Composable
private fun PlayerControls(
    session: PlaybackSession,
    state: PlaybackState,
    decoderName: String,
    hardware: Boolean,
    positionMs: Long,
    durationMs: Long,
    seekSensitivity: Int,
    orientationLocked: Boolean,
    gestureLocked: Boolean,
    playbackSpeed: Float,
    kernelName: String,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onCycleSeek: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSpeed: () -> Unit,
    onSwitchKernel: () -> Unit,
    onSubtitles: () -> Unit,
    onToggleOrientation: () -> Unit,
    onToggleGestureLock: () -> Unit,
    onAspect: () -> Unit,
    onMute: () -> Unit,
    muted: Boolean,
    onFavorite: () -> Unit,
    onRating: () -> Unit,
    onRotate: () -> Unit,
    onMirror: () -> Unit,
    onDebug: () -> Unit,
    onAudioTracks: () -> Unit,
    handedness: Handedness,
    quickSeekSeconds: Int,
    seekPreviewEnabled: Boolean,
    onSeekPreview: suspend (Long) -> Bitmap?,
    onOrganize: () -> Unit,
    onScreenshot: () -> Unit,
    onQueue: () -> Unit,
) {
    val mediaKey = session.serverId to session.relativePath
    var sliderPosition by remember(mediaKey) {
        mutableFloatStateOf(positionMs.toFloat())
    }
    var sliderDragging by remember(mediaKey) { mutableStateOf(false) }
    var fineMode by remember(mediaKey) { mutableStateOf(false) }
    var fineAnchor by remember(mediaKey) { mutableFloatStateOf(0f) }
    var showQuickPoints by remember(mediaKey) { mutableStateOf(false) }
    var previewBitmap by remember(mediaKey) { mutableStateOf<Bitmap?>(null) }
    var previewJob by remember(mediaKey) { mutableStateOf<Job?>(null) }
    val controlsScope = rememberCoroutineScope()
    LaunchedEffect(positionMs) {
        if (!sliderDragging) sliderPosition = positionMs.toFloat()
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xB8000000))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, "退出播放器", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    session.fileName,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(if (hardware) "硬件解码" else "软件解码")
                        if (session.subtitlePath != null) append(" · 字幕 ${session.subtitleCharset}")
                        if (decoderName != "-") append(" · $decoderName")
                    },
                    color = if (hardware) Color(0xFF79D7AD) else Color(0xFFFFB4AB),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onDebug) {
                Icon(Icons.Rounded.BugReport, "调试面板", tint = Color.White)
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color(0xCC000000))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (sliderDragging && previewBitmap != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Black,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(160.dp)
                        .aspectRatio(16f / 9f),
                ) {
                    Image(
                        bitmap = previewBitmap!!.asImageBitmap(),
                        contentDescription = "拖动位置预览",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatTime(positionMs),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp)
                        .pointerInput(durationMs) {
                            var lastTapUpMs = 0L
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var moved = false
                                var longPressActivated = false
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Final)
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                        ?: event.changes.firstOrNull()
                                        ?: break
                                    if (
                                        (change.position - down.position).getDistance() >
                                        viewConfiguration.touchSlop
                                    ) {
                                        moved = true
                                    }
                                    if (!moved && !longPressActivated &&
                                        change.uptimeMillis - down.uptimeMillis >= 450L
                                    ) {
                                        longPressActivated = true
                                        fineMode = true
                                        fineAnchor = sliderPosition
                                    }
                                    if (!change.pressed) {
                                        if (!moved && !longPressActivated) {
                                            if (change.uptimeMillis - lastTapUpMs <= 300L) {
                                                showQuickPoints = !showQuickPoints
                                                lastTapUpMs = 0L
                                            } else {
                                                lastTapUpMs = change.uptimeMillis
                                            }
                                        }
                                        if (!sliderDragging) fineMode = false
                                        break
                                    }
                                }
                            }
                        },
                ) {
                    Slider(
                        value = sliderPosition.coerceAtMost(
                            durationMs.toFloat().coerceAtLeast(1f)
                        ),
                        onValueChange = {
                            if (!sliderDragging) fineAnchor = sliderPosition
                            sliderDragging = true
                            sliderPosition = if (fineMode) {
                                fineAnchor + (it - fineAnchor) / 8f
                            } else it
                            if (seekPreviewEnabled) {
                                previewJob?.cancel()
                                val previewPosition = sliderPosition.toLong()
                                previewJob = controlsScope.launch {
                                    delay(70)
                                    previewBitmap = onSeekPreview(previewPosition)
                                }
                            }
                        },
                        onValueChangeFinished = {
                            previewJob?.cancel()
                            sliderDragging = false
                            fineMode = false
                            onSeek(sliderPosition.toLong())
                        },
                        valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (fineMode) {
                        Text(
                            "精细",
                            color = Color(0xFFFFD166),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }
                }
                Text(
                    formatTime(durationMs),
                    color = Color(0xFFB9B9C0),
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                )
            }
            if (showQuickPoints) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    listOf(0, 25, 50, 75).forEach { percent ->
                        TextButton(
                            onClick = {
                                onSeek(durationMs * percent / 100)
                                showQuickPoints = false
                            }
                        ) { Text("$percent%", color = Color.White) }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = when (handedness) {
                        Handedness.LEFT -> Arrangement.Start
                        Handedness.CENTER -> Arrangement.Center
                        Handedness.RIGHT -> Arrangement.End
                    },
                ) {
                    IconButton(onClick = onPrevious) {
                        Icon(Icons.Rounded.SkipPrevious, "上一个", tint = Color.White)
                    }
                    IconButton(onClick = {
                        onSeek((positionMs - quickSeekSeconds * 1_000L).coerceAtLeast(0))
                    }) {
                        Icon(
                            Icons.Rounded.FastRewind,
                            "快退 $quickSeekSeconds 秒",
                            tint = Color.White,
                        )
                    }
                    IconButton(onClick = onPlayPause, modifier = Modifier.size(56.dp)) {
                        Icon(
                            if (state == PlaybackState.PLAYING) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (state == PlaybackState.PLAYING) "暂停" else "播放",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                    IconButton(onClick = {
                        onSeek((positionMs + quickSeekSeconds * 1_000L).coerceAtMost(durationMs))
                    }) {
                        Icon(
                            Icons.Rounded.FastForward,
                            "快进 $quickSeekSeconds 秒",
                            tint = Color.White,
                        )
                    }
                    IconButton(onClick = onNext) {
                        Icon(Icons.Rounded.SkipNext, "下一个", tint = Color.White)
                    }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onSubtitles) {
                        Icon(
                            Icons.Rounded.Subtitles,
                            "字幕设置",
                            tint = if (session.subtitleEnabled && session.subtitlePath != null) {
                                Color(0xFF8FB3F0)
                            } else Color.White,
                        )
                    }
                    TextButton(onClick = onSwitchKernel) {
                        Text(kernelName, color = Color.White)
                    }
                    TextButton(onClick = onSpeed) {
                        Text("${playbackSpeed}×", color = Color.White)
                    }
                    TextButton(onClick = onCycleSeek) {
                        Text("横滑 ${formatSeekRange(seekSensitivity)}", color = Color.White)
                    }
                    IconButton(onClick = onToggleOrientation) {
                        Icon(
                            if (orientationLocked) Icons.Rounded.ScreenLockRotation else Icons.Rounded.ScreenRotation,
                            if (orientationLocked) "解除方向锁定" else "锁定当前方向",
                            tint = if (orientationLocked) Color(0xFF8FB3F0) else Color.White,
                        )
                    }
                    IconButton(onClick = onToggleGestureLock) {
                        Icon(
                            Icons.Rounded.Lock,
                            if (gestureLocked) "手势已锁定" else "锁定手势",
                            tint = if (gestureLocked) Color(0xFF8FB3F0) else Color.White,
                        )
                    }
                    IconButton(onClick = onAspect) {
                        Icon(Icons.Rounded.AspectRatio, "画面比例", tint = Color.White)
                    }
                    IconButton(onClick = onAudioTracks) {
                        Icon(Icons.Rounded.Audiotrack, "切换音轨", tint = Color.White)
                    }
                    IconButton(onClick = onRotate) {
                        Icon(Icons.Rounded.RotateRight, "旋转 90 度", tint = Color.White)
                    }
                    IconButton(onClick = onMirror) {
                        Icon(
                            Icons.Rounded.Flip,
                            "水平镜像",
                            tint = if (session.mirrorH) Color(0xFF8FB3F0) else Color.White,
                        )
                    }
                    IconButton(onClick = onMute) {
                        Icon(
                            if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                            if (muted) "恢复声音" else "静音",
                            tint = if (muted) Color(0xFF8FB3F0) else Color.White,
                        )
                    }
                    IconButton(onClick = onFavorite) {
                        Icon(
                            if (session.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                            if (session.isFavorite) "取消收藏" else "收藏",
                            tint = if (session.isFavorite) Color(0xFFFFD166) else Color.White,
                        )
                    }
                    TextButton(onClick = onRating) {
                        Text(
                            if (session.rating > 0) "${session.rating}★" else "评分",
                            color = if (session.rating > 0) Color(0xFFFFD166) else Color.White,
                        )
                    }
                    IconButton(onClick = onOrganize) {
                        Icon(Icons.Rounded.BookmarkAdd, "书签、标签与备注", tint = Color.White)
                    }
                    IconButton(onClick = onScreenshot) {
                        Icon(Icons.Rounded.PhotoCamera, "保存当前画面", tint = Color.White)
                    }
                    IconButton(onClick = onQueue) {
                        Icon(Icons.Rounded.PlaylistPlay, "播放队列与循环", tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun OrganizeDialog(
    session: PlaybackSession,
    bookmarks: List<BookmarkEntity>,
    tags: List<TagEntity>,
    onDismiss: () -> Unit,
    onAddBookmark: (String?) -> Unit,
    onSeekBookmark: (BookmarkEntity) -> Unit,
    onDeleteBookmark: (BookmarkEntity) -> Unit,
    onAddTag: (String, String) -> Unit,
    onAddCollection: (String) -> Unit,
    onRemoveTag: (TagEntity) -> Unit,
    onSaveNote: (String) -> Unit,
) {
    var bookmarkName by remember { mutableStateOf("") }
    var tagName by remember { mutableStateOf("") }
    var collectionName by remember { mutableStateOf("") }
    var note by remember(session.relativePath) { mutableStateOf(session.note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("书签与整理") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.BookmarkAdd, null)
                    Text("当前时间书签", Modifier.padding(start = 8.dp))
                }
                OutlinedTextField(
                    value = bookmarkName,
                    onValueChange = { bookmarkName = it.take(80) },
                    label = { Text("名称（可不填）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        onAddBookmark(bookmarkName.ifBlank { null })
                        bookmarkName = ""
                    }
                ) { Text("在当前位置打点") }
                bookmarks.forEach { bookmark ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { onSeekBookmark(bookmark) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                "${formatTime(bookmark.positionMs)}  " +
                                    (bookmark.label ?: "未命名书签"),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { onDeleteBookmark(bookmark) }) {
                            Icon(Icons.Rounded.DeleteOutline, "删除书签")
                        }
                    }
                }
                Row(
                    Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Star, null)
                    Text("收藏夹", Modifier.padding(start = 8.dp))
                }
                Text(
                    "可同时加入多个自定义收藏夹，加入后会自动点亮收藏。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = collectionName,
                        onValueChange = { collectionName = it.take(24) },
                        label = { Text("收藏夹名称") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            onAddCollection(collectionName)
                            collectionName = ""
                        },
                        enabled = collectionName.isNotBlank(),
                    ) { Text("加入") }
                }
                Row(
                    Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.LocalOffer, null)
                    Text("标签", Modifier.padding(start = 8.dp))
                }
                if (tags.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        tags.forEach { tag ->
                            AssistChip(
                                onClick = { onRemoveTag(tag) },
                                label = { Text("${tag.name}  ×") },
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = tagName,
                        onValueChange = { tagName = it.take(30) },
                        label = { Text("新标签") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            onAddTag(tagName, "#6E93D6")
                            tagName = ""
                        },
                        enabled = tagName.isNotBlank(),
                    ) { Text("添加") }
                }
                Row(
                    Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Notes, null)
                    Text("备注", Modifier.padding(start = 8.dp))
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(2_000) },
                    label = { Text("只保存在本机") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { onSaveNote(note) }) { Text("保存备注") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                options.chunked(3).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        row.forEach { (value, label) ->
                            AssistChip(
                                onClick = { onSelect(value) },
                                label = { Text(label) },
                                leadingIcon = if (selected == value) {
                                    { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
                                } else null,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun DebugPanel(
    session: PlaybackSession,
    decoder: DecoderInfo,
    debug: DebugSnapshot,
    modifier: Modifier = Modifier,
) {
    val dropRate = if (decoder.renderedFrames > 0) {
        decoder.droppedFrames * 100.0 / decoder.renderedFrames
    } else 0.0
    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
        color = Color(0xED17171B),
        modifier = modifier.width(330.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("调试面板", color = Color.White, style = MaterialTheme.typography.titleLarge)
            DebugSection("网络")
            DebugLine(
                "吞吐",
                "${"%.2f".format(debug.throughputMbps)} MB/s",
                warning = debug.throughputMbps in 0.01..2.99,
            )
            DebugLine(
                "缓冲水位",
                "${"%.1f".format(debug.bufferSeconds)} 秒 · ${"%.1f".format(debug.proxyMb)} MB",
                warning = debug.bufferSeconds in 0.01..9.99,
            )
            DebugLine("在途 / 命中", "${debug.inflight} · ${(debug.hitRate * 100).toInt()}%")
            DebugLine("重连 / 方言", "${debug.reconnects} · ${debug.dialect}", debug.reconnects > 0)
            DebugSection("解码")
            DebugLine("内核", session.kernel.name)
            DebugLine(
                "视频解码器",
                decoder.videoDecoder,
                warning = !decoder.isHardware && decoder.videoDecoder != "-",
            )
            DebugLine("音频解码器", decoder.audioDecoder)
            DebugLine(
                "画面",
                "${decoder.width}×${decoder.height} @ ${"%.2f".format(decoder.frameRate)} fps",
            )
            DebugLine(
                "帧",
                "${decoder.renderedFrames} · 丢 ${decoder.droppedFrames} (${"%.3f".format(dropRate)}%)",
                warning = dropRate > 0.5 || decoder.maxConsecutiveDropped > 5,
            )
            DebugSection("显示与设备")
            DebugLine("刷新率", "${"%.1f".format(debug.refreshRate)} Hz · SurfaceView")
            DebugLine("音频输出", debug.outputDevice)
            DebugLine(
                "Java 堆",
                "${"%.1f".format(debug.heapUsedMb)} / ${"%.0f".format(debug.heapMaxMb)} MB",
            )
        }
    }
}

@Composable
private fun DebugSection(text: String) {
    Text(
        text,
        color = Color(0xFF9FC0F8),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 7.dp),
    )
}

@Composable
private fun DebugLine(label: String, value: String, warning: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = Color(0xFFBFC0C8), modifier = Modifier.width(92.dp))
        Text(
            value,
            color = if (warning) Color(0xFFFFB4AB) else Color.White,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EndedPanel(
    onReplay: () -> Unit,
    onNext: () -> Unit,
    onDelete: () -> Unit,
    autoNext: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color(0xE6242428),
        modifier = modifier,
    ) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("播放完毕", color = Color.White, style = MaterialTheme.typography.headlineSmall)
            Text(
                if (autoNext) "30 秒后自动播放下一部" else "画面会保持在这里，等待你的选择",
                color = Color(0xFFC9C9D0),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onReplay) { Text("重看") }
                Button(onClick = onNext) { Text("下一个") }
                TextButton(onClick = onDelete) {
                    Icon(Icons.Rounded.DeleteOutline, null, tint = Color(0xFFFFB4AB))
                    Text("删除本片", color = Color(0xFFFFB4AB))
                }
            }
        }
    }
}

@Composable
private fun SubtitleDialog(
    options: List<String>,
    currentPath: String?,
    currentCharset: String?,
    enabled: Boolean,
    offsetMs: Long,
    loading: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String, String?) -> Unit,
    onDisable: () -> Unit,
    onOffset: (Long) -> Unit,
    onSearch: suspend (String) -> List<SubtitleSearchHit>,
    onSeekHit: (SubtitleSearchHit) -> Unit,
) {
    var selectedPath by remember(options, currentPath) {
        mutableStateOf(currentPath ?: options.firstOrNull())
    }
    var selectedCharset by remember(currentCharset) {
        mutableStateOf<String?>(currentCharset)
    }
    var searchQuery by remember { mutableStateOf("") }
    var searchHits by remember { mutableStateOf<List<SubtitleSearchHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Subtitles, null) },
        title = { Text("字幕") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when {
                    loading -> Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator(Modifier.size(28.dp)) }
                    options.isEmpty() -> Text(
                        "同目录没有可用的外挂字幕。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> {
                        Text("字幕文件", style = MaterialTheme.typography.titleSmall)
                        options.forEach { path ->
                            AssistChip(
                                onClick = { selectedPath = path },
                                label = {
                                    Text(
                                        path.substringAfterLast('/'),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                leadingIcon = if (selectedPath == path) {
                                    { Icon(Icons.Rounded.Check, null, Modifier.size(17.dp)) }
                                } else null,
                            )
                        }
                        Text("文字编码", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                null to "自动",
                                "UTF-8" to "UTF-8",
                                "GB18030" to "GB18030",
                                "Big5" to "Big5",
                                "Shift_JIS" to "日文",
                            ).forEach { (charset, label) ->
                                AssistChip(
                                    onClick = { selectedCharset = charset },
                                    label = { Text(label) },
                                    leadingIcon = if (selectedCharset == charset) {
                                        { Icon(Icons.Rounded.Check, null, Modifier.size(15.dp)) }
                                    } else null,
                                )
                            }
                        }
                        Text(
                            "切换后会在当前位置立即重载；选择错误时可回到“自动”。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("时间轴偏移", style = MaterialTheme.typography.titleSmall)
                        Text(
                            signedDuration(offsetMs),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            listOf(
                                -500L to "-0.5秒",
                                -100L to "-0.1秒",
                                0L to "归零",
                                100L to "+0.1秒",
                                500L to "+0.5秒",
                            ).forEach { (delta, label) ->
                                TextButton(onClick = {
                                    onOffset(
                                        if (delta == 0L) 0L
                                        else (offsetMs + delta).coerceIn(-60_000L, 60_000L)
                                    )
                                }) { Text(label) }
                            }
                        }
                        Text("搜索字幕内容", style = MaterialTheme.typography.titleSmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it.take(80) },
                                label = { Text("关键词") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    searching = true
                                    scope.launch {
                                        try {
                                            searchHits = onSearch(searchQuery)
                                            searchError = null
                                        } catch (t: Throwable) {
                                            if (t is CancellationException) throw t
                                            searchError = t.message ?: "字幕搜索失败，请重试"
                                        } finally {
                                            searching = false
                                        }
                                    }
                                },
                                enabled = searchQuery.isNotBlank() && !searching,
                            ) { Text(if (searching) "搜索中" else "搜索") }
                        }
                        searchError?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        searchHits.forEach { hit ->
                            TextButton(
                                onClick = { onSeekHit(hit) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "${formatTime(hit.positionMs)}  ${hit.text}",
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            Row {
                if (enabled) TextButton(onClick = onDisable) { Text("关闭字幕") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
        confirmButton = {
            Button(
                onClick = { selectedPath?.let { onSelect(it, selectedCharset) } },
                enabled = !loading && selectedPath != null,
            ) { Text("应用") }
        },
    )
}

@Composable
private fun GestureBubble(
    overlay: GestureOverlay,
    modifier: Modifier = Modifier,
) {
    val isSeek = overlay.icon == DragMode.SEEK
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = modifier.border(1.dp, Color.White.copy(alpha = 0.16f), shape),
        shape = shape,
        color = Color(0x7017171A),
    ) {
        Column(
            Modifier
                .padding(
                    horizontal = if (isSeek) 16.dp else 14.dp,
                    vertical = 12.dp,
                )
                .width(if (isSeek) 206.dp else 154.dp),
        ) {
            overlay.preview?.let { preview ->
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = "跳转位置预览",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                )
                Spacer(Modifier.height(10.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (overlay.icon) {
                        DragMode.BRIGHTNESS -> Icons.Rounded.Brightness6
                        DragMode.VOLUME -> Icons.Rounded.VolumeUp
                        DragMode.SUBTITLE -> Icons.Rounded.Subtitles
                        else -> Icons.Rounded.PlayArrow
                    },
                    contentDescription = null,
                    tint = if (overlay.warning) Color(0xFFFFC267) else Color.White,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    overlay.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    modifier = Modifier.padding(start = 9.dp),
                )
            }
            if (overlay.progress != null) {
                LinearProgressIndicator(
                    progress = { overlay.progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .height(4.dp),
                    color = if (overlay.warning) Color(0xFFFFC267) else Color(0xFF8EB7FF),
                    trackColor = Color.White.copy(alpha = 0.2f),
                )
            }
            if (overlay.detail.isNotBlank()) {
                Text(
                    overlay.detail,
                    color = if (overlay.warning) Color(0xFFFFC267) else Color(0xFFB9B9C0),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
        }
    }
}

private fun Activity.setWindowBrightness(value: Float) {
    window.attributes = window.attributes.apply {
        screenBrightness = value.coerceIn(0.01f, 1f)
    }
}

private fun Activity.followSystemBrightness() {
    window.attributes = window.attributes.apply {
        screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
}

private fun Activity.windowBrightnessPercent(): Float {
    val override = window.attributes.screenBrightness
    return if (override >= 0f) override.coerceIn(0.01f, 1f)
    else systemBrightnessPercent()
}

private fun Context.systemBrightnessPercent(): Float {
    val raw = runCatching {
        android.provider.Settings.System.getInt(
            contentResolver,
            android.provider.Settings.System.SCREEN_BRIGHTNESS,
        )
    }.getOrDefault(128)
    return (raw / 255f).coerceIn(0.01f, 1f)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00:00"
    val totalSec = ms / 1000
    return "%02d:%02d:%02d".format(totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60)
}

private fun formatSeekRange(seconds: Int): String = when {
    seconds >= 3_600 && seconds % 3_600 == 0 -> "${seconds / 3_600}小时"
    seconds >= 60 && seconds % 60 == 0 -> "${seconds / 60}分钟"
    else -> "${seconds}秒"
}

internal fun signedDuration(ms: Long): String {
    val sign = if (ms >= 0) "+" else "−"
    val absoluteMs = abs(ms)
    if (absoluteMs < 60_000L) {
        val tenths = (absoluteMs + 50L) / 100L
        val value = if (tenths % 10L == 0L) {
            "${tenths / 10L}"
        } else {
            "${tenths / 10L}.${tenths % 10L}"
        }
        return "$sign${value}秒"
    }
    val seconds = absoluteMs / 1_000L
    return "$sign${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

internal fun curvedSeekDeltaMs(dragFraction: Float, maxSeconds: Int): Long {
    val normalized = dragFraction.coerceIn(-1f, 1f)
    val curved = normalized.sign * abs(normalized).pow(1.65f)
    return (curved * maxSeconds.coerceIn(10, 14_400) * 1_000f).toLong()
}
