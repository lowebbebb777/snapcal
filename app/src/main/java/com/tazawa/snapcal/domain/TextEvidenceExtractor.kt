package com.tazawa.snapcal.domain

import java.text.Normalizer

/** OCR 行から、カレンダー候補として必要な証拠だけを抽出する決定的ルール。 */
class TextEvidenceExtractor {
    fun extract(lines: List<OcrLine>): EventCandidate {
        val orderedLines = lines
            .sortedWith(
                compareBy<OcrLine> { it.bounds?.top ?: Int.MAX_VALUE }
                    .thenBy { it.bounds?.left ?: Int.MAX_VALUE },
            )
            .mapIndexedNotNull { index, line ->
                normalize(line.text).takeIf { it.isNotBlank() }?.let {
                    IndexedLine(index = index, text = it, bounds = line.bounds)
                }
            }
        val orderedTexts = orderedLines.map { it.text }

        val exclusions = orderedTexts
            .flatMap { text -> EXCLUSION_KEYWORDS.filter { keyword -> text.contains(keyword) } }
            .toSet()

        val allDates = orderedTexts.flatMap(::extractDates)
        val times = orderedTexts.flatMap(::extractTimes)
        val allAnchors = orderedLines.filter { line ->
            extractDates(line.text).isNotEmpty() || CLOCK.containsMatchIn(line.text)
        }

        // A page header, notice or unrelated date must not override the
        // actual appointment date attached to the only start time.
        // Never infer a choice if multiple time-bearing events are present.
        val linkedAnchor = if (allDates.size > 1 && times.size == 1) {
            orderedLines.filter { extractTimes(it.text).isNotEmpty() }
                .singleOrNull()
                ?.takeIf { extractDates(it.text).size == 1 }
        } else null
        val dates = linkedAnchor?.let { extractDates(it.text) } ?: allDates
        val temporalAnchors = if (linkedAnchor != null) listOf(linkedAnchor) else allAnchors
        val titles = selectRegionTitles(orderedLines, temporalAnchors)

        return EventCandidate(
            titleCandidates = titles,
            dateCandidates = dates,
            timeCandidates = times,
            exclusionKeywords = exclusions,
            ambiguousEvidence = temporalAnchorsAreSeparated(temporalAnchors),
        )
    }

    private fun selectRegionTitles(
        lines: List<IndexedLine>,
        anchors: List<IndexedLine>,
    ): List<String> {
        if (anchors.isEmpty()) return emptyList()
        val sameLine = anchors.mapNotNull { extractTitle(it.text) }.distinct()
        if (sameLine.isNotEmpty()) return sameLine

        if (anchors.all { it.bounds != null }) {
            val nearbyTitles = lines.mapNotNull { line ->
                val title = extractTitle(line.text) ?: return@mapNotNull null
                val bounds = line.bounds ?: return@mapNotNull null
                title.takeIf {
                    anchors.any { anchor ->
                        val anchorBounds = checkNotNull(anchor.bounds)
                        sameVisualRegion(bounds, anchorBounds)
                    }
                }
            }
            // In appointment screenshots, patient names and UI labels can
            // appear near the date. Prefer a unique facility name only when
            // appointment context is explicit; otherwise keep ambiguity.
            val appointmentContext = lines.any { line ->
                APPOINTMENT_CONTEXT.any { hint -> line.text.contains(hint) }
            }
            if (appointmentContext) {
                val facilityTitles = nearbyTitles.filter { FACILITY_NAME.containsMatchIn(it) }
                if (facilityTitles.size == 1) return facilityTitles
            }
            return nearbyTitles
        }

        // Boundsの無いテスト入力では、各日時行の直前1行だけを候補にする。
        return anchors.mapNotNull { anchor ->
            lines.asReversed().firstNotNullOfOrNull { line ->
                if (line.index >= anchor.index) null else extractTitle(line.text)
            }
        }.distinct()
    }

    private fun sameVisualRegion(first: OcrBounds, second: OcrBounds): Boolean {
        val firstHeight = (first.bottom - first.top).coerceAtLeast(1)
        val secondHeight = (second.bottom - second.top).coerceAtLeast(1)
        val firstWidth = (first.right - first.left).coerceAtLeast(1)
        val secondWidth = (second.right - second.left).coerceAtLeast(1)
        val verticalGap = when {
            first.bottom < second.top -> second.top - first.bottom
            second.bottom < first.top -> first.top - second.bottom
            else -> 0
        }
        val firstCenter = (first.left.toLong() + first.right) / 2
        val secondCenter = (second.left.toLong() + second.right) / 2
        val horizontalDistance = kotlin.math.abs(firstCenter - secondCenter)
        return verticalGap <= maxOf(firstHeight, secondHeight) * REGION_LINE_MULTIPLIER + REGION_PADDING &&
            horizontalDistance <= maxOf(firstWidth, secondWidth) * 2L + REGION_PADDING
    }

