package com.allam.alarm.alarm

import android.content.Context

/** getSystemService 의 null 가능성을 한 곳에서 처리. */
inline fun <reified T : Any> Context.service(): T = requireNotNull(getSystemService(T::class.java)) {
    "System service ${T::class.java.simpleName} unavailable"
}
