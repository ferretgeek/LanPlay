package com.lanplay.player.data.prefs

import android.content.Context
import com.lanplay.player.data.BackupSettings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import android.util.Base64
import android.content.ComponentName
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "lanplay_settings")

/** 解码器档位（需求 P-17 / 播放器规格 §3.2） */
enum class DecoderMode { HW, HW_PLUS, SW }
enum class PlayerKernel { MEDIA3, VLC }
enum class ResumePolicy { ALWAYS, ASK, NEVER }
enum class OrientationMode { AUTO, FORCE_LANDSCAPE, FORCE_PORTRAIT }
enum class DarkMode { FOLLOW_SYSTEM, LIGHT, DARK }
enum class SubtitleFont { SANS, SERIF, MONOSPACE }
enum class GestureRegion { FULL, LOWER_TWO_THIRDS, LOWER_HALF, MIDDLE_SIXTY }
enum class Handedness { LEFT, CENTER, RIGHT }
enum class HomeLayout { GALLERY, DASHBOARD }

/**
 * IO 与解码参数快照。播放会话开始时读一次，避免每块读取都走 Flow。
 * 三个 IO 参数对应需求 X-02。
 *
 * 读块默认值偏离需求 §15.6 的 1 MB，改为 512 KB：实测 4K 首帧 1679 → 932 ms（快 45%），
 * 缓冲水位不受影响（仍稳定 30 s 以上）。原因是首帧要等预读窗口的初始 8 个块就位，
 * 块越小这段等待越短。已向用户报备并确认。
 */
data class IoSettings(
    val prefetchMb: Int = 48,
    val readBlockKb: Int = 512,
    val concurrentReads: Int = 6,
    val decoderMode: DecoderMode = DecoderMode.HW,
) {
    val prefetchBytes: Long get() = prefetchMb.toLong() * 1024L * 1024L
    val readBlockBytes: Int get() = readBlockKb * 1024
}

/** 第 2 阶段需要跨视频、跨进程记忆的用户设置。 */
data class PlayerSettings(
    val showAllFiles: Boolean = false,
    val resumePolicy: ResumePolicy = ResumePolicy.ALWAYS,
    val seekSensitivitySeconds: Int = 90,
    val lastVolumePercent: Float = 0.5f,
    val lastBrightnessPercent: Float = 0.5f,
    val volumeSoftLimitPercent: Float = 0.5f,
    val brightnessSoftLimitPercent: Float = 0.5f,
    val volumeSensitivityPercent: Int = 100,
    val brightnessSensitivityPercent: Int = 100,
    val softLimitArmedSeconds: Int = 5,
    val hapticEnabled: Boolean = true,
    val orientationMode: OrientationMode = OrientationMode.AUTO,
    val playerKernel: PlayerKernel = PlayerKernel.MEDIA3,
    val doubleTapSeconds: Int = 10,
    val doubleTapCenterPause: Boolean = true,
    val longPressSpeed: Float = 2f,
    val horizontalSeekEnabled: Boolean = true,
    val verticalAdjustEnabled: Boolean = true,
    val precisionSeekEnabled: Boolean = true,
    val transformGestureEnabled: Boolean = true,
    val subtitleOffsetGestureEnabled: Boolean = false,
    val gestureRegion: GestureRegion = GestureRegion.FULL,
    val systemEdgeExclusion: Boolean = true,
    val seekPreviewEnabled: Boolean = false,
    /** 播完先保留最后一帧和操作面板，30 秒无人操作后再连播。 */
    val autoPlayNext: Boolean = true,
    val fadePlayback: Boolean = true,
    val handedness: Handedness = Handedness.RIGHT,
)

data class AppearanceSettings(
    val themeId: String = "mist",
    val darkMode: DarkMode = DarkMode.FOLLOW_SYSTEM,
    val homeLayout: HomeLayout = HomeLayout.GALLERY,
)

data class PrivacySettings(
    val appLockEnabled: Boolean = false,
    /** 0=立即；60=1 分钟；300=5 分钟。 */
    val lockGraceSeconds: Int = 60,
    val blurArtwork: Boolean = false,
    val pinConfigured: Boolean = false,
    val disguiseEnabled: Boolean = false,
)

data class CacheSettings(
    val imageCacheLimitMb: Int = 1024,
    /** 0 表示从不自动彻底删除。 */
    val trashRetentionDays: Int = 0,
)

data class SubtitleStyleSettings(
    /**
     * 75% 对应播放器高度约 3.9%，在 6~7 英寸手机横屏上约为 15~17sp。
     * 100% 作为可选的大字号保留，但不再作为默认值，避免两行字幕遮住过多画面。
     */
    val sizePercent: Int = 75,
    val textColor: String = "#FFFFFF",
    val edgeColor: String = "#000000",
    val edgeWidth: Int = 2,
    val backgroundEnabled: Boolean = false,
    val bottomPaddingPercent: Int = 8,
    val font: SubtitleFont = SubtitleFont.SANS,
)

data class AudioEnhancementSettings(
    /** 播放器内部增益；100% 不放大，最高 200%。 */
    val volumeBoostPercent: Int = 100,
    val loudnessNormalization: Boolean = false,
    /** flat / voice / cinema / bass / treble / custom */
    val equalizerPreset: String = "flat",
    /** VLC 10 段 EQ，单位 dB，范围 -12～+12。 */
    val equalizerBands: List<Float> = List(10) { 0f },
)

