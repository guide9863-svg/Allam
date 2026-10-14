package com.allam.alarm.alarm

import com.allam.alarm.core.WakeMode
import com.allam.alarm.core.WakeOutcome
import com.allam.alarm.core.WakePhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 울림 화면에 보여줄 상태 (서비스 → 화면). */
data class RingUiState(
    val alarmId: Int,
    val label: String,
    val timeText: String,
    val phase: WakePhase,
    val mode: WakeMode,
    val steps: Int,
    val targetSteps: Int,
    val motionSec: Int,
    val targetMotionSec: Int,
    val progress: Float,
    val intensity: Float,
    val quietRemainingSec: Int,
    val stillTimeoutSec: Int,
    val snoozesLeft: Int,
    val snoozeMinutes: Int,
    val reRings: Int,
    val hardwareSteps: Boolean,
    val hasMotionSensor: Boolean,
    val outcome: WakeOutcome?,
    val startedAtWall: Long,
    val endedAtWall: Long,
    val snoozeUntilWall: Long,
    val triggerAtWall: Long,
) {
    val isFinished: Boolean
        get() = phase == WakePhase.AWAKE || phase == WakePhase.SNOOZED || phase == WakePhase.TIMED_OUT
}

object RingState {
    private val _state = MutableStateFlow<RingUiState?>(null)
    val state: StateFlow<RingUiState?> = _state

    fun publish(s: RingUiState) {
        _state.value = s
    }

    fun clear() {
        _state.value = null
    }
}
