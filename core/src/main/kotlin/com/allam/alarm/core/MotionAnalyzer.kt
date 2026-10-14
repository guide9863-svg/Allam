package com.allam.alarm.core

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class MotionStatus(
    /** 지금까지 연속으로 걸은 걸음 수 (멈추면 0으로 초기화). */
    val steps: Int,
    /** 지금까지 연속으로 움직인 초 (멈추면 0으로 초기화). */
    val activeSeconds: Int,
    /** 마지막 움직임 이후 경과 시간. */
    val stillForMs: Long,
    /** 직전 1초 동안의 움직임 세기 (0~1, UI 막대용). */
    val intensity: Float,
    /** 하드웨어 만보계 센서를 쓰는 중인지 (아니면 가속도계로 걸음 추정). */
    val hardwareSteps: Boolean,
    /** 멈춰서 진행도가 초기화된 횟수. */
    val resets: Int,
)

/**
 * 가속도계 · 자이로스코프 · 걸음 센서 값을 받아 "사람이 실제로 움직이고 있는지"를 판정한다.
 *
 * - 1초 단위로 (중력을 뺀) 선형 가속도 RMS 와 자이로 각속도 RMS 를 계산해 문턱값을 넘으면 "움직인 1초"로 센다.
 * - 걸음은 하드웨어 걸음 감지 센서를 우선 쓰고, 없으면 가속도 크기의 봉우리로 추정한다.
 * - stillTimeoutMs 동안 아무 움직임이 없으면 걸음/움직임 진행도를 0으로 되돌린다.
 *   (침대에서 뒤척이며 조금씩 쌓아서 끄는 것을 막기 위함)
 *
 * 시간 값은 모두 같은 단조 시계(ms) 기준이어야 한다 (안드로이드에선 elapsedRealtime).
 */
