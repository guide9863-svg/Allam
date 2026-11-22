package com.allam.alarm.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.allam.alarm.core.ClipBoost
import com.allam.alarm.core.ClipMath
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

/** 편집한 알람음(WAV)이 저장되는 곳. 잠금 해제 전(Direct Boot)에도 울리도록 device-protected 저장소를 쓴다. */
object SoundFiles {
    private fun dir(context: Context): File =
        File(context.applicationContext.createDeviceProtectedStorageContext().filesDir, "sounds").apply { mkdirs() }

    fun file(context: Context, id: String): File = File(dir(context), "$id.wav")
}

/** mp3·m4a·flac·ogg·wav 등 휴대폰이 재생할 수 있는 음악 파일을 PCM 으로 푼다. */
object AudioDecoder {
    class Info(val durationMs: Long, val title: String)

    /** 길이와 제목. 읽을 수 없는 파일이면 null. */
    fun probe(context: Context, uri: Uri): Info? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: return null
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim().orEmpty()
                .ifBlank { displayName(context, uri) }
            Info(duration, title)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun displayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null
        }
    }.getOrNull().orEmpty().ifBlank { "내 알람음" }

    /**
     * [startUs, endUs) 구간을 모노 16bit 로 풀어 [sink] 로 차례대로 넘긴다.
     * sink(samples, count, sampleRate, 첫 샘플의 시각(us)). 메모리에 전체를 올리지 않는다.
     */
    fun decodeMono(
        context: Context,
        uri: Uri,
        startUs: Long,
        endUs: Long,
        isActive: () -> Boolean,
        sink: (ShortArray, Int, Int, Long) -> Unit,
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("no audio track")
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var mono = ShortArray(8192)

            while (!outputDone) {
                if (!isActive()) throw CancellationException()
                if (!inputDone) {
                    val i = decoder.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = decoder.getInputBuffer(i)!!
                        val time = extractor.sampleTime
                        val size = if (time > endUs) -1 else extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(i, 0, size, time, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = decoder.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = decoder.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (o >= 0) {
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (info.size > 0 && !isConfig && channels > 0 && sampleRate > 0) {
                        val out = decoder.getOutputBuffer(o)!!.duplicate().order(ByteOrder.nativeOrder())
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        val frames = info.size / ((if (isFloat) 4 else 2) * channels)
                        val pts = info.presentationTimeUs
                        // 이 버퍼에서 [startUs, endUs) 에 드는 프레임만 남긴다
                        val first = ((startUs - pts) * sampleRate / 1_000_000L).coerceIn(0, frames.toLong()).toInt()
                        val last = if (endUs == Long.MAX_VALUE) frames
                        else ((endUs - pts) * sampleRate / 1_000_000L).coerceIn(0, frames.toLong()).toInt()
                        val count = last - first
                        if (count > 0) {
                            if (mono.size < count) mono = ShortArray(count)
                            if (isFloat) {
                                val fb = out.asFloatBuffer()
                                for (k in 0 until count) {
                                    var sum = 0f
                                    val base = (first + k) * channels
                                    for (c in 0 until channels) sum += fb.get(base + c)
                                    mono[k] = (sum / channels * 32_767f).toInt().coerceIn(-32_768, 32_767).toShort()
                                }
                            } else {
                                val sb = out.asShortBuffer()
                                for (k in 0 until count) {
                                    var sum = 0
                                    val base = (first + k) * channels
                                    for (c in 0 until channels) sum += sb.get(base + c)
                                    mono[k] = (sum / channels).toShort()
                                }
                            }
                            sink(mono, count, sampleRate, pts + first * 1_000_000L / sampleRate)
                        }
                        if (pts >= endUs) outputDone = true
                    }
                    decoder.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}

/** 고른 구간을 잘라 증폭·페이드를 입힌 뒤 WAV 로 저장한다. */
object ClipExporter {
    private const val CHUNK = 16_384

    /** @return 저장된 알람음의 길이(ms). 실패하면 예외. */
    fun export(
        context: Context,
        uri: Uri,
        startMs: Long,
        endMs: Long,
        fadeInMs: Long,
        fadeOutMs: Long,
        boost: ClipBoost,
        outFile: File,
        isActive: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): Long {
        val raw = File.createTempFile("clip", ".pcm", context.cacheDir)
        val tmp = File(outFile.parentFile, outFile.name + ".tmp")
        try {
            // 1단계: 구간을 풀어 임시 파일에 쓰면서 가장 큰 소리를 찾는다
            var sampleRate = 0
            var frames = 0L
            var peak = 0
            val bb = ByteBuffer.allocate(CHUNK * 2).order(ByteOrder.LITTLE_ENDIAN)
            val span = (endMs - startMs).coerceAtLeast(1)
            FileOutputStream(raw).channel.use { ch ->
                AudioDecoder.decodeMono(context, uri, startMs * 1000, endMs * 1000, isActive) { buf, n, sr, us ->
                    if (sampleRate == 0) sampleRate = sr
                    var off = 0
                    while (off < n) {
                        val len = minOf(n - off, CHUNK)
                        bb.clear()
                        for (k in off until off + len) {
                            val v = buf[k]
                            val a = abs(v.toInt())
                            if (a > peak) peak = a
                            bb.putShort(v)
                        }
                        bb.flip()
                        while (bb.hasRemaining()) ch.write(bb)
                        off += len
                    }
                    frames += n
                    onProgress((0.8f * (us / 1000 - startMs) / span).coerceIn(0f, 0.8f))
                }
            }
            if (sampleRate <= 0 || frames < sampleRate / 2) throw IOException("clip too short")

            // 2단계: 증폭·페이드를 입혀 WAV 로 쓴다
            val gain = ClipMath.gainFor(boost, peak)
            val fadeIn = fadeInMs * sampleRate / 1000
            val fadeOut = fadeOutMs * sampleRate / 1000
            val samples = ShortArray(CHUNK)
            var done = 0L
            FileInputStream(raw).channel.use { inp ->
                FileOutputStream(tmp).use { os ->
                    os.write(ClipMath.wavHeader(sampleRate, 1, (frames * 2).toInt()))
                    val out = os.channel
                    while (done < frames) {
                        if (!isActive()) throw CancellationException()
                        bb.clear()
                        while (bb.hasRemaining() && inp.read(bb) >= 0) Unit
                        bb.flip()
                        val n = bb.remaining() / 2
                        if (n == 0) break
                        for (k in 0 until n) samples[k] = bb.getShort(k * 2)
                        ClipMath.applyGainAndFade(samples, n, done, frames, fadeIn, fadeOut, gain)
                        bb.clear()
                        for (k in 0 until n) bb.putShort(samples[k])
                        bb.flip()
                        while (bb.hasRemaining()) out.write(bb)
                        done += n
                        onProgress(0.8f + 0.2f * done / frames)
                    }
                }
            }
            if (done != frames) throw IOException("short read")
            outFile.delete()
            if (!tmp.renameTo(outFile)) throw IOException("rename failed")
            return frames * 1000 / sampleRate
        } finally {
            raw.delete()
            tmp.delete()
        }
    }
}
