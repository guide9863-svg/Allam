package com.allam.alarm.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allam.alarm.alarm.AlarmScheduler
import com.allam.alarm.core.Alarm
import com.allam.alarm.core.AlarmCalculator
import com.allam.alarm.core.AppData
import com.allam.alarm.core.RepeatRule
import com.allam.alarm.data.AlarmStore
import java.time.format.DateTimeFormatter

@Composable
fun AlarmListScreen(
    data: AppData,
    permissions: PermissionStatus,
    modifier: Modifier = Modifier,
    onEdit: (Alarm) -> Unit,
    onAdd: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenShift: () -> Unit,
) {
    val now by rememberNow()
    val sorted = remember(data.alarms) { data.alarms.sortedWith(compareBy({ it.hour }, { it.minute })) }
    val upcoming = remember(data, now) {
        data.alarms.mapNotNull { a ->
            if (!a.enabled) null else AlarmCalculator.nextTrigger(a, data.schedule, now)?.let { a to it }
        }.minByOrNull { it.second }
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("기상 알람", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                val today = now.toLocalDate()
                val todayType = data.schedule.typeOn(today)
                if (todayType != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("오늘 근무 ", style = MaterialTheme.typography.bodyMedium)
                        ShiftBadge(todayType, size = 20.dp, fontSize = 11)
                        Text(" ${todayType.name}", style = MaterialTheme.typography.bodyMedium)
                        val tomorrow = data.schedule.typeOn(today.plusDays(1))
                        if (tomorrow != null) {
                            Text("  ·  내일 ", style = MaterialTheme.typography.bodyMedium)
                            ShiftBadge(tomorrow, size = 20.dp, fontSize = 11)
                            Text(" ${tomorrow.name}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("다음 알람", style = MaterialTheme.typography.labelLarge)
                        if (upcoming == null) {
                            Text("예정된 알람이 없어요", style = MaterialTheme.typography.titleLarge)
                        } else {
                            val (alarm, at) = upcoming
                            Text(
                                nextRingText(at, now),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            val type = data.schedule.typeOn(at.toLocalDate())
                            val sub = listOfNotNull(
                                alarm.label.ifBlank { null },
                                type?.let { "${it.name} 날" },
                            ).joinToString(" · ")
                            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            if (permissions.criticalMissing) {
                item {
                    WarningCard(
                        "알람이 제대로 울리지 않을 수 있어요",
                        "알림·정확한 알람·잠금화면 표시 권한이 필요합니다.",
                        "해결하기",
                        onOpenSettings,
                    )
                }
            }

            if (!data.schedule.isConfigured) {
                item {
                    WarningCard(
                        "교대 근무표를 먼저 설정하세요",
                        "근무 패턴(예: 당번·비번)과 오늘 근무를 알려주면 근무일에 맞춰 알람이 반복됩니다.",
                        "교대표 설정",
                        onOpenShift,
                    )
                }
            }

            if (sorted.isEmpty()) {
                item {
                    Text(
                        "아래 + 버튼으로 알람을 추가하세요.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }

            items(sorted, key = { it.id }) { alarm ->
                AlarmCard(alarm, data, onEdit)
            }
        }

        ExtendedFloatingActionButton(
            onClick = onAdd,
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("알람 추가") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )
    }
}

@Composable
private fun WarningCard(title: String, body: String, action: String, onAction: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(body, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun AlarmCard(alarm: Alarm, data: AppData, onEdit: (Alarm) -> Unit) {
    val context = LocalContext.current
    val now by rememberNow()
    var menu by remember { mutableStateOf(false) }
    val next = if (alarm.enabled) AlarmCalculator.nextTrigger(alarm, data.schedule, now) else null
    val snooze = data.snoozes.firstOrNull { it.alarmId == alarm.id }
    val hasSkip = AlarmCalculator.hasUpcomingSkip(alarm, now.toLocalDate())

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit(alarm) },
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier
                    .weight(1f)
                    .alpha(if (alarm.enabled) 1f else 0.45f),
            ) {
                Text(formatTime(alarm.hour, alarm.minute), fontSize = 40.sp, fontWeight = FontWeight.Light)
                if (alarm.label.isNotBlank()) Text(alarm.label, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (alarm.repeat is RepeatRule.Shift) {
                        (alarm.repeat as RepeatRule.Shift).typeIds.mapNotNull { data.schedule.type(it) }.forEach {
                            ColorDot(shiftColor(it), 10.dp)
                            Spacer(Modifier.width(3.dp))
                        }
                        Spacer(Modifier.width(3.dp))
                    }
                    Text(
                        AlarmCalculator.describeRepeat(alarm.repeat, data.schedule),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val status = when {
                    snooze != null -> "스누즈 중 · " + AlarmScheduler.instantOf(snooze.triggerAtMillis).format(timeFmt) + " 에 다시 울림"
                    !alarm.enabled -> "꺼짐"
                    next == null -> "울릴 날이 없어요 (교대표/반복 설정 확인)"
                    else -> "다음: " + nextRingText(next, now) + if (hasSkip) " (1회 건너뜀)" else ""
                }
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Switch(checked = alarm.enabled, onCheckedChange = { AlarmStore.setEnabled(context, alarm.id, it) })
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "더보기") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (alarm.enabled && alarm.repeat != RepeatRule.Once) {
                        if (hasSkip) {
                            DropdownMenuItem(text = { Text("건너뛰기 취소") }, onClick = {
                                menu = false
                                AlarmStore.clearSkips(context, alarm.id)
                            })
                        } else {
                            DropdownMenuItem(text = { Text("다음 알람 1회 건너뛰기") }, onClick = {
                                menu = false
                                AlarmStore.skipNext(context, alarm.id)
                                Toast.makeText(context, "다음 1회를 건너뜁니다", Toast.LENGTH_SHORT).show()
                            })
                        }
                    }
                    DropdownMenuItem(text = { Text("10초 뒤 미리 울려보기") }, onClick = {
                        menu = false
                        AlarmScheduler.scheduleTest(context, alarm.id)
                        Toast.makeText(context, "10초 뒤 이 알람 설정으로 울립니다. 화면을 꺼 보세요.", Toast.LENGTH_LONG).show()
                    })
                    DropdownMenuItem(text = { Text("삭제") }, onClick = {
                        menu = false
                        AlarmStore.delete(context, alarm.id)
                    })
                }
            }
        }
    }
}
