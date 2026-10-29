package com.tazawa.snapcal.capture

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tazawa.snapcal.notification.NotificationCenter
import com.tazawa.snapcal.processing.ProcessingOutcome
import com.tazawa.snapcal.processing.ProcessingRuntime
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ShareUiState {
    data object AwaitingPermission : ShareUiState
    data object Processing : ShareUiState
    data class Finished(val outcome: ProcessingOutcome) : ShareUiState
    data object InvalidInput : ShareUiState
}

class ShareProcessingViewModel(application: Application) : AndroidViewModel(application) {
    private val runtime = ProcessingRuntime(application)
    private val notificationCenter = NotificationCenter(application).also { it.ensureChannels() }
    private val mutableState = MutableStateFlow<ShareUiState>(ShareUiState.AwaitingPermission)
    val state: StateFlow<ShareUiState> = mutableState.asStateFlow()

    private var pendingUri: Uri? = null
    private var job: Job? = null

    fun setInput(uri: Uri?) {
        if (pendingUri != null || job != null) return
        pendingUri = uri?.takeIf { it.scheme == "content" }
        if (pendingUri == null) mutableState.value = ShareUiState.InvalidInput
    }

    fun start() {
        if (job != null) return
        val uri = pendingUri ?: run {
            mutableState.value = ShareUiState.InvalidInput
            return
        }
        mutableState.value = ShareUiState.Processing
        job = viewModelScope.launch {
            val outcome = try {
                runtime.process(uri, System.currentTimeMillis())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ProcessingOutcome.Failed("共有画像の処理に失敗しました")
            } finally {
                pendingUri = null
            }
            runCatching { notificationCenter.postResult(outcome) }
            mutableState.value = ShareUiState.Finished(outcome)
        }
    }

    override fun onCleared() {
        val activeJob = job
        if (activeJob == null) {
            runtime.close()
        } else {
            activeJob.cancel()
            activeJob.invokeOnCompletion { runtime.close() }
        }
        pendingUri = null
        super.onCleared()
    }
}
