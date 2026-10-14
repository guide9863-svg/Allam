package com.allam.alarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.allam.alarm.data.AlarmRepository
import com.allam.alarm.data.AlarmStore
import java.time.ZonedDateTime

/** AlarmManager 가 깨우면 호출된다: 다음 회차를 등록하고 울림 서비스를 시작한다. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_FIRE) return
        val alarmId = intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, AlarmScheduler.TEST_ALARM_ID)
        val isSnooze = intent.getBooleanExtra(AlarmScheduler.EXTRA_IS_SNOOZE, false)
        val isTest = intent.getBooleanExtra(AlarmScheduler.EXTRA_IS_TEST, false)
        val snoozeCount = intent.getIntExtra(AlarmScheduler.EXTRA_SNOOZE_COUNT, 0)
        val triggerAt = intent.getLongExtra(AlarmScheduler.EXTRA_TRIGGER_AT, 0L)

        when {
            isTest -> Unit
            isSnooze -> {
                AlarmStore.removeSnooze(context, alarmId)
                Notifications.cancelSnooze(context, alarmId)
                // 스누즈 중에 알람이 삭제됐다면 울리지 않는다
                if (alarmId != AlarmScheduler.TEST_ALARM_ID && AlarmRepository.get(context).alarm(alarmId) == null) return
            }
            else -> {
                val alarm = AlarmRepository.get(context).alarm(alarmId) ?: return
                if (!alarm.enabled) return
                val updated = AlarmStore.onFired(context, alarmId) ?: alarm
                // 같은 시각의 다른 알람을 건드리지 않도록 이 알람만 다음 회차로 등록
                val now = ZonedDateTime.now()
                val firedAt = if (triggerAt > 0) AlarmScheduler.instantOf(triggerAt) else now
                val after = if (firedAt.isAfter(now)) firedAt else now
                AlarmScheduler.schedule(context, updated, AlarmRepository.get(context).schedule, after)
            }
        }
        AlarmService.start(context, alarmId, snoozeCount, isTest, triggerAt)
    }
}

/** 재부팅·시간 변경·앱 업데이트 후 모든 알람을 다시 등록한다. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.rescheduleAll(context)
    }
}