    private fun temporalAnchorsAreSeparated(anchors: List<IndexedLine>): Boolean {
        if (anchors.size < 2) return false
        return anchors.zipWithNext().any { (first, second) ->
            when {
                first.bounds != null && second.bounds != null ->
                    !sameVisualRegion(first.bounds, second.bounds)
                else -> second.index - first.index > MAX_FALLBACK_ANCHOR_GAP
            }
        }
    }

    private fun extractDates(text: String): List<DateEvidence> {
        val matches = mutableListOf<IndexedDate>()

        YEAR_SLASH_DATE.findAll(text).forEach { match ->
            matches += IndexedDate(
                range = match.range,
                evidence = DateEvidence(
                    rawText = match.value,
                    year = match.groupValues[1].toInt(),
                    month = match.groupValues[2].toInt(),
                    day = match.groupValues[3].toInt(),
                ),
            )
        }
        YEAR_JAPANESE_DATE.findAll(text).forEach { match ->
            matches += IndexedDate(
                range = match.range,
                evidence = DateEvidence(
                    rawText = match.value,
                    year = match.groupValues[1].toInt(),
                    month = match.groupValues[2].toInt(),
                    day = match.groupValues[3].toInt(),
                ),
            )
        }

        NO_YEAR_SLASH_DATE.findAll(text).forEach { match ->
            if (matches.none { rangesOverlap(it.range, match.range) }) {
                matches += IndexedDate(
                    range = match.range,
                    evidence = DateEvidence(
                        rawText = match.value,
                        year = null,
                        month = match.groupValues[1].toInt(),
                        day = match.groupValues[2].toInt(),
                    ),
                )
            }
        }
        NO_YEAR_JAPANESE_DATE.findAll(text).forEach { match ->
            if (matches.none { rangesOverlap(it.range, match.range) }) {
                matches += IndexedDate(
                    range = match.range,
                    evidence = DateEvidence(
                        rawText = match.value,
                        year = null,
                        month = match.groupValues[1].toInt(),
                        day = match.groupValues[2].toInt(),
                    ),
                )
            }
        }

        return matches.sortedBy { it.range.first }.map { it.evidence }
    }

    private fun extractTimes(text: String): List<TimeEvidence> {
        val matches = CLOCK.findAll(text).toList()

        if (matches.size == 2) {
            val between = text.substring(matches[0].range.last + 1, matches[1].range.first)
            if (RANGE_SEPARATOR.containsMatchIn(between)) {
                val start = parseClock(matches[0]) ?: return emptyList()
                val inheritedMarker = matches[0].groupValues[1].takeIf {
                    it.isNotBlank() && matches[1].groupValues[1].isBlank()
                }
                val end = parseClock(matches[1], inheritedMarker) ?: return emptyList()
                return listOf(TimeEvidence(start = start, end = end))
            }
        }
        val clocks = matches.mapNotNull { match ->
            parseClock(match)?.let { IndexedClock(match.range, it) }
        }
        return clocks.map { TimeEvidence(start = it.clock) }
    }

    private fun parseClock(match: MatchResult, inheritedMarker: String? = null): ClockEvidence? {
        val marker = match.groupValues[1].ifBlank { inheritedMarker.orEmpty() }
        val rawHour = match.groupValues[2].toIntOrNull() ?: return null
        val minute = (match.groupValues[3].ifBlank { match.groupValues[4] })
            .ifBlank { "0" }
            .toIntOrNull() ?: return null

        if (minute !in 0..59) return null
        val hour = when (marker) {
            "午前" -> {
                if (rawHour !in 1..12) return null
                if (rawHour == 12) 0 else rawHour
            }
            "午後" -> {
                if (rawHour !in 1..12) return null
                if (rawHour == 12) 12 else rawHour + 12
            }
            else -> {
                if (rawHour !in 0..23) return null
                rawHour
            }
        }
        return ClockEvidence(match.value, hour, minute)
    }

