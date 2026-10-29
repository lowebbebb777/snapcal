package com.tazawa.snapcal.capture

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tazawa.snapcal.processing.ProcessingOutcome

class ShareReceiverActivity : ComponentActivity() {
    private val viewModel: ShareProcessingViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (hasRequiredPermissions()) viewModel.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.setInput(sharedUri())
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            ShareScreen(
                state = state,
                onGrantAndStart = {
                    if (hasRequiredPermissions()) {
                        viewModel.start()
                    } else {
                        permissionLauncher.launch(requiredPermissions().toTypedArray())
                    }
                },
                onClose = ::finish,
                onChooseCandidates = { id ->
                    startActivity(ReviewChoiceActivity.intent(this, id))
                    finish()
                },
            )
        }
    }

    private fun sharedUri(): Uri? {
        if (intent.action != android.content.Intent.ACTION_SEND ||
            intent.type?.startsWith("image/") != true
        ) {
            return null
        }
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
        }
    }

    private fun hasCalendarPermission(): Boolean =
        checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.READ_CALENDAR)
        add(Manifest.permission.WRITE_CALENDAR)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun hasRequiredPermissions(): Boolean =
        hasCalendarPermission() &&
            (Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
}

@Composable
private fun ShareScreen(
    state: ShareUiState,
    onGrantAndStart: () -> Unit,
    onClose: () -> Unit,
    onChooseCandidates: (String) -> Unit,
) {
    val background = Color(0xFF071311)
    val ink = Color(0xFFEDF7F5)
    val muted = Color(0xFFA5BCB7)
    val mint = Color(0xFF54F0BA)
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(background)
                    .padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.Start,
            ) {
                Text("SNAPCAL · SHARE", color = mint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(
                    modifier = Modifier.padding(top = 10.dp, bottom = 22.dp),
                    text = titleFor(state),
                    color = ink,
                    fontSize = 34.sp,
                    lineHeight = 38.sp,
                    fontWeight = FontWeight.Black,
                )
                Text(textFor(state), color = muted, fontSize = 15.sp)

                when (state) {
                    ShareUiState.AwaitingPermission -> Button(
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                        onClick = onGrantAndStart,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = mint, contentColor = Color(0xFF041C15)),
                    ) { Text("カレンダーと通知を許可して処理", fontWeight = FontWeight.Bold) }
                    ShareUiState.Processing -> CircularProgressIndicator(
                        modifier = Modifier.padding(top = 28.dp),
                        color = mint,
                    )
                    else -> {
                        val session = ((state as? ShareUiState.Finished)?.outcome as? ProcessingOutcome.NeedsReview)
                            ?.reviewSessionId
                        Button(
                            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                            onClick = if (session != null) {{ onChooseCandidates(session) }} else onClose,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (session != null) mint else Color(0xFF17352D),
                                contentColor = if (session != null) background else ink,
                            ),
                        ) { Text(if (session != null) "候補から選んで登録" else "閉じる") }
                    }
                }
            }
        }
    }
}

private fun titleFor(state: ShareUiState): String = when (state) {
    ShareUiState.AwaitingPermission -> "画像を端末内で\n解析します"
    ShareUiState.Processing -> "予定情報を\n読み取っています"
    ShareUiState.InvalidInput -> "画像を\n読み込めませんでした"
    is ShareUiState.Finished -> when (state.outcome) {
        is ProcessingOutcome.Added -> "カレンダーへ\n追加しました"
        is ProcessingOutcome.NeedsReview -> "今回は\n追加しませんでした"
        is ProcessingOutcome.Ignored -> "予定情報を\n確認できませんでした"
        is ProcessingOutcome.Failed -> "処理に\n失敗しました"
    }
}

private fun textFor(state: ShareUiState): String = when (state) {
    ShareUiState.AwaitingPermission -> "画像とOCR全文は保存・送信しません。予定は選択したカレンダー提供元へ同期される場合があります。"
    ShareUiState.Processing -> "この画面を閉じると安全に中断します。画像の参照は処理後すぐ破棄します。"
    ShareUiState.InvalidInput -> "Galleryなどから画像を1枚だけ共有してください。"
    is ShareUiState.Finished -> when (state.outcome) {
        is ProcessingOutcome.Added -> "追加結果は通知から確認・取り消しできます。"
        is ProcessingOutcome.NeedsReview -> state.outcome.detail
        is ProcessingOutcome.Ignored -> state.outcome.detail
        is ProcessingOutcome.Failed -> state.outcome.detail
    }
}
