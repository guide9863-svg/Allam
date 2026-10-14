package com.allam.alarm.core

import kotlinx.serialization.Serializable
import java.time.LocalDate

/** 근무 종류 (주간, 야간, 당번, 비번, 휴무 ...). */
@Serializable
data class ShiftType(
    val id: String,
    val name: String,
    val short: String,
    val colorArgb: Long,
    val isWork: Boolean = true,
)

/**
 * 교대 근무표.
 *
 * cycle 은 근무 종류 id 의 반복 순서이고, anchorEpochDay 날짜가 cycle[0] (1일차)이다.
 * overrides 는 근무 교환/변경으로 특정 날짜만 다른 근무가 된 경우를 저장한다.
 */
@Serializable
data class ShiftSchedule(
    val types: List<ShiftType> = ShiftPresets.defaultTypes,
    val cycle: List<String> = emptyList(),
    val anchorEpochDay: Long = 0,
    val overrides: Map<Long, String> = emptyMap(),
    val presetName: String? = null,
) {
    val isConfigured: Boolean get() = cycle.isNotEmpty()

    fun type(id: String): ShiftType? = types.firstOrNull { it.id == id }

    /** 해당 날짜가 패턴의 몇 번째 칸(0부터)인지. 패턴이 비어 있으면 null. */
    fun cycleIndexOn(date: LocalDate): Int? {
        if (cycle.isEmpty()) return null
        return Math.floorMod(date.toEpochDay() - anchorEpochDay, cycle.size.toLong()).toInt()
    }

    fun isOverridden(date: LocalDate): Boolean = overrides.containsKey(date.toEpochDay())

    fun typeIdOn(date: LocalDate): String? =
        overrides[date.toEpochDay()] ?: cycleIndexOn(date)?.let { cycle[it] }

    fun typeOn(date: LocalDate): ShiftType? = typeIdOn(date)?.let(::type)

    /** today 가 패턴의 index 번째 칸이 되도록 시작일을 다시 잡는다. */
    fun withDateAtIndex(date: LocalDate, index: Int): ShiftSchedule =
        copy(anchorEpochDay = date.toEpochDay() - index)

    fun withOverride(date: LocalDate, typeId: String?): ShiftSchedule {
        val key = date.toEpochDay()
        val next = overrides.toMutableMap()
        if (typeId == null || typeId == cycleIndexOn(date)?.let { cycle[it] }) next.remove(key) else next[key] = typeId
        return copy(overrides = next)
    }

    /** 오래된 변경 기록 정리. */
    fun pruneOverrides(before: LocalDate): ShiftSchedule =
        copy(overrides = overrides.filterKeys { it >= before.toEpochDay() })

    fun applyPreset(preset: ShiftPreset, today: LocalDate, todayIndex: Int = 0): ShiftSchedule {
        val mergedTypes = types.toMutableList()
        for (t in ShiftPresets.defaultTypes) if (mergedTypes.none { it.id == t.id }) mergedTypes += t
        return copy(
            types = mergedTypes,
            cycle = preset.cycle,
            presetName = preset.name,
        ).withDateAtIndex(today, todayIndex.coerceIn(0, preset.cycle.size - 1))
    }
}

data class ShiftPreset(val name: String, val description: String, val cycle: List<String>)

object ShiftPresets {
    const val DAY = "day"
    const val NIGHT = "night"
    const val DUTY = "duty"
    const val OFF_DUTY = "offduty"
    const val REST = "rest"

    val defaultTypes: List<ShiftType> = listOf(
        ShiftType(DUTY, "당번", "당", 0xFFEF4444, isWork = true),
        ShiftType(DAY, "주간", "주", 0xFFF59E0B, isWork = true),
        ShiftType(NIGHT, "야간", "야", 0xFF6366F1, isWork = true),
        ShiftType(OFF_DUTY, "비번", "비", 0xFF10B981, isWork = false),
        ShiftType(REST, "휴무", "휴", 0xFF9CA3AF, isWork = false),
    )

    val presets: List<ShiftPreset> = listOf(
        ShiftPreset("격일제 (당번·비번)", "24시간 근무 → 24시간 휴식, 2일 주기", listOf(DUTY, OFF_DUTY)),
        ShiftPreset("당·비·휴", "24시간 근무 → 비번 → 휴무, 3일 주기", listOf(DUTY, OFF_DUTY, REST)),
        ShiftPreset("당·비·비", "24시간 근무 → 이틀 휴식, 3일 주기", listOf(DUTY, OFF_DUTY, OFF_DUTY)),
        ShiftPreset("주·야·비·휴", "4조 2교대 (12시간), 4일 주기", listOf(DAY, NIGHT, OFF_DUTY, REST)),
        ShiftPreset(
            "주·주·야·야·비·비",
            "3조 2교대 (12시간), 6일 주기",
            listOf(DAY, DAY, NIGHT, NIGHT, OFF_DUTY, OFF_DUTY),
        ),
        ShiftPreset(
            "주·주·야·야·비·비·휴·휴",
            "4조 2교대 (12시간), 8일 주기",
            listOf(DAY, DAY, NIGHT, NIGHT, OFF_DUTY, OFF_DUTY, REST, REST),
        ),
    )

    /** 사용자 추가 근무 종류에 쓸 색상 팔레트. */
    val palette: List<Long> = listOf(
        0xFFEF4444, 0xFFF97316, 0xFFF59E0B, 0xFF84CC16, 0xFF10B981,
        0xFF06B6D4, 0xFF3B82F6, 0xFF6366F1, 0xFFA855F7, 0xFFEC4899, 0xFF9CA3AF,
    )
}
