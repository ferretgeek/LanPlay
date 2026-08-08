package com.lanplay.player.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactionTest {
    @Test
    fun sensitiveStructuredFieldsAreAlwaysRemoved() {
        assertEquals("file" to "<redacted>", LogRedaction.field("file", "private.mp4"))
        assertEquals("HOST" to "<redacted>", LogRedaction.field("HOST", "192.0.2.10"))
        assertEquals("first" to "<redacted>", LogRedaction.field("first", "private subtitle line"))
        assertEquals("name" to "<redacted>", LogRedaction.field("name", "private device name"))
        assertEquals("detail" to "<redacted>", LogRedaction.field("detail", "raw exception detail"))
    }

    @Test
    fun freeTextRemovesPathsAddressesAndMediaNames() {
        val privateAddress = listOf(192, 168, 10, 20).joinToString(".")
        val redacted = LogRedaction.text(
            "无法打开目录「private/folder」 C:\\Private\\clip.mkv $privateAddress secret.mp4" // SENSITIVE-SCAN-ALLOW: synthetic fixtures
        )
        assertFalse(redacted.contains("private", ignoreCase = true))
        assertFalse(redacted.contains(privateAddress))
        assertFalse(redacted.contains("secret.mp4"))
        assertTrue(redacted.contains("<redacted>") || redacted.contains("<local-path>"))
    }

    @Test
    fun freeTextRemovesSmbUrisHostEndpointsAndIpv6() {
        val redacted = LogRedaction.text(
            "failed smb://fixture-host/fixture-share at fixture-host:445 and [2001:db8::1]" // SENSITIVE-SCAN-ALLOW: synthetic fixtures
        )
        assertFalse(redacted.contains("fixture-host"))
        assertFalse(redacted.contains("fixture-share"))
        assertFalse(redacted.contains("2001:db8::1"))
        assertTrue(redacted.contains("<smb-uri>"))
        assertTrue(redacted.contains("<smb-endpoint>"))
        assertTrue(redacted.contains("<ip>"))
    }
}
