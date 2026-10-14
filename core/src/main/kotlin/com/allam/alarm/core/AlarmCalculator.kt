package com.allam.alarm.core

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

object AlarmCalculator {
    /** 교대 패턴은 최대 수십 일 주기이므로 400일이면 충분하다. */
    const val SEARCH_DAYS = 400L

    fun matches(rule: RepeatRule, date: LocalDate, schedule: ShiftSchedule): Boolean = when (rule) {
        RepeatRule.Once -> true
        is RepeatRule.Weekly -> date.dayOfWeek.value in rule.days
        is RepeatRule.Shift -> schedule.typeIdOn(date)?.let { it in rule.typeIds } ?: false
    }

    /**
     * after 보다 엄격하게 뒤에 오는 다음 울림 시각. 울릴 날이 없으면 null.
     * 꺼져 있는 알람도 계산은 해 준다 (켜기 전에 미리 보여주기 위해) — 스케줄러가 enabled 를 따로 확인한다.
     */
    fun nextTrigger(
        alarm: Alarm,
        schedule: ShiftSchedule,
        after: ZonedDateTime,
        honorSkips: Boolean = true,
    ): ZonedDateTime? {
        val time = LocalTime.of(alarm.hour, alarm.minute)
        val start = after.toLocalDate()
        for (i in 0..SEARCH_DAYS) {
            val date = start.plusDays(i)
            val candidate = ZonedDateTime.of(date, time, after.zone)
            if (!candidate.isAfter(after)) continue
            if (honorSkips && date.toEpochDay() in alarm.skipEpochDays) continue
            if (matches(alarm.repeat, date, schedule)) return candidate
        }
        return null
    }

    /** from 부터 days 일 동안 이 알람이 울리는 날짜들 (달력 표시용, 시각 무시). */
    fun ringDates(alarm: Alarm, schedule: ShiftSchedule, from: LocalDate, days: Int): List<LocalDate> =
        (0 until days).map { from.plusDays(it.toLong()) }.filter { date ->
            date.toEpochDay() !in alarm.skipEpochDays && matches(alarm.repeat, date, schedule)
        }

    /** 다음 1회 건너뛰기: 다음에 울릴 날짜를 skip 목록에 추가. */
    fun skipNext(alarm: Alarm, schedule: ShiftSchedule, now: ZonedDateTime): Alarm {
        val next = nextTrigger(alarm, schedule, now) ?: return alarm
        return alarm.copy(skipEpochDays = alarm.skipEpochDays + next.toLocalDate().toEpochDay())
    }

    /** 오늘 이후의 건너뛰기를 모두 취소. */
    fun clearSkips(alarm: Alarm): Alarm = alarm.copy(skipEpochDays = emptySet())

    fun pruneSkips(alarm: Alarm, today: LocalDate): Alarm =
        alarm.copy(skipEpochDays = alarm.skipEpochDays.filter { it >= today.toEpochDay() }.toSet())

    fun hasUpcomingSkip(alarm: Alarm, today: LocalDate): Boolean =
        alarm.skipEpochDays.any { it >= today.toEpochDay() }

    private val dayNames = mapOf(1 to "월", 2 to "화", 3 to "수", 4 to "목", 5 to "금", 6 to "토", 7 to "일")

    fun dayName(day: DayOfWeek): String = dayNames.getValue(day.value)

    fun describeRepeat(rule: RepeatRule, schedule: ShiftSchedule): String = when (rule) {
        RepeatRule.Once -> "한 번"
        is RepeatRule.Weekly -> when {
            rule.days.size == 7 -> "매일"
            rule.days == setOf(1, 2, 3, 4, 5) -> "주중 (월~금)"
            rule.days == setOf(6, 7) -> "주말"
            rule.days.isEmpty() -> "요일 선택 안 됨"
            else -> "매주 " + rule.days.sorted().joinToString(", ") { dayNames.getValue(it) }
        }
        is RepeatRule.Shift -> {
            val names = schedule.types.filter { it.id in rule.typeIds }.map { it.name }
            when {
                !schedule.isConfigured -> "교대 연동 (교대표 미설정)"
                names.isEmpty() -> "교대 연동 (근무 선택 안 됨)"
                else -> "교대 연동: " + names.joinToString(", ") + " 날"
            }
        }
    }

    /** "7시간 23분 후" 형식. */
    fun formatRemaining(duration: Duration): String {
        val totalMinutes = ((duration.seconds + 59) / 60).coerceAtLeast(0)
        val days = totalMinutes / (60 * 24)
        val hours = (totalMinutes / 60) % 24
        val minutes = totalMinutes % 60
        val parts = buildList {
            if (days > 0) add("${days}일")
            if (hours > 0) add("${hours}시간")
            if (minutes > 0 || (days == 0L && hours == 0L)) add("${minutes}분")
        }
        return if (totalMinutes == 0L) "1분 이내" else parts.joinToString(" ") + " 후"
    }
}
