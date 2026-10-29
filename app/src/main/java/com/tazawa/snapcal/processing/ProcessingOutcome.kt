package com.tazawa.snapcal.processing

/**
 * User-visible diagnostics contain only generic status/reason codes.
 * Never include OCR text, patient names, event titles, or image paths.
 */
sealed interface ProcessingOutcome {
    data class Added(val contentHash: String) : ProcessingOutcome
    data class NeedsReview(val detail: String) : ProcessingOutcome
    data class Ignored(val detail: String) : ProcessingOutcome
    data class Failed(val detail: String) : ProcessingOutcome
}