    private fun extractTitle(text: String): String? {
        if (text.startsWith("http://") || text.startsWith("https://")) return null
        if (LOCATION_PREFIX.containsMatchIn(text)) return null

        var remainder = text
            // Remove weekday markers attached to a date before stripping dates.
            // Japanese OCR sometimes reads (月) as (定).
            .replace(DATE_WITH_WEEKDAY, " ")
            .replace(YEAR_SLASH_DATE, " ")
            .replace(YEAR_JAPANESE_DATE, " ")
            .replace(NO_YEAR_SLASH_DATE, " ")
            .replace(NO_YEAR_JAPANESE_DATE, " ")
            .replace(CLOCK, " ")
            .replace(TITLE_RANGE_SEPARATOR, " ")
            .replace(TITLE_LABEL, " ")
            .replace(PUNCTUATION_ONLY_PARTS, " ")
            .trim()
            .replace(WHITESPACE, " ")

        if (remainder in GENERIC_LINES || remainder in UI_ACTION_LINES) return null
        if (remainder.length < 2 || remainder.none { it.isLetterOrDigit() }) return null
        return remainder.take(MAX_TITLE_LENGTH)
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).trim()

    private fun rangesOverlap(first: IntRange, second: IntRange): Boolean =
        first.first <= second.last && second.first <= first.last

    private data class IndexedDate(val range: IntRange, val evidence: DateEvidence)
    private data class IndexedClock(val range: IntRange, val clock: ClockEvidence)
    private data class IndexedLine(val index: Int, val text: String, val bounds: OcrBounds?)

    companion object {
        val EXCLUSION_KEYWORDS = setOf("営業時間", "受付時間", "販売期間", "有効期限", "締切")

        private const val MAX_TITLE_LENGTH = 120
        private const val REGION_LINE_MULTIPLIER = 4
        private const val REGION_PADDING = 24
        private const val MAX_FALLBACK_ANCHOR_GAP = 2
        private val YEAR_SLASH_DATE = Regex("(?<!\\d)(\\d{4})/(\\d{1,2})/(\\d{1,2})(?!\\d)")
        private val NO_YEAR_SLASH_DATE = Regex("(?<![\\d/])(\\d{1,2})/(\\d{1,2})(?![\\d/])")
        private val YEAR_JAPANESE_DATE = Regex("(?<!\\d)(\\d{4})年\\s*(\\d{1,2})月\\s*(\\d{1,2})日")
        private val NO_YEAR_JAPANESE_DATE = Regex("(?<![\\d年月])(\\d{1,2})月\\s*(\\d{1,2})日")
        private val DATE_WITH_WEEKDAY = Regex(
            "(?:\\d{4}年\\s*)?\\d{1,2}月\\s*\\d{1,2}日\\s*[(（][月火水木金土日定][)）]" +
                "|(?:\\d{4}/)?\\d{1,2}/\\d{1,2}\\s*[(（][月火水木金土日定][)）]",
        )
        private val APPOINTMENT_CONTEXT = setOf("診察券", "予約時間", "予約日時", "診療予約")
        private val FACILITY_NAME = Regex("(?:クリニック|病院|医院|診療所|歯科)$")
        private val CLOCK = Regex("(?:(午前|午後)\\s*)?(\\d{1,2})(?::(\\d{2})|時(?:\\s*(\\d{1,2})分?)?)")
        private val RANGE_SEPARATOR = Regex("(?:〜|～|~|[-‐‑–—−ー]|から|→|まで)")
        // Preserve the Japanese long vowel ー in names (e.g., ミーティング).
        private val TITLE_RANGE_SEPARATOR = Regex(
            "(?:〜|～|~|[-‐-–—−]|→|(?<![\\p{L}\\p{N}])ー(?![\\p{L}\\p{N}])" +
                "|(?<!\\p{L})から(?!\\p{L})|(?<!\\p{L})まで(?!\\p{L}))",
        )
        private val LOCATION_PREFIX = Regex("^(?:場所|会場|住所|開催場所)\\s*[:：]")
        private val TITLE_LABEL = Regex("^(?:イベント名|予定|タイトル|公演名)\\s*[:：]")
        private val PUNCTUATION_ONLY_PARTS = Regex("[|｜・:：,，]+")
        private val WHITESPACE = Regex("\\s+")
        private val GENERIC_LINES = setOf("日時", "開催日時", "開催日", "日付", "時間", "お知らせ", "詳細")
        private val UI_ACTION_LINES = setOf(
            "チケット購入",
            "共有する",
            "詳しく見る",
            "予約する",
            "申し込む",
            "閉じる",
        )
    }
}
