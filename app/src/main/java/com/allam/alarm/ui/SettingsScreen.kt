package com.allam.alarm.ui

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.allam.alarm.alarm.AlarmScheduler
import com.allam.alarm.alarm.SensorAvailability
import com.allam.alarm.core.AppData
import com.allam.alarm.core.WakeOutcome
import com.allam.alarm.data.AlarmStore
import java.time.format.DateTimeFormatter

private val logFmt = DateTimeFormatter.ofPattern("M/d HH:mm")

@Composable
fun SettingsScreen(
    data: AppData,
    permissions: PermissionStatus,
    modifier: Modifier = Modifier,
    onRequestRuntimePermissions: (List<String>) -> Unit,
) {
    val context = LocalContext.current
    val sensors = remember(permissions) { SensorAvailability.of(context) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("설정", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        Section("알람이 확실히 울리려면") {
            PermissionRow("알림 허용", "알람 화면과 소리를 띄우는 데 필요", permissions.notifications) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    onRequestRuntimePermissions(listOf(Manifest.permission.POST_NOTIFICATIONS))
                }
                context.launchSafely(SettingsIntents.notifications(context))
            }
            PermissionRow("정확한 시각 알람", "절전 상태에서도 정시에 울리기", permissions.exactAlarm) {
                context.launchSafely(SettingsIntents.exactAlarm(context))
            }
            PermissionRow("잠금화면 위 전체화면", "화면이 꺼져 있어도 알람 화면 띄우기", permissions.fullScreen) {
                context.launchSafely(SettingsIntents.fullScreen(context))
            }
            PermissionRow("신체 활동 (걸음 감지)", "걸음 센서로 기상 확인 (없으면 가속도로 추정)", permissions.activity) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    onRequestRuntimePermissions(listOf(Manifest.permission.ACTIVITY_RECOGNITION))
                }
                context.launchSafely(SettingsIntents.appDetails(context))
            }
            PermissionRow("배터리 최적화 제외", "삼성 등 일부 폰에서 앱이 잠드는 것 방지 (권장)", permissions.batteryUnrestricted) {
                context.launchSafely(SettingsIntents.battery(context))
            }
        }

        Section("센서 상태") {
            SensorRow("가속도계", sensors.accelerometer)
            SensorRow("자이로스코프", sensors.gyroscope)
            SensorRow("걸음 감지 센서", sensors.hardwareSteps)
            if (!sensors.anyMotion) {
                Text(
                    "움직임 센서가 없어 기상 확인을 할 수 없어요. 알람은 '긴급 해제(계산 문제)'로만 끌 수 있습니다.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (!sensors.hardwareSteps) {
                Text(
                    "걸음 감지 센서를 쓸 수 없어 가속도계로 걸음을 추정합니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Section("테스트") {
            Text("10초 뒤 기본 설정(걸음 20 또는 움직임 15초)으로 울립니다. 화면을 끄고 기다려 보세요.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                AlarmScheduler.scheduleTest(context, AlarmScheduler.TEST_ALARM_ID)
                Toast.makeText(context, "10초 뒤 테스트 알람이 울립니다", Toast.LENGTH_SHORT).show()
            }) { Text("10초 뒤 테스트 알람") }
        }

        Section("기상 기록") {
            if (data.logs.isEmpty()) {
                Text("아직 기록이 없어요.", style = MaterialTheme.typography.bodySmall)
            } else {
                data.logs.take(20).forEach { log ->
                    val start = AlarmScheduler.instantOf(log.ringStartedAt)
                    val minutes = ((log.endedAt - log.ringStartedAt) / 60_000).coerceAtLeast(0)
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(
                            "${start.format(logFmt)} ${log.label.ifBlank { "알람" }}",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            buildString {
                                append(log.outcome.title)
                                if (log.outcome != WakeOutcome.TIMED_OUT) append(" · ${minutes}분 만에")
                                if (log.outcome == WakeOutcome.STEPS) append(" · ${log.steps}걸음")
                                if (log.reRings > 0) append(" · 다시 울림 ${log.reRings}회")
                                if (log.snoozes > 0) append(" · 스누즈 ${log.snoozes}회")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (log.outcome == WakeOutcome.TIMED_OUT) MaterialTheme.colorScheme.error else Color.Unspecified,
                        )
                    }
                    HorizontalDivider()
                }
                TextButton(onClick = { AlarmStore.clearLogs(context) }) { Text("기록 지우기") }
            }
        }

        Section("이렇게 동작해요") {
            listOf(
                "알람은 알람 볼륨으로 울려서 무음·진동 모드여도 울립니다. 기본값은 최대 볼륨 고정입니다.",
                "'소리 끄고 움직이기'를 누르면 잠시 조용해지지만, 정해진 시간 동안 움직임이 없으면 다시 울립니다.",
                "휴대폰을 들고 걷거나(걸음) 계속 움직이면(가속도·자이로) 기상으로 인정되어 완전히 꺼집니다.",
                "울리는 동안에는 알람 진동이 움직임으로 오인되지 않도록 걸음 센서만 세고, 소리를 끈 뒤에는 모든 움직임을 셉니다.",
                "울리는 중에 전원을 껐다 켜거나 앱이 종료돼도, 기상 확인 전이면 1~2분 안에 다시 울립니다.",
                "움직이다 멈추면 진행도가 0으로 돌아갑니다. 침대에서 조금씩 뒤척여 끄는 것을 막기 위함입니다.",
                "GPS는 실내에서 오차가 커서 쓰지 않고, 걸음 센서로 '자리 이동'을 확인합니다.",
                "60분 동안 기상이 확인되지 않으면 안전을 위해 알람을 멈추고 알림을 남깁니다.",
                "몸이 아프거나 센서가 고장 났을 때는 '긴급 해제'(계산 문제 3개)로 끌 수 있습니다.",
            ).forEach {
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text("•  ")
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(title: String, desc: String, ok: Boolean, onFix: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = if (ok) Color(0xFF16A34A) else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(desc, style = MaterialTheme.typography.bodySmall)
        }
        if (!ok) TextButton(onClick = onFix) { Text("설정") }
    }
}

@Composable
private fun SensorRow(name: String, ok: Boolean) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = if (ok) Color(0xFF16A34A) else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(8.dp))
        Text(name + if (ok) " 사용 가능" else " 없음/권한 없음")
    }
}
