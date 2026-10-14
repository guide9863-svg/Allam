package com.allam.alarm.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.allam.alarm.R
import com.allam.alarm.ui.MainActivity
import com.allam.alarm.ui.RingActivity

object Notifications {
    const val CHANNEL_RING = "ring"
    const val CHANNEL_INFO = "info"
    const val RING_ID = 1001
    private const val RE_RING_ID = 1002
    private const val FALLBACK_ID = 1003
    private const val SNOOZE_BASE = 2000
    private const val RESULT_ID = 3000

    fun createChannels(context: Context) {
        val nm = context.service<NotificationManager>()
        val ring = NotificationChannel(CHANNEL_RING, "알람 울림", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "알람이 울릴 때 잠금화면 위로 알람 화면을 띄웁니다"
            setSound(null, null) // 소리는 앱이 알람 볼륨으로 직접 재생
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }
        val info = NotificationChannel(CHANNEL_INFO, "스누즈·알림", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "스누즈 대기, 기상 미확인 안내"
            setSound(null, null)
        }
        nm.createNotificationChannels(listOf(ring, info))
    }

    fun ringActivityIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        10,
        Intent(context, RingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun serviceAction(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(context, AlarmService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun ringing(context: Context, title: String, text: String, showQuietAction: Boolean): Notification {
        val content = ringActivityIntent(context)
        return NotificationCompat.Builder(context, CHANNEL_RING)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(content)
            .setFullScreenIntent(content, true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                if (showQuietAction) {
                    addAction(0, "소리 끄고 움직이기", serviceAction(context, AlarmService.ACTION_QUIET, 11))
                }
            }
            .build()
    }

    /**
     * 다시 울릴 때 별도의 전체화면 알림을 새로 띄운다.
     * (같은 알림을 고치기만 하면 전체화면이 다시 뜨지 않아 홈으로 나간 경우 화면이 안 돌아온다)
     */
    fun showReRing(context: Context, title: String, text: String) {
        val content = ringActivityIntent(context)
        val n = NotificationCompat.Builder(context, CHANNEL_RING)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(content)
            .setFullScreenIntent(content, true)
            .build()
        context.service<NotificationManager>().cancel(RE_RING_ID)
        notify(context, RE_RING_ID, n)
    }

    fun cancelReRing(context: Context) {
        context.service<NotificationManager>().cancel(RE_RING_ID)
    }

    /** 서비스를 바로 시작할 수 없을 때: 전체화면으로 울림 화면을 띄우고, 화면이 서비스를 시작한다. */
    fun showFallbackRing(context: Context, alarmId: Int, snoozeCount: Int, isTest: Boolean, triggerAt: Long, resume: Boolean) {
        val intent = Intent(context, RingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(RingActivity.EXTRA_FALLBACK_START, true)
            .putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            .putExtra(AlarmScheduler.EXTRA_SNOOZE_COUNT, snoozeCount)
            .putExtra(AlarmScheduler.EXTRA_IS_TEST, isTest)
            .putExtra(AlarmScheduler.EXTRA_TRIGGER_AT, triggerAt)
            .putExtra(AlarmService.EXTRA_RESUME, resume)
        val pi = PendingIntent.getActivity(
            context,
            13,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_RING)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("알람 시간이에요!")
            .setContentText("눌러서 알람 화면 열기")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()
        notify(context, FALLBACK_ID, n)
    }

    fun cancelFallbackRing(context: Context) {
        context.service<NotificationManager>().cancel(FALLBACK_ID)
    }

    fun showSnooze(context: Context, alarmId: Int, label: String, timeText: String) {
        val n = NotificationCompat.Builder(context, CHANNEL_INFO)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("스누즈 중 · $timeText 에 다시 울립니다")
            .setContentText(label.ifBlank { "알람" })
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(mainIntent(context))
            .build()
        notify(context, SNOOZE_BASE + alarmId, n)
    }

    fun cancelSnooze(context: Context, alarmId: Int) {
        context.service<NotificationManager>().cancel(SNOOZE_BASE + alarmId)
    }

    fun showTimedOut(context: Context, label: String) {
        val n = NotificationCompat.Builder(context, CHANNEL_INFO)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("60분 동안 울렸지만 기상이 확인되지 않았어요")
            .setContentText(label.ifBlank { "알람" } + " · 알람을 멈췄습니다")
            .setAutoCancel(true)
            .setContentIntent(mainIntent(context))
            .build()
        notify(context, RESULT_ID, n)
    }

    private fun mainIntent(context: Context) = PendingIntent.getActivity(
        context,
        12,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notify(context: Context, id: Int, n: Notification) {
        val nm = context.service<NotificationManager>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !nm.areNotificationsEnabled()) return
        nm.notify(id, n)
    }

    fun update(context: Context, id: Int, n: Notification) = notify(context, id, n)
}
