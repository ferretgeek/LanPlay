package com.lanplay.player.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupSerializationTest {
    @Serializable
    private data class VersionedFixture(val version: Int = 2)

    @Test
    fun formatVersionIsWrittenEvenWhenItEqualsTheDefault() {
        val codec = createBackupJsonCodec()
        val root = codec.parseToJsonElement(codec.encodeToString(VersionedFixture())).jsonObject

        assertEquals(2, root.getValue("version").jsonPrimitive.int)
    }
}
