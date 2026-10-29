package com.tazawa.snapcal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tazawa.snapcal.capture.MonitoringPreferences
import com.tazawa.snapcal.capture.MonitoringService
import com.tazawa.snapcal.calendar.CalendarGateway
import com.tazawa.snapcal.calendar.CalendarListResult
import com.tazawa.snapcal.calendar.WritableCalendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var monitoringPreferences: MonitoringPreferences
    private var monitoringEnabled by mutableStateOf(false)
    private var writableCalendars by mutableStateOf<List<WritableCalendar>>(emptyList())
    private var selectedCalendarId by mutableStateOf(MonitoringPreferences.NO_CALENDAR)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (requiredPermissions().all { result[it] == true || checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            refreshWritableCalendars()
            startMonitoring()
        }
    }

    private val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (hasCalendarPermission()) refreshWritableCalendars()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        monitoringPreferences = MonitoringPreferences(this)
        monitoringEnabled = monitoringPreferences.enabled
        selectedCalendarId = monitoringPreferences.calendarId
        enableEdgeToEdge()
        setContent {
            SnapCalApp(
                monitoringEnabled = monitoringEnabled,
                calendars = writableCalendars,
                selectedCalendarId = selectedCalendarId,
                onRequestCalendars = ::requestCalendarAccess,
                onSelectCalendar = ::selectCalendar,
                onStartMonitoring = ::beginMonitoringSetup,
                onStopMonitoring = ::stopMonitoring,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (::monitoringPreferences.isInitialized) monitoringEnabled = monitoringPreferences.enabled
        if (::monitoringPreferences.isInitialized && hasCalendarPermission()) refreshWritableCalendars()
    }

    private fun requestCalendarAccess() {
        val missing = calendarPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) refreshWritableCalendars()
        else calendarPermissionLauncher.launch(missing.toTypedArray())
    }

    private fun refreshWritableCalendars() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                CalendarGateway(contentResolver).listWritableCalendars()
            }
            if (result is CalendarListResult.Available) {
                writableCalendars = result.calendars
                val saved = monitoringPreferences.calendarId
                val selected = saved.takeIf { id -> result.calendars.any { it.id == id } }
                    ?: result.calendars.firstOrNull()?.id
                    ?: MonitoringPreferences.NO_CALENDAR
                selectedCalendarId = selected
                monitoringPreferences.calendarId = selected
            }
        }
    }

    private fun selectCalendar(calendarId: Long) {
        if (writableCalendars.none { it.id == calendarId }) return
        monitoringPreferences.calendarId = calendarId
        selectedCalendarId = calendarId
    }

    private fun beginMonitoringSetup() {
        val missing = requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray()) else startMonitoring()
    }

    private fun startMonitoring() {
        startForegroundService(Intent(this, MonitoringService::class.java).setAction(MonitoringService.ACTION_START))
        monitoringPreferences.enabled = true
        monitoringEnabled = true
    }

    private fun stopMonitoring() {
        startService(Intent(this, MonitoringService::class.java).setAction(MonitoringService.ACTION_STOP))
        monitoringPreferences.enabled = false
        monitoringEnabled = false
    }

    private fun requiredPermissions(): List<String> = buildList {
        addAll(calendarPermissions())
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun calendarPermissions() = listOf(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    )

    private fun hasCalendarPermission(): Boolean = calendarPermissions().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }
}

internal fun calendarAccountLabel(accountName: String): String {
    val normalized = accountName.trim().replace(Regex("\\s+"), " ")
    val at = normalized.indexOf('@')
    if (at <= 0 || at == normalized.lastIndex) return normalized
    val local = normalized.substring(0, at)
    val maskedLocal = if (local.length == 1) "${local.first()}•••" else "${local.first()}•••${local.last()}"
    return maskedLocal + normalized.substring(at)
}

