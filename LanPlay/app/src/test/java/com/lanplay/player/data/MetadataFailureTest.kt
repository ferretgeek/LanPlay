package com.lanplay.player.data

import com.lanplay.player.data.db.MediaMetaEntity
import com.lanplay.player.smb.SmbEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class MetadataFailureTest {
    private val entry = SmbEntry(
        name = "影片.mkv",
        relativePath = "目录/影片.mkv",
        isDirectory = false,
        size = 1_000,
        lastModified = 2_000,
    )

    @Test
    fun transientFailureKeepsMatchingKnownMetadata() {
        val old = MediaMetaEntity(
            id = 9,
            serverId = 1,
            fullPath = entry.relativePath,
            fileName = entry.name,
            fileSize = entry.size,
            lastModified = entry.lastModified,
            durationMs = 99_000,
            width = 1920,
            height = 1080,
            thumbnailPath = "private-thumbnail",
        )

        val failed = failedProbeMetadata(old, 1, entry, 3_000)

        assertEquals(99_000L, failed.durationMs)
        assertEquals("private-thumbnail", failed.thumbnailPath)
        assertTrue(failed.probeFailed)
    }

    @Test
    fun changedFingerprintDoesNotReuseStaleMetadata() {
        val old = MediaMetaEntity(
            id = 9,
            serverId = 1,
            fullPath = entry.relativePath,
            fileName = entry.name,
            fileSize = 999,
            lastModified = 1_999,
            durationMs = 99_000,
            thumbnailPath = "stale-thumbnail",
        )

        val failed = failedProbeMetadata(old, 1, entry, 3_000)

        assertNull(failed.durationMs)
        assertNull(failed.thumbnailPath)
        assertTrue(failed.probeFailed)
    }

    @Test
    fun onlyCompleteScraperIndexCanPruneExistingMetadata() {
        assertFalse(metadataIndexAllowsPruning(complete = false))
        assertTrue(metadataIndexAllowsPruning(complete = true))
    }
}
