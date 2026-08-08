package com.lanplay.player.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupValidationTest {
    @Test
    fun duplicateTargetWithinServerIsRejectedButSamePathAcrossServersIsValid() {
        assertFalse(hasUniqueBackupTargets(listOf(1L to "A.mkv", 1L to "A.mkv")))
        assertTrue(hasUniqueBackupTargets(listOf(1L to "A.mkv", 2L to "A.mkv")))
    }

    @Test
    fun playbackBoundsMatchRuntimeCapabilities() {
        assertTrue(isSupportedBackupPlaybackState(-60_000L, 0))
        assertTrue(isSupportedBackupPlaybackState(60_000L, 5))
        assertFalse(isSupportedBackupPlaybackState(60_001L, 5))
        assertFalse(isSupportedBackupPlaybackState(0L, 6))
    }
}
