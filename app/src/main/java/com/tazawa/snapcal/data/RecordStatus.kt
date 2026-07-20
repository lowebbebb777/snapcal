package com.tazawa.snapcal.data

/**
 * Screenshot processing lifecycle. The stored value is deliberately stable so
 * enum renames cannot silently change the database representation.
 */
enum class RecordStatus(val storedValue: String) {
    QUEUED("queued"),
    RECOGNIZING("recognizing"),
    CANDIDATE_READY("candidate_ready"),
    ADDING("adding"),
    ADD_RECONCILE_REQUIRED("add_reconcile_required"),
    AUTO_ADDED("auto_added"),
    NEEDS_REVIEW("needs_review"),
    IGNORED("ignored"),
    FAILED("failed"),
    UNDO_PENDING("undo_pending"),
    DELETE_RECONCILE_REQUIRED("delete_reconcile_required"),
    UNDONE("undone");

    companion object {
        fun fromStoredValue(value: String): RecordStatus =
            entries.firstOrNull { it.storedValue == value }
                ?: throw IllegalArgumentException("Unknown record status: $value")
    }
}

