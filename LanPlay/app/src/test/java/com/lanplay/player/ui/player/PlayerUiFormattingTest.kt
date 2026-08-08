package com.lanplay.player.ui.player

import android.content.pm.ActivityInfo
import com.lanplay.player.data.prefs.OrientationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerUiFormattingTest {
    @Test
    fun subSecondSubtitleOffsetsRemainVisible() {
        assertEquals("+0.1秒", signedDuration(100))
        assertEquals("−0.5秒", signedDuration(-500))
        assertEquals("+1秒", signedDuration(1_000))
        assertEquals("+1:30", signedDuration(90_000))
    }

    @Test
    fun unlockingOrientationRestoresConfiguredMode() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR,
            requestedOrientation(OrientationMode.AUTO),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            requestedOrientation(OrientationMode.FORCE_LANDSCAPE),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            requestedOrientation(OrientationMode.FORCE_PORTRAIT),
        )
    }

    @Test
    fun seekCurveKeepsSmallMovesControlledAndFullSwipeReachesConfiguredRange() {
        assertEquals(3_600_000L, curvedSeekDeltaMs(1f, 3_600))
        assertEquals(-3_600_000L, curvedSeekDeltaMs(-1f, 3_600))
        assertTrue(curvedSeekDeltaMs(0.1f, 3_600) in 1L until 360_000L)
        assertEquals(14_400_000L, curvedSeekDeltaMs(1f, 99_999))
    }
}
