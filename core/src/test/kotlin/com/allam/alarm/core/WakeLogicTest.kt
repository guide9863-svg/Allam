package com.allam.alarm.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class WakeLogicTest {
    private val g = 9.81f

    /** 책상/침대 위에 가만히 놓인 휴대폰: 아주 작은 잡음만. */
    private fun feedStill(a: MotionAnalyzer, fromMs: Long, toMs: Long, rnd: Random = Random(1)) {
        var t = fromMs
        while (t < toMs) {
            a.onAccelerometer(rnd.nextFloat() * 0.05f, rnd.nextFloat() * 0.05f, g + rnd.nextFloat() * 0.05f, t)
            a.onGyroscope(rnd.nextFloat() * 0.01f, rnd.nextFloat() * 0.01f, rnd.nextFloat() * 0.01f, t)
            t += 20
        }
    }

    /** 손에 들고 걷기: 약 2Hz 로 수직 가속도가 ±3 m/s² 흔들리고 손목도 약간 회전. */
    private fun feedWalking(a: MotionAnalyzer, fromMs: Long, toMs: Long) {
        var t = fromMs
        while (t < toMs) {
            val s = (t - fromMs) / 1000.0
            val bounce = (3.0 * sin(2 * PI * 1.9 * s)).toFloat()
            a.onAccelerometer(0.4f * bounce, 0.2f, g + bounce, t)
            a.onGyroscope((0.6 * sin(2 * PI * 1.9 * s)).toFloat(), 0.2f, 0.1f, t)
            t += 20
        }
    }

    /** 침대에서 휴대폰을 마구 흔들기. */
    private fun feedShaking(a: MotionAnalyzer, fromMs: Long, toMs: Long) {
        var t = fromMs
        while (t < toMs) {
            val s = (t - fromMs) / 1000.0
            a.onAccelerometer((8 * sin(2 * PI * 4 * s)).toFloat(), 0f, g, t)
            a.onGyroscope((4 * sin(2 * PI * 4 * s)).toFloat(), 0f, 0f, t)
            t += 20
        }
    }

    @Test
    fun stillPhone_isNotActive() {
        val a = MotionAnalyzer(stillTimeoutMs = 20_000, sensitivity = 1, startMs = 0)
        feedStill(a, 0, 60_000)
        val st = a.tick(60_000)
        assertEquals(0, st.activeSeconds)
        assertEquals(0, st.steps)
        assertTrue(st.stillForMs >= 59_000)
    }

    @Test
    fun walking_countsStepsAndMotion() {
        val a = MotionAnalyzer(stillTimeoutMs = 20_000, sensitivity = 1, startMs = 0)
        feedWalking(a, 0, 15_000)
        val st = a.tick(15_000)
        // 1.9Hz * 15초 ≈ 28 걸음
        assertTrue("steps=${st.steps}", st.steps in 22..32)
        assertTrue("active=${st.activeSeconds}", st.activeSeconds >= 13)
        assertTrue(st.stillForMs < 2_000)
    }

    @Test
    fun hardwareSteps_disableSoftwareEstimate() {
        val a = MotionAnalyzer(stillTimeoutMs = 20_000, sensitivity = 1, startMs = 0)
        a.hardwareSteps = true
        feedWalking(a, 0, 5_000)
        assertEquals(0, a.tick(5_000).steps)
        repeat(7) { a.onHardwareStep(5_000L + it * 500) }
        assertEquals(7, a.tick(8_600).steps)
    }

    @Test
    fun progressResets_afterStandingStill() {
        val a = MotionAnalyzer(stillTimeoutMs = 20_000, sensitivity = 1, startMs = 0)
        feedShaking(a, 0, 10_000)
        assertTrue(a.tick(10_000).activeSeconds >= 9)
        feedStill(a, 10_000, 31_000)
        val st = a.tick(31_000)
        assertEquals(0, st.activeSeconds)
        assertEquals(0, st.steps)
        assertEquals(1, st.resets)
    }

    @Test
    fun ringing_quiet_reRing_thenAwakeByWalking() {
        val check = WakeCheck(mode = WakeMode.STEPS_OR_MOTION, targetSteps = 30, targetMotionSec = 30, stillTimeoutSec = 20)
        val a = MotionAnalyzer(check.stillTimeoutSec * 1000L, check.sensitivity, 0)
        val c = WakeController(check, startMs = 0, maxSnoozes = 2, snoozesUsed = 0)

        assertEquals(WakePhase.RINGING, c.update(a.tick(1_000), 1_000))
        assertTrue(c.requestQuiet())
        a.restartStillTimer(1_000)
        assertFalse(c.isSoundOn)

        // 소리를 끄고 다시 누워버림 → 20초 뒤 다시 울림
        feedStill(a, 1_000, 22_000)
        assertEquals(WakePhase.RINGING, c.update(a.tick(22_000), 22_000))
        assertEquals(1, c.reRings)
        assertTrue(c.isSoundOn)

        // 일어나서 걷기 → 걸음 목표 달성
        assertTrue(c.requestQuiet())
        a.restartStillTimer(22_000)
        var t = 22_000L
        while (!c.isFinished && t < 80_000) {
            feedWalking(a, t, t + 1_000)
            t += 1_000
            c.update(a.tick(t), t)
        }
        assertEquals(WakePhase.AWAKE, c.phase)
        assertEquals(WakeOutcome.STEPS, c.outcome)
        assertTrue("took ${t - 22_000}ms", t - 22_000 <= 25_000)
    }

    @Test
    fun stepsOnly_rejectsShakingInBedWhenHardwareStepsAvailable() {
        val check = WakeCheck(mode = WakeMode.STEPS_ONLY, targetSteps = 30)
        val a = MotionAnalyzer(check.stillTimeoutSec * 1000L, check.sensitivity, 0).apply { hardwareSteps = true }
        val c = WakeController(check, startMs = 0, maxSnoozes = 0, snoozesUsed = 0)
        var t = 0L
        while (t < 60_000) {
            feedShaking(a, t, t + 1_000)
            t += 1_000
            c.update(a.tick(t), t)
        }
        assertEquals(WakePhase.RINGING, c.phase)
        assertTrue(a.tick(t).activeSeconds >= 50)
    }

    @Test
    fun motionMode_acceptsSustainedMovement() {
        val check = WakeCheck(mode = WakeMode.STEPS_OR_MOTION, targetSteps = 1000, targetMotionSec = 30)
        val a = MotionAnalyzer(check.stillTimeoutSec * 1000L, check.sensitivity, 0).apply { hardwareSteps = true }
        val c = WakeController(check, startMs = 0, maxSnoozes = 0, snoozesUsed = 0)
        var t = 0L
        while (!c.isFinished && t < 60_000) {
            feedShaking(a, t, t + 1_000)
            t += 1_000
            c.update(a.tick(t), t)
        }
        assertEquals(WakeOutcome.MOTION, c.outcome)
        assertTrue(t in 30_000..33_000)
    }

    @Test
    fun snoozeLimit_andTimeout_andEmergency() {
        val check = WakeCheck()
        val c = WakeController(check, startMs = 0, maxSnoozes = 1, snoozesUsed = 1)
        assertFalse(c.requestSnooze(10))
        val still = MotionStatus(0, 0, 0, 0f, false, 0)
        assertEquals(WakePhase.TIMED_OUT, c.update(still, WakeController.DEFAULT_MAX_RING_MS))
        assertEquals(WakeOutcome.TIMED_OUT, c.outcome)

        val c2 = WakeController(check, startMs = 0, maxSnoozes = 2, snoozesUsed = 0)
        assertTrue(c2.requestSnooze(5))
        assertEquals(1, c2.snoozesUsed)
        assertEquals(WakePhase.SNOOZED, c2.phase)

        val c3 = WakeController(check, startMs = 0, maxSnoozes = 0, snoozesUsed = 0)
        c3.emergencyDismiss(7)
        assertEquals(WakeOutcome.EMERGENCY, c3.outcome)
        assertEquals(7, c3.endedAtMs)
    }

    @Test
    fun quietCountdown() {
        val c = WakeController(WakeCheck(stillTimeoutSec = 20), 0, 0, 0)
        assertEquals(20, c.quietRemainingSec(MotionStatus(0, 0, 0, 0f, false, 0)))
        assertEquals(5, c.quietRemainingSec(MotionStatus(0, 0, 15_000, 0f, false, 0)))
        assertEquals(0, c.quietRemainingSec(MotionStatus(0, 0, 25_000, 0f, false, 0)))
    }

    @Test
    fun toneSynth_buffersAreLoudAndLoopable() {
        for (type in SoundType.entries) {
            val pcm = ToneSynth.generate(type)
            assertTrue(pcm.size > ToneSynth.SAMPLE_RATE / 2)
            assertTrue(pcm.maxOf { it.toInt() } > 25_000)
        }
    }

    @Test
    fun mathChallenge_answersAreCorrect() {
        val problems = MathChallenge.generate(Random(42))
        assertEquals(3, problems.size)
        for (p in problems) {
            val (lhs, c) = p.text.split(" + ")
            val (a, b) = lhs.split(" × ")
            assertEquals(a.toInt() * b.toInt() + c.toInt(), p.answer)
        }
    }
}
