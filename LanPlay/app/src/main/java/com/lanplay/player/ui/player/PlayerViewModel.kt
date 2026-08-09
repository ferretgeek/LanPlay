package com.lanplay.player.ui.player

import android.content.Context
import android.media.AudioManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanplay.player.data.prefs.OrientationMode
import com.lanplay.player.data.prefs.PlayerSettings
import com.lanplay.player.data.prefs.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val settings = settingsRepository.playerSettings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PlayerSettings(),
    )
    val subtitleStyle = settingsRepository.subtitleStyleSettings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        com.lanplay.player.data.prefs.SubtitleStyleSettings(),
    )

    fun currentVolumePercent(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
    }

    fun setVolumePercent(value: Float, persist: Boolean = true): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val target = (value.coerceIn(0f, 1f) * max).toInt()
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        val actual = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
        if (persist) saveVolumePercent(actual)
        return actual
    }

    fun saveVolumePercent(value: Float) {
        viewModelScope.launch { settingsRepository.setLastVolumePercent(value) }
    }

    fun saveBrightness(value: Float) {
        viewModelScope.launch { settingsRepository.setLastBrightnessPercent(value) }
    }

    fun cycleSeekSensitivity() {
        val values = intArrayOf(30, 60, 90, 120, 180, 300)
        val current = settings.value.seekSensitivitySeconds
        val currentIndex = values.indexOf(current).takeIf { it >= 0 } ?: -1
        val next = values[(currentIndex + 1) % values.size]
        viewModelScope.launch { settingsRepository.setSeekSensitivitySeconds(next) }
    }

    fun setOrientationMode(mode: OrientationMode) {
        viewModelScope.launch { settingsRepository.setOrientationMode(mode) }
    }
}
