package com.allam.alarm.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.allam.alarm.core.Alarm
import com.allam.alarm.core.AlarmCalculator
import com.allam.alarm.core.ShiftSchedule
import com.allam.alarm.core.SnoozeState
import com.allam.alarm.data.AlarmRepository
import com.allam.alarm.data.AlarmStore
import com.allam.alarm.ui.MainActivity
import java.time.Instant
import java.time.ZonedDateTime

/**
 * AlarmManager.setAlarmClock 으로 알람을 등록한다.
 * setAlarmClock 은 Doze(절전) 상태에서도 정확한 시각에 깨우는, 시계 앱용 API 이다.
 */
object AlarmScheduler {
    const val ACTION_FIRE = "com.allam.alarm.action.FIRE"
    const val EXTRA_ALARM_ID = "alarm_id"
    const val EXTRA_SNOOZE_COUNT = "snooze_count"
    const val EXTRA_IS_SNOOZE = "is_snooze"
    const val EXTRA_IS_TEST = "is_test"
    const val EXTRA_TRIGGER_AT = "trigger_at"

    /** 미리 울려보기에서 쓰는 기본 알람 id (저장되지 않음). */
    const val TEST_ALARM_ID = 0

    private const val SNOOZE_REQUEST_OFFSET = 1_000_000
    private const val TEST_REQUEST_CODE = 2_000_000

    /** 재부팅 등으로 놓친 스누즈는 이 시간 이내면 바로 울리고, 더 오래됐으면 버린다. */
    private const val STALE_SNOOZE_MS = 30 * 60 * 1000L

    private fun alarmManager(context: Context): AlarmManager = context.service()

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager(context).canScheduleExactAlarms()

    fun rescheduleAll(context: Context) {
        val data = AlarmRepository.get(context)
        val now = ZonedDateTime.now()
        for (alarm in data.alarms) schedule(context, alarm, data.schedule, now)

        val nowMs = System.currentTimeMillis()
        val (stale, live) = data.snoozes.partition { it.triggerAtMillis < nowMs - STALE_SNOOZE_MS }
        for (s in stale) AlarmStore.removeSnooze(context, s.alarmId)
        for (s in live) scheduleSnooze(context, s)
    }

    /** 한 알람의 다음 울림만 다시 등록 (after 이후의 첫 시각). */
    fun schedule(context: Context, alarm: Alarm, schedule: ShiftSchedule, after: ZonedDateTime) {
        val next = if (alarm.enabled) AlarmCalculator.nextTrigger(alarm, schedule, after) else null
        val operation = firePendingIntent(context, alarm.id, next?.toInstant()?.toEpochMilli() ?: 0L)
        if (next == null) {
            alarmManager(context).cancel(operation)
            return
        }
        setAlarm(context, next.toInstant().toEpochMilli(), operation)
    }

    fun nextTriggerMillis(alarm: Alarm, schedule: ShiftSchedule): Long? =
        if (!alarm.enabled) null
        else AlarmCalculator.nextTrigger(alarm, schedule, ZonedDateTime.now())?.toInstant()?.toEpochMilli()

    fun cancel(context: Context, alarmId: Int) {
        alarmManager(context).cancel(firePendingIntent(context, alarmId, 0L))
    }

    fun scheduleSnooze(context: Context, state: SnoozeState) {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .setData(Uri.parse("allam://snooze/${state.alarmId}"))
            .putExtra(EXTRA_ALARM_ID, state.alarmId)
            .putExtra(EXTRA_SNOOZE_COUNT, state.snoozeCount)
            .putExtra(EXTRA_IS_SNOOZE, true)
            .putExtra(EXTRA_TRIGGER_AT, state.triggerAtMillis)
        val pi = PendingIntent.getBroadcast(
            context,
            SNOOZE_REQUEST_OFFSET + state.alarmId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        setAlarm(context, state.triggerAtMillis, pi)
    }

    fun cancelSnooze(context: Context, alarmId: Int) {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .setData(Uri.parse("allam://snooze/$alarmId"))
        val pi = PendingIntent.getBroadcast(
            context,
            SNOOZE_REQUEST_OFFSET + alarmId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager(context).cancel(pi)
        Notifications.cancelSnooze(context, alarmId)
    }

    /** delaySec 초 뒤에 해당 알람 설정으로 미리 울려본다. */
    fun scheduleTest(context: Context, alarmId: Int, delaySec: Int = 10): Long {
        val at = System.currentTimeMillis() + delaySec * 1000L
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .setData(Uri.parse("allam://test"))
            .putExtra(EXTRA_ALARM_ID, alarmId)
            .putExtra(EXTRA_IS_TEST, true)
            .putExtra(EXTRA_TRIGGER_AT, at)
        val pi = PendingIntent.getBroadcast(
            context,
            TEST_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        setAlarm(context, at, pi)
        return at
    }

    private fun firePendingIntent(context: Context, alarmId: Int, triggerAt: Long): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .setData(Uri.parse("allam://alarm/$alarmId"))
            .putExtra(EXTRA_ALARM_ID, alarmId)
            .putExtra(EXTRA_TRIGGER_AT, triggerAt)
        return PendingIntent.getBroadcast(
            context,
            alarmId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun setAlarm(context: Context, triggerAt: Long, operation: PendingIntent) {
        val am = alarmManager(context)
        if (canScheduleExact(context)) {
            val show = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), operation)
        } else {
            // 정확한 알람 권한이 없을 때의 차선책 (몇 분 늦을 수 있음). 설정 화면에서 경고한다.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
        }
    }

    fun instantOf(millis: Long): ZonedDateTime =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), java.time.ZoneId.systemDefault())
}
