package com.tazawa.snapcal.ocr

/**
 * Remove the Android status-bar OCR lines from portrait screenshots.
 *
 * Do not blindly crop every image: a portrait event flyer may start with
 * a legitimate time. Only remove the top strip if a left-aligned system clock
 * appears inside the narrow status-bar band.
 */
internal object SystemStatusBarFilter {
    private const val MIN_PORTRAIT_RATIO = 1.25
    private const val TOP_BAND_FRACTION = 0.055
    private const val CLOCK_MAX_LEFT_FRACTION = 0.40
    private val SYSTEM_CLOCK = Regex("""^\s*(?:0?\d|1\d|2[0-3]):[0-5]\d(?=\s|$)""")

    fun filter(lines: List<RecognizedLine>, imageWidth: Int, imageHeight: Int): List<RecognizedLine> {
        if (imageWidth <= 0 || imageHeight <= 0 ||
            imageHeight.toDouble() < imageWidth * MIN_PORTRAIT_RATIO
        ) return lines

        val bandBottom = (imageHeight * TOP_BAND_FRACTION).toInt()
        val topLines = lines.filter { it.top >= 0 && it.bottom <= bandBottom }
        val hasSystemClock = topLines.any { line ->
            line.left <= imageWidth * CLOCK_MAX_LEFT_FRACTION &&
                SYSTEM_CLOCK.containsMatchIn(line.text)
        }
        if (!hasSystemClock) return lines
        return lines.filterNot { it.top >= 0 && it.bottom <= bandBottom }
    }
}
