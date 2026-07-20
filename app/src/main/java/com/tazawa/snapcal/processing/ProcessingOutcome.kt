package com.tazawa.snapcal.processing

sealed interface ProcessingOutcome {
    data class Added(val contentHash: String) : ProcessingOutcome
    data object NeedsReview : ProcessingOutcome
    data object Ignored : ProcessingOutcome
    data object Failed : ProcessingOutcome
}
