package com.tazawa.snapcal.domain

import java.time.LocalDateTime

/**
 * Conservative on-device review options. Never invent a title, date or time.
 * Only offer candidates backed by a single nearby date and start time.
 * Ambiguous screens continue to require a human to choose.
 */
class ReviewOptionBuilder(
    private val extractor: TextEvidenceExtractor = TextEvidenceExtractor(),
    private val normalizer: EventNormalizer = EventNormalizer(),
    private val gate: AutoAddGate = AutoAddGate(),
) {
    fun build(
        lines: List<OcrLine>,
        capturedAt: LocalDateTime,
        now: LocalDateTime,
    ): List<NormalizedEvent> {
        val ordered = lines.sortedWith(
            compareBy<OcrLine> { it.bounds?.top ?: Int.MAX_VALUE }
                .thenBy { it.bounds?.left ?: Int.MAX_VALUE },
        )
        val results = mutableListOf<NormalizedEvent>()
        for (index in ordered.indices) {
            val anchor = ordered[index]
            val evidence = extractor.extract(listOf(anchor))
            if (evidence.timeCandidates.size != 1) continue

            // A standalone time may pair with an explicitly dated preceding line,
            // but never pair across another time-bearing line.
            val temporalLines: List<OcrLine> = when (evidence.dateCandidates.size) {
                1 -> listOf(anchor)
                0 -> {
                    val preceding = ordered.take(index).takeLast(2).asReversed()
                        .firstOrNull { previous ->
                            val e = extractor.extract(listOf(previous))
                            e.dateCandidates.size == 1 && e.timeCandidates.isEmpty() &&
                                isNearby(previous, anchor)
                        } ?: continue
                    listOf(preceding, anchor)
                }
                else -> continue
            }
            val temporal = extractor.extract(temporalLines)
            if (temporal.dateCandidates.size != 1 || temporal.timeCandidates.size != 1) continue
            if (temporal.exclusionKeywords.isNotEmpty()) continue

            val title = if (temporal.titleCandidates.size == 1) {
                temporal.titleCandidates.single()
            } else if (temporal.titleCandidates.isEmpty()) {
                // Prefer the closest clearly readable label just above the event.
                val titleLine = ordered.take(index).asReversed().take(4)
                    .firstOrNull { potential ->
                        isNearby(potential, anchor) &&
                            !temporalLines.contains(potential) &&
                            extractor.extract(listOf(potential)).let {
                                it.dateCandidates.isEmpty() && it.timeCandidates.isEmpty()
                            } &&
                            extractor.extract(listOf(potential, anchor))
                                .titleCandidates.size == 1
                    }
                titleLine?.let { potential ->
                    extractor.extract(listOf(potential, anchor)).titleCandidates.singleOrNull()
                } ?: continue
            } else continue

            val only = EventCandidate(
                titleCandidates = listOf(title),
                dateCandidates = temporal.dateCandidates,
                timeCandidates = temporal.timeCandidates,
            )
            val normalized = normalizer.normalize(only, capturedAt)
            if (gate.classify(normalized, now) == Classification.AUTO_ADD) {
                results += checkNotNull(normalized.event)
            }
        }
        return results.distinctBy { Triple(it.title, it.start, it.end) }.take(MAX_OPTIONS)
    }

    private fun isNearby(a: OcrLine, b: OcrLine): Boolean {
        val first = a.bounds ?: return true
        val second = b.bounds ?: return true
        val verticalGap = when {
            first.bottom < second.top -> second.top - first.bottom
            second.bottom < first.top -> first.top - second.bottom
            else -> 0
        }
        val height = maxOf(first.bottom - first.top, second.bottom - second.top).coerceAtLeast(1)
        return verticalGap <= height * 5 + 50
    }

    private companion object {
        const val MAX_OPTIONS = 4
    }
}
