package com.allam.alarm.core

enum class WakePhase {
    /** 소리가 울리는 중. */
    RINGING,

    /** 사용자가 "소리 끄고 움직이기"를 누른 상태. 움직임이 멈추면 다시 RINGING 으로. */
    QUIET,

    /** 움직임으로 기상이 확인됨 (또는 긴급 해제). 끝. */
    AWAKE,

    /** 스누즈로 미룸. 끝 (나중에 다시 울림). */
    SNOOZED,

    /** 최대 울림 시간을 넘김. 끝. */
    TIMED_OUT,
}

/**
 * 알람이 울린 뒤 기상 확인까지의 상태 기계. 센서/소리와 분리된 순수 로직.
 *
 * RINGING --(소리 끄고 움직이기)--> QUIET --(stillTimeout 동안 정지)--> RINGING (반복)
 * RINGING/QUIET --(걸음 또는 움직임 목표 달성)--> AWAKE
 */
class WakeController(
    val check: WakeCheck,
    val startMs: Long,
    val maxSnoozes: Int,
    snoozesUsed: Int,
    val maxRingMs: Long = DEFAULT_MAX_RING_MS,
) {
    var phase: WakePhase = WakePhase.RINGING
        private set
    var outcome: WakeOutcome? = null
        private set
    var reRings: Int = 0
        private set
    var snoozesUsed: Int = snoozesUsed
        private set
    var endedAtMs: Long = -1
        private set

    val snoozesLeft: Int get() = (maxSnoozes - snoozesUsed).coerceAtLeast(0)
    val isFinished: Boolean get() = phase == WakePhase.AWAKE || phase == WakePhase.SNOOZED || phase == WakePhase.TIMED_OUT
    val isSoundOn: Boolean get() = phase == WakePhase.RINGING

    fun goalMethod(status: MotionStatus): WakeOutcome? = when {
        status.steps >= check.targetSteps -> WakeOutcome.STEPS
        check.mode == WakeMode.STEPS_OR_MOTION && status.activeSeconds >= check.targetMotionSec -> WakeOutcome.MOTION
        else -> null
    }

    /** 0~1 진행도: 걸음/움직임 중 더 많이 채운 쪽. */
    fun progress(status: MotionStatus): Float {
        val stepP = status.steps.toFloat() / check.targetSteps.coerceAtLeast(1)
        val motionP = if (check.mode == WakeMode.STEPS_OR_MOTION) {
            status.activeSeconds.toFloat() / check.targetMotionSec.coerceAtLeast(1)
        } else 0f
        return maxOf(stepP, motionP).coerceIn(0f, 1f)
    }

    /** QUIET 상태에서 다시 울리기까지 남은 초. */
    fun quietRemainingSec(status: MotionStatus): Int =
        ((check.stillTimeoutSec * 1000L - status.stillForMs + 999) / 1000).toInt().coerceAtLeast(0)

    fun update(status: MotionStatus, nowMs: Long): WakePhase {
        if (isFinished) return phase
        val method = goalMethod(status)
        when {
            method != null -> finish(WakePhase.AWAKE, method, nowMs)
            nowMs - startMs >= maxRingMs -> finish(WakePhase.TIMED_OUT, WakeOutcome.TIMED_OUT, nowMs)
            phase == WakePhase.QUIET && status.stillForMs >= check.stillTimeoutSec * 1000L -> {
                phase = WakePhase.RINGING
                reRings++
            }
        }
        return phase
    }

    /** "소리 끄고 움직이기". 울리는 중일 때만 가능. */
    fun requestQuiet(): Boolean {
        if (phase != WakePhase.RINGING) return false
        phase = WakePhase.QUIET
        return true
    }

    fun requestSnooze(nowMs: Long): Boolean {
        if (isFinished || snoozesLeft <= 0) return false
        snoozesUsed++
        phase = WakePhase.SNOOZED
        endedAtMs = nowMs
        return true
    }

    fun emergencyDismiss(nowMs: Long) {
        if (isFinished) return
        finish(WakePhase.AWAKE, WakeOutcome.EMERGENCY, nowMs)
    }

    private fun finish(p: WakePhase, o: WakeOutcome, nowMs: Long) {
        phase = p
        outcome = o
        endedAtMs = nowMs
    }

    companion object {
        /** 집에 휴대폰을 두고 나간 경우 등을 대비한 안전장치: 60분 울려도 기상 확인이 안 되면 멈춘다. */
        const val DEFAULT_MAX_RING_MS = 60 * 60 * 1000L
    }
}
