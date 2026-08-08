package com.lanplay.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleMatchingTest {
    @Test
    fun exactAndLanguageCandidatesKeepExpectedPriority() {
        assertEquals(
            "ABC-1.srt",
            chooseSubtitleName(
                "ABC-1",
                listOf("ABC-1.chs.ass", "ABC-1.srt", "ABC-1.idx", "ABC-1.sub"),
            ),
        )
        assertEquals(
            "ABC-1.chs.ass",
            chooseSubtitleName("ABC-1", listOf("ABC-1.eng.srt", "ABC-1.chs.ass")),
        )
        assertEquals(
            "ABC-1.idx",
            chooseSubtitleName("ABC-1", listOf("ABC-1.sub", "ABC-1.idx")),
        )
    }

    @Test
    fun numericPrefixDoesNotMatchAnotherMovie() {
        assertFalse(subtitleStemMatches("ABC-1", "ABC-10"))
        assertEquals(null, chooseSubtitleName("ABC-1", listOf("ABC-10.srt")))
    }

    @Test
    fun recognizedSeparatorsStillAllowDescriptiveSuffixes() {
        assertTrue(subtitleStemMatches("ABC-1", "ABC-1-中文字幕"))
        assertTrue(subtitleStemMatches("ABC-1", "ABC-1_导演评论"))
        assertTrue(subtitleStemMatches("ABC-1", "ABC-1【简体】"))
    }

    @Test
    fun assSearchStopsReadingAsSoonAsLimitIsReached() {
        var visited = 0
        val lines = sequence {
            repeat(100) { index ->
                visited++
                yield(
                    "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,关键词 $index"
                )
            }
        }

        val hits = searchAssSubtitleLines(lines, "关键词", limit = 3)

        assertEquals(3, hits.size)
        assertEquals(3, visited)
    }

    @Test
    fun srtAndWebVttBuildAnUnshiftedTimeline() {
        val srt = """
            1
            00:00:01,000 --> 00:00:02,500
            第一行
            第二行
        """.trimIndent()
        val vtt = """
            WEBVTT

            00:03.000 --> 00:04.250 align:start
            <b>提示</b>
        """.trimIndent()

        assertEquals(
            listOf(TimedSubtitleCue(1_000, 2_500, "第一行\n第二行")),
            parseTextSubtitleTimeline(srt, "srt"),
        )
        assertEquals(
            listOf(TimedSubtitleCue(3_000, 4_250, "提示")),
            parseTextSubtitleTimeline(vtt, "vtt"),
        )
    }
}
