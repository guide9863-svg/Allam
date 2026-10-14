package com.allam.alarm.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allam.alarm.alarm.AlarmScheduler
import com.allam.alarm.alarm.AlarmService
import com.allam.alarm.alarm.Notifications
import com.allam.alarm.alarm.RingState
import com.allam.alarm.alarm.RingUiState
import com.allam.alarm.core.MathChallenge
import com.allam.alarm.core.WakeMode
import com.allam.alarm.core.WakeOutcome
import com.allam.alarm.core.WakePhase
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 잠금화면 위에 뜨는 알람 화면. 뒤로가기·볼륨 버튼으로는 끌 수 없다. */
class RingActivity : ComponentActivity() {
    companion object {
        /** 서비스를 백그라운드에서 시작하지 못했을 때: 이 화면이 열리면서 대신 시작한다. */
        const val EXTRA_FALLBACK_START = "fallback_start"
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        startServiceIfFallback(intent)
    }

    private fun startServiceIfFallback(intent: Intent?) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_FALLBACK_START, false)) return
        intent.removeExtra(EXTRA_FALLBACK_START)
        val current = RingState.state.value
        if (current != null && !current.isFinished) return
        AlarmService.start(
            this,
            intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, AlarmScheduler.TEST_ALARM_ID),
            intent.getIntExtra(AlarmScheduler.EXTRA_SNOOZE_COUNT, 0),
            intent.getBooleanExtra(AlarmScheduler.EXTRA_IS_TEST, false),
            intent.getLongExtra(AlarmScheduler.EXTRA_TRIGGER_AT, 0L),
            intent.getBooleanExtra(AlarmService.EXTRA_RESUME, false),
        )
        Notifications.cancelFallbackRing(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startServiceIfFallback(intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = Unit // 뒤로가기로 못 끈다
            },
        )
        setContent {
            AllamTheme(dark = true) {
                RingScreen(onClose = {
                    RingState.clear()
                    finish()
                })
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val s = RingState.state.value
        if (s != null && !s.isFinished &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE)
        ) {
            return true // 볼륨 버튼으로 소리 줄이기 방지
        }
        return super.onKeyDown(keyCode, event)
    }
}

private val clockFmt = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun RingScreen(onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val state by RingState.state.collectAsStateWithLifecycle()
    val s = state

    LaunchedEffect(s == null) {
        if (s == null) {
            delay(4000)
            if (RingState.state.value == null) onClose()
        }
    }
    LaunchedEffect(s?.phase) {
        if (s != null && (s.phase == WakePhase.AWAKE || s.phase == WakePhase.SNOOZED)) {
            delay(6000)
            onClose()
        }
    }
    if (s == null) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }

    val transition = rememberInfiniteTransition(label = "flash")
    val flash by transition.animateColor(
        initialValue = Color(0xFFB91C1C),
        targetValue = Color(0xFF1A0000),
        animationSpec = infiniteRepeatable(tween(450), RepeatMode.Reverse),
        label = "flashColor",
    )
    val bg = when (s.phase) {
        WakePhase.RINGING -> flash
        WakePhase.QUIET -> Color(0xFF0F172A)
        WakePhase.AWAKE -> Color(0xFF065F46)
        WakePhase.SNOOZED -> Color(0xFF1E1B4B)
        WakePhase.TIMED_OUT -> Color(0xFF27272A)
    }
    val now by produceState(LocalTime.now()) {
        while (true) {
            delay(1000)
            value = LocalTime.now()
        }
    }
    var showEmergency by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(bg)
            .systemBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Text(now.format(clockFmt), color = Color.White, fontSize = 72.sp, fontWeight = FontWeight.Light)
        Text(
            listOfNotNull(s.label.ifBlank { null }, "${s.timeText} 알람").joinToString(" · "),
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 18.sp,
        )
        Spacer(Modifier.height(32.dp))

        when (s.phase) {
            WakePhase.RINGING, WakePhase.QUIET -> ActiveContent(s, onEmergency = { showEmergency = true })
            WakePhase.AWAKE -> FinishedContent(
                title = "기상 완료! 좋은 아침이에요 ☀️",
                body = buildString {
                    val mins = ((s.endedAtWall - s.startedAtWall) / 60_000).coerceAtLeast(0)
                    append(if (mins == 0L) "1분도 안 걸렸어요" else "${mins}분 만에 일어났어요")
                    when (s.outcome) {
                        WakeOutcome.STEPS -> append(" · ${s.steps}걸음")
                        WakeOutcome.MOTION -> append(" · ${s.motionSec}초 움직임")
                        WakeOutcome.EMERGENCY -> append(" · 긴급 해제")
                        else -> Unit
                    }
                    if (s.reRings > 0) append("\n다시 울림 ${s.reRings}회")
                },
                onClose = onClose,
            )
            WakePhase.SNOOZED -> FinishedContent(
                title = "${s.snoozeMinutes}분 뒤 다시 울립니다",
                body = if (s.snoozeUntilWall > 0) {
                    Instant.ofEpochMilli(s.snoozeUntilWall).atZone(ZoneId.systemDefault()).format(clockFmt) + " 에 다시 울려요"
                } else "",
                onClose = onClose,
            )
            WakePhase.TIMED_OUT -> FinishedContent(
                title = "알람을 멈췄어요",
                body = "60분 동안 기상이 확인되지 않았습니다.",
                onClose = onClose,
            )
        }
    }

    if (showEmergency && !s.isFinished) {
        EmergencyDialog(
            onDismiss = { showEmergency = false },
            onSolved = {
                showEmergency = false
                AlarmService.send(context, AlarmService.ACTION_EMERGENCY)
            },
        )
    }
}

