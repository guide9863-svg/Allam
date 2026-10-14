package com.allam.alarm.core

import kotlin.math.PI
import kotlin.math.sin

/**
 * 귀에 거슬리도록 설계된 알람음을 16bit PCM(mono)으로 합성한다.
 * 결과 버퍼는 끊김 없이 반복 재생(loop)되도록 주기 단위로 만든다.
 */
object ToneSynth {
    const val SAMPLE_RATE = 44_100
    private const val FULL = 32_000.0

    fun generate(type: SoundType): ShortArray = when (type) {
        SoundType.SIREN -> siren()
        SoundType.FIRE_BELL -> fireBell()
        SoundType.CHAOS -> chaos()
        SoundType.SYSTEM -> siren()
    }

    /** 600Hz ↔ 1800Hz 를 0.5초마다 오르내리는 사각파 사이렌 (2초 루프). */
    private fun siren(): ShortArray {
        val seconds = 2.0
        val n = (SAMPLE_RATE * seconds).toInt()
        val out = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val cycle = (t % 1.0) // 1초 주기 오르내림
            val f = if (cycle < 0.5) 600 + 1200 * (cycle / 0.5) else 1800 - 1200 * ((cycle - 0.5) / 0.5)
            phase += 2 * PI * f / SAMPLE_RATE
            out[i] = square(phase, harsh = true)
        }
        return out
    }

    /** 화재경보기 계열 3.1kHz 고음 삐-삐-삐 (사람 귀가 가장 예민한 대역). */
    private fun fireBell(): ShortArray {
        val pattern = listOf(0.12 to true, 0.06 to false, 0.12 to true, 0.06 to false, 0.12 to true, 0.32 to false)
        val total = pattern.sumOf { it.first }
        val n = (SAMPLE_RATE * total).toInt()
        val out = ShortArray(n)
        var phase = 0.0
        var idx = 0
        for ((dur, on) in pattern) {
            val len = (SAMPLE_RATE * dur).toInt()
            for (k in 0 until len) {
                if (idx >= n) break
                phase += 2 * PI * 3100 / SAMPLE_RATE
                out[idx++] = if (on) square(phase, harsh = true) else 0
            }
        }
        return out
    }

    /** 서로 어긋나는 두 음 + 빠르게 바뀌는 음정 (불협화음, 1.6초 루프). */
    private fun chaos(): ShortArray {
        val freqs = listOf(880.0, 1245.0, 2093.0, 1480.0, 987.0, 2637.0, 1175.0, 1661.0)
        val step = 0.2
        val n = (SAMPLE_RATE * step * freqs.size).toInt()
        val out = ShortArray(n)
        var p1 = 0.0
        var p2 = 0.0
        for (i in 0 until n) {
            val seg = (i / (SAMPLE_RATE * step)).toInt().coerceAtMost(freqs.size - 1)
            val f = freqs[seg]
            p1 += 2 * PI * f / SAMPLE_RATE
            p2 += 2 * PI * (f * 1.06) / SAMPLE_RATE // 반음 어긋난 음을 겹쳐 맥놀이
            val v = 0.5 * squareValue(p1) + 0.5 * squareValue(p2)
            out[i] = (v * FULL).toInt().coerceIn(-32_767, 32_767).toShort()
        }
        return out
    }

    private fun squareValue(phase: Double): Double = if (sin(phase) >= 0) 1.0 else -1.0

    /** 사각파 + 약간의 3배음 강조로 날카롭게. */
    private fun square(phase: Double, harsh: Boolean): Short {
        val base = squareValue(phase)
        val v = if (harsh) 0.85 * base + 0.15 * sin(3 * phase) else base
        return (v * FULL).toInt().coerceIn(-32_767, 32_767).toShort()
    }
}
