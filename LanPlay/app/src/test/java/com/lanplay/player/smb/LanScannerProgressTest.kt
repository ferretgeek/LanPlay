package com.lanplay.player.smb

import org.junit.Assert.assertEquals
import org.junit.Test

class LanScannerProgressTest {
    @Test
    fun taskTotalMatchesDistinctNetworkPrefixes() {
        assertEquals(0, scanAddresses(emptyList()).size)
        assertEquals(254, scanAddresses(listOf("192.168.1")).size)
        assertEquals(508, scanAddresses(listOf("192.168.1", "10.0.0")).size)
        assertEquals(254, scanAddresses(listOf("192.168.1", "192.168.1")).size)
    }
}
