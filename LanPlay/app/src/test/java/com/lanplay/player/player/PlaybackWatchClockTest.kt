package com.lanplay.player.player

import android.media.AudioDeviceInfo
import com.lanplay.player.data.TimedSubtitleCue
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackWatchClockTest {
    @Test
    fun systemRoutedAudioDeviceOverridesConnectedDeviceHeuristic() {
        data class Device(val type: Int, val name: String)

        val speaker = Device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "speaker")
        val bluetooth = Device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "bluetooth")

        assertEquals(
            speaker,
            chooseAudioOutput(
                routed = listOf(speaker),
                connected = listOf(bluetooth, speaker),
                typeOf = Device::type,
            ),
        )
        assertEquals(
            bluetooth,
            chooseAudioOutput(
                routed = emptyList(),
                connected = listOf(speaker, bluetooth),
                typeOf = Device::type,
            ),
        )
    }

    @Test
    fun onlyARealMediaChangeCountsAnotherPlayback() {
        val previous = PlaybackSession(
            recordId = 1,
            serverId = 7,
            relativePath = "目录/影片.mkv",
            fileName = "影片.mkv",
            sizeBytes = 100,
            url = "http://127.0.0.1/item",
        )
        assertEquals(false, shouldCountPlayback(previous, 7, "目录/影片.mkv"))
        assertEquals(true, shouldCountPlayback(previous, 8, "目录/影片.mkv"))
        assertEquals(true, shouldCountPlayback(previous, 7, "目录/另一部.mkv"))
        assertEquals(true, shouldCountPlayback(null, 7, "目录/影片.mkv"))
    }

    @Test
    fun outroNeverOverridesDisabledAutoPlay() {
        assertEquals(
            false,
            shouldAutoAdvanceAtOutro(
                enabled = false,
                positionMs = 95_000,
                durationMs = 100_000,
                skipOutroMs = 10_000,
                alreadyTriggered = false,
            ),
        )
        assertEquals(
            true,
            shouldAutoAdvanceAtOutro(
                enabled = true,
                positionMs = 95_000,
                durationMs = 100_000,
                skipOutroMs = 10_000,
                alreadyTriggered = false,
            ),
        )
    }

    @Test
    fun pauseCapturesFinalPlayingInterval() {
        var now = 1_000L
        val clock = PlaybackWatchClock { now }

        clock.onStateChanged(PlaybackState.READY, PlaybackState.PLAYING)
        now = 6_000L
        clock.onStateChanged(PlaybackState.PLAYING, PlaybackState.PAUSED)

        assertEquals(5_000L, clock.snapshot(isPlaying = false))
    }

    @Test
    fun periodicSaveAndPauseDoNotDoubleCount() {
        var now = 10_000L
        val clock = PlaybackWatchClock { now }

        clock.onStateChanged(PlaybackState.READY, PlaybackState.PLAYING)
        now = 13_000L
        val first = clock.snapshot(isPlaying = true)
        assertEquals(3_000L, first)
        clock.commit(first)

        now = 15_500L
        clock.onStateChanged(PlaybackState.PLAYING, PlaybackState.PAUSED)
        assertEquals(2_500L, clock.snapshot(isPlaying = false))
    }

    @Test
    fun timeAccumulatedDuringDatabaseWriteSurvivesCommit() {
        var now = 0L
        val clock = PlaybackWatchClock { now }

        clock.onStateChanged(PlaybackState.READY, PlaybackState.PLAYING)
        now = 2_000L
        val saving = clock.snapshot(isPlaying = true)
        now = 3_000L
        clock.onStateChanged(PlaybackState.PLAYING, PlaybackState.PAUSED)
        clock.commit(saving)

        assertEquals(1_000L, clock.snapshot(isPlaying = false))
    }

    @Test
    fun liveSubtitleOffsetQueriesTheBaseTimelineWithoutReopeningMedia() {
        val timeline = listOf(TimedSubtitleCue(1_000, 2_000, "字幕"))

        assertEquals(listOf("字幕"), subtitleTextsAt(timeline, 1_500, offsetMs = 0))
        assertEquals(emptyList<String>(), subtitleTextsAt(timeline, 1_500, offsetMs = 1_000))
        assertEquals(listOf("字幕"), subtitleTextsAt(timeline, 2_500, offsetMs = 1_000))
    }
}