class MotionAnalyzer(
    private val stillTimeoutMs: Long,
    sensitivity: Int,
    startMs: Long,
) {
    private val scale = when (sensitivity) {
        0 -> 1.5
        2 -> 0.7
        else -> 1.0
    }
    val accThreshold = ACC_RMS_THRESHOLD * scale
    val gyroThreshold = GYRO_RMS_THRESHOLD * scale
    private val stepHigh = STEP_HIGH * scale
    private val stepLow = STEP_LOW * scale

    /** true 면 onHardwareStep 만 걸음으로 세고, 가속도 기반 추정은 끈다. */
    var hardwareSteps: Boolean = false

    var steps = 0
        private set
    var activeSeconds = 0
        private set
    var resets = 0
        private set

    /** 초기화와 상관없이 누적된 움직임 초 (센서 고장 판단용). */
    var totalActiveSeconds = 0
        private set
    var lastActivityMs = startMs
        private set
    private var lastIntensity = 0f

    // 중력 추정 (저역통과)
    private var gravityInit = false
    private var gX = 0.0
    private var gY = 0.0
    private var gZ = 0.0
    private var lastAccMs = -1L

    // 1초 구간 누적값
    private var bucketStart = -1L
    private var accSumSq = 0.0
    private var accN = 0
    private var gyroSumSq = 0.0
    private var gyroN = 0

    // 소프트웨어 걸음 추정
    private var magFast = 0.0
    private var magSlow = 0.0
    private var magInit = false
    private var stepRearmed = true
    private var lastSoftStepMs = Long.MIN_VALUE / 2

    fun onAccelerometer(x: Float, y: Float, z: Float, tMs: Long) {
        advanceBucket(tMs)
        val dt = if (lastAccMs < 0) 0.02 else ((tMs - lastAccMs).coerceIn(1, 500)) / 1000.0
        lastAccMs = tMs
        if (!gravityInit) {
            gX = x.toDouble(); gY = y.toDouble(); gZ = z.toDouble()
            gravityInit = true
        } else {
            val a = lowPassFactor(dt, GRAVITY_TAU_SEC)
            gX = a * gX + (1 - a) * x
            gY = a * gY + (1 - a) * y
            gZ = a * gZ + (1 - a) * z
        }
        val lx = x - gX
        val ly = y - gY
        val lz = z - gZ
        accSumSq += lx * lx + ly * ly + lz * lz
        accN++

        if (!hardwareSteps) detectSoftwareStep(sqrt((x * x + y * y + z * z).toDouble()), dt, tMs)
    }

    fun onGyroscope(x: Float, y: Float, z: Float, tMs: Long) {
        advanceBucket(tMs)
        gyroSumSq += (x * x + y * y + z * z).toDouble()
        gyroN++
    }

    fun onHardwareStep(tMs: Long) {
        if (!hardwareSteps) return
        registerStep(tMs)
    }

    /** 소리를 잠시 끈 순간처럼, 사용자가 반응했을 때 "정지 시간" 타이머만 다시 시작 (진행도는 그대로). */
    fun restartStillTimer(nowMs: Long) {
        lastActivityMs = max(lastActivityMs, nowMs)
    }

    fun tick(nowMs: Long): MotionStatus {
        advanceBucket(nowMs)
        val stillFor = (nowMs - lastActivityMs).coerceAtLeast(0)
        if (stillFor >= stillTimeoutMs && (steps > 0 || activeSeconds > 0)) {
            steps = 0
            activeSeconds = 0
            resets++
        }
        return MotionStatus(steps, activeSeconds, stillFor, lastIntensity, hardwareSteps, resets)
    }

    private fun registerStep(tMs: Long) {
        steps++
        lastActivityMs = max(lastActivityMs, tMs)
    }

    private fun detectSoftwareStep(magnitude: Double, dt: Double, tMs: Long) {
        if (!magInit) {
            magFast = magnitude
            magSlow = magnitude
            magInit = true
            return
        }
        magFast = lowPassFactor(dt, STEP_FAST_TAU_SEC).let { it * magFast + (1 - it) * magnitude }
        magSlow = lowPassFactor(dt, STEP_SLOW_TAU_SEC).let { it * magSlow + (1 - it) * magnitude }
        val d = magFast - magSlow
        if (stepRearmed && d > stepHigh && tMs - lastSoftStepMs >= MIN_STEP_INTERVAL_MS) {
            stepRearmed = false
            lastSoftStepMs = tMs
            registerStep(tMs)
        } else if (!stepRearmed && d < stepLow) {
            stepRearmed = true
        }
    }

    private fun advanceBucket(tMs: Long) {
        if (bucketStart < 0) {
            bucketStart = tMs
            return
        }
        if (tMs < bucketStart + BUCKET_MS) return
        finalizeBucket(bucketStart + BUCKET_MS)
        val elapsedBuckets = (tMs - bucketStart) / BUCKET_MS
        bucketStart += elapsedBuckets * BUCKET_MS
    }

    private fun finalizeBucket(bucketEndMs: Long) {
        val accRms = if (accN > 0) sqrt(accSumSq / accN) else 0.0
        val gyroRms = if (gyroN > 0) sqrt(gyroSumSq / gyroN) else 0.0
        val ratio = max(accRms / accThreshold, gyroRms / gyroThreshold)
        lastIntensity = min(1.0, ratio).toFloat()
        if (ratio >= 1.0) {
            activeSeconds++
            totalActiveSeconds++
            lastActivityMs = max(lastActivityMs, bucketEndMs)
        }
        accSumSq = 0.0; accN = 0
        gyroSumSq = 0.0; gyroN = 0
    }

    private fun lowPassFactor(dtSec: Double, tauSec: Double): Double = exp(-dtSec / tauSec)

    companion object {
        const val BUCKET_MS = 1000L

        /** 휴대폰을 들고 걷거나 흔들 때 선형가속도 RMS 는 대략 1.5~4 m/s², 가만히 쥐고 있을 땐 0.3 이하. */
        const val ACC_RMS_THRESHOLD = 1.0

        /** 손목을 크게 돌리거나 들고 움직일 때 1 rad/s 이상, 누워서 화면을 볼 때는 0.3 이하. */
        const val GYRO_RMS_THRESHOLD = 1.2

        const val GRAVITY_TAU_SEC = 0.4
        const val STEP_FAST_TAU_SEC = 0.05
        const val STEP_SLOW_TAU_SEC = 1.0
        const val STEP_HIGH = 1.3
        const val STEP_LOW = 0.3
        const val MIN_STEP_INTERVAL_MS = 280L
    }
}
