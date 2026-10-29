package com.tazawa.snapcal.capture

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import com.tazawa.snapcal.notification.NotificationCenter
import com.tazawa.snapcal.processing.ProcessingOutcome
import com.tazawa.snapcal.processing.ProcessingRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class MonitoringService : Service() {
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)
    private val queue = Channel<CapturedImage>(capacity = 8)
    private lateinit var preferences: MonitoringPreferences
    private lateinit var notificationCenter: NotificationCenter
    private lateinit var runtime: ProcessingRuntime
    private lateinit var observer: ScreenshotObserver
    private var started = false

    override fun onCreate() {
        super.onCreate()
        preferences = MonitoringPreferences(this)
        notificationCenter = NotificationCenter(this).also { it.ensureChannels() }
        runtime = ProcessingRuntime(this)
        observer = ScreenshotObserver(contentResolver, ::offerScreenshot)
        scope.launch {
            for (capture in queue) process(capture)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            preferences.enabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            startForeground(NotificationCenter.MONITORING_NOTIFICATION_ID, notificationCenter.monitoring())
            observer.start()
            preferences.enabled = true
            started = true
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroying = true
        observer.stop()
        queue.close()
        serviceJob.cancel()
        serviceJob.invokeOnCompletion { runtime.close() }
        preferences.enabled = false
        started = false
        super.onDestroy()
    }

    private fun offerScreenshot(uri: Uri, capturedAt: Long) {
        if (!queue.trySend(CapturedImage(uri, capturedAt)).isSuccess) {
            scope.launch {
                if (!destroying) {
                    notificationCenter.postResult(ProcessingOutcome.Failed("スクショ処理待ちがいっぱいです"))
                }
            }
        }
    }

    private suspend fun process(capture: CapturedImage) {
        val outcome = try {
            runtime.process(capture.uri, capture.capturedAt)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            ProcessingOutcome.Failed("スクショ処理でエラーが発生しました")
        }
        if (destroying) return
        notificationCenter.postResult(outcome)
    }

    private data class CapturedImage(val uri: Uri, val capturedAt: Long)

    @Volatile
    private var destroying = false

    companion object {
        const val ACTION_START = "com.tazawa.snapcal.action.START_MONITORING"
        const val ACTION_STOP = "com.tazawa.snapcal.action.STOP_MONITORING"
    }
}
