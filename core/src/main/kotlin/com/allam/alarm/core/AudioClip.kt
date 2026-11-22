package com.allam.alarm.core

import kotlinx.serialization.Serializable
import kotlin.math.abs

/** 사용자가 자기 음악 파일을 잘라 만든 알람음. 실제 소리는 앱 저장소의 `<id>.wav` 에 있다. */
@Serializable
data class CustomSound(
    val id: String,
    val name: String,
    val durationMs: Long,
)

/** 잘라낸 구간의 음량 처리. */
enum class ClipBoost(val title: String) {
    ORIGINAL("원본"),

    /** 가장 큰 소리가 최대치에 닿도록 전체를 키운다 (소리가 깨지지 않음). */
    MAX("최대 음량"),

    /** 최대 음량보다 3배 더 키워 일부러 찢어지게 만든다. */
    EXTREME("초강력"),
}

/** 음악 파일을 잘라 알람음으로 만드는 순수 계산. */
object ClipMath {
    const val MIN_CLIP_MS = 1_000L
    const val MAX_CLIP_MS = 180_000L
    private const val FULL = 32_000f

    /** 16bit PCM WAV 헤더(44바이트). */
    fun wavHeader(sampleRate: Int, channels: Int, dataBytes: Int): ByteArray {
        val h = ByteArray(44)
        fun str(at: Int, s: String) = s.forEachIndexed { i, c -> h[at + i] = c.code.toByte() }
        fun i32(at: Int, v: Int) = repeat(4) { h[at + it] = (v shr (8 * it)).toByte() }
        fun i16(at: Int, v: Int) = repeat(2) { h[at + it] = (v shr (8 * it)).toByte() }
        str(0, "RIFF"); i32(4, 36 + dataBytes); str(8, "WAVE")
        str(12, "fmt "); i32(16, 16); i16(20, 1); i16(22, channels)
        i32(24, sampleRate); i32(28, sampleRate * channels * 2); i16(32, channels * 2); i16(34, 16)
        str(36, "data"); i32(40, dataBytes)
        return h
    }

    /** @param peak 구간에서 가장 큰 샘플의 절댓값(0..32768) */
    fun gainFor(boost: ClipBoost, peak: Int): Float {
        if (boost == ClipBoost.ORIGINAL || peak <= 0) return 1f
        val normalize = (FULL / peak).coerceAtMost(20f)
        return if (boost == ClipBoost.EXTREME) normalize * 3f else normalize
    }

    /**
     * 증폭과 페이드 인/아웃을 제자리에서 적용한다.
     * @param frameOffset buf[0] 이 전체 구간에서 몇 번째 샘플인지
     */
    fun applyGainAndFade(
        buf: ShortArray,
        count: Int,
        frameOffset: Long,
        totalFrames: Long,
        fadeInFrames: Long,
        fadeOutFrames: Long,
        gain: Float,
    ) {
        for (i in 0 until count) {
            val pos = frameOffset + i
            var g = gain
            if (pos < fadeInFrames) g *= pos.toFloat() / fadeInFrames
            val fromEnd = totalFrames - 1 - pos
            if (fromEnd < fadeOutFrames) g *= fromEnd.coerceAtLeast(0).toFloat() / fadeOutFrames
            buf[i] = (buf[i] * g).toInt().coerceIn(-32_767, 32_767).toShort()
        }
    }

    /**
     * 구간 슬라이더를 움직였을 때 최소·최대 길이를 지키도록 맞춘다.
     * 움직인 쪽 손잡이는 그대로 두고 반대쪽을 끌고 간다.
     */
    fun adjustRange(oldStart: Long, oldEnd: Long, newStart: Long, newEnd: Long, durationMs: Long): Pair<Long, Long> {
        val minLen = MIN_CLIP_MS.coerceAtMost(durationMs)
        var s = newStart.coerceIn(0, durationMs)
        var e = newEnd.coerceIn(0, durationMs)
        val startMoved = abs(newStart - oldStart) >= abs(newEnd - oldEnd)
        if (startMoved) {
            s = s.coerceAtMost(durationMs - minLen)
            e = e.coerceIn(s + minLen, s + MAX_CLIP_MS).coerceAtMost(durationMs)
        } else {
            e = e.coerceAtLeast(minLen)
            s = s.coerceIn((e - MAX_CLIP_MS).coerceAtLeast(0), e - minLen)
        }
        return s to e
    }

    /** 0:07.5 형식. */
    fun formatMs(ms: Long): String {
        val tenths = (ms.coerceAtLeast(0) + 50) / 100
        return "%d:%02d.%d".format(tenths / 600, tenths / 10 % 60, tenths % 10)
    }
}

/** 파형 그림용: 전체 길이를 일정한 칸으로 나눠 칸마다 가장 큰 소리 크기를 모은다. */
class PeakBins(private val bins: Int, private val durationUs: Long) {
    private val peaks = IntArray(bins)

    fun add(buf: ShortArray, count: Int, sampleRate: Int, firstUs: Long) {
        if (durationUs <= 0 || sampleRate <= 0) return
        for (i in 0 until count) {
            val us = firstUs + i * 1_000_000L / sampleRate
            val bin = (us * bins / durationUs).toInt().coerceIn(0, bins - 1)
            val a = abs(buf[i].toInt())
            if (a > peaks[bin]) peaks[bin] = a
        }
    }

    /** 0..1 로 정규화한 칸별 크기. */
    fun snapshot(): FloatArray = FloatArray(bins) { peaks[it] / 32_768f }
}