@Composable
private fun SnapCalApp(
    monitoringEnabled: Boolean,
    calendars: List<WritableCalendar>,
    selectedCalendarId: Long,
    onRequestCalendars: () -> Unit,
    onSelectCalendar: (Long) -> Unit,
    onStartMonitoring: () -> Unit,
    onStopMonitoring: () -> Unit,
) {
    val background = Color(0xFF071311)
    val panel = Color(0xFF10241F)
    val ink = Color(0xFFEDF7F5)
    val muted = Color(0xFFA5BCB7)
    val mint = Color(0xFF54F0BA)
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(background)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 22.dp, vertical = 58.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    text = "SNAPCAL · ON DEVICE",
                    color = mint,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                )
                Text(
                    text = "スクショを、\n次の予定へ。",
                    color = ink,
                    fontSize = 44.sp,
                    lineHeight = 47.sp,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    text = "画像は外へ送信しません。日本語OCRと予定判定は、すべてこの端末内で実行します。",
                    color = muted,
                    fontSize = 15.sp,
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = panel),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, Color(0xFF23483E)),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("自動登録するカレンダー", color = ink, fontWeight = FontWeight.Bold)
                        if (calendars.isEmpty()) {
                            Text(
                                "カレンダー権限を許可すると、書き込み可能な登録先だけを端末から読み込みます。",
                                color = muted,
                                fontSize = 13.sp,
                            )
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onRequestCalendars,
                                border = BorderStroke(1.dp, mint),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = mint),
                            ) { Text("カレンダーを選ぶ") }
                        } else {
                            calendars.forEach { calendar ->
                                val selected = calendar.id == selectedCalendarId
                                val accountLabel = calendarAccountLabel(calendar.accountName)
                                OutlinedButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = { onSelectCalendar(calendar.id) },
                                    border = BorderStroke(1.dp, if (selected) mint else Color(0xFF35584F)),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = if (selected) Color(0xFF173C31) else Color.Transparent,
                                        contentColor = if (selected) mint else ink,
                                    ),
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = if (selected) "✓ ${calendar.displayName}" else calendar.displayName,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        )
                                        if (accountLabel.isNotEmpty()) {
                                            Text(
                                                modifier = Modifier.fillMaxWidth(),
                                                text = accountLabel,
                                                color = if (selected) mint.copy(alpha = 0.72f) else muted,
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = TextAlign.Center,
                                            )
                                        }
                                    }
                                }
                            }
                            Text(
                                text = "Googleカレンダーは、Androidのアカウント同期と「他のアプリとのデータ共有」を有効にすると表示されます。",
                                color = muted,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = panel),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, Color(0xFF23483E)),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(15.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .background(if (monitoringEnabled) mint else muted, CircleShape),
                            )
                            Text(
                                modifier = Modifier.padding(start = 10.dp),
                                text = if (monitoringEnabled) "常駐監視中" else "常駐監視は停止中",
                                color = ink,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Text(
                            text = "画面にバブルを表示せず、新しいスクショをバックグラウンドで処理します。監視は通知から停止できます。",
                            color = muted,
                            fontSize = 13.sp,
                        )
                        if (monitoringEnabled) {
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                onClick = onStopMonitoring,
                                border = BorderStroke(1.dp, Color(0xFFFF7F86)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFFB7BB)),
                            ) { Text("監視を停止") }
                        } else {
                            Button(
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                onClick = onStartMonitoring,
                                colors = ButtonDefaults.buttonColors(containerColor = mint, contentColor = Color(0xFF041C15)),
                            ) { Text("常駐監視を開始", fontWeight = FontWeight.Bold) }
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1B17)),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, Color(0xFF1C3A32)),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("権限を広げたくない場合", color = ink, fontWeight = FontWeight.Bold)
                        Text(
                            "GalleryやLINEの共有ボタンからSnapCalを選べば、画像1枚の一時権限だけで処理できます。",
                            color = muted,
                            fontSize = 13.sp,
                        )
                    }
                }

                Text(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    text = "予定は選択したカレンダー提供元へ同期される場合があります。追加後は通知から取り消せます。",
                    color = muted,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
