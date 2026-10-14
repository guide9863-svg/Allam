package com.allam.alarm.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 알람 소리 종류. SYSTEM 을 제외하면 모두 앱이 직접 합성하는 소리라 잠금 해제 전(Direct Boot)에도 울린다. */
@Serializable
enum class SoundType(val title: String) {
    SIREN("초강력 사이렌"),
    FIRE_BELL("화재경보 비상벨"),
    CHAOS("혼돈의 불협화음"),
    SYSTEM("휴대폰 기본 알람음"),
}

/** 기상 확인 방식. */
@Serializable
enum class WakeMode(val title: String) {
    /** 걸음 수 또는 연속 움직임 시간 중 하나만 채우면 기상 인정. */
    STEPS_OR_MOTION("걸음 또는 움직임"),

    /** 걸음 수만 인정 (엄격 모드: 침대에서 흔들기만으로는 안 꺼짐). */
    STEPS_ONLY("걸음만 인정 (엄격)"),
}

/**
 * 움직임으로 기상을 확인하는 조건.
 *
 * @property stillTimeoutSec 이 시간 동안 움직임이 없으면 진행도가 0으로 초기화되고, 소리를 잠시 꺼둔 상태였다면 다시 울린다.
 * @property sensitivity 0 = 둔감(더 크게 움직여야 함), 1 = 보통, 2 = 민감
 */
@Serializable
data class WakeCheck(
    val mode: WakeMode = WakeMode.STEPS_OR_MOTION,
    val targetSteps: Int = 30,
    val targetMotionSec: Int = 30,
    val stillTimeoutSec: Int = 20,
    val sensitivity: Int = 1,
)

/** 반복 규칙. */
@Serializable
sealed class RepeatRule {
    /** 한 번만 울리고 자동으로 꺼진다. */
    @Serializable
    @SerialName("once")
    data object Once : RepeatRule()

    /** 요일 반복. days 는 ISO 요일 번호(1=월 ... 7=일). */
    @Serializable
    @SerialName("weekly")
    data class Weekly(val days: Set<Int>) : RepeatRule()

    /** 교대 근무 연동. 교대표에서 해당 날짜의 근무가 typeIds 중 하나일 때만 울린다. */
    @Serializable
    @SerialName("shift")
    data class Shift(val typeIds: Set<String>) : RepeatRule()
}

@Serializable
data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val label: String = "",
    val enabled: Boolean = true,
    val repeat: RepeatRule = RepeatRule.Once,
    /** "이번만 건너뛰기"로 지정된 날짜(epochDay). */
    val skipEpochDays: Set<Long> = emptySet(),
    val sound: SoundType = SoundType.SIREN,
    val ringtoneUri: String? = null,
    val vibrate: Boolean = true,
    val flash: Boolean = false,
    val forceMaxVolume: Boolean = true,
    val snoozeMinutes: Int = 5,
    val maxSnoozes: Int = 2,
    val wakeCheck: WakeCheck = WakeCheck(),
    /** 마지막으로 울린 회차의 예정 시각(ms). 꺼져 있던 동안 놓친 알람 판단용. */
    val lastFiredAt: Long = 0,
)

/** 스누즈로 잠시 미뤄진 알람. 재부팅 후에도 복구할 수 있게 저장한다. */
@Serializable
data class SnoozeState(
    val alarmId: Int,
    val triggerAtMillis: Long,
    val snoozeCount: Int,
)

/**
 * 지금 울리고 있는 알람. 전원 재시작·프로세스 종료로 울림이 끊겨도
 * 감시 알람(watchdog)이 이 정보로 다시 울린다. 기상 확인 전까지는 지워지지 않는다.
 */
@Serializable
data class ActiveRing(
    val alarmId: Int,
    val isTest: Boolean,
    val startedAtWall: Long,
    val triggerAt: Long,
    val snoozesUsed: Int,
    /** 최대 볼륨으로 바꾸기 전 원래 알람 볼륨 (복구용). */
    val savedVolume: Int? = null,
)

@Serializable
enum class WakeOutcome(val title: String) {
    STEPS("걸음으로 기상 확인"),
    MOTION("움직임으로 기상 확인"),
    EMERGENCY("긴급 해제"),
    TIMED_OUT("시간 초과 (기상 미확인)"),
}

@Serializable
data class WakeLog(
    val alarmId: Int,
    val label: String,
    val ringStartedAt: Long,
    val endedAt: Long,
    val outcome: WakeOutcome,
    val steps: Int,
    val motionSec: Int,
    val snoozes: Int,
    val reRings: Int,
)

/** 앱 전체 저장 데이터 (하나의 JSON 으로 저장). */
@Serializable
data class AppData(
    val alarms: List<Alarm> = emptyList(),
    val schedule: ShiftSchedule = ShiftSchedule(),
    val nextAlarmId: Int = 1,
    val snoozes: List<SnoozeState> = emptyList(),
    val logs: List<WakeLog> = emptyList(),
    val activeRing: ActiveRing? = null,
) {
    fun alarm(id: Int): Alarm? = alarms.firstOrNull { it.id == id }

    companion object {
        const val MAX_LOGS = 50

        fun decode(text: String?): AppData =
            if (text.isNullOrBlank()) AppData()
            else runCatching { AllamJson.decodeFromString(serializer(), text) }.getOrElse { AppData() }

        fun encode(data: AppData): String = AllamJson.encodeToString(serializer(), data)
    }
}

val AllamJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
