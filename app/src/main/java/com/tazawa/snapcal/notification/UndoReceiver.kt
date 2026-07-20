package com.tazawa.snapcal.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tazawa.snapcal.processing.UndoCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class UndoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_UNDO) return
        val contentHash = intent.getStringExtra(EXTRA_CONTENT_HASH)
            ?.takeIf { HASH.matches(it) }
            ?: return
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val success = UndoCoordinator(context).use { coordinator ->
                    runCatching { coordinator.undo(contentHash) }.getOrDefault(false)
                }
                runCatching {
                    NotificationCenter(context).apply {
                        ensureChannels()
                        postUndoResult(success)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_UNDO = "com.tazawa.snapcal.action.UNDO"
        const val EXTRA_CONTENT_HASH = "content_hash"
        private val HASH = Regex("[a-f0-9]{64}")
    }
}