@Singleton
class SettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val store get() = context.settingsDataStore
    private val pinMutex = Mutex()

    object Keys {
        val PREFETCH_MB = intPreferencesKey("prefetchMb")
        val READ_BLOCK_KB = intPreferencesKey("readBlockKb")
        val CONCURRENT_READS = intPreferencesKey("concurrentReads")
        val DECODER_MODE = stringPreferencesKey("decoderMode")
        val SHOW_ALL_FILES = booleanPreferencesKey("showAllFiles")
        val RESUME_POLICY = stringPreferencesKey("resumePolicy")
        val SEEK_SENSITIVITY_SECONDS = intPreferencesKey("seekSensitivitySeconds")
        val LAST_VOLUME_PERCENT = floatPreferencesKey("lastVolumePercent")
        val LAST_BRIGHTNESS_PERCENT = floatPreferencesKey("lastBrightnessPercent")
        val VOLUME_SOFT_LIMIT_PERCENT = floatPreferencesKey("volumeSoftLimitPercent")
        val BRIGHTNESS_SOFT_LIMIT_PERCENT = floatPreferencesKey("brightnessSoftLimitPercent")
        val VOLUME_SENSITIVITY_PERCENT = intPreferencesKey("volumeSensitivityPercent")
        val BRIGHTNESS_SENSITIVITY_PERCENT = intPreferencesKey("brightnessSensitivityPercent")
        val SOFT_LIMIT_ARMED_SECONDS = intPreferencesKey("softLimitArmedSeconds")
        val HAPTIC_ENABLED = booleanPreferencesKey("hapticEnabled")
        val ORIENTATION_MODE = stringPreferencesKey("orientationMode")
        val PLAYER_KERNEL = stringPreferencesKey("playerKernel")
        val THEME_ID = stringPreferencesKey("themeId")
        val DARK_MODE = stringPreferencesKey("darkMode")
        val APP_LOCK_ENABLED = booleanPreferencesKey("appLockEnabled")
        val LOCK_GRACE_SECONDS = intPreferencesKey("lockGraceSeconds")
        val IMAGE_CACHE_LIMIT_MB = intPreferencesKey("imageCacheLimitMb")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboardingCompleted")
        val TRASH_RETENTION_DAYS = intPreferencesKey("trashRetentionDays")
        val BLUR_ARTWORK = booleanPreferencesKey("blurArtwork")
        val APP_LOCK_PIN_SALT = stringPreferencesKey("appLockPinSalt")
        val APP_LOCK_PIN_HASH = stringPreferencesKey("appLockPinHash")
        val APP_LOCK_PIN_KDF_VERSION = intPreferencesKey("appLockPinKdfVersion")
        val APP_LOCK_PIN_FAILED_ATTEMPTS = intPreferencesKey("appLockPinFailedAttempts")
        val APP_LOCK_PIN_NEXT_ALLOWED_AT = longPreferencesKey("appLockPinNextAllowedAt")
        val SUBTITLE_SIZE_PERCENT = intPreferencesKey("subtitleSizePercent")
        val SUBTITLE_TEXT_COLOR = stringPreferencesKey("subtitleTextColor")
        val SUBTITLE_EDGE_COLOR = stringPreferencesKey("subtitleEdgeColor")
        val SUBTITLE_EDGE_WIDTH = intPreferencesKey("subtitleEdgeWidth")
        val SUBTITLE_BACKGROUND = booleanPreferencesKey("subtitleBackground")
        val SUBTITLE_BOTTOM_PADDING = intPreferencesKey("subtitleBottomPadding")
        val SUBTITLE_FONT = stringPreferencesKey("subtitleFont")
        val DOUBLE_TAP_SECONDS = intPreferencesKey("doubleTapSeconds")
        val DOUBLE_TAP_CENTER_PAUSE = booleanPreferencesKey("doubleTapCenterPause")
        val LONG_PRESS_SPEED = floatPreferencesKey("longPressSpeed")
        val HORIZONTAL_SEEK_ENABLED = booleanPreferencesKey("horizontalSeekEnabled")
        val VERTICAL_ADJUST_ENABLED = booleanPreferencesKey("verticalAdjustEnabled")
        val PRECISION_SEEK_ENABLED = booleanPreferencesKey("precisionSeekEnabled")
        val TRANSFORM_GESTURE_ENABLED = booleanPreferencesKey("transformGestureEnabled")
        val SUBTITLE_OFFSET_GESTURE_ENABLED =
            booleanPreferencesKey("subtitleOffsetGestureEnabled")
        val GESTURE_REGION = stringPreferencesKey("gestureRegion")
        val SYSTEM_EDGE_EXCLUSION = booleanPreferencesKey("systemEdgeExclusion")
        val SEEK_PREVIEW_ENABLED = booleanPreferencesKey("seekPreviewEnabled")
        val AUTO_PLAY_NEXT = booleanPreferencesKey("autoPlayNext")
        val FADE_PLAYBACK = booleanPreferencesKey("fadePlayback")
        val HANDEDNESS = stringPreferencesKey("handedness")
        val HOME_LAYOUT = stringPreferencesKey("homeLayout")
        val MERGED_DIRECTORIES = stringSetPreferencesKey("mergedDirectories")
        val DISGUISE_ENABLED = booleanPreferencesKey("disguiseEnabled")
        val AUDIO_BOOST_PERCENT = intPreferencesKey("audioBoostPercent")
        val LOUDNESS_NORMALIZATION = booleanPreferencesKey("loudnessNormalization")
        val EQUALIZER_PRESET = stringPreferencesKey("equalizerPreset")
        val EQUALIZER_BANDS = stringPreferencesKey("equalizerBands")
    }

    val ioSettings: Flow<IoSettings> = store.data.map { p ->
        IoSettings(
            prefetchMb = p[Keys.PREFETCH_MB] ?: 48,
            readBlockKb = p[Keys.READ_BLOCK_KB] ?: 512,
            concurrentReads = p[Keys.CONCURRENT_READS] ?: 6,
            decoderMode = runCatching { DecoderMode.valueOf(p[Keys.DECODER_MODE] ?: "HW") }
                .getOrDefault(DecoderMode.HW),
        )
    }

    suspend fun currentIoSettings(): IoSettings = ioSettings.first()

    /** 已由 BackupRepository 完整校验；单次 edit 保证设置不会半导入。 */
    internal suspend fun importValidatedBackupSettings(value: BackupSettings) {
        store.edit { p ->
            p[Keys.THEME_ID] = value.theme
            p[Keys.DARK_MODE] = value.darkMode
            p[Keys.HOME_LAYOUT] = value.homeLayout
            p[Keys.RESUME_POLICY] = value.resumePolicy
            p[Keys.SEEK_SENSITIVITY_SECONDS] = value.seekSeconds
            p[Keys.DOUBLE_TAP_SECONDS] = value.doubleTapSeconds
            p[Keys.LONG_PRESS_SPEED] = value.longPressSpeed
            p[Keys.AUTO_PLAY_NEXT] = value.autoPlayNext
            p[Keys.FADE_PLAYBACK] = value.fadePlayback
            p[Keys.SEEK_PREVIEW_ENABLED] = value.seekPreviewEnabled
            p[Keys.AUDIO_BOOST_PERCENT] = value.audioBoostPercent
            p[Keys.LOUDNESS_NORMALIZATION] = value.loudnessNormalization
            p[Keys.EQUALIZER_PRESET] = value.equalizerPreset
            p[Keys.EQUALIZER_BANDS] = value.equalizerBands.joinToString(",")
            p[Keys.HANDEDNESS] = value.handedness
            p[Keys.PLAYER_KERNEL] = value.playerKernel
            p[Keys.ORIENTATION_MODE] = value.orientation
            p[Keys.PREFETCH_MB] = value.prefetchMb
            p[Keys.READ_BLOCK_KB] = value.readBlockKb
            p[Keys.CONCURRENT_READS] = value.concurrentReads
            p[Keys.DECODER_MODE] = value.decoderMode
            p[Keys.SUBTITLE_SIZE_PERCENT] = value.subtitleSize
            p[Keys.SUBTITLE_TEXT_COLOR] = value.subtitleTextColor
            p[Keys.SUBTITLE_EDGE_COLOR] = value.subtitleEdgeColor
            p[Keys.SUBTITLE_EDGE_WIDTH] = value.subtitleEdgeWidth
            p[Keys.SUBTITLE_BACKGROUND] = value.subtitleBackground
            p[Keys.SUBTITLE_BOTTOM_PADDING] = value.subtitleBottom
            p[Keys.SUBTITLE_FONT] = value.subtitleFont
        }
    }

    val playerSettings: Flow<PlayerSettings> = store.data.map { p ->
        PlayerSettings(
            showAllFiles = p[Keys.SHOW_ALL_FILES] ?: false,
            resumePolicy = runCatching {
                ResumePolicy.valueOf(p[Keys.RESUME_POLICY] ?: ResumePolicy.ALWAYS.name)
            }.getOrDefault(ResumePolicy.ALWAYS),
            seekSensitivitySeconds = (p[Keys.SEEK_SENSITIVITY_SECONDS] ?: 90)
                .coerceIn(MIN_SEEK_RANGE_SECONDS, MAX_SEEK_RANGE_SECONDS),
            lastVolumePercent = (p[Keys.LAST_VOLUME_PERCENT] ?: 0.5f).coerceIn(0f, 1f),
            lastBrightnessPercent = (p[Keys.LAST_BRIGHTNESS_PERCENT] ?: 0.5f).coerceIn(0.01f, 1f),
            volumeSoftLimitPercent = (p[Keys.VOLUME_SOFT_LIMIT_PERCENT] ?: 0.5f).coerceIn(0f, 1f),
            brightnessSoftLimitPercent =
                (p[Keys.BRIGHTNESS_SOFT_LIMIT_PERCENT] ?: 0.5f).coerceIn(0f, 1f),
            volumeSensitivityPercent = (p[Keys.VOLUME_SENSITIVITY_PERCENT] ?: 100)
                .takeIf { it in GESTURE_SENSITIVITY_PRESETS } ?: 100,
            brightnessSensitivityPercent = (p[Keys.BRIGHTNESS_SENSITIVITY_PERCENT] ?: 100)
                .takeIf { it in GESTURE_SENSITIVITY_PRESETS } ?: 100,
            softLimitArmedSeconds = p[Keys.SOFT_LIMIT_ARMED_SECONDS] ?: 5,
            hapticEnabled = p[Keys.HAPTIC_ENABLED] ?: true,
            orientationMode = runCatching {
                OrientationMode.valueOf(p[Keys.ORIENTATION_MODE] ?: OrientationMode.AUTO.name)
            }.getOrDefault(OrientationMode.AUTO),
            playerKernel = runCatching {
                PlayerKernel.valueOf(p[Keys.PLAYER_KERNEL] ?: PlayerKernel.MEDIA3.name)
            }.getOrDefault(PlayerKernel.MEDIA3),
            doubleTapSeconds = (p[Keys.DOUBLE_TAP_SECONDS] ?: 10)
                .takeIf { it in intArrayOf(5, 10, 15, 30) } ?: 10,
            doubleTapCenterPause = p[Keys.DOUBLE_TAP_CENTER_PAUSE] ?: true,
            longPressSpeed = (p[Keys.LONG_PRESS_SPEED] ?: 2f)
                .takeIf { it in listOf(1.5f, 2f, 2.5f, 3f) } ?: 2f,
            horizontalSeekEnabled = p[Keys.HORIZONTAL_SEEK_ENABLED] ?: true,
            verticalAdjustEnabled = p[Keys.VERTICAL_ADJUST_ENABLED] ?: true,
            precisionSeekEnabled = p[Keys.PRECISION_SEEK_ENABLED] ?: true,
            transformGestureEnabled = p[Keys.TRANSFORM_GESTURE_ENABLED] ?: true,
            subtitleOffsetGestureEnabled =
                p[Keys.SUBTITLE_OFFSET_GESTURE_ENABLED] ?: false,
            gestureRegion = runCatching {
                GestureRegion.valueOf(p[Keys.GESTURE_REGION] ?: GestureRegion.FULL.name)
            }.getOrDefault(GestureRegion.FULL),
            systemEdgeExclusion = p[Keys.SYSTEM_EDGE_EXCLUSION] ?: true,
            seekPreviewEnabled = p[Keys.SEEK_PREVIEW_ENABLED] ?: false,
            autoPlayNext = p[Keys.AUTO_PLAY_NEXT] ?: true,
            fadePlayback = p[Keys.FADE_PLAYBACK] ?: true,
            handedness = runCatching {
                Handedness.valueOf(p[Keys.HANDEDNESS] ?: Handedness.RIGHT.name)
            }.getOrDefault(Handedness.RIGHT),
        )
    }

    suspend fun currentPlayerSettings(): PlayerSettings = playerSettings.first()

    val audioEnhancementSettings: Flow<AudioEnhancementSettings> = store.data.map { p ->
        val bands = p[Keys.EQUALIZER_BANDS]
            ?.split(',')
            ?.mapNotNull { it.toFloatOrNull()?.coerceIn(-12f, 12f) }
            ?.takeIf { it.size == 10 }
            ?: List(10) { 0f }
        AudioEnhancementSettings(
            volumeBoostPercent = (p[Keys.AUDIO_BOOST_PERCENT] ?: 100).coerceIn(100, 200),
            loudnessNormalization = p[Keys.LOUDNESS_NORMALIZATION] ?: false,
            equalizerPreset = p[Keys.EQUALIZER_PRESET] ?: "flat",
            equalizerBands = bands,
        )
    }

    suspend fun currentAudioEnhancementSettings(): AudioEnhancementSettings =
        audioEnhancementSettings.first()

    suspend fun setAudioBoostPercent(value: Int) {
        store.edit { it[Keys.AUDIO_BOOST_PERCENT] = value.coerceIn(100, 200) }
    }

    suspend fun setLoudnessNormalization(value: Boolean) {
        store.edit { it[Keys.LOUDNESS_NORMALIZATION] = value }
    }

    suspend fun setEqualizerPreset(value: String, bands: List<Float>) {
        val normalized = bands.take(10).map { it.coerceIn(-12f, 12f) }
            .let { it + List((10 - it.size).coerceAtLeast(0)) { 0f } }
        store.edit {
            it[Keys.EQUALIZER_PRESET] = value
            it[Keys.EQUALIZER_BANDS] = normalized.joinToString(",")
        }
    }

    suspend fun setEqualizerBand(index: Int, value: Float) {
        if (index !in 0..9) return
        val bands = currentAudioEnhancementSettings().equalizerBands.toMutableList()
        bands[index] = value.coerceIn(-12f, 12f)
        store.edit {
            it[Keys.EQUALIZER_PRESET] = "custom"
            it[Keys.EQUALIZER_BANDS] = bands.joinToString(",")
        }
    }

    suspend fun setShowAllFiles(value: Boolean) {
        store.edit { it[Keys.SHOW_ALL_FILES] = value }
    }

    suspend fun mergedDirectories(serverId: Long): Set<String> =
        store.data.first()[Keys.MERGED_DIRECTORIES].orEmpty()
            .mapNotNull { encoded ->
                val separator = encoded.indexOf('|')
                if (separator <= 0 || encoded.substring(0, separator).toLongOrNull() != serverId) {
                    null
                } else {
                    encoded.substring(separator + 1)
                }
            }
            .toSet()

    suspend fun setMergedDirectory(serverId: Long, path: String, enabled: Boolean) {
        val encoded = "$serverId|${path.trim('/')}"
        store.edit { preferences ->
            val values = preferences[Keys.MERGED_DIRECTORIES].orEmpty().toMutableSet()
            if (enabled) values += encoded else values -= encoded
            preferences[Keys.MERGED_DIRECTORIES] = values
        }
    }

    suspend fun setResumePolicy(value: ResumePolicy) {
        store.edit { it[Keys.RESUME_POLICY] = value.name }
    }

    suspend fun setSeekSensitivitySeconds(value: Int) {
        require(value in MIN_SEEK_RANGE_SECONDS..MAX_SEEK_RANGE_SECONDS)
        store.edit { it[Keys.SEEK_SENSITIVITY_SECONDS] = value }
    }

    suspend fun setLastVolumePercent(value: Float) {
        store.edit { it[Keys.LAST_VOLUME_PERCENT] = value.coerceIn(0f, 1f) }
    }

    suspend fun setLastBrightnessPercent(value: Float) {
        store.edit { it[Keys.LAST_BRIGHTNESS_PERCENT] = value.coerceIn(0.01f, 1f) }
    }

    suspend fun setOrientationMode(value: OrientationMode) {
        store.edit { it[Keys.ORIENTATION_MODE] = value.name }
    }

    suspend fun setVolumeSoftLimitPercent(value: Float) {
        require(value == 0f || value in 0.4f..0.7f)
        store.edit { it[Keys.VOLUME_SOFT_LIMIT_PERCENT] = value }
    }

    suspend fun setBrightnessSoftLimitPercent(value: Float) {
        require(value == 0f || value in 0.4f..0.7f)
        store.edit { it[Keys.BRIGHTNESS_SOFT_LIMIT_PERCENT] = value }
    }

    suspend fun setVolumeSensitivityPercent(value: Int) {
        require(value in GESTURE_SENSITIVITY_PRESETS)
        store.edit { it[Keys.VOLUME_SENSITIVITY_PERCENT] = value }
    }

    suspend fun setBrightnessSensitivityPercent(value: Int) {
        require(value in GESTURE_SENSITIVITY_PRESETS)
        store.edit { it[Keys.BRIGHTNESS_SENSITIVITY_PERCENT] = value }
    }

    suspend fun setHapticEnabled(value: Boolean) {
        store.edit { it[Keys.HAPTIC_ENABLED] = value }
    }

    suspend fun setPlayerKernel(value: PlayerKernel) {
        store.edit { it[Keys.PLAYER_KERNEL] = value.name }
    }

    suspend fun setDoubleTapSeconds(value: Int) {
        require(value in intArrayOf(5, 10, 15, 30))
        store.edit { it[Keys.DOUBLE_TAP_SECONDS] = value }
    }

    suspend fun setDoubleTapCenterPause(value: Boolean) {
        store.edit { it[Keys.DOUBLE_TAP_CENTER_PAUSE] = value }
    }

    suspend fun setLongPressSpeed(value: Float) {
        require(value in listOf(1.5f, 2f, 2.5f, 3f))
        store.edit { it[Keys.LONG_PRESS_SPEED] = value }
    }

    suspend fun setHorizontalSeekEnabled(value: Boolean) {
        store.edit { it[Keys.HORIZONTAL_SEEK_ENABLED] = value }
    }

    suspend fun setVerticalAdjustEnabled(value: Boolean) {
        store.edit { it[Keys.VERTICAL_ADJUST_ENABLED] = value }
    }

    suspend fun setPrecisionSeekEnabled(value: Boolean) {
        store.edit { it[Keys.PRECISION_SEEK_ENABLED] = value }
    }

    suspend fun setTransformGestureEnabled(value: Boolean) {
        store.edit { it[Keys.TRANSFORM_GESTURE_ENABLED] = value }
    }

    suspend fun setSubtitleOffsetGestureEnabled(value: Boolean) {
        store.edit { it[Keys.SUBTITLE_OFFSET_GESTURE_ENABLED] = value }
    }

    suspend fun setGestureRegion(value: GestureRegion) {
        store.edit { it[Keys.GESTURE_REGION] = value.name }
    }

    suspend fun setSystemEdgeExclusion(value: Boolean) {
        store.edit { it[Keys.SYSTEM_EDGE_EXCLUSION] = value }
    }

    suspend fun setSeekPreviewEnabled(value: Boolean) {
        store.edit { it[Keys.SEEK_PREVIEW_ENABLED] = value }
    }

    suspend fun setAutoPlayNext(value: Boolean) {
        store.edit { it[Keys.AUTO_PLAY_NEXT] = value }
    }

    suspend fun setFadePlayback(value: Boolean) {
        store.edit { it[Keys.FADE_PLAYBACK] = value }
    }

    suspend fun setHandedness(value: Handedness) {
        store.edit { it[Keys.HANDEDNESS] = value.name }
    }

    suspend fun resetGestureSettings() {
        store.edit {
            it.remove(Keys.SEEK_SENSITIVITY_SECONDS)
            it.remove(Keys.DOUBLE_TAP_SECONDS)
            it.remove(Keys.DOUBLE_TAP_CENTER_PAUSE)
            it.remove(Keys.LONG_PRESS_SPEED)
            it.remove(Keys.HORIZONTAL_SEEK_ENABLED)
            it.remove(Keys.VERTICAL_ADJUST_ENABLED)
            it.remove(Keys.PRECISION_SEEK_ENABLED)
            it.remove(Keys.TRANSFORM_GESTURE_ENABLED)
            it.remove(Keys.SUBTITLE_OFFSET_GESTURE_ENABLED)
            it.remove(Keys.GESTURE_REGION)
            it.remove(Keys.SYSTEM_EDGE_EXCLUSION)
            it.remove(Keys.SEEK_PREVIEW_ENABLED)
            it.remove(Keys.HAPTIC_ENABLED)
            it.remove(Keys.VOLUME_SENSITIVITY_PERCENT)
            it.remove(Keys.BRIGHTNESS_SENSITIVITY_PERCENT)
        }
    }

    suspend fun resetPlaybackSettings() {
        store.edit {
            it.remove(Keys.RESUME_POLICY)
            it.remove(Keys.ORIENTATION_MODE)
            it.remove(Keys.PLAYER_KERNEL)
            it.remove(Keys.AUTO_PLAY_NEXT)
            it.remove(Keys.FADE_PLAYBACK)
            it.remove(Keys.HANDEDNESS)
            it.remove(Keys.VOLUME_SOFT_LIMIT_PERCENT)
            it.remove(Keys.BRIGHTNESS_SOFT_LIMIT_PERCENT)
        }
    }

    /** 保留服务器、观看记录、应用锁与首次引导，仅恢复可调体验参数。 */
    suspend fun resetAllSettings() {
        resetGestureSettings()
        resetPlaybackSettings()
        setDisguiseEnabled(false)
        store.edit {
            it.remove(Keys.PREFETCH_MB)
            it.remove(Keys.READ_BLOCK_KB)
            it.remove(Keys.CONCURRENT_READS)
            it.remove(Keys.DECODER_MODE)
            it.remove(Keys.SHOW_ALL_FILES)
            it.remove(Keys.THEME_ID)
            it.remove(Keys.DARK_MODE)
            it.remove(Keys.HOME_LAYOUT)
            it.remove(Keys.IMAGE_CACHE_LIMIT_MB)
            it.remove(Keys.TRASH_RETENTION_DAYS)
            it.remove(Keys.BLUR_ARTWORK)
            it.remove(Keys.SUBTITLE_SIZE_PERCENT)
            it.remove(Keys.SUBTITLE_TEXT_COLOR)
            it.remove(Keys.SUBTITLE_EDGE_COLOR)
            it.remove(Keys.SUBTITLE_EDGE_WIDTH)
            it.remove(Keys.SUBTITLE_BACKGROUND)
            it.remove(Keys.SUBTITLE_BOTTOM_PADDING)
            it.remove(Keys.SUBTITLE_FONT)
            it.remove(Keys.AUDIO_BOOST_PERCENT)
            it.remove(Keys.LOUDNESS_NORMALIZATION)
            it.remove(Keys.EQUALIZER_PRESET)
            it.remove(Keys.EQUALIZER_BANDS)
            it.remove(Keys.DISGUISE_ENABLED)
        }
    }

    val appearanceSettings: Flow<AppearanceSettings> = store.data.map { p ->
        AppearanceSettings(
            themeId = p[Keys.THEME_ID] ?: "mist",
            darkMode = runCatching {
                DarkMode.valueOf(p[Keys.DARK_MODE] ?: DarkMode.FOLLOW_SYSTEM.name)
            }.getOrDefault(DarkMode.FOLLOW_SYSTEM),
            homeLayout = runCatching {
                HomeLayout.valueOf(p[Keys.HOME_LAYOUT] ?: HomeLayout.GALLERY.name)
            }.getOrDefault(HomeLayout.GALLERY),
        )
    }

    suspend fun setThemeId(value: String) {
        store.edit { it[Keys.THEME_ID] = value }
    }

    suspend fun setDarkMode(value: DarkMode) {
        store.edit { it[Keys.DARK_MODE] = value.name }
    }

    suspend fun setHomeLayout(value: HomeLayout) {
        store.edit { it[Keys.HOME_LAYOUT] = value.name }
    }

    val privacySettings: Flow<PrivacySettings> = store.data.map { p ->
        PrivacySettings(
            appLockEnabled = p[Keys.APP_LOCK_ENABLED] ?: false,
            lockGraceSeconds = (p[Keys.LOCK_GRACE_SECONDS] ?: 60)
                .takeIf { it in intArrayOf(0, 60, 300) } ?: 60,
            blurArtwork = p[Keys.BLUR_ARTWORK] ?: false,
            pinConfigured = !p[Keys.APP_LOCK_PIN_SALT].isNullOrBlank() &&
                !p[Keys.APP_LOCK_PIN_HASH].isNullOrBlank(),
            disguiseEnabled = p[Keys.DISGUISE_ENABLED] ?: false,
        )
    }

    suspend fun currentPrivacySettings(): PrivacySettings = privacySettings.first()

    suspend fun setAppLockEnabled(value: Boolean) {
        if (value && !currentPrivacySettings().pinConfigured) return
        store.edit { it[Keys.APP_LOCK_ENABLED] = value }
    }

    suspend fun configureAppLock(pin: String) = pinMutex.withLock {
        configureAppLockLocked(pin)
    }

    private suspend fun configureAppLockLocked(pin: String) {
        require(pin.matches(Regex("\\d{4,8}"))) { "PIN 必须是 4～8 位数字" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val digest = withContext(Dispatchers.Default) { pinDigestV3(salt, pin) }
        store.edit {
            it[Keys.APP_LOCK_PIN_SALT] = Base64.encodeToString(salt, Base64.NO_WRAP)
            it[Keys.APP_LOCK_PIN_HASH] = Base64.encodeToString(digest, Base64.NO_WRAP)
            it[Keys.APP_LOCK_PIN_KDF_VERSION] = PIN_KDF_VERSION
            it[Keys.APP_LOCK_PIN_FAILED_ATTEMPTS] = 0
            it[Keys.APP_LOCK_PIN_NEXT_ALLOWED_AT] = 0L
            it[Keys.APP_LOCK_ENABLED] = true
        }
        digest.fill(0)
    }

    suspend fun verifyAppLockPin(pin: String): Boolean = pinMutex.withLock {
        val now = System.currentTimeMillis()
        val p = store.data.first()
        if ((p[Keys.APP_LOCK_PIN_NEXT_ALLOWED_AT] ?: 0L) > now) return@withLock false
        val salt = runCatching {
            Base64.decode(p[Keys.APP_LOCK_PIN_SALT], Base64.NO_WRAP)
        }.getOrNull() ?: return@withLock false
        val expected = runCatching {
            Base64.decode(p[Keys.APP_LOCK_PIN_HASH], Base64.NO_WRAP)
        }.getOrNull() ?: return@withLock false
        val version = p[Keys.APP_LOCK_PIN_KDF_VERSION] ?: 1
        val actual = withContext(Dispatchers.Default) {
            when {
                version >= PIN_KDF_VERSION -> pinDigestV3(salt, pin)
                version == 2 -> pinDigestV2(salt, pin)
                else -> legacyPinDigest(salt, pin)
            }
        }
        val matched = MessageDigest.isEqual(expected, actual)
        actual.fill(0)
        if (matched) {
            if (version < PIN_KDF_VERSION) {
                // 首次成功验证旧摘要时无感升级到慢 KDF + Keystore pepper。
                configureAppLockLocked(pin)
            } else {
                store.edit {
                    it[Keys.APP_LOCK_PIN_FAILED_ATTEMPTS] = 0
                    it[Keys.APP_LOCK_PIN_NEXT_ALLOWED_AT] = 0L
                }
            }
            return@withLock true
        }
        store.edit { current ->
            val failures = (current[Keys.APP_LOCK_PIN_FAILED_ATTEMPTS] ?: 0) + 1
            val exponent = (failures - 1).coerceIn(0, 8)
            val delayMs = (PIN_BASE_DELAY_MS shl exponent).coerceAtMost(PIN_MAX_DELAY_MS)
            current[Keys.APP_LOCK_PIN_FAILED_ATTEMPTS] = failures
            current[Keys.APP_LOCK_PIN_NEXT_ALLOWED_AT] = now + delayMs
        }
        false
    }

    suspend fun appLockRetryAfterMs(): Long {
        val next = store.data.first()[Keys.APP_LOCK_PIN_NEXT_ALLOWED_AT] ?: 0L
        return (next - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    suspend fun setLockGraceSeconds(value: Int) {
        require(value in intArrayOf(0, 60, 300))
        store.edit { it[Keys.LOCK_GRACE_SECONDS] = value }
    }

    suspend fun setBlurArtwork(value: Boolean) {
        store.edit { it[Keys.BLUR_ARTWORK] = value }
    }

    suspend fun setDisguiseEnabled(value: Boolean) {
        val packageManager = context.packageManager
        val normal = ComponentName(context, "${context.packageName}.LauncherAlias")
        val disguised = ComponentName(context, "${context.packageName}.ToolLauncherAlias")
        packageManager.setComponentEnabledSetting(
            if (value) disguised else normal,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        packageManager.setComponentEnabledSetting(
            if (value) normal else disguised,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        store.edit { it[Keys.DISGUISE_ENABLED] = value }
    }

    val cacheSettings: Flow<CacheSettings> = store.data.map { p ->
        CacheSettings(
            imageCacheLimitMb = (p[Keys.IMAGE_CACHE_LIMIT_MB] ?: 1024)
                .takeIf { it in intArrayOf(512, 1024, 2048) } ?: 1024,
            trashRetentionDays = (p[Keys.TRASH_RETENTION_DAYS] ?: 0)
                .takeIf { it in intArrayOf(0, 7, 30, 90) } ?: 0,
        )
    }

    suspend fun currentCacheSettings(): CacheSettings = cacheSettings.first()

    suspend fun setImageCacheLimitMb(value: Int) {
        require(value in intArrayOf(512, 1024, 2048))
        store.edit { it[Keys.IMAGE_CACHE_LIMIT_MB] = value }
    }

    suspend fun setTrashRetentionDays(value: Int) {
        require(value in intArrayOf(0, 7, 30, 90))
        store.edit { it[Keys.TRASH_RETENTION_DAYS] = value }
    }

    val subtitleStyleSettings: Flow<SubtitleStyleSettings> = store.data.map { p ->
        SubtitleStyleSettings(
            sizePercent = (p[Keys.SUBTITLE_SIZE_PERCENT] ?: 75).coerceIn(50, 250),
            textColor = p[Keys.SUBTITLE_TEXT_COLOR] ?: "#FFFFFF",
            edgeColor = p[Keys.SUBTITLE_EDGE_COLOR] ?: "#000000",
            edgeWidth = (p[Keys.SUBTITLE_EDGE_WIDTH] ?: 2).coerceIn(0, 4),
            backgroundEnabled = p[Keys.SUBTITLE_BACKGROUND] ?: false,
            bottomPaddingPercent = (p[Keys.SUBTITLE_BOTTOM_PADDING] ?: 8).coerceIn(0, 35),
            font = runCatching {
                SubtitleFont.valueOf(p[Keys.SUBTITLE_FONT] ?: SubtitleFont.SANS.name)
            }.getOrDefault(SubtitleFont.SANS),
        )
    }

    suspend fun setSubtitleSizePercent(value: Int) {
        store.edit { it[Keys.SUBTITLE_SIZE_PERCENT] = value.coerceIn(50, 250) }
    }

    suspend fun setSubtitleColors(text: String, edge: String) {
        store.edit {
            it[Keys.SUBTITLE_TEXT_COLOR] = text
            it[Keys.SUBTITLE_EDGE_COLOR] = edge
        }
    }

    suspend fun setSubtitleEdgeWidth(value: Int) {
        store.edit { it[Keys.SUBTITLE_EDGE_WIDTH] = value.coerceIn(0, 4) }
    }

    suspend fun setSubtitleBackground(value: Boolean) {
        store.edit { it[Keys.SUBTITLE_BACKGROUND] = value }
    }

    suspend fun setSubtitleBottomPadding(value: Int) {
        store.edit { it[Keys.SUBTITLE_BOTTOM_PADDING] = value.coerceIn(0, 35) }
    }

    suspend fun setSubtitleFont(value: SubtitleFont) {
        store.edit { it[Keys.SUBTITLE_FONT] = value.name }
    }

    val onboardingCompleted: Flow<Boolean> =
        store.data.map { it[Keys.ONBOARDING_COMPLETED] ?: false }

    suspend fun setOnboardingCompleted(completed: Boolean = true) {
        store.edit { it[Keys.ONBOARDING_COMPLETED] = completed }
    }

    /**
     * 供测试钩子按字符串 key 改参数（`set --es key prefetchMb --ei value 64`）。
     * 返回是否识别该 key。取值范围按需求 X-02 收敛，越界的值直接拒绝而不是静默夹取。
     */
    suspend fun setByKey(key: String, intValue: Int?, stringValue: String?): Boolean {
        when (key) {
            "prefetchMb" -> {
                val v = intValue ?: return false
                if (v !in intArrayOf(16, 32, 48, 64)) return false
                store.edit { it[Keys.PREFETCH_MB] = v }
            }
            "readBlockKb" -> {
                val v = intValue ?: return false
                if (v !in intArrayOf(512, 1024, 2048)) return false
                store.edit { it[Keys.READ_BLOCK_KB] = v }
            }
            "concurrentReads" -> {
                val v = intValue ?: return false
                if (v !in intArrayOf(2, 4, 6, 8)) return false
                store.edit { it[Keys.CONCURRENT_READS] = v }
            }
            "decoderMode" -> {
                val v = stringValue?.uppercase() ?: return false
                val mode = runCatching { DecoderMode.valueOf(v) }.getOrNull() ?: return false
                store.edit { it[Keys.DECODER_MODE] = mode.name }
            }
            "playerKernel" -> {
                val v = stringValue?.uppercase() ?: return false
                val kernel = runCatching { PlayerKernel.valueOf(v) }.getOrNull() ?: return false
                store.edit { it[Keys.PLAYER_KERNEL] = kernel.name }
            }
            "onboardingCompleted" -> {
                val value = stringValue?.toBooleanStrictOrNull() ?: return false
                store.edit { it[Keys.ONBOARDING_COMPLETED] = value }
            }
            "appLockEnabled" -> {
                val value = stringValue?.toBooleanStrictOrNull() ?: return false
                setAppLockEnabled(value)
            }
            "appLockPin" -> {
                val value = stringValue ?: return false
                configureAppLock(value)
            }
            "homeLayout" -> {
                val value = stringValue?.uppercase() ?: return false
                val layout = runCatching { HomeLayout.valueOf(value) }.getOrNull()
                    ?: return false
                setHomeLayout(layout)
            }
            "subtitleSizePercent" -> {
                val value = intValue ?: return false
                if (value !in 50..250) return false
                setSubtitleSizePercent(value)
            }
            "audioBoostPercent" -> {
                val value = intValue ?: return false
                if (value !in 100..200) return false
                setAudioBoostPercent(value)
            }
            "loudnessNormalization" -> {
                val value = stringValue?.toBooleanStrictOrNull() ?: return false
                setLoudnessNormalization(value)
            }
            "equalizerPreset" -> {
                val value = stringValue?.lowercase() ?: return false
                val bands = when (value) {
                    "flat" -> List(10) { 0f }
                    "voice" -> listOf(-3f, -2f, -1f, 1f, 3f, 4f, 3f, 1f, -1f, -2f)
                    "cinema" -> listOf(3f, 2f, 0f, -1f, 0f, 2f, 3f, 4f, 3f, 2f)
                    "bass" -> listOf(7f, 6f, 4f, 2f, 0f, -1f, -2f, -2f, -1f, 0f)
                    "treble" -> listOf(-2f, -2f, -1f, 0f, 1f, 2f, 4f, 6f, 7f, 7f)
                    else -> return false
                }
                setEqualizerPreset(value, bands)
            }
            "disguiseEnabled" -> {
                val value = stringValue?.toBooleanStrictOrNull() ?: return false
                setDisguiseEnabled(value)
            }
            else -> return false
        }
        return true
    }

    suspend fun getByKey(key: String): String? {
        return when (key) {
            "prefetchMb" -> currentIoSettings().prefetchMb.toString()
            "readBlockKb" -> currentIoSettings().readBlockKb.toString()
            "concurrentReads" -> currentIoSettings().concurrentReads.toString()
            "decoderMode" -> currentIoSettings().decoderMode.name
            "playerKernel" -> currentPlayerSettings().playerKernel.name
            "onboardingCompleted" -> onboardingCompleted.first().toString()
            "appLockEnabled" -> currentPrivacySettings().appLockEnabled.toString()
            "homeLayout" -> appearanceSettings.first().homeLayout.name
            "subtitleSizePercent" -> subtitleStyleSettings.first().sizePercent.toString()
            "audioBoostPercent" ->
                currentAudioEnhancementSettings().volumeBoostPercent.toString()
            "loudnessNormalization" ->
                currentAudioEnhancementSettings().loudnessNormalization.toString()
            "equalizerPreset" -> currentAudioEnhancementSettings().equalizerPreset
            "disguiseEnabled" -> currentPrivacySettings().disguiseEnabled.toString()
            else -> null
        }
    }

    private fun legacyPinDigest(salt: ByteArray, pin: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(salt + pin.toByteArray(Charsets.UTF_8))

    private fun pinDigestV3(salt: ByteArray, pin: String): ByteArray {
        val stretched = pinDigestV2(salt, pin)
        return try {
            Mac.getInstance("HmacSHA256").run {
                init(pinPepperKey())
                doFinal(stretched)
            }
        } finally {
            stretched.fill(0)
        }
    }

    @Synchronized
    private fun pinPepperKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(PIN_PEPPER_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
            "AndroidKeyStore",
        ).run {
            init(
                KeyGenParameterSpec.Builder(
                    PIN_PEPPER_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                ).setDigests(KeyProperties.DIGEST_SHA256).build()
            )
            generateKey()
        }
    }

    private fun pinDigestV2(salt: ByteArray, pin: String): ByteArray {
        val chars = pin.toCharArray()
        return try {
            val spec = PBEKeySpec(chars, salt, PIN_KDF_ITERATIONS, PIN_KDF_BITS)
            try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        } finally {
            chars.fill('\u0000')
        }
    }

    private companion object {
        const val MIN_SEEK_RANGE_SECONDS = 10
        const val MAX_SEEK_RANGE_SECONDS = 14_400
        val GESTURE_SENSITIVITY_PRESETS = setOf(50, 75, 100, 150)
        const val PIN_KDF_VERSION = 3
        const val PIN_PEPPER_ALIAS = "lanplay_app_lock_hmac_v1"
        const val PIN_KDF_ITERATIONS = 210_000
        const val PIN_KDF_BITS = 256
        const val PIN_BASE_DELAY_MS = 1_000L
        const val PIN_MAX_DELAY_MS = 5L * 60L * 1_000L
    }
}
