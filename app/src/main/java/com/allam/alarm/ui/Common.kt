package com.allam.alarm.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.allam.alarm.alarm.AlarmScheduler
import com.allam.alarm.alarm.SensorAvailability
import com.allam.alarm.alarm.service
import com.allam.alarm.core.AlarmCalculator
import com.allam.alarm.core.ShiftType
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

fun shiftColor(type: ShiftType): Color = Color(type.colorArgb.toInt())

@Composable
fun ShiftBadge(type: ShiftType?, size: Dp = 22.dp, fontSize: Int = 12) {
    val bg = type?.let(::shiftColor) ?: Color.Gray.copy(alpha = 0.3f)
    Box(
        modifier = Modifier
            .size(size)
            .background(bg, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(type?.short ?: "-", color = Color.White, fontSize = fontSize.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ColorDot(color: Color, size: Dp = 12.dp) {
    Box(Modifier.size(size).background(color, CircleShape))
}

/** 30초마다 갱신되는 현재 시각. */
@Composable
fun rememberNow(periodMs: Long = 30_000): State<ZonedDateTime> = produceState(ZonedDateTime.now()) {
    while (true) {
        delay(periodMs)
        value = ZonedDateTime.now()
    }
}

private val dateFmt = DateTimeFormatter.ofPattern("M/d")
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

fun dayLabel(date: LocalDate, today: LocalDate): String {
    val base = "${date.format(dateFmt)}(${AlarmCalculator.dayName(date.dayOfWeek)})"
    return when (ChronoUnit.DAYS.between(today, date)) {
        0L -> "오늘 $base"
        1L -> "내일 $base"
        2L -> "모레 $base"
        else -> base
    }
}

fun nextRingText(next: ZonedDateTime, now: ZonedDateTime): String =
    "${dayLabel(next.toLocalDate(), now.toLocalDate())} ${next.format(timeFmt)} · " +
        AlarmCalculator.formatRemaining(Duration.between(now, next))

fun formatTime(hour: Int, minute: Int) = "%02d:%02d".format(hour, minute)

/** 알람이 제대로 울리기 위해 필요한 권한/설정 상태. */
data class PermissionStatus(
    val notifications: Boolean,
    val exactAlarm: Boolean,
    val fullScreen: Boolean,
    val activity: Boolean,
    val batteryUnrestricted: Boolean,
) {
    val criticalMissing: Boolean get() = !notifications || !exactAlarm || !fullScreen

    companion object {
        fun of(context: Context): PermissionStatus {
            val nm = context.service<NotificationManager>()
            val notifications = nm.areNotificationsEnabled() &&
                (
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
                    )
            val fullScreen = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()
            val pm = context.service<PowerManager>()
            return PermissionStatus(
                notifications = notifications,
                exactAlarm = AlarmScheduler.canScheduleExact(context),
                fullScreen = fullScreen,
                activity = SensorAvailability.hasActivityPermission(context),
                batteryUnrestricted = pm.isIgnoringBatteryOptimizations(context.packageName),
            )
        }
    }
}

object SettingsIntents {
    private fun pkg(context: Context) = Uri.parse("package:${context.packageName}")

    fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg(context))

    fun notifications(context: Context) =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    fun exactAlarm(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg(context))
        } else appDetails(context)

    fun fullScreen(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= 34) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg(context))
        } else appDetails(context)

    @Suppress("BatteryLife")
    fun battery(context: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg(context))
}

fun Context.launchSafely(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { runCatching { startActivity(SettingsIntents.appDetails(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}
