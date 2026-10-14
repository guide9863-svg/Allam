package com.allam.alarm

import android.app.Application
import com.allam.alarm.alarm.Notifications
import com.allam.alarm.data.AlarmRepository

class AllamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 잠금 해제 전(Direct Boot)에도 안전: 채널 생성과 device-protected 저장소만 사용
        Notifications.createChannels(this)
        AlarmRepository.init(this)
    }
}
