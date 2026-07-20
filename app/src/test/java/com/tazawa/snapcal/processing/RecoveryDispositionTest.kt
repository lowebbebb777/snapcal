package com.tazawa.snapcal.processing

import com.tazawa.snapcal.calendar.AddReconcileResult
import com.tazawa.snapcal.calendar.DeleteReconcileResult
import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveryDispositionTest {
    @Test
    fun `temporary add reconciliation failures remain retryable`() {
        listOf(
            AddReconcileResult.PermissionDenied,
            AddReconcileResult.ProviderUnavailable,
        ).forEach { result ->
            assertEquals(ReconcileDisposition.RETRY, result.recoveryDisposition())
        }
    }

    @Test
    fun `only an adopted add completes recovery`() {
        assertEquals(
            ReconcileDisposition.COMPLETE,
            AddReconcileResult.Adopted(42L).recoveryDisposition(),
        )
        listOf(
            AddReconcileResult.NoMatch,
            AddReconcileResult.SignatureMismatch,
            AddReconcileResult.Ambiguous(2),
        ).forEach { result ->
            assertEquals(ReconcileDisposition.FAIL, result.recoveryDisposition())
        }
    }

    @Test
    fun `uncertain delete results remain retryable`() {
        listOf(
            DeleteReconcileResult.DeleteNotConfirmed,
            DeleteReconcileResult.PermissionDenied,
            DeleteReconcileResult.ProviderUnavailable,
        ).forEach { result ->
            assertEquals(ReconcileDisposition.RETRY, result.recoveryDisposition())
        }
    }

    @Test
    fun `confirmed delete completes and ownership mismatches fail closed`() {
        listOf(
            DeleteReconcileResult.AlreadyAbsent,
            DeleteReconcileResult.Deleted(42L),
        ).forEach { result ->
            assertEquals(ReconcileDisposition.COMPLETE, result.recoveryDisposition())
        }
        listOf(
            DeleteReconcileResult.SignatureMismatch,
            DeleteReconcileResult.TokenMismatch,
            DeleteReconcileResult.EventIdMismatch,
            DeleteReconcileResult.Ambiguous(2),
        ).forEach { result ->
            assertEquals(ReconcileDisposition.FAIL, result.recoveryDisposition())
        }
    }
}
