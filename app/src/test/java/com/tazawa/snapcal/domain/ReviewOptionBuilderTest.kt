package com.tazawa.snapcal.domain

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewOptionBuilderTest {
    private val builder = ReviewOptionBuilder()
    private val captured = LocalDateTime.of(2026, 10, 29, 15, 30)

    @Test fun `two independent appointments become two selectable options`() {
        val options = builder.build(
            listOf(
                line("青空クリニック", 110),
                line("11月2日（月）16:50", 150),
                line("定例ミーティング", 430),
                line("11月3日（火）18:30", 470),
            ), captured, captured,
        )
        assertEquals(2, options.size)
        assertEquals(listOf("青空クリニック", "定例ミーティング"), options.map { it.title })
        assertEquals(listOf(2, 3), options.map { it.start.dayOfMonth })
    }

    @Test fun `date and time on adjacent lines form selectable candidate`() {
        val options = builder.build(
            listOf(
                line("打ち合わせ", 150),
                line("11月6日", 190),
                line("14:30", 230),
            ), captured, captured,
        )
        assertEquals(1, options.size)
        assertEquals("打ち合わせ", options.single().title)
        assertEquals(14, options.single().start.hour)
    }

    @Test fun `unrelated deadline is never paired with a distant time`() {
        val options = builder.build(
            listOf(
                line("有効期限 11月30日", 20),
                line("イベントのタイトル", 460),
                line("16:50", 550),
            ), captured, captured,
        )
        assertTrue(options.isEmpty())
    }

    @Test fun `two possible dates on the same row are not silently selected`() {
        val options = builder.build(listOf(line("11月2日 または 11月3日 16:50", 150)), captured, captured)
        assertTrue(options.isEmpty())
    }

    @Test fun `hours and expiry evidence are never offered as events`() {
        val options = builder.build(
            listOf(
                line("営業時間 11月2日 16:50", 150),
                line("有効期限 11月3日 15:00", 280),
            ), captured, captured,
        )
        assertTrue(options.isEmpty())
    }

    @Test fun `past year events never appear in choices`() {
        val options = builder.build(
            listOf(line("イベント 2025年11月2日 16:50", 150)), captured, captured,
        )
        assertTrue(options.isEmpty())
    }

    private fun line(text: String, top: Int) =
        OcrLine(text, OcrBounds(70, top, 430, top + 36))
}
