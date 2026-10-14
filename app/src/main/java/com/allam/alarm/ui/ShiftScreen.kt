@file:OptIn(ExperimentalLayoutApi::class)

package com.allam.alarm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allam.alarm.core.AlarmCalculator
import com.allam.alarm.core.AppData
import com.allam.alarm.core.ShiftPreset
import com.allam.alarm.core.ShiftPresets
import com.allam.alarm.core.ShiftSchedule
import com.allam.alarm.core.ShiftType
import com.allam.alarm.data.AlarmStore
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun ShiftScreen(data: AppData, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val schedule = data.schedule
    val today = LocalDate.now()
    var showPresets by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }
    var overrideDay by remember { mutableStateOf<LocalDate?>(null) }
    var editType by remember { mutableStateOf<ShiftType?>(null) }
    var addingType by remember { mutableStateOf(false) }
    var month by remember { mutableStateOf(YearMonth.now()) }

    fun save(s: ShiftSchedule) = AlarmStore.setSchedule(context, s)

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("교대 근무표", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        Section("내 근무 패턴") {
            if (!schedule.isConfigured) {
                Text("아직 패턴이 없어요. 내 교대 방식을 골라주세요.")
                Spacer(Modifier.height(8.dp))
                Button(onClick = { showPresets = true }) { Text("패턴 선택하기") }
            } else {
                val idx = schedule.cycleIndexOn(today) ?: 0
                Text(
                    (schedule.presetName ?: "직접 만든 패턴") + " · ${schedule.cycle.size}일 주기",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                CycleRow(schedule, highlight = idx)
                Spacer(Modifier.height(8.dp))
                val todayType = schedule.typeOn(today)
                Text(
                    "오늘(${dayLabel(today, today).removePrefix("오늘 ")}) 근무: ${todayType?.name ?: "-"}" +
                        if (schedule.isOverridden(today)) " (변경됨)" else " · 패턴 ${idx + 1}일차",
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showPresets = true }) { Text("패턴 바꾸기") }
                    OutlinedButton(onClick = { showEditor = true }) { Text("직접 편집") }
                }
            }
        }

        if (schedule.isConfigured) {
            Section("오늘 근무 맞추기") {
                Text("오늘이 패턴의 몇 번째 날인지 누르면 전체 일정이 맞춰집니다.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                TodayIndexPicker(schedule.cycle, schedule, schedule.cycleIndexOn(today) ?: 0) { i ->
                    save(schedule.withDateAtIndex(today, i))
                }
            }

            Section("달력") {
                MonthHeader(month, onPrev = { month = month.minusMonths(1) }, onNext = { month = month.plusMonths(1) })
                ShiftCalendar(data, month, today) { overrideDay = it }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                    Text(" 알람 울리는 날   ", style = MaterialTheme.typography.bodySmall)
                    Text("테두리 = 근무 변경한 날 · 날짜를 누르면 근무 변경", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Section("근무 종류") {
            schedule.types.forEach { t ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { editType = t }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ShiftBadge(t, 26.dp, 13)
                    Spacer(Modifier.width(12.dp))
                    Text(t.name, modifier = Modifier.weight(1f))
                    Text(if (t.isWork) "근무" else "쉬는 날", style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = { addingType = true }) { Text("+ 근무 종류 추가") }
        }
    }

    if (showPresets) {
        PresetDialog(
            onDismiss = { showPresets = false },
            onApply = { preset, index ->
                save(schedule.applyPreset(preset, today, index))
                showPresets = false
            },
        )
    }
    if (showEditor) {
        PatternEditorDialog(
            schedule = schedule,
            today = today,
            onDismiss = { showEditor = false },
            onSave = { cycle, index ->
                save(schedule.copy(cycle = cycle, presetName = null).withDateAtIndex(today, index))
                showEditor = false
            },
        )
    }
    overrideDay?.let { day ->
        OverrideDialog(
            schedule = schedule,
            day = day,
            onDismiss = { overrideDay = null },
            onSelect = { typeId ->
                save(schedule.withOverride(day, typeId))
                overrideDay = null
            },
        )
    }
    editType?.let { t ->
        TypeEditorDialog(
            initial = t,
            canDelete = t.id !in schedule.cycle,
            onDismiss = { editType = null },
            onSave = { updated ->
                save(schedule.copy(types = schedule.types.map { if (it.id == updated.id) updated else it }))
                editType = null
            },
            onDelete = {
                save(
                    schedule.copy(
                        types = schedule.types.filterNot { it.id == t.id },
                        overrides = schedule.overrides.filterValues { it != t.id },
                    ),
                )
                editType = null
            },
        )
    }
    if (addingType) {
        TypeEditorDialog(
            initial = ShiftType(
                id = "custom_${System.currentTimeMillis()}",
                name = "",
                short = "",
                colorArgb = ShiftPresets.palette[schedule.types.size % ShiftPresets.palette.size],
            ),
            canDelete = false,
            onDismiss = { addingType = false },
            onSave = { t ->
                save(schedule.copy(types = schedule.types + t))
                addingType = false
            },
            onDelete = {},
        )
    }
}

@Composable
private fun CycleRow(schedule: ShiftSchedule, highlight: Int?) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        schedule.cycle.forEachIndexed { i, id ->
            val t = schedule.type(id)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    if (i == highlight) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(8.dp)).padding(2.dp)
                    } else Modifier.padding(2.dp),
                ) { ShiftBadge(t, 30.dp, 14) }
                Text("${i + 1}", fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun TodayIndexPicker(cycle: List<String>, schedule: ShiftSchedule, selected: Int, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        cycle.forEachIndexed { i, id ->
            val t = schedule.type(id) ?: ShiftPresets.defaultTypes.firstOrNull { it.id == id }
            FilterChip(
                selected = i == selected,
                onClick = { onSelect(i) },
                label = { Text("${i + 1}일차 ${t?.name ?: "?"}") },
                leadingIcon = { t?.let { ColorDot(shiftColor(it), 10.dp) } },
            )
        }
    }
}

