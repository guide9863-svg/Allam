package com.allam.alarm.alarm

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.allam.alarm.core.ActiveRing
import com.allam.alarm.core.Alarm
import com.allam.alarm.core.MotionAnalyzer
import com.allam.alarm.core.SnoozeState
import com.allam.alarm.core.WakeCheck
import com.allam.alarm.core.WakeController
import com.allam.alarm.core.WakeLog
import com.allam.alarm.core.WakePhase
import com.allam.alarm.data.AlarmRepository
import com.allam.alarm.data.AlarmStore
import com.allam.alarm.ui.RingActivity
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 알람이 울리는 동안 살아 있는 포그라운드 서비스.
 * 소리·진동·플래시를 내고, 센서로 움직임을 감시하며, 기상이 확인될 때까지 반복해서 울린다.
 */
class AlarmService : Service() {

    companion object {
        const val ACTION_START = "com.allam.alarm.action.START"
        const val ACTION_QUIET = "com.allam.alarm.action.QUIET"
        const val ACTION_SNOOZE = "com.allam.alarm.action.SNOOZE"
        const val ACTION_EMERGENCY = "com.allam.alarm.action.EMERGENCY"
        const val EXTRA_RESUME = "resume"

        private const val TICK_MS = 500L

        /** 감시 알람: 30초마다 "지금+90초"로 다시 맞춘다. 울림이 끊기면 최대 90초 뒤 다시 울린다. */
        private const val WATCHDOG_REARM_MS = 30_000L
        private const val WATCHDOG_DELAY_MS = 90_000L

        /** 걸음 센서가 이만큼 움직이는 동안 한 번도 응답하지 않으면 가속도 기반 걸음 추정으로 전환. */
        private const val HW_STEP_FALLBACK_ACTIVE_SEC = 60

        /** @return 서비스를 시작했으면 true. 백그라운드 시작이 막히면 false. */
        fun start(context: Context, alarmId: Int, snoozeCount: Int, isTest: Boolean, triggerAt: Long, resume: Boolean = false): Boolean {
            val intent = Intent(context, AlarmService::class.java)
                .setAction(ACTION_START)
                .putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                .putExtra(AlarmScheduler.EXTRA_SNOOZE_COUNT, snoozeCount)
                .putExtra(AlarmScheduler.EXTRA_IS_TEST, isTest)
                .putExtra(AlarmScheduler.EXTRA_TRIGGER_AT, triggerAt)
                .putExtra(EXTRA_RESUME, resume)
            return runCatching { ContextCompat.startForegroundService(context, intent) }.isSuccess
        }

        fun send(context: Context, action: String) {
            runCatching { context.startService(Intent(context, AlarmService::class.java).setAction(action)) }
        }

        /** 미리 울려보기 / 삭제된 알람용 기본 설정. */
        fun testAlarm(): Alarm {
            val now = LocalTime.now()
            return Alarm(
                id = AlarmScheduler.TEST_ALARM_ID,
                hour = now.hour,
                minute = now.minute,
                label = "테스트 알람",
                maxSnoozes = 1,
                wakeCheck = WakeCheck(targetSteps = 20, targetMotionSec = 15),
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var session: RingSession? = null
    private var cpuLock: PowerManager.WakeLock? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_QUIET -> session?.quiet()
            ACTION_SNOOZE -> session?.snooze()
            ACTION_EMERGENCY -> session?.emergency()
        }
        // 울림이 없으면 정리. 같은 시점에 들어온 새 시작 요청이 있으면 stopSelf(startId)는 서비스를 멈추지 않는다.
        if (session == null) stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        // 채널이 없으면 startForeground 가 실패하므로 한 번 더 보장 (중복 호출 안전)
        Notifications.createChannels(this)
        val requestedId = intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, AlarmScheduler.TEST_ALARM_ID)
        val isTest = intent.getBooleanExtra(AlarmScheduler.EXTRA_IS_TEST, false)
        val resume = intent.getBooleanExtra(EXTRA_RESUME, false)
        val data = AlarmRepository.get(this)
        val resumeFrom = if (resume) data.activeRing else null

        val base = data.alarm(requestedId) ?: testAlarm()
        // 미리 울려보기는 실제 알람의 스누즈·기록을 건드리지 않도록 테스트 id 로 돌린다
        val alarm = if (isTest) base.copy(id = AlarmScheduler.TEST_ALARM_ID) else base

        // startForegroundService 후에는 반드시 몇 초 안에 startForeground 를 호출해야 한다.
        val title = alarm.label.ifBlank { "알람" } + " · " + timeText(alarm)
        startInForeground(Notifications.ringing(this, title, "일어나서 움직여야 꺼집니다!", showQuietAction = true))
        Notifications.cancelFallbackRing(this)

        if (session != null) return // 이미 울리는 중: 하나로 합친다
        if (resume && resumeFrom == null) {
            // 되살릴 울림이 없다 (그 사이 기상 확인됨)
            finishSession()
            return
        }

        acquireCpuLock()
        val s = RingSession(
            alarm = alarm,
            isTest = isTest,
            snoozeCount = resumeFrom?.snoozesUsed ?: intent.getIntExtra(AlarmScheduler.EXTRA_SNOOZE_COUNT, 0),
            triggerAt = resumeFrom?.triggerAt ?: intent.getLongExtra(AlarmScheduler.EXTRA_TRIGGER_AT, 0L),
            resumeFrom = resumeFrom,
        )
        session = s
        s.begin()
        launchRingScreen()
        handler.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            val s = session ?: return
            s.onTick()
            if (session != null) handler.postDelayed(this, TICK_MS)
        }
    }

    private fun startInForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, Notifications.RING_ID, notification, type)
    }

    private fun acquireCpuLock() {
        if (cpuLock?.isHeld == true) return
        cpuLock = service<PowerManager>().newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "allam:ring").apply {
            setReferenceCounted(false)
            acquire(WakeController.DEFAULT_MAX_RING_MS + 5 * 60 * 1000L)
        }
    }

    /** 화면을 켠다 (다시 울릴 때 꺼진 화면을 깨우기 위함, 일부 기기에서만 동작하는 보조 수단). */
    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        runCatching {
            service<PowerManager>()
                .newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                    "allam:screen",
                )
                .acquire(10_000)
        }
    }

    private fun launchRingScreen() {
        wakeScreen()
        // 잠금 상태에서는 전체화면 알림이 화면을 띄운다. 사용 중일 때는 직접 띄우기를 시도한다.
        runCatching {
            startActivity(
                Intent(this, RingActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            )
        }
    }

    private fun timeText(alarm: Alarm): String = "%02d:%02d".format(alarm.hour, alarm.minute)

    private fun finishSession() {
        handler.removeCallbacks(tick)
        session = null
        cpuLock?.let { if (it.isHeld) it.release() }
        cpuLock = null
        Notifications.cancelReRing(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
    }

    override fun onDestroy() {
        // 정상 종료가 아닌 경우(시스템이 서비스를 멈춤)에도 소리·센서는 정리하되,
        // activeRing 과 감시 알람은 남겨 두어 기상 확인 전이면 다시 울리게 한다.
        session?.release()
        session = null
        handler.removeCallbacksAndMessages(null)
        cpuLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    /** 알람 한 번의 울림. */
    private inner class RingSession(
        private val alarm: Alarm,
        private val isTest: Boolean,
        snoozeCount: Int,
        private val triggerAt: Long,
        resumeFrom: ActiveRing?,
    ) {
        private val ctx = this@AlarmService
        private val nowElapsed = SystemClock.elapsedRealtime()
        private val startWall = resumeFrom?.startedAtWall ?: System.currentTimeMillis()

        /** 재개된 울림이면 처음 울린 시각 기준으로 60분 제한을 이어서 센다. */
        private val startMs = nowElapsed - (System.currentTimeMillis() - startWall).coerceAtLeast(0)
        private val check = alarm.wakeCheck
        private val analyzer = MotionAnalyzer(check.stillTimeoutSec * 1000L, check.sensitivity, nowElapsed)
        private val controller = WakeController(check, startMs, alarm.maxSnoozes, snoozeCount)
        private val monitor = MotionMonitor(ctx, analyzer)
        private val sound = AlarmSoundPlayer(ctx, alarm.sound, alarm.ringtoneUri)
        private val volume = VolumeEnforcer(ctx, resumeFrom?.savedVolume)
        private val vibrator = AlarmVibrator(ctx)
        private val torch = TorchStrobe(ctx)
        private var lastPhase = WakePhase.RINGING
        private var lastWatchdogArm = 0L
        private var released = false

        fun begin() {
            monitor.start()
            outputsOn()
            saveActiveRing()
            armWatchdog(SystemClock.elapsedRealtime())
            publish()
        }

        private fun saveActiveRing() {
            AlarmStore.setActiveRing(
                ctx,
                ActiveRing(
                    alarmId = alarm.id,
                    isTest = isTest,
                    startedAtWall = startWall,
                    triggerAt = triggerAt,
                    snoozesUsed = controller.snoozesUsed,
                    savedVolume = volume.savedVolume,
                ),
            )
        }

        private fun armWatchdog(nowElapsed: Long) {
            lastWatchdogArm = nowElapsed
            AlarmScheduler.armWatchdog(ctx, System.currentTimeMillis() + WATCHDOG_DELAY_MS)
        }

        fun onTick() {
            val now = SystemClock.elapsedRealtime()
            val status = analyzer.tick(now)
            if (analyzer.hardwareSteps && monitor.hardwareStepEvents == 0 &&
                analyzer.totalActiveSeconds >= HW_STEP_FALLBACK_ACTIVE_SEC
            ) {
                monitor.fallbackToSoftwareSteps()
            }
            val phase = controller.update(status, now)
            if (phase != lastPhase) onPhaseChanged(lastPhase, phase)
            lastPhase = phase
            if (phase == WakePhase.RINGING && alarm.forceMaxVolume) volume.enforceMax()
            if (now - lastWatchdogArm >= WATCHDOG_REARM_MS) armWatchdog(now)
            publish()
            if (controller.isFinished) complete()
        }

        fun quiet() {
            if (!controller.requestQuiet()) return
            analyzer.restartStillTimer(SystemClock.elapsedRealtime())
            onPhaseChanged(lastPhase, WakePhase.QUIET)
            lastPhase = WakePhase.QUIET
            publish()
        }

        fun snooze() {
            val now = SystemClock.elapsedRealtime()
            if (!controller.requestSnooze(now)) return
            val at = System.currentTimeMillis() + alarm.snoozeMinutes * 60_000L
            val state = SnoozeState(alarm.id, at, controller.snoozesUsed)
            if (alarm.id != AlarmScheduler.TEST_ALARM_ID) AlarmStore.addSnooze(ctx, state)
            AlarmScheduler.scheduleSnooze(ctx, state)
            val atText = AlarmScheduler.instantOf(at).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
            Notifications.showSnooze(ctx, alarm.id, alarm.label, atText)
            lastPhase = WakePhase.SNOOZED
            publish(snoozeUntil = at)
            complete()
        }

        fun emergency() {
            controller.emergencyDismiss(SystemClock.elapsedRealtime())
            lastPhase = controller.phase
            publish()
            complete()
        }

        private fun onPhaseChanged(from: WakePhase, to: WakePhase) {
            when (to) {
                WakePhase.RINGING -> {
                    outputsOn()
                    if (from == WakePhase.QUIET) {
                        // 다시 울림: 화면을 켜고 새 전체화면 알림으로 알람 화면을 다시 띄운다
                        launchRingScreen()
                        Notifications.showReRing(ctx, "다시 울립니다! 움직임이 멈췄어요", "일어나서 걸어야 꺼집니다")
                    }
                }
                WakePhase.QUIET -> {
                    outputsOff()
                    Notifications.cancelReRing(ctx)
                    Notifications.update(
                        ctx,
                        Notifications.RING_ID,
                        Notifications.ringing(ctx, "계속 움직이세요", "멈추면 ${check.stillTimeoutSec}초 뒤 다시 울립니다", false),
                    )
                }
                else -> outputsOff()
            }
        }

        private fun outputsOn() {
            // 진동·스피커 떨림이 '움직임'으로 세어지지 않도록 울리는 동안은 가속도·자이로를 쉰다 (걸음 센서는 계속)
            monitor.suppressMotion = true
            if (alarm.forceMaxVolume) volume.enforceMax()
            sound.start()
            if (alarm.vibrate) vibrator.start()
            if (alarm.flash) torch.start()
        }

        private fun outputsOff() {
            sound.pause()
            vibrator.stop()
            torch.stop()
            if (monitor.suppressMotion) {
                monitor.suppressMotion = false
                analyzer.resyncFilters(SystemClock.elapsedRealtime())
            }
        }

        private fun complete() {
            if (released) return
            val status = analyzer.tick(SystemClock.elapsedRealtime())
            val outcome = controller.outcome
            if (outcome != null && alarm.id != AlarmScheduler.TEST_ALARM_ID) {
                AlarmStore.addLog(
                    ctx,
                    WakeLog(
                        alarmId = alarm.id,
                        label = alarm.label,
                        ringStartedAt = startWall,
                        endedAt = System.currentTimeMillis(),
                        outcome = outcome,
                        steps = status.steps,
                        motionSec = status.activeSeconds,
                        snoozes = controller.snoozesUsed,
                        reRings = controller.reRings,
                    ),
                )
            }
            if (controller.phase == WakePhase.TIMED_OUT) Notifications.showTimedOut(ctx, alarm.label)
            // 정상 종료(기상/스누즈/시간초과/긴급해제): 감시 알람과 울림 기록 제거
            AlarmScheduler.cancelWatchdog(ctx)
            AlarmStore.setActiveRing(ctx, null)
            release()
            finishSession()
        }

        fun release() {
            if (released) return
            released = true
            monitor.stop()
            sound.release()
            vibrator.stop()
            torch.stop()
            volume.restore()
        }

        private fun publish(snoozeUntil: Long = 0L) {
            val status = analyzer.tick(SystemClock.elapsedRealtime())
            RingState.publish(
                RingUiState(
                    alarmId = alarm.id,
                    label = alarm.label,
                    timeText = timeText(alarm),
                    phase = controller.phase,
                    mode = check.mode,
                    steps = status.steps,
                    targetSteps = check.targetSteps,
                    motionSec = status.activeSeconds,
                    targetMotionSec = check.targetMotionSec,
                    progress = controller.progress(status),
                    intensity = if (monitor.suppressMotion) 0f else status.intensity,
                    quietRemainingSec = controller.quietRemainingSec(status),
                    stillTimeoutSec = check.stillTimeoutSec,
                    snoozesLeft = controller.snoozesLeft,
                    snoozeMinutes = alarm.snoozeMinutes,
                    reRings = controller.reRings,
                    hardwareSteps = status.hardwareSteps,
                    hasMotionSensor = monitor.hasMotionSensor,
                    outcome = controller.outcome,
                    startedAtWall = startWall,
                    endedAtWall = if (controller.isFinished) System.currentTimeMillis() else 0L,
                    snoozeUntilWall = snoozeUntil,
                    triggerAtWall = triggerAt,
                ),
            )
        }
    }
}
