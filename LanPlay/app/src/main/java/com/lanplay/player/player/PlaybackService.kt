package com.lanplay.player.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.IBinder
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState as SystemPlaybackState
import com.lanplay.player.StartupActivity
import com.lanplay.player.data.db.DatabaseBootstrap
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 播放期间的前台服务。它只负责让长时间播放在退到桌面、息屏后仍是系统认可的
 * 媒体任务；实际播放实例仍由 [PlaybackController] 唯一持有。
 */
@AndroidEntryPoint
class PlaybackService : Service() {

    @Inject lateinit var playbackLazy: Lazy<PlaybackController>
    private val playback get() = playbackLazy.get()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var mediaSession: MediaSession
    private lateinit var notifications: NotificationManager

    override fun onCreate() {
        super.onCreate()
        if (!DatabaseBootstrap.isPrepared()) {
            stopSelf()
            return
        }
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "正在播放",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "在后台继续播放局域网视频"
                setShowBadge(false)
            }
        )
        mediaSession = MediaSession(this, "LanPlay").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = playback.play()
                override fun onPause() = playback.pause()
                override fun onSkipToNext() = playback.playAdjacent(1)
                override fun onSkipToPrevious() = playback.playAdjacent(-1)
                override fun onSeekTo(pos: Long) = playback.seekTo(pos)
                override fun onStop() {
                    startService(
                        Intent(this@PlaybackService, PlaybackService::class.java)
                            .setAction(ACTION_STOP)
                    )
                }
            })
            isActive = true
        }
        scope.launch {
            combine(playback.session, playback.state) { session, state ->
                session to state
            }.collect { (session, state) ->
                    // 通知、锁屏和蓝牙设备均不暴露私人媒体文件名。
                    val title = if (session != null) "正在播放" else "LanPlay"
                    mediaSession.setMetadata(
                        MediaMetadata.Builder()
                            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                            .putString(MediaMetadata.METADATA_KEY_ARTIST, "局域网共享")
                            .putLong(MediaMetadata.METADATA_KEY_DURATION, playback.durationMs)
                            .build()
                    )
                    val systemState = when (state) {
                        PlaybackState.PLAYING -> SystemPlaybackState.STATE_PLAYING
                        PlaybackState.PAUSED, PlaybackState.READY ->
                            SystemPlaybackState.STATE_PAUSED
                        PlaybackState.BUFFERING -> SystemPlaybackState.STATE_BUFFERING
                        PlaybackState.ENDED -> SystemPlaybackState.STATE_STOPPED
                        PlaybackState.ERROR -> SystemPlaybackState.STATE_ERROR
                        else -> SystemPlaybackState.STATE_NONE
                    }
                    mediaSession.setPlaybackState(
                        SystemPlaybackState.Builder()
                            .setActions(
                                SystemPlaybackState.ACTION_PLAY or
                                    SystemPlaybackState.ACTION_PAUSE or
                                    SystemPlaybackState.ACTION_PLAY_PAUSE or
                                    SystemPlaybackState.ACTION_SEEK_TO or
                                    SystemPlaybackState.ACTION_SKIP_TO_NEXT or
                                    SystemPlaybackState.ACTION_SKIP_TO_PREVIOUS or
                                    SystemPlaybackState.ACTION_STOP
                            )
                            .setState(
                                systemState,
                                playback.positionMs,
                                session?.playbackSpeed ?: 1f,
                            )
                            .build()
                    )
                    if (session != null) {
                        notifications.notify(NOTIFICATION_ID, notification(title))
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!DatabaseBootstrap.isPrepared() || !::mediaSession.isInitialized) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_PAUSE -> playback.pause()
            ACTION_PLAY -> playback.play()
            ACTION_PREVIOUS -> playback.playAdjacent(-1)
            ACTION_NEXT -> playback.playAdjacent(1)
            ACTION_STOP -> {
                scope.launch {
                    try {
                        playback.stop(fromService = true)
                    } finally {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
                return START_NOT_STICKY
            }
        }
        startForeground(
            NOTIFICATION_ID,
            notification(if (playback.session.value != null) "正在播放" else "LanPlay"),
        )
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (::mediaSession.isInitialized) {
            if (playback.session.value != null) playback.requestStop(fromService = true)
            mediaSession.isActive = false
            mediaSession.release()
        }
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun notification(title: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, StartupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(action: String, request: Int): PendingIntent = PendingIntent.getService(
            this,
            request,
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun notificationAction(icon: Int, label: String, intent: PendingIntent) =
            Notification.Action.Builder(Icon.createWithResource(this, icon), label, intent).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText("来自局域网共享")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(
                notificationAction(
                    android.R.drawable.ic_media_previous,
                    "上一个",
                    action(ACTION_PREVIOUS, 2),
                )
            )
            .addAction(
                notificationAction(
                    if (playback.state.value == PlaybackState.PLAYING)
                        android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (playback.state.value == PlaybackState.PLAYING) "暂停" else "继续",
                    action(
                        if (playback.state.value == PlaybackState.PLAYING) ACTION_PAUSE else ACTION_PLAY,
                        3,
                    ),
                )
            )
            .addAction(
                notificationAction(
                    android.R.drawable.ic_media_next,
                    "下一个",
                    action(ACTION_NEXT, 4),
                )
            )
            .addAction(
                notificationAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "退出",
                    action(ACTION_STOP, 5),
                )
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    companion object {
        const val CHANNEL_ID = "lanplay_playback"
        const val NOTIFICATION_ID = 1001
        const val ACTION_PAUSE = "com.lanplay.player.PAUSE"
        const val ACTION_PLAY = "com.lanplay.player.PLAY"
        const val ACTION_STOP = "com.lanplay.player.STOP"
        const val ACTION_PREVIOUS = "com.lanplay.player.PREVIOUS"
        const val ACTION_NEXT = "com.lanplay.player.NEXT"
        const val EXTRA_TITLE = "title"
    }
}