@Composable
private fun MonthHeader(month: YearMonth, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "이전 달") }
        Text(
            "${month.year}년 ${month.monthValue}월",
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
        )
        IconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "다음 달") }
    }
}

@Composable
private fun ShiftCalendar(data: AppData, month: YearMonth, today: LocalDate, onDayClick: (LocalDate) -> Unit) {
    val schedule = data.schedule
    val first = month.atDay(1)
    val days = month.lengthOfMonth()
    val offset = first.dayOfWeek.value % 7 // 일요일 시작
    val ringDays = remember(data, month) {
        data.alarms.filter { it.enabled }
            .flatMap { AlarmCalculator.ringDates(it, schedule, first, days) }
            .toSet()
    }
    val rows = (offset + days + 6) / 7
    val weekNames = listOf("일", "월", "화", "수", "목", "금", "토")

    Column {
        Row {
            weekNames.forEachIndexed { i, n ->
                Text(
                    n,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (i) {
                        0 -> Color(0xFFDC2626)
                        6 -> Color(0xFF2563EB)
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        for (r in 0 until rows) {
            Row {
                for (c in 0 until 7) {
                    val dayNum = r * 7 + c - offset + 1
                    if (dayNum !in 1..days) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        val date = month.atDay(dayNum)
                        val type = schedule.typeOn(date)
                        val isToday = date == today
                        val overridden = schedule.isOverridden(date)
                        Column(
                            Modifier
                                .weight(1f)
                                .aspectRatio(0.78f)
                                .padding(1.dp)
                                .background(
                                    if (isToday) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                    RoundedCornerShape(8.dp),
                                )
                                .clickable { onDayClick(date) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                "$dayNum",
                                fontSize = 12.sp,
                                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                color = when (c) {
                                    0 -> Color(0xFFDC2626)
                                    6 -> Color(0xFF2563EB)
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                            )
                            Box(
                                if (overridden) {
                                    Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(7.dp)).padding(1.dp)
                                } else Modifier.padding(1.dp),
                            ) { ShiftBadge(type, 22.dp, 11) }
                            Box(
                                Modifier
                                    .padding(top = 2.dp)
                                    .size(5.dp)
                                    .background(
                                        if (date in ringDays) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        CircleShape,
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetDialog(onDismiss: () -> Unit, onApply: (ShiftPreset, Int) -> Unit) {
    var chosen by remember { mutableStateOf<ShiftPreset?>(null) }
    var index by remember { mutableStateOf(0) }
    val types = ShiftSchedule()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (chosen == null) "교대 패턴 선택" else "오늘은 몇 번째 날인가요?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val c = chosen
                if (c == null) {
                    ShiftPresets.presets.forEach { p ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { chosen = p; index = 0 }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(p.name, fontWeight = FontWeight.Bold)
                            Text(p.description, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                p.cycle.forEach { ShiftBadge(types.type(it), 20.dp, 11) }
                            }
                        }
                        HorizontalDivider()
                    }
                } else {
                    Text("${c.name}: 오늘 근무에 해당하는 칸을 고르세요.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    TodayIndexPicker(c.cycle, types, index) { index = it }
                }
            }
        },
        confirmButton = {
            val c = chosen
            if (c != null) TextButton(onClick = { onApply(c, index) }) { Text("적용") }
        },
        dismissButton = {
            TextButton(onClick = { if (chosen != null) chosen = null else onDismiss() }) {
                Text(if (chosen != null) "이전" else "취소")
            }
        },
    )
}

@Composable
private fun PatternEditorDialog(
    schedule: ShiftSchedule,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSave: (List<String>, Int) -> Unit,
) {
    var cycle by remember { mutableStateOf(schedule.cycle) }
    var index by remember { mutableStateOf(schedule.cycleIndexOn(today) ?: 0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("패턴 직접 만들기") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("아래 근무를 순서대로 눌러 한 주기를 만드세요. 위의 칸을 누르면 지워집니다.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    cycle.forEachIndexed { i, id ->
                        Box(Modifier.clickable {
                            cycle = cycle.toMutableList().also { it.removeAt(i) }
                            if (index >= cycle.size) index = (cycle.size - 1).coerceAtLeast(0)
                        }) { ShiftBadge(schedule.type(id), 30.dp, 14) }
                    }
                    if (cycle.isEmpty()) Text("(비어 있음)", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    schedule.types.forEach { t ->
                        OutlinedButton(onClick = { if (cycle.size < 60) cycle = cycle + t.id }) {
                            ColorDot(shiftColor(t), 10.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(t.name)
                        }
                    }
                }
                if (cycle.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("오늘은 몇 번째 날인가요?", fontWeight = FontWeight.SemiBold)
                    TodayIndexPicker(cycle, schedule, index.coerceAtMost(cycle.size - 1)) { index = it }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = cycle.isNotEmpty(), onClick = { onSave(cycle, index.coerceIn(0, cycle.size - 1)) }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun OverrideDialog(schedule: ShiftSchedule, day: LocalDate, onDismiss: () -> Unit, onSelect: (String?) -> Unit) {
    val patternType = schedule.cycleIndexOn(day)?.let { schedule.type(schedule.cycle[it]) }
    val current = schedule.typeIdOn(day)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${day.monthValue}월 ${day.dayOfMonth}일(${AlarmCalculator.dayName(day.dayOfWeek)}) 근무") },
        text = {
            Column {
                Text("근무 교환·변경이 있으면 이 날만 바꿀 수 있어요.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(null) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = !schedule.isOverridden(day), onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text("패턴대로 (${patternType?.name ?: "-"})")
                }
                schedule.types.forEach { t ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(t.id) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = schedule.isOverridden(day) && current == t.id, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        ShiftBadge(t, 22.dp, 11)
                        Spacer(Modifier.width(8.dp))
                        Text(t.name)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
private fun TypeEditorDialog(
    initial: ShiftType,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onSave: (ShiftType) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var short by remember { mutableStateOf(initial.short) }
    var color by remember { mutableStateOf(initial.colorArgb) }
    var isWork by remember { mutableStateOf(initial.isWork) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isEmpty()) "근무 종류 추가" else "근무 종류 편집") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(8) },
                    label = { Text("이름 (예: 오전)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = short,
                    onValueChange = { short = it.take(2) },
                    label = { Text("달력 표시 (1~2글자)") },
                    singleLine = true,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ShiftPresets.palette.forEach { c ->
                        Box(
                            Modifier
                                .size(28.dp)
                                .background(Color(c.toInt()), CircleShape)
                                .then(
                                    if (c == color) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier,
                                )
                                .clickable { color = c },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isWork, onCheckedChange = { isWork = it })
                    Text("근무하는 날")
                }
                if (canDelete && initial.name.isNotEmpty()) {
                    TextButton(onClick = onDelete) { Text("이 근무 종류 삭제", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim(),
                            short = short.trim().ifEmpty { name.trim().take(1) },
                            colorArgb = color,
                            isWork = isWork,
                        ),
                    )
                },
            ) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
