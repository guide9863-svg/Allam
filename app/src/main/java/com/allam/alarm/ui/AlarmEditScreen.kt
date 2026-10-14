@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.allam.alarm.ui

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import com.allam.alarm.alarm.AlarmScheduler
import com.allam.alarm.alarm.AlarmSoundPlayer
import com.allam.alarm.alarm.SensorAvailability
import com.allam.alarm.core.Alarm
import com.allam.alarm.core.AlarmCalculator
import com.allam.alarm.core.RepeatRule
import com.allam.alarm.core.ShiftSchedule
import com.allam.alarm.core.SoundType
import com.allam.alarm.core.WakeMode
import com.allam.alarm.data.AlarmStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

private enum class RepeatKind(val title: String) { SHIFT("교대 근무"), WEEKLY("요일"), ONCE("한 번") }

private fun Alarm.repeatKind() = when (repeat) {
    is RepeatRule.Shift -> RepeatKind.SHIFT
    is RepeatRule.Weekly -> RepeatKind.WEEKLY
    RepeatRule.Once -> RepeatKind.ONCE
}

@Composable
fun AlarmEditScreen(initial: Alarm, schedule: ShiftSchedule, onClose: () -> Unit) {
    val context = LocalContext.current
    val isNew = initial.id <= 0
    var draft by remember { mutableStateOf(initial) }
    val timeState = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = DateFormat.is24HourFormat(context),
    )
    // 반복 종류를 바꿔도 이전 선택을 기억
    var shiftIds by remember { mutableStateOf((initial.repeat as? RepeatRule.Shift)?.typeIds ?: emptySet()) }
    var weekDays by remember { mutableStateOf((initial.repeat as? RepeatRule.Weekly)?.days ?: setOf(1, 2, 3, 4, 5)) }
    var kind by remember { mutableStateOf(initial.repeatKind()) }

    fun build(): Alarm = draft.copy(
        hour = timeState.hour,
        minute = timeState.minute,
        repeat = when (kind) {
            RepeatKind.SHIFT -> RepeatRule.Shift(shiftIds)
            RepeatKind.WEEKLY -> if (weekDays.isEmpty()) RepeatRule.Once else RepeatRule.Weekly(weekDays)
            RepeatKind.ONCE -> RepeatRule.Once
        },
        enabled = true,
    )

    fun save(): Alarm? {
        val a = build()
        if (kind == RepeatKind.SHIFT && shiftIds.isEmpty()) {
            Toast.makeText(context, "울릴 근무를 하나 이상 선택하세요", Toast.LENGTH_SHORT).show()
            return null
        }
        return AlarmStore.save(context, a)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "알람 추가" else "알람 편집") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    TextButton(onClick = { if (save() != null) onClose() }) { Text("저장", fontWeight = FontWeight.Bold) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(state = timeState)
            }

            OutlinedTextField(
                value = draft.label,
                onValueChange = { draft = draft.copy(label = it.take(30)) },
                label = { Text("이름 (예: 당번 출근)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Section("반복") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    RepeatKind.entries.forEachIndexed { i, k ->
                        SegmentedButton(
                            selected = kind == k,
                            onClick = { kind = k },
                            shape = SegmentedButtonDefaults.itemShape(i, RepeatKind.entries.size),
                        ) { Text(k.title) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                when (kind) {
                    RepeatKind.SHIFT -> ShiftRepeatPicker(schedule, shiftIds) { shiftIds = it }
                    RepeatKind.WEEKLY -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1 to "월", 2 to "화", 3 to "수", 4 to "목", 5 to "금", 6 to "토", 7 to "일").forEach { (d, name) ->
                            FilterChip(
                                selected = d in weekDays,
                                onClick = { weekDays = if (d in weekDays) weekDays - d else weekDays + d },
                                label = { Text(name) },
                            )
                        }
                    }
                    RepeatKind.ONCE -> Text("다음 해당 시각에 한 번 울리고 자동으로 꺼집니다.", style = MaterialTheme.typography.bodySmall)
                }
                val preview = remember(kind, shiftIds, weekDays, schedule) {
                    val probe = build().copy(skipEpochDays = emptySet())
                    AlarmCalculator.ringDates(probe, schedule, LocalDate.now(), 14)
                }
                if (kind != RepeatKind.ONCE) {
                    Spacer(Modifier.height(6.dp))
                    val today = LocalDate.now()
                    Text(
                        if (preview.isEmpty()) "앞으로 2주 동안 울리는 날이 없어요"
                        else "앞으로 2주: " + preview.joinToString(", ") { dayLabel(it, today).removePrefix("오늘 ").removePrefix("내일 ").removePrefix("모레 ") },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            WakeCheckSection(draft) { draft = it }
            SoundSection(draft) { draft = it }

            Section("스누즈 (다시 알림)") {
                Stepper(
                    "스누즈 횟수",
                    if (draft.maxSnoozes == 0) "사용 안 함" else "${draft.maxSnoozes}회",
                    onMinus = { draft = draft.copy(maxSnoozes = (draft.maxSnoozes - 1).coerceAtLeast(0)) },
                    onPlus = { draft = draft.copy(maxSnoozes = (draft.maxSnoozes + 1).coerceAtMost(5)) },
                )
                if (draft.maxSnoozes > 0) {
                    Stepper(
                        "간격",
                        "${draft.snoozeMinutes}분",
                        onMinus = { draft = draft.copy(snoozeMinutes = (draft.snoozeMinutes - 1).coerceAtLeast(1)) },
                        onPlus = { draft = draft.copy(snoozeMinutes = (draft.snoozeMinutes + 1).coerceAtMost(30)) },
                    )
                }
                Text(
                    "스누즈를 다 쓰면 반드시 움직여야만 꺼집니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            OutlinedButton(
                onClick = {
                    val saved = save() ?: return@OutlinedButton
                    AlarmScheduler.scheduleTest(context, saved.id)
                    Toast.makeText(context, "저장했어요. 10초 뒤 이 설정으로 울립니다.", Toast.LENGTH_LONG).show()
                    onClose()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("저장하고 10초 뒤 미리 울려보기") }

            if (!isNew) {
                TextButton(
                    onClick = {
                        AlarmStore.delete(context, initial.id)
                        onClose()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("이 알람 삭제", color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ShiftRepeatPicker(schedule: ShiftSchedule, selected: Set<String>, onChange: (Set<String>) -> Unit) {
    if (!schedule.isConfigured) {
        Text(
            "교대표가 아직 설정되지 않았어요. 아래 근무를 골라 두고, 교대표 탭에서 패턴을 설정하면 그 날짜에 울립니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(6.dp))
    }
    Text("어떤 근무 날에 울릴까요?", style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        schedule.types.forEach { t ->
            FilterChip(
                selected = t.id in selected,
                onClick = { onChange(if (t.id in selected) selected - t.id else selected + t.id) },
                label = { Text(t.name) },
                leadingIcon = { ColorDot(shiftColor(t), 10.dp) },
            )
        }
    }
}

@Composable
private fun WakeCheckSection(draft: Alarm, onChange: (Alarm) -> Unit) {
    val context = LocalContext.current
    val hardwareSteps = remember { SensorAvailability.of(context).hardwareSteps }
    val w = draft.wakeCheck
    Section("기상 확인 (움직여야 꺼짐)") {
        Text(
            "알람이 울리면 '소리 끄고 움직이기'를 누르고 휴대폰을 들고 걷거나 계속 움직여야 꺼집니다. " +
                "${w.stillTimeoutSec}초 동안 움직임이 없으면 진행도가 0이 되고 다시 울립니다.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            WakeMode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = w.mode == m,
                    onClick = { onChange(draft.copy(wakeCheck = w.copy(mode = m))) },
                    shape = SegmentedButtonDefaults.itemShape(i, WakeMode.entries.size),
                ) { Text(m.title) }
            }
        }
        if (w.mode == WakeMode.STEPS_ONLY && !hardwareSteps) {
            Text(
                "이 휴대폰은 걸음 센서(또는 신체 활동 권한)가 없어 가속도로 걸음을 추정합니다. " +
                    "엄격 모드여도 위아래로 흔들면 걸음으로 셀 수 있어요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        LabeledSlider("목표 걸음 수", "${w.targetSteps}걸음", w.targetSteps, 10..100, 5) {
            onChange(draft.copy(wakeCheck = w.copy(targetSteps = it)))
        }
        if (w.mode == WakeMode.STEPS_OR_MOTION) {
            LabeledSlider("또는 연속 움직임", "${w.targetMotionSec}초", w.targetMotionSec, 10..120, 5) {
                onChange(draft.copy(wakeCheck = w.copy(targetMotionSec = it)))
            }
        }
        LabeledSlider("멈춤 허용 시간", "${w.stillTimeoutSec}초", w.stillTimeoutSec, 10..60, 5) {
            onChange(draft.copy(wakeCheck = w.copy(stillTimeoutSec = it)))
        }
        Text("움직임 감도", style = MaterialTheme.typography.bodyMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("둔감 (크게)", "보통", "민감").forEachIndexed { i, label ->
                SegmentedButton(
                    selected = w.sensitivity == i,
                    onClick = { onChange(draft.copy(wakeCheck = w.copy(sensitivity = i))) },
                    shape = SegmentedButtonDefaults.itemShape(i, 3),
                ) { Text(label) }
            }
        }
    }
}

@Composable
private fun SoundSection(draft: Alarm, onChange: (Alarm) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var previewJob by remember { mutableStateOf<Job?>(null) }
    var previewPlayer by remember { mutableStateOf<AlarmSoundPlayer?>(null) }
    DisposableEffect(Unit) {
        onDispose { previewPlayer?.release() }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@rememberLauncherForActivityResult
        val uri = IntentCompat.getParcelableExtra(data, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        if (uri != null) onChange(draft.copy(sound = SoundType.SYSTEM, ringtoneUri = uri.toString()))
    }

    Section("소리 · 진동") {
        SoundType.entries.forEach { type ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = draft.sound == type,
                        onClick = { onChange(draft.copy(sound = type)) },
                        role = Role.RadioButton,
                    )
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = draft.sound == type, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(type.title)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                previewJob?.cancel()
                previewPlayer?.release()
                val p = AlarmSoundPlayer(context, draft.sound, draft.ringtoneUri)
                previewPlayer = p
                p.start()
                previewJob = scope.launch {
                    delay(3000)
                    p.release()
                }
            }) { Text("3초 미리 듣기") }
            if (draft.sound == SoundType.SYSTEM) {
                OutlinedButton(onClick = {
                    val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        .putExtra(
                            RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                            draft.ringtoneUri?.let(Uri::parse),
                        )
                    runCatching { picker.launch(intent) }
                }) { Text("알람음 고르기") }
            }
        }
        Text("미리 듣기는 현재 알람 볼륨으로 재생돼요. 실제 알람은 최대 볼륨으로 울립니다.", style = MaterialTheme.typography.bodySmall)
        SwitchRow("알람 볼륨 최대로 고정", "울리는 동안 볼륨을 줄여도 다시 최대로 올립니다", draft.forceMaxVolume) {
            onChange(draft.copy(forceMaxVolume = it))
        }
        SwitchRow("진동", null, draft.vibrate) { onChange(draft.copy(vibrate = it)) }
        SwitchRow("플래시 깜빡이기", "카메라 플래시를 빠르게 깜빡입니다", draft.flash) { onChange(draft.copy(flash = it)) }
    }
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Stepper(title: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onMinus) { Text("−") }
        Text(value, modifier = Modifier.width(88.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        OutlinedButton(onClick = onPlus) { Text("+") }
    }
}

@Composable
private fun LabeledSlider(title: String, valueText: String, value: Int, range: IntRange, step: Int, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(title, modifier = Modifier.weight(1f))
            Text(valueText, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { v -> onChange(((v / step).roundToInt() * step).coerceIn(range.first, range.last)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first) / step - 1,
        )
    }
}
