package com.lanplay.player.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SmbPathValidationTest {
    @Test
    fun normalizesCompatibleLeadingAndTrailingSeparators() {
        assertEquals("电影/示例.mkv", SmbFileRepository.normalizeRelativePath("/电影/示例.mkv/"))
        assertEquals("", SmbFileRepository.normalizeRelativePath("/"))
        assertEquals("电影\\示例.mkv", SmbFileRepository.toSmbPath("电影/示例.mkv"))
    }

    @Test
    fun rejectsParentTraversalAndAmbiguousSegments() {
        assertThrows(IllegalArgumentException::class.java) {
            SmbFileRepository.normalizeRelativePath(".lanplay_meta/../private.mkv")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SmbFileRepository.normalizeRelativePath("电影//示例.mkv")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SmbFileRepository.normalizeRelativePath("电影\\..\\示例.mkv")
        }
    }

    @Test
    fun rejectsControlCharacters() {
        assertThrows(IllegalArgumentException::class.java) {
            SmbFileRepository.normalizeRelativePath("电影/坏\u0000名字.mkv")
        }
    }

    @Test
    fun validatesConnectionTargetWithoutLeakingItFromToString() {
        val target = SmbTarget(
            host = "192.0.2.10",
            share = "example-share",
            username = "<USER>",
            password = "<PASSWORD>",
        ).requireValid()
        assertEquals("SmbTarget(port=445, mode=ACCOUNT)", target.toString())
        assertThrows(IllegalArgumentException::class.java) {
            target.copy(share = "../other").requireValid()
        }
        assertThrows(IllegalArgumentException::class.java) {
            target.copy(host = "server@example").requireValid()
        }
    }
}
