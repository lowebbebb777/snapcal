package com.tazawa.snapcal.capture

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.tazawa.snapcal.domain.NormalizedEvent
import com.tazawa.snapcal.notification.NotificationCenter
import com.tazawa.snapcal.processing.ProcessingOutcome
import com.tazawa.snapcal.processing.ProcessingRuntime
import com.tazawa.snapcal.processing.ReviewSessionStore
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * A one-tap review screen. Candidate text remains in process memory only;
 * the Intent contains an opaque session UUID, never OCR or patient content.
 */
class ReviewChoiceActivity : ComponentActivity() {
    private var display by mutableStateOf<ReviewDisplay>(ReviewDisplay.Expired)
    private var sessionId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val id = intent.getStringExtra(EXTRA_SESSION_ID)
            ?.takeIf { SESSION_UUID.matches(it) }
        sessionId = id
        display = id?.let(ReviewSessionStore::get)
            ?.takeIf { it.isNotEmpty() }
            ?.let(ReviewDisplay::Options)
            ?: ReviewDisplay.Expired

        setContent {
            ReviewScreen(
                display = display,
                onChoose = ::choose,
                onClose = ::finish,
            )
        }
    }

    private fun choose(index: Int) {
        if (display !is ReviewDisplay.Options) return
        val id = sessionId ?: return
        val event = ReviewSessionStore.consume(id, index)
        if (event == null) {
            display = ReviewDisplay.Expired
            return
        }
        display = ReviewDisplay.Saving
        lifecycleScope.launch {
            val result = try {
                ProcessingRuntime(applicationContext).use { runtime ->
                    runtime.addReviewedEvent(event)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ProcessingOutcome.Failed("選択した予定を登録できませんでした")
            }
            runCatching {
                NotificationCenter(applicationContext).apply {
                    ensureChannels()
                    postResult(result)
                }
            }
            display = ReviewDisplay.Completed(result)
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "com.tazawa.snapcal.extra.REVIEW_SESSION_ID"
        private val SESSION_UUID = Regex("[a-f0-9-]{36}")
        fun intent(context: android.content.Context, sessionId: String): Intent =
            Intent(context, ReviewChoiceActivity::class.java)
                .putExtra(EXTRA_SESSION_ID, sessionId)
    }
}

private sealed interface ReviewDisplay {
    data class Options(val events: List<NormalizedEvent>) : ReviewDisplay
    data object Saving : ReviewDisplay
    data object Expired : ReviewDisplay
    data class Completed(val result: ProcessingOutcome) : ReviewDisplay
}

@Composable
private fun ReviewScreen(
    display: ReviewDisplay,
    onChoose: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val background = Color(0xFF071311)
    val ink = Color(0xFFEDF7F5)
    val mint = Color(0xFF54F0BA)
    val muted = Color(0xFFA5BCB7)
    val formatter = DateTimeFormatter.ofPattern("yyyy年M月d日(E) H:mm", Locale.JAPAN)
    MaterialTheme {
        Surface(color = background, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(background)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 54.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("SNAPCAL · REVIEW", color = mint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("予定を選んで登録", color = ink, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                when (display) {
                    is ReviewDisplay.Options -> {
                        Text(
                            "確定できない日時があります。登録したい予定を1件だけタップしてください。",
                            color = muted,
                        )
                        display.events.forEachIndexed { index, event ->
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { onChoose(index) },
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF173C31),
                                    contentColor = ink,
                                ),
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                ) {
                                    Text(event.title, fontWeight = FontWeight.Bold)
                                    Text(event.start.format(formatter), color = mint)
                                    Text("終了 ${event.end.format(formatter)}", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    ReviewDisplay.Saving -> Text("カレンダーに登録しています…", color = muted)
                    ReviewDisplay.Expired ->
                        Text("候補の有効期限が切れました。もう一度スクショを撮ってください。", color = muted)
                    is ReviewDisplay.Completed -> {
                        val message = when (val outcome = display.result) {
                            is ProcessingOutcome.Added -> "予定を登録しました"
                            is ProcessingOutcome.NeedsReview -> outcome.detail
                            is ProcessingOutcome.Ignored -> outcome.detail
                            is ProcessingOutcome.Failed -> outcome.detail
                        }
                        Text(message, color = muted)
                    }
                }
                if (display != ReviewDisplay.Saving) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onClose,
                        colors = ButtonDefaults.buttonColors(containerColor = mint, contentColor = background),
                    ) { Text("閉じる") }
                }
            }
        }
    }
}
