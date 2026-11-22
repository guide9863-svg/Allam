package com.allam.alarm.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioClipTest {
    @Test
    fun wavHeaderFields() {
        val h = ClipMath.wavHeader(44_100, 1, 1000)
        assertEquals(44, h.size)
        assertEquals("RIFF", String(h, 0, 4))
        assertEquals("WAVEfmt ", String(h, 8, 8))
        fun i32(at: Int) = (0..3).sumOf { (h[at + it].toInt() and 0xFF) shl (8 * it) }
        assertEquals(1036, i32(4))
        assertEquals(44_100, i32(24))
        assertEquals(88_200, i32(28))
        assertEquals(1000, i32(40))
    }

    @Test
    fun gainNormalizesToFullScale() {
        assertEquals(1f, ClipMath.gainFor(ClipBoost.ORIGINAL, 100))
        assertEquals(2f, ClipMath.gainFor(ClipBoost.MAX, 16_000))
        assertEquals(6f, ClipMath.gainFor(ClipBoost.EXTREME, 16_000))
        assertEquals(1f, ClipMath.gainFor(ClipBoost.MAX, 0))
        assertEquals(20f, ClipMath.gainFor(ClipBoost.MAX, 10))
    }

    @Test
    fun fadeAndClip() {
        val buf = ShortArray(10) { 10_000 }
        ClipMath.applyGainAndFade(buf, 10, 0, 10, 4, 4, 1f)
        assertEquals(0, buf[0].toInt())
        assertEquals(5_000, buf[2].toInt())
        assertEquals(10_000, buf[5].toInt())
        assertEquals(0, buf[9].toInt())

        val loud = ShortArray(4) { 20_000 }
        ClipMath.applyGainAndFade(loud, 4, 100, 1000, 0, 0, 3f)
        assertTrue(loud.all { it.toInt() == 32_767 })
    }

    @Test
    fun rangeKeepsLimits() {
        val d = 300_000L
        // 끝을 멀리 끌면 시작이 따라온다 (최대 3분)
        assertEquals(70_000L to 250_000L, ClipMath.adjustRange(0, 30_000, 0, 250_000, d))
        // 시작을 끝 너머로 끌면 끝이 밀린다 (최소 1초)
        assertEquals(40_000L to 41_000L, ClipMath.adjustRange(0, 30_000, 40_000, 30_000, d))
        // 파일 끝을 넘지 않는다
        assertEquals(299_000L to 300_000L, ClipMath.adjustRange(0, 30_000, 299_900, 30_000, d))
        // 끝을 시작 앞으로 끌면 시작이 밀린다
        assertEquals(4_000L to 5_000L, ClipMath.adjustRange(10_000, 30_000, 10_000, 5_000, d))
    }

    @Test
    fun peaksAndFormat() {
        val bins = PeakBins(4, 1_000_000)
        val buf = ShortArray(1000) { if (it < 250) 16_384 else 0 }
        bins.add(buf, 1000, 1000, 0)
        val p = bins.snapshot()
        assertEquals(0.5f, p[0])
        assertEquals(0f, p[3])
        assertEquals("1:07.5", ClipMath.formatMs(67_480))
    }
}