@Composable
private fun ColumnScope.ActiveContent(s: RingUiState, onEmergency: () -> Unit) {
    val context = LocalContext.current
    val ringing = s.phase == WakePhase.RINGING
    Text(
        if (ringing) "일어나세요!" else "계속 움직이세요",
        color = Color.White,
        fontSize = 40.sp,
        fontWeight = FontWeight.Black,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        when {
            !s.hasMotionSensor -> "움직임 센서가 없어 긴급 해제로만 끌 수 있어요"
            ringing && s.reRings > 0 -> "움직임이 멈춰서 다시 울려요 (${s.reRings}회째)\n'소리 끄고 움직이기'를 누르고 걸으세요"
            ringing -> "'소리 끄고 움직이기'를 누르고\n휴대폰을 들고 걸으세요"
            else -> "멈추면 ${s.quietRemainingSec}초 뒤 다시 울립니다"
        },
        color = Color.White.copy(alpha = 0.9f),
        fontSize = 18.sp,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(28.dp))

    ProgressLine(
        "걸음",
        "${s.steps} / ${s.targetSteps}" + if (s.hardwareSteps) "" else " (추정)",
        s.steps.toFloat() / s.targetSteps.coerceAtLeast(1),
    )
    if (s.mode == WakeMode.STEPS_OR_MOTION) {
        Text("또는", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        ProgressLine("연속 움직임", "${s.motionSec} / ${s.targetMotionSec}초", s.motionSec.toFloat() / s.targetMotionSec.coerceAtLeast(1))
    }
    Spacer(Modifier.height(8.dp))
    ProgressLine(
        "지금 움직임 세기",
        when {
            ringing -> "소리를 끄면 측정"
            s.intensity >= 1f -> "감지됨 ✓"
            else -> "약함"
        },
        s.intensity,
        Color(0xFF38BDF8),
    )
    if (ringing) {
        Text(
            "울리는 동안에는 진동 때문에 걸음 센서만 셉니다",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp,
        )
    }

    Spacer(Modifier.weight(1f))

    if (ringing) {
        Button(
            onClick = { AlarmService.send(context, AlarmService.ACTION_QUIET) },
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFB91C1C)),
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
        ) { Text("소리 끄고 움직이기", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(12.dp))
    }
    if (s.snoozesLeft > 0) {
        OutlinedButton(
            onClick = { AlarmService.send(context, AlarmService.ACTION_SNOOZE) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("${s.snoozeMinutes}분 뒤 다시 울리기 (남은 ${s.snoozesLeft}회)", color = Color.White) }
    }
    TextButton(onClick = onEmergency) {
        Text("긴급 해제 (계산 문제)", color = Color.White.copy(alpha = 0.6f))
    }
}

@Composable
private fun ProgressLine(title: String, value: String, fraction: Float, color: Color = Color.White) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(title, color = Color.White, modifier = Modifier.weight(1f))
            Text(value, color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            color = color,
            trackColor = Color.White.copy(alpha = 0.2f),
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp),
        )
    }
}

@Composable
private fun FinishedContent(title: String, body: String, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(body, color = Color.White.copy(alpha = 0.9f), fontSize = 18.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        Button(onClick = onClose) { Text("닫기") }
    }
}

@Composable
private fun EmergencyDialog(onDismiss: () -> Unit, onSolved: () -> Unit) {
    val problems = remember { MathChallenge.generate() }
    var index by remember { mutableIntStateOf(0) }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val p = problems[index]
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("긴급 해제 ${index + 1} / ${problems.size}") },
        text = {
            Column {
                Text("움직일 수 없을 때만 쓰세요.", fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Text("${p.text} = ?", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { v -> input = v.filter { it.isDigit() }.take(5) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (input.toIntOrNull() == p.answer) {
                    error = null
                    input = ""
                    if (index + 1 >= problems.size) onSolved() else index++
                } else {
                    error = "틀렸어요. 다시 계산해 보세요."
                    input = ""
                }
            }) { Text("확인") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
