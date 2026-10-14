package com.allam.alarm.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class AlarmCalculatorTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 10, 14) // 수요일

    private fun at(date: LocalDate, h: Int, m: Int) = ZonedDateTime.of(date, java.time.LocalTime.of(h, m), seoul)

    private val alternating = ShiftSchedule().applyPreset(ShiftPresets.presets[0], today, 0) // 오늘 = 당번

    @Test
    fun alternatingDayShift_ringsEveryOtherDay() {
        assertEquals(ShiftPresets.DUTY, alternating.typeIdOn(today))
        assertEquals(ShiftPresets.OFF_DUTY, alternating.typeIdOn(today.plusDays(1)))
        assertEquals(ShiftPresets.DUTY, alternating.typeIdOn(today.plusDays(2)))
        assertEquals(ShiftPresets.OFF_DUTY, alternating.typeIdOn(today.minusDays(1)))
        assertEquals(ShiftPresets.DUTY, alternating.typeIdOn(today.minusDays(200)))

        val alarm = Alarm(id = 1, hour = 7, minute = 0, repeat = RepeatRule.Shift(setOf(ShiftPresets.DUTY)))
        // 오늘 06:00 기준 → 오늘 07:00
        assertEquals(at(today, 7, 0), AlarmCalculator.nextTrigger(alarm, alternating, at(today, 6, 0)))
        // 오늘 07:00 지난 뒤 → 모레 07:00 (내일은 비번)
        assertEquals(at(today.plusDays(2), 7, 0), AlarmCalculator.nextTrigger(alarm, alternating, at(today, 7, 0)))
    }

    @Test
    fun fourDayCycle_dayAndNightAlarms() {
        // 오늘이 '야' (index 1) 인 주·야·비·휴
        val s = ShiftSchedule().applyPreset(ShiftPresets.presets[3], today, 1)
        assertEquals(ShiftPresets.NIGHT, s.typeIdOn(today))
        assertEquals(ShiftPresets.OFF_DUTY, s.typeIdOn(today.plusDays(1)))
        assertEquals(ShiftPresets.REST, s.typeIdOn(today.plusDays(2)))
        assertEquals(ShiftPresets.DAY, s.typeIdOn(today.plusDays(3)))

        val dayAlarm = Alarm(id = 1, hour = 6, minute = 30, repeat = RepeatRule.Shift(setOf(ShiftPresets.DAY)))
        val nightAlarm = Alarm(id = 2, hour = 16, minute = 0, repeat = RepeatRule.Shift(setOf(ShiftPresets.NIGHT)))
        val now = at(today, 9, 0)
        assertEquals(at(today.plusDays(3), 6, 30), AlarmCalculator.nextTrigger(dayAlarm, s, now))
        assertEquals(at(today, 16, 0), AlarmCalculator.nextTrigger(nightAlarm, s, now))
        assertEquals(at(today.plusDays(4), 16, 0), AlarmCalculator.nextTrigger(nightAlarm, s, at(today, 16, 1)))
    }

    @Test
    fun override_swapsSingleDay() {
        val tomorrow = today.plusDays(1)
        val s = alternating.withOverride(tomorrow, ShiftPresets.DUTY).withOverride(today, ShiftPresets.OFF_DUTY)
        val alarm = Alarm(id = 1, hour = 7, minute = 0, repeat = RepeatRule.Shift(setOf(ShiftPresets.DUTY)))
        assertEquals(at(tomorrow, 7, 0), AlarmCalculator.nextTrigger(alarm, s, at(today, 5, 0)))
        assertTrue(s.isOverridden(tomorrow))
        // 패턴과 같은 값으로 바꾸면 override 가 제거된다
        val reverted = s.withOverride(tomorrow, ShiftPresets.OFF_DUTY)
        assertFalse(reverted.isOverridden(tomorrow))
        assertEquals(ShiftPresets.OFF_DUTY, reverted.typeIdOn(tomorrow))
    }

    @Test
    fun unconfiguredSchedule_neverRings() {
        val alarm = Alarm(id = 1, hour = 7, minute = 0, repeat = RepeatRule.Shift(setOf(ShiftPresets.DUTY)))
        assertNull(AlarmCalculator.nextTrigger(alarm, ShiftSchedule(), at(today, 5, 0)))
    }

    @Test
    fun once_rollsToTomorrowWhenPassed() {
        val alarm = Alarm(id = 1, hour = 7, minute = 0)
        assertEquals(at(today, 7, 0), AlarmCalculator.nextTrigger(alarm, ShiftSchedule(), at(today, 6, 59)))
        assertEquals(at(today.plusDays(1), 7, 0), AlarmCalculator.nextTrigger(alarm, ShiftSchedule(), at(today, 7, 0)))
    }

    @Test
    fun weekly_picksNextMatchingDay() {
        val alarm = Alarm(id = 1, hour = 8, minute = 0, repeat = RepeatRule.Weekly(setOf(1, 5))) // 월, 금
        assertEquals(at(LocalDate.of(2026, 10, 16), 8, 0), AlarmCalculator.nextTrigger(alarm, ShiftSchedule(), at(today, 9, 0)))
        assertEquals("매주 월, 금", AlarmCalculator.describeRepeat(alarm.repeat, ShiftSchedule()))
    }

    @Test
    fun skipNext_skipsOnlyOneOccurrence() {
        val alarm = Alarm(id = 1, hour = 7, minute = 0, repeat = RepeatRule.Shift(setOf(ShiftPresets.DUTY)))
        val now = at(today, 5, 0)
        val skipped = AlarmCalculator.skipNext(alarm, alternating, now)
        assertEquals(at(today.plusDays(2), 7, 0), AlarmCalculator.nextTrigger(skipped, alternating, now))
        assertTrue(AlarmCalculator.hasUpcomingSkip(skipped, today))
        assertEquals(at(today, 7, 0), AlarmCalculator.nextTrigger(AlarmCalculator.clearSkips(skipped), alternating, now))
        assertTrue(AlarmCalculator.pruneSkips(skipped, today.plusDays(1)).skipEpochDays.isEmpty())
    }

    @Test
    fun ringDates_forCalendar() {
        val alarm = Alarm(id = 1, hour = 7, minute = 0, repeat = RepeatRule.Shift(setOf(ShiftPresets.DUTY)))
        val dates = AlarmCalculator.ringDates(alarm, alternating, today, 6)
        assertEquals(listOf(today, today.plusDays(2), today.plusDays(4)), dates)
    }

    @Test
    fun describeAndFormat() {
        assertEquals("교대 연동: 당번 날", AlarmCalculator.describeRepeat(RepeatRule.Shift(setOf(ShiftPresets.DUTY)), alternating))
        assertEquals("매일", AlarmCalculator.describeRepeat(RepeatRule.Weekly((1..7).toSet()), alternating))
        assertEquals("7시간 23분 후", AlarmCalculator.formatRemaining(Duration.ofMinutes(7 * 60 + 23)))
        assertEquals("1일 2시간 후", AlarmCalculator.formatRemaining(Duration.ofHours(26)))
        assertEquals("1분 이내", AlarmCalculator.formatRemaining(Duration.ZERO))
        assertEquals("5분 후", AlarmCalculator.formatRemaining(Duration.ofSeconds(4 * 60 + 1)))
    }

    @Test
    fun appData_roundTripsThroughJson() {
        val data = AppData(
            alarms = listOf(
                Alarm(id = 1, hour = 7, minute = 0, repeat = RepeatRule.Shift(setOf(ShiftPresets.DUTY)), skipEpochDays = setOf(20000L)),
                Alarm(id = 2, hour = 6, minute = 15, repeat = RepeatRule.Weekly(setOf(1, 2)), sound = SoundType.FIRE_BELL),
                Alarm(id = 3, hour = 9, minute = 45),
            ),
            schedule = alternating.withOverride(today, ShiftPresets.REST),
            nextAlarmId = 4,
            snoozes = listOf(SnoozeState(1, 123L, 1)),
            logs = listOf(WakeLog(1, "출근", 1L, 2L, WakeOutcome.STEPS, 30, 10, 0, 1)),
        )
        val text = AppData.encode(data)
        assertEquals(data, AppData.decode(text))
        assertEquals(AppData(), AppData.decode("{broken"))
        assertEquals(AppData(), AppData.decode(null))
    }
}
