package com.allam.alarm.data

import android.content.Context
import android.content.SharedPreferences
import com.allam.alarm.alarm.AlarmScheduler
import com.allam.alarm.core.Alarm
import com.allam.alarm.core.AlarmCalculator
import com.allam.alarm.core.AppData
import com.allam.alarm.core.RepeatRule
import com.allam.alarm.core.ShiftSchedule
import com.allam.alarm.core.SnoozeState
import com.allam.alarm.core.WakeLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * 알람·교대표·기록을 하나의 JSON 으로 저장한다.
 *
 * 재부팅 후 잠금 해제 전(Direct Boot)에도 알람을 다시 등록하고 울릴 수 있도록
 * device-protected storage 에 저장한다.
 */
object AlarmRepository {
    private const val PREFS = "allam"
    private const val KEY = "data"

    private val lock = Any()
    private var prefs: SharedPreferences? = null
    private val _data = MutableStateFlow(AppData())
    val data: StateFlow<AppData> = _data

    fun init(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            val storage = context.applicationContext.createDeviceProtectedStorageContext()
            val p = storage.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs = p
            _data.value = AppData.decode(p.getString(KEY, null))
        }
    }

    fun get(context: Context): AppData {
        init(context)
        return _data.value
    }

    fun update(context: Context, transform: (AppData) -> AppData): AppData {
        init(context)
        synchronized(lock) {
            val next = transform(_data.value)
            if (next != _data.value) {
                _data.value = next
                // 리시버/서비스가 곧바로 종료될 수 있으므로 동기 저장
                prefs?.edit()?.putString(KEY, AppData.encode(next))?.commit()
            }
            return next
        }
    }
}

/** 화면에서 호출하는 알람 조작. 변경 후 알람 등록까지 책임진다. */
object AlarmStore {
    fun save(context: Context, alarm: Alarm): Alarm {
        var saved = alarm
        AlarmRepository.update(context) { d ->
            if (alarm.id <= 0) {
                saved = alarm.copy(id = d.nextAlarmId)
                d.copy(alarms = d.alarms + saved, nextAlarmId = d.nextAlarmId + 1)
            } else {
                d.copy(alarms = d.alarms.map { if (it.id == alarm.id) alarm else it })
            }
        }
        AlarmScheduler.rescheduleAll(context)
        return saved
    }

    fun delete(context: Context, id: Int) {
        AlarmRepository.update(context) { d ->
            d.copy(alarms = d.alarms.filterNot { it.id == id }, snoozes = d.snoozes.filterNot { it.alarmId == id })
        }
        AlarmScheduler.cancel(context, id)
        AlarmScheduler.cancelSnooze(context, id)
        AlarmScheduler.rescheduleAll(context)
    }

    fun setEnabled(context: Context, id: Int, enabled: Boolean) {
        AlarmRepository.update(context) { d ->
            d.copy(
                alarms = d.alarms.map { if (it.id == id) it.copy(enabled = enabled, skipEpochDays = emptySet()) else it },
                snoozes = if (enabled) d.snoozes else d.snoozes.filterNot { it.alarmId == id },
            )
        }
        if (!enabled) AlarmScheduler.cancelSnooze(context, id)
        AlarmScheduler.rescheduleAll(context)
    }

    fun skipNext(context: Context, id: Int) {
        AlarmRepository.update(context) { d ->
            d.copy(alarms = d.alarms.map { if (it.id == id) AlarmCalculator.skipNext(it, d.schedule, ZonedDateTime.now()) else it })
        }
        AlarmScheduler.rescheduleAll(context)
    }

    fun clearSkips(context: Context, id: Int) {
        AlarmRepository.update(context) { d ->
            d.copy(alarms = d.alarms.map { if (it.id == id) AlarmCalculator.clearSkips(it) else it })
        }
        AlarmScheduler.rescheduleAll(context)
    }

    fun setSchedule(context: Context, schedule: ShiftSchedule) {
        AlarmRepository.update(context) { d -> d.copy(schedule = schedule) }
        AlarmScheduler.rescheduleAll(context)
    }

    /** 알람이 울린 직후: 한 번 알람은 끄고, 지난 건너뛰기 날짜는 정리. */
    fun onFired(context: Context, id: Int): Alarm? {
        var result: Alarm? = null
        AlarmRepository.update(context) { d ->
            val today = LocalDate.now()
            d.copy(alarms = d.alarms.map {
                if (it.id != id) it
                else {
                    val pruned = AlarmCalculator.pruneSkips(it, today)
                    (if (it.repeat == RepeatRule.Once) pruned.copy(enabled = false) else pruned).also { a -> result = a }
                }
            })
        }
        return result
    }

    fun addSnooze(context: Context, state: SnoozeState) {
        AlarmRepository.update(context) { d ->
            d.copy(snoozes = d.snoozes.filterNot { it.alarmId == state.alarmId } + state)
        }
    }

    fun removeSnooze(context: Context, alarmId: Int) {
        AlarmRepository.update(context) { d -> d.copy(snoozes = d.snoozes.filterNot { it.alarmId == alarmId }) }
    }

    fun addLog(context: Context, log: WakeLog) {
        AlarmRepository.update(context) { d -> d.copy(logs = (listOf(log) + d.logs).take(AppData.MAX_LOGS)) }
    }

    fun clearLogs(context: Context) {
        AlarmRepository.update(context) { d -> d.copy(logs = emptyList()) }
    }
}
