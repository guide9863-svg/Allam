package com.allam.alarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.allam.alarm.core.WakeController
import com.allam.alarm.data.AlarmRepository
import com.allam.alarm.data.AlarmStore
import java.time.ZonedDateTime

/** AlarmManager 가 깨우면 호출된다: 다음 회차를 등록하고 울림 서비스를 시작한다. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_FIRE) return
        if (intent.getBooleanExtra(AlarmScheduler.EXTRA_IS_WATCHDOG, false)) {
            resumeActiveRing(context)
            return
        }
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
                val now = ZonedDateTime.now()
                val firedAt = if (triggerAt > 0) AlarmScheduler.instantOf(triggerAt) else now
                val updated = AlarmStore.onFired(context, alarmId, firedAt.toInstant().toEpochMilli()) ?: alarm
                // 같은 시각의 다른 알람을 건드리지 않도록 이 알람만 다음 회차로 등록
                val after = if (firedAt.isAfter(now)) firedAt else now
                AlarmScheduler.schedule(context, updated, AlarmRepository.get(context).schedule, after)
            }
        }
        startOrFallback(context, alarmId, snoozeCount, isTest, triggerAt, resume = false)
    }

    companion object {
        /** 서비스 시작이 막히면(정확한 알람 권한이 없는 경우 등) 전체화면 알림으로 화면을 띄워 거기서 시작한다. */
        fun startOrFallback(context: Context, alarmId: Int, snoozeCount: Int, isTest: Boolean, triggerAt: Long, resume: Boolean) {
            if (!AlarmService.start(context, alarmId, snoozeCount, isTest, triggerAt, resume)) {
                Notifications.showFallbackRing(context, alarmId, snoozeCount, isTest, triggerAt, resume)
            }
        }

        /** 울림이 기상 확인 없이 끊겼다면(프로세스 종료·재부팅) 다시 울린다. */
        fun resumeActiveRing(context: Context) {
            val ring = AlarmRepository.get(context).activeRing ?: return
            if (System.currentTimeMillis() - ring.startedAtWall >= WakeController.DEFAULT_MAX_RING_MS) {
                VolumeEnforcer.restoreTo(context, ring.savedVolume)
                AlarmStore.setActiveRing(context, null)
                return
            }
            startOrFallback(context, ring.alarmId, ring.snoozesUsed, ring.isTest, ring.triggerAt, resume = true)
        }
    }
}

/** 재부팅·시간 변경·앱 업데이트 후 모든 알람을 다시 등록한다. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val isBoot = intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        AlarmScheduler.rescheduleAll(context, catchUpMissed = isBoot)
        // 울리던 중에 전원을 껐다 켰거나 앱이 업데이트됐다면 곧바로 다시 울린다.
        // (Android 15 부터 부팅 브로드캐스트에서는 미디어 서비스를 바로 시작할 수 없어 감시 알람을 1초 뒤로 건다)
        if (AlarmRepository.get(context).activeRing != null) {
            AlarmScheduler.armWatchdog(context, System.currentTimeMillis() + 1_000)
        }
    }
}
