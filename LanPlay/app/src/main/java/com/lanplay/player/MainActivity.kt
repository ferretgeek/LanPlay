package com.lanplay.player

import android.app.PictureInPictureParams
import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.media.AudioManager
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.lanplay.player.player.PlaybackController
import com.lanplay.player.player.PlaybackService
import com.lanplay.player.data.prefs.AppearanceSettings
import com.lanplay.player.data.prefs.SettingsRepository
import com.lanplay.player.data.BackupRepository
import com.lanplay.player.data.TrashRepository
import com.lanplay.player.data.ServerRepository
import com.lanplay.player.data.db.DatabaseBootstrap
import com.lanplay.player.data.db.WatchRecordDao
import com.lanplay.player.data.crypto.CacheCipher
import com.lanplay.player.core.log.Metric
import com.lanplay.player.ui.BenchmarkGalleryScreen
import com.lanplay.player.ui.HomeScreen
import com.lanplay.player.ui.OnboardingScreen
import com.lanplay.player.ui.player.PlayerScreen
import com.lanplay.player.ui.theme.LanPlayTheme
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var playbackControllerLazy: Lazy<PlaybackController>
    @Inject
    lateinit var settingsRepository: SettingsRepository
    @Inject
    lateinit var trashRepositoryLazy: Lazy<TrashRepository>
    @Inject
    lateinit var serverRepositoryLazy: Lazy<ServerRepository>
    @Inject
    lateinit var watchRecordDaoLazy: Lazy<WatchRecordDao>
    @Inject
    lateinit var backupRepositoryLazy: Lazy<BackupRepository>

    private val playbackController get() = playbackControllerLazy.get()
    private val trashRepository get() = trashRepositoryLazy.get()
    private val serverRepository get() = serverRepositoryLazy.get()
    private val watchRecordDao get() = watchRecordDaoLazy.get()
    private val backupRepository get() = backupRepositoryLazy.get()
    private var initialTab = 0
    private var initialActorScreen = false
    private var benchmarkGallery = false
    private val benchmarkRunId = mutableLongStateOf(0L)
    private val pipMode = mutableStateOf(false)
    private val appUnlocked = mutableStateOf(false)
    private val appLockStateResolved = mutableStateOf(false)
    private val pinError = mutableStateOf<String?>(null)
    private val pinChecking = mutableStateOf(false)
    private val continueShortcutRequested = mutableStateOf(false)
    private var authenticationInProgress = false
    private var volumeSoftLimit = 0.5f
    private var volumeKeyArmedUntil = 0L
    private var volumeKeyReleasedAfterLimit = false
    private var volumeKeyUnlockedThisPress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // 只允许 debug 自动唤醒测试机；release 不在锁屏上暴露媒体内容。
        if (BuildConfig.DEBUG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        val benchmarkEnabledForBuild = isBenchmarkEnabledForBuild()
        val benchmarkRequested = benchmarkEnabledForBuild &&
            intent?.getBooleanExtra(EXTRA_BENCHMARK_GALLERY, false) == true
        if (benchmarkRequested) {
            // 必须在 Window 首次附着前决定；Android 16 可能继续保护先安全后清除的 Surface。
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
        initialTab = when (intent?.getStringExtra("screen")) {
            "history" -> 1
            "continue" -> 1
            "cleanup" -> 2
            "trash" -> 3
            "settings" -> 4
            else -> 0
        }
        continueShortcutRequested.value = intent?.getStringExtra("screen") == "continue"
        initialActorScreen = intent?.getStringExtra("screen") == "actors"
        benchmarkGallery = benchmarkRequested
        benchmarkRunId.longValue = if (benchmarkEnabledForBuild) {
            intent?.getLongExtra(EXTRA_BENCHMARK_RUN, 0L) ?: 0L
        } else {
            0L
        }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (!DatabaseBootstrap.isPrepared() || DatabaseBootstrap.failureMessage() != null) {
            val recovery = Intent(this, StartupActivity::class.java)
            if (DatabaseBootstrap.failureMessage() != null) {
                recovery.putExtra(StartupActivity.EXTRA_FORCE_RECOVERY, true)
            }
            startActivity(recovery)
            finish()
            return
        }
        lifecycleScope.launch {
            settingsRepository.playerSettings.collectLatest {
                volumeSoftLimit = it.volumeSoftLimitPercent
            }
        }
        if (CacheCipher.consumeDatabaseRecoveryNotice(this)) {
            Toast.makeText(
                this,
                "旧数据库已完整保留，并已创建新的空数据库",
                Toast.LENGTH_LONG,
            ).show()
        }
        intent.getStringExtra(EXTRA_RECOVERY_BACKUP_URI)?.let { value ->
            intent.removeExtra(EXTRA_RECOVERY_BACKUP_URI)
            val uri = Uri.parse(value)
            lifecycleScope.launch {
                runCatching { backupRepository.importFrom(uri) }
                    .onSuccess { result ->
                        Toast.makeText(
                            this@MainActivity,
                            "恢复完成：${result.records} 条记录；请重新填写服务器密码",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    .onFailure {
                        Toast.makeText(
                            this@MainActivity,
                            it.message ?: "备份导入失败；旧数据库仍保留在私有恢复目录",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
            }
        }
        setContent {
            val appearance by settingsRepository.appearanceSettings.collectAsStateWithLifecycle(
                initialValue = AppearanceSettings()
            )
            LanPlayTheme(appearance) {
                Surface(Modifier.fillMaxSize()) { Root() }
            }
        }
        lifecycleScope.launch {
            // 不阻塞冷启动；只有用户明确选择了保留天数才会执行永久删除。
            delay(5_000)
            val days = settingsRepository.currentCacheSettings().trashRetentionDays
            if (days > 0) {
                serverRepository.listAll().forEach { server ->
                    currentCoroutineContext().ensureActive()
                    try {
                        trashRepository.purgeExpired(server.id, server.target, days)
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        Metric.error(
                            "TRASH_AUTO_CLEANUP",
                            t.message ?: "回收站自动清理失败",
                            "server" to server.id,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra("screen") == "continue") {
            continueShortcutRequested.value = true
        }
        if (isBenchmarkEnabledForBuild() &&
            intent.getBooleanExtra(EXTRA_BENCHMARK_GALLERY, false)
        ) {
            benchmarkGallery = true
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            benchmarkRunId.longValue =
                intent.getLongExtra(EXTRA_BENCHMARK_RUN, System.nanoTime())
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (playbackController.session.value != null &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            val audio = getSystemService(AUDIO_SERVICE) as AudioManager
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (current - 1).coerceAtLeast(0), 0)
                return true
            }
            val limitStep = (volumeSoftLimit * max).toInt().coerceAtLeast(1)
            val canPass = volumeSoftLimit == 0f ||
                (volumeKeyReleasedAfterLimit && System.currentTimeMillis() <= volumeKeyArmedUntil)
            if (!canPass && current >= limitStep) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, limitStep, 0)
                volumeKeyArmedUntil = System.currentTimeMillis() + 5_000L
                volumeKeyReleasedAfterLimit = false
                volumeKeyUnlockedThisPress = false
                Toast.makeText(
                    this,
                    "已达安全上限，再按一次音量上键可继续",
                    Toast.LENGTH_SHORT,
                ).show()
                @Suppress("DEPRECATION")
                window.decorView.performHapticFeedback(
                    android.view.HapticFeedbackConstants.LONG_PRESS
                )
                return true
            }
            if (canPass && volumeSoftLimit != 0f && current >= limitStep) {
                volumeKeyUnlockedThisPress = true
            }
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (current + 1).coerceAtMost(max), 0)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (playbackController.session.value != null && keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (volumeKeyUnlockedThisPress) {
                volumeKeyArmedUntil = 0L
                volumeKeyReleasedAfterLimit = false
                volumeKeyUnlockedThisPress = false
            } else if (volumeKeyArmedUntil > System.currentTimeMillis()) {
                volumeKeyReleasedAfterLimit = true
            }
            return true
        }
        if (playbackController.session.value != null && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    @Composable
    private fun Root() {
        // 只由本项目 Macrobenchmark 的显式 Intent 使用。自动滚动由应用自身驱动，
        // 绕开部分 Android 16 厂商系统禁止 UIAutomator 注入触摸事件的问题。
        if (benchmarkGallery) {
            key(benchmarkRunId.longValue) { BenchmarkGalleryScreen() }
            return
        }
        if (!appLockStateResolved.value) {
            // DataStore 尚未给出应用锁状态时保持中性底色，避免未启用应用锁也闪现“已锁定”。
            return
        }
        if (!appUnlocked.value) {
            AppLockScreen(
                error = pinError.value,
                checking = pinChecking.value,
                onBiometric = ::showAuthentication,
                onPin = ::unlockWithPin,
            )
            return
        }
        val onboardingCompleted by settingsRepository.onboardingCompleted
            .collectAsStateWithLifecycle(initialValue = false)
        if (!onboardingCompleted) {
            OnboardingScreen(
                onComplete = {
                    lifecycleScope.launch { settingsRepository.setOnboardingCompleted() }
                },
                onThemeSelected = {
                    lifecycleScope.launch { settingsRepository.setThemeId(it) }
                },
                onDarkModeSelected = {
                    lifecycleScope.launch { settingsRepository.setDarkMode(it) }
                },
            )
            return
        }
        val session by playbackController.session.collectAsStateWithLifecycle()
        val view = LocalView.current
        LaunchedEffect(continueShortcutRequested.value, onboardingCompleted) {
            if (continueShortcutRequested.value && onboardingCompleted) {
                val recent = withContext(Dispatchers.IO) {
                    serverRepository.current()?.let {
                        watchRecordDao.mostRecentInProgress(it.id)
                    }
                }
                if (recent != null) {
                    runCatching { playbackController.open(recent.fullPath) }
                        .onFailure {
                            Metric.error(
                                "SHORTCUT_CONTINUE",
                                it.message ?: "无法继续观看",
                            )
                        }
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "还没有未看完的视频",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                continueShortcutRequested.value = false
            }
        }

        val activeSession = session
        if (activeSession == null) {
            HomeScreen(initialTab = initialTab, initialActorScreen = initialActorScreen)
        } else {
            LaunchedEffect(activeSession.relativePath) {
                if (
                    !BuildConfig.DEBUG &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        NOTIFICATION_PERMISSION_REQUEST,
                    )
                }
                ContextCompat.startForegroundService(
                    this@MainActivity,
                    android.content.Intent(this@MainActivity, PlaybackService::class.java)
                        .putExtra(PlaybackService.EXTRA_TITLE, activeSession.fileName),
                )
            }
            // 播放期间保持屏幕常亮并进入沉浸式，退出时还原
            DisposableEffect(Unit) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val controller = WindowCompat.getInsetsController(window, view)
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
                updatePictureInPictureParams(true, view)
                onDispose {
                    updatePictureInPictureParams(false, view)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    controller.show(WindowInsetsCompat.Type.systemBars())
                }
            }
            PlayerScreen(
                controller = playbackController,
                session = activeSession,
                isPip = pipMode.value,
                onExit = { playbackController.requestStop() },
            )
        }
    }

    override fun onDestroy() {
        if (isFinishing) playbackController.requestStop()
        super.onDestroy()
    }

    override fun onStop() {
        if (!authenticationInProgress && !isChangingConfigurations) {
            lastBackgroundAtMs = System.currentTimeMillis()
        }
        playbackController.requestPersist()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            val privacy = settingsRepository.currentPrivacySettings()
            if (!privacy.appLockEnabled) {
                appUnlocked.value = true
                appLockStateResolved.value = true
                return@launch
            }
            if (!privacy.pinConfigured) {
                // 兼容曾短暂存在的无 PIN 预览版设置，避免用户被永久锁在门外。
                settingsRepository.setAppLockEnabled(false)
                appUnlocked.value = true
                appLockStateResolved.value = true
                return@launch
            }
            val withinGrace = appUnlocked.value &&
                lastBackgroundAtMs > 0L &&
                System.currentTimeMillis() - lastBackgroundAtMs <
                privacy.lockGraceSeconds * 1_000L
            appLockStateResolved.value = true
            if (!withinGrace && !authenticationInProgress) {
                appUnlocked.value = false
                showAuthentication()
            }
        }
    }

    private fun showAuthentication() {
        if (authenticationInProgress || isFinishing) return
        authenticationInProgress = true
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult,
                ) {
                    authenticationInProgress = false
                    lastBackgroundAtMs = 0L
                    pinError.value = null
                    appUnlocked.value = true
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    authenticationInProgress = false
                    appUnlocked.value = false
                    pinError.value = "也可以使用应用锁 PIN"
                }
            },
        )
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle("解锁 LanPlay")
            .setSubtitle("验证身份后查看私人媒体")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        prompt.authenticate(builder.build())
    }

    private fun unlockWithPin(pin: String) {
        if (pinChecking.value) return
        pinChecking.value = true
        lifecycleScope.launch {
            try {
                if (settingsRepository.verifyAppLockPin(pin)) {
                    pinError.value = null
                    lastBackgroundAtMs = 0L
                    appUnlocked.value = true
                } else {
                    val retryMs = settingsRepository.appLockRetryAfterMs()
                    pinError.value = if (retryMs > 0) {
                        "PIN 不正确，请在 ${((retryMs + 999) / 1_000)} 秒后重试"
                    } else {
                        "PIN 不正确，请重试"
                    }
                }
            } finally {
                pinChecking.value = false
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            playbackController.session.value != null &&
            !isInPictureInPictureMode
        ) {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
            )
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipMode.value = isInPictureInPictureMode
    }

    private fun updatePictureInPictureParams(enabled: Boolean, sourceView: android.view.View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val sourceRect = Rect()
        sourceView.getGlobalVisibleRect(sourceRect)
        setPictureInPictureParams(
            PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .setAutoEnterEnabled(enabled)
                .apply {
                    if (enabled && !sourceRect.isEmpty) setSourceRectHint(sourceRect)
                }
                .build()
        )
    }

    companion object {
        const val EXTRA_BENCHMARK_GALLERY = "benchmark_gallery"
        const val EXTRA_BENCHMARK_RUN = "benchmark_run"
        const val EXTRA_RECOVERY_BACKUP_URI = "recovery_backup_uri"
        const val EXTRA_RECOVERY_ID = "recovery_id"
        const val NOTIFICATION_PERMISSION_REQUEST = 1001
        var lastBackgroundAtMs: Long = 0L
    }

    private fun isBenchmarkEnabledForBuild(): Boolean =
        BuildConfig.DEBUG ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                applicationInfo.isProfileableByShell)
}

@Composable
private fun AppLockScreen(
    error: String?,
    checking: Boolean,
    onBiometric: () -> Unit,
    onPin: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Rounded.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            "LanPlay 已锁定",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 18.dp),
        )
        Text(
            "私人媒体内容受到保护",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter(Char::isDigit).take(8) },
            label = { Text("应用锁 PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            supportingText = { error?.let { Text(it) } },
            isError = error?.startsWith("PIN 不正确") == true,
            singleLine = true,
        )
        Button(
            onClick = { onPin(pin) },
            enabled = pin.length in 4..8 && !checking,
            modifier = Modifier.padding(top = 10.dp),
        ) { Text(if (checking) "正在验证…" else "解锁") }
        TextButton(onClick = onBiometric, modifier = Modifier.padding(top = 4.dp)) {
            Text("使用指纹、面容或系统密码")
        }
    }
}
