package com.tazawa.snapcal.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.tazawa.snapcal.MainActivity
import com.tazawa.snapcal.R
import com.tazawa.snapcal.capture.MonitoringService
import com.tazawa.snapcal.processing.ProcessingOutcome

class NotificationCenter(
    private val context: Context,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_MONITORING,
                    "スクリーンショット監視",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "SnapCalの監視状態"
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
                NotificationChannel(
                    CHANNEL_RESULTS,
                    "解析結果",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "予定の追加結果"
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
            ),
        )
    }

    fun monitoring(): Notification {
        val openIntent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            context,
            2,
            Intent(context, MonitoringService::class.java).setAction(MonitoringService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_MONITORING)
            .setSmallIcon(R.drawable.ic_snapcal_notification)
            .setContentTitle("SnapCalは監視中です")
            .setContentText("新しいスクリーンショットだけを端末内で処理します")
            .setContentIntent(openIntent)
            .addAction(0, "停止", stopIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
    }

    fun postResult(outcome: ProcessingOutcome) {
        val title = when (outcome) {
            is ProcessingOutcome.Added -> "予定を1件追加しました"
            ProcessingOutcome.NeedsReview -> "予定は追加しませんでした"
            ProcessingOutcome.Ignored -> "予定情報を確認できませんでした"
            ProcessingOutcome.Failed -> "画像を処理できませんでした"
        }
        val openIntent = PendingIntent.getActivity(
            context,
            10,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_snapcal_notification)
            .setContentTitle(title)
            .setContentText("詳細は端末のロックを解除して確認してください")
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .apply {
                if (outcome is ProcessingOutcome.Added) {
                    val undoIntent = PendingIntent.getBroadcast(
                        context,
                        outcome.contentHash.hashCode(),
                        Intent(context, UndoReceiver::class.java)
                            .setAction(UndoReceiver.ACTION_UNDO)
                            .putExtra(UndoReceiver.EXTRA_CONTENT_HASH, outcome.contentHash),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    addAction(0, "取り消す", undoIntent)
                }
            }
            .build()
        manager.notify(RESULT_NOTIFICATION_ID, notification)
    }

    fun postUndoResult(success: Boolean) {
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_snapcal_notification)
            .setContentTitle(if (success) "予定を取り消しました" else "予定を取り消せませんでした")
            .setContentText("詳細は端末のロックを解除して確認してください")
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        manager.notify(RESULT_NOTIFICATION_ID, notification)
    }

    companion object {
        const val MONITORING_NOTIFICATION_ID = 3101
        private const val RESULT_NOTIFICATION_ID = 3102
        private const val CHANNEL_MONITORING = "snapcal_monitoring"
        private const val CHANNEL_RESULTS = "snapcal_results"
    }
}
