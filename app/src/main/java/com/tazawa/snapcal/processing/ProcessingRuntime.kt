package com.tazawa.snapcal.processing

import android.content.Context
import android.net.Uri
import androidx.room.Room
import com.tazawa.snapcal.calendar.AddEventResult
import com.tazawa.snapcal.calendar.AddReconcileResult
import com.tazawa.snapcal.calendar.CalendarEventDraft
import com.tazawa.snapcal.calendar.CalendarEventFactory
import com.tazawa.snapcal.calendar.CalendarGateway
import com.tazawa.snapcal.calendar.CalendarListResult
import com.tazawa.snapcal.capture.MonitoringPreferences
import com.tazawa.snapcal.data.EnqueueResult
import com.tazawa.snapcal.data.RecordStatus
import com.tazawa.snapcal.data.RecordTransition
import com.tazawa.snapcal.data.ScreenshotRecordRepository
import com.tazawa.snapcal.data.SnapCalDatabase
import com.tazawa.snapcal.domain.AutoAddGate
import com.tazawa.snapcal.domain.Classification
import com.tazawa.snapcal.domain.EventNormalizer
import com.tazawa.snapcal.domain.OcrBounds
import com.tazawa.snapcal.domain.OcrLine
import com.tazawa.snapcal.domain.TextEvidenceExtractor
import com.tazawa.snapcal.ocr.MlKitJapaneseTextRecognizer
import java.io.Closeable
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class ProcessingRuntime(
    context: Context,
) : Closeable {
    private val appContext = context.applicationContext
    private val recognizer = MlKitJapaneseTextRecognizer(appContext)
    private val database = Room.databaseBuilder(
        appContext,
        SnapCalDatabase::class.java,
        "snapcal.db",
    ).build()
    private val repository = ScreenshotRecordRepository(database.screenshotRecordDao())
    private val calendarGateway = CalendarGateway(appContext.contentResolver)
    private val preferences = MonitoringPreferences(appContext)
    private val extractor = TextEvidenceExtractor()
    private val normalizer = EventNormalizer()
    private val gate = AutoAddGate()

    suspend fun process(uri: Uri, capturedAt: Long): ProcessingOutcome = withContext(Dispatchers.IO) {
        var contentHash: String? = null
        try {
            contentHash = hash(uri)
            when (repository.enqueue(contentHash, capturedAt)) {
                is EnqueueResult.AlreadyExists -> return@withContext ProcessingOutcome.Ignored("同じ画像は処理済みです。新しいスクショで再試行してください")
                is EnqueueResult.Created -> Unit
                EnqueueResult.Conflict -> return@withContext ProcessingOutcome.Failed("処理履歴が競合しています")
            }
            requireTransition(contentHash, RecordTransition.StartRecognizing)

            val recognized = recognizer.recognize(uri)
            if (recognized.isEmpty()) {
                requireTransition(contentHash, RecordTransition.Ignored("ocr_empty"))
                return@withContext ProcessingOutcome.Ignored("画像から文字を読み取れませんでした")
            }

            val candidate = extractor.extract(
                recognized.map { line ->
                    OcrLine(
                        text = line.text,
                        bounds = OcrBounds(line.left, line.top, line.right, line.bottom),
                    )
                },
            )
            val zone = ZoneId.systemDefault()
            val captureReference = Instant.ofEpochMilli(capturedAt).atZone(zone).toLocalDateTime()
            val now = LocalDateTime.now(zone)
            val normalization = normalizer.normalize(candidate, captureReference)
            val classification = gate.classify(normalization, now)
            if (classification != Classification.AUTO_ADD) {
                requireTransition(
                    contentHash,
                    RecordTransition.NeedsReview(classification.name.lowercase()),
                )
                return@withContext ProcessingOutcome.NeedsReview(ProcessingDiagnostic.describe(classification, candidate))
            }

            val event = checkNotNull(normalization.event)
            val calendarId = selectCalendarId() ?: run {
                requireTransition(contentHash, RecordTransition.Failed("calendar_unavailable"))
                return@withContext ProcessingOutcome.Failed("登録先のカレンダーを確認できません")
            }
            val startInstant = event.start.atZone(zone).toInstant()
            val endInstant = event.end.atZone(zone).toInstant()
            val eventSignature = CalendarEventFactory.signature(event.title, startInstant, calendarId)
            if (repository.findByEventSignature(eventSignature) != null) {
                requireTransition(contentHash, RecordTransition.Ignored("duplicate_event"))
                return@withContext ProcessingOutcome.Ignored("同じ予定が登録済みです")
            }

            try {
                requireTransition(contentHash, RecordTransition.CandidateReady(eventSignature))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                requireTransition(contentHash, RecordTransition.Ignored("duplicate_event_race"))
                return@withContext ProcessingOutcome.Ignored("同じ予定の登録処理が重複しました")
            }
            val operationToken = UUID.randomUUID().toString()
            requireTransition(
                contentHash,
                RecordTransition.BeginAdding(operationToken, calendarId),
            )

            val draft = CalendarEventDraft(
                title = event.title,
                startInstant = startInstant,
                endInstant = endInstant,
                timeZoneId = zone.id,
            )
            return@withContext when (
                val result = calendarGateway.addEvent(calendarId, draft, operationToken)
            ) {
                is AddEventResult.Added -> {
                    requireTransition(
                        contentHash,
                        RecordTransition.AutoAdded(calendarId, result.eventId),
                    )
                    ProcessingOutcome.Added(contentHash)
                }
                is AddEventResult.ReconcileRequired -> reconcileUnknownAdd(
                    contentHash = contentHash,
                    calendarId = calendarId,
                    operationToken = result.operationToken,
                    signature = result.signature,
                )
                AddEventResult.PermissionDenied,
                AddEventResult.CalendarUnavailable,
                AddEventResult.ReminderUnsupported,
                AddEventResult.ProviderUnavailable,
                -> {
                    requireTransition(
                        contentHash,
                        RecordTransition.Failed("calendar_known_failure"),
                    )
                    ProcessingOutcome.Failed("カレンダー書き込み権限・同期先を確認してください")
                }
            }
        } catch (error: CancellationException) {
            settleUnexpected(contentHash, "processing_cancelled")
            throw error
        } catch (_: Exception) {
            settleUnexpected(contentHash, "processing_failed")
            ProcessingOutcome.Failed("画像の解析または処理に失敗しました")
        }
    }

    private suspend fun reconcileUnknownAdd(
        contentHash: String,
        calendarId: Long,
        operationToken: String,
        signature: String,
    ): ProcessingOutcome {
        requireTransition(
            contentHash,
            RecordTransition.AddReconcileRequired("calendar_result_unknown"),
        )
        return when (val result = calendarGateway.reconcileAdd(operationToken, signature)) {
            is AddReconcileResult.Adopted -> {
                requireTransition(
                    contentHash,
                    RecordTransition.AutoAdded(calendarId, result.eventId),
                )
                ProcessingOutcome.Added(contentHash)
            }
            AddReconcileResult.NoMatch -> {
                requireTransition(contentHash, RecordTransition.Failed("add_not_committed"))
                ProcessingOutcome.Failed("予定の書き込みが完了しませんでした")
            }
            AddReconcileResult.SignatureMismatch,
            is AddReconcileResult.Ambiguous,
            -> {
                requireTransition(contentHash, RecordTransition.Failed("add_reconcile_unsafe"))
                ProcessingOutcome.Failed("登録結果を安全に確認できませんでした")
            }
            AddReconcileResult.PermissionDenied,
            AddReconcileResult.ProviderUnavailable,
            -> ProcessingOutcome.Failed("カレンダーの応答を確認できません")
        }
    }

    private suspend fun requireTransition(
        contentHash: String,
        transition: RecordTransition,
    ) {
        checkNotNull(repository.transition(contentHash, transition)) {
            "Processing record is unavailable"
        }
    }

    private suspend fun settleUnexpected(
        contentHash: String?,
        errorCode: String,
    ) = withContext(NonCancellable + Dispatchers.IO) {
        if (contentHash == null) return@withContext
        runCatching {
            val record = repository.findByContentHash(contentHash) ?: return@runCatching
            when (record.status) {
                RecordStatus.ADDING -> requireTransition(
                    contentHash,
                    RecordTransition.AddReconcileRequired(errorCode),
                )
                RecordStatus.ADD_RECONCILE_REQUIRED,
                RecordStatus.AUTO_ADDED,
                RecordStatus.NEEDS_REVIEW,
                RecordStatus.IGNORED,
                RecordStatus.FAILED,
                RecordStatus.UNDONE,
                -> Unit
                RecordStatus.QUEUED,
                RecordStatus.RECOGNIZING,
                RecordStatus.CANDIDATE_READY,
                RecordStatus.UNDO_PENDING,
                RecordStatus.DELETE_RECONCILE_REQUIRED,
                -> requireTransition(contentHash, RecordTransition.Failed(errorCode))
            }
        }
    }

    private fun selectCalendarId(): Long? {
        preferences.calendarId.takeIf { it != MonitoringPreferences.NO_CALENDAR }?.let { return it }
        val first = (calendarGateway.listWritableCalendars() as? CalendarListResult.Available)
            ?.calendars
            ?.firstOrNull()
            ?.id
            ?: return null
        preferences.calendarId = first
        return first
    }

    private fun hash(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val stream = checkNotNull(appContext.contentResolver.openInputStream(uri)) {
            "Image stream is unavailable"
        }
        stream.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    override fun close() {
        try {
            recognizer.close()
        } finally {
            database.close()
        }
    }
}
