package com.allam.alarm.alarm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.allam.alarm.core.MotionAnalyzer

data class SensorAvailability(
    val accelerometer: Boolean,
    val gyroscope: Boolean,
    val stepDetector: Boolean,
    val activityPermission: Boolean,
) {
    val anyMotion: Boolean get() = accelerometer || gyroscope
    val hardwareSteps: Boolean get() = stepDetector && activityPermission

    companion object {
        fun of(context: Context): SensorAvailability {
            val sm: SensorManager? = context.getSystemService(SensorManager::class.java)
            return SensorAvailability(
                accelerometer = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null,
                gyroscope = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null,
                stepDetector = sm?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null,
                activityPermission = hasActivityPermission(context),
            )
        }

        fun hasActivityPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED
    }
}

/**
 * 가속도계·자이로·걸음 감지 센서를 MotionAnalyzer 에 연결한다.
 * 포그라운드 서비스 안에서 돌기 때문에 화면이 꺼져 있어도 센서 값을 받는다.
 */
class MotionMonitor(context: Context, private val analyzer: MotionAnalyzer) : SensorEventListener {
    private val sm: SensorManager? = context.getSystemService(SensorManager::class.java)
    private val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val stepDetector =
        if (SensorAvailability.hasActivityPermission(context)) sm?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) else null

    val hasMotionSensor: Boolean get() = accel != null || gyro != null
    var hardwareStepEvents = 0
        private set
    private var running = false

    fun start() {
        if (running || sm == null) return
        running = true
        analyzer.hardwareSteps = stepDetector != null
        accel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyro?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        stepDetector?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, 0) }
    }

    /** 걸음 센서가 고장/미응답이면 가속도 기반 걸음 추정으로 전환. */
    fun fallbackToSoftwareSteps() {
        if (!analyzer.hardwareSteps) return
        analyzer.hardwareSteps = false
        stepDetector?.let { sm?.unregisterListener(this, it) }
    }

    fun stop() {
        if (!running) return
        running = false
        sm?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> analyzer.onAccelerometer(event.values[0], event.values[1], event.values[2], now)
            Sensor.TYPE_GYROSCOPE -> analyzer.onGyroscope(event.values[0], event.values[1], event.values[2], now)
            Sensor.TYPE_STEP_DETECTOR -> {
                hardwareStepEvents++
                analyzer.onHardwareStep(now)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
