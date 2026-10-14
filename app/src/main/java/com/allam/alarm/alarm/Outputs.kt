package com.allam.alarm.alarm

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.allam.alarm.core.SoundType
import com.allam.alarm.core.ToneSynth

private val alarmAttributes: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ALARM)
    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
    .build()

/**
 * 알람 소리. 알람 스트림(USAGE_ALARM)으로 재생하므로 무음/진동 모드에서도 울린다.
 * 합성음은 AudioTrack 정적 버퍼를 무한 반복한다.
 */
class AlarmSoundPlayer(
    private val context: Context,
    private val sound: SoundType,
    private val ringtoneUri: String?,
) {
    private var track: AudioTrack? = null
    private var media: MediaPlayer? = null
    var isPlaying = false
        private set

    fun start() {
        if (isPlaying) return
        media?.let {
            runCatching { it.start() }.onSuccess { isPlaying = true; return }
        }
        track?.let {
            runCatching { it.play() }.onSuccess { isPlaying = true; return }
        }
        if (sound == SoundType.SYSTEM && startSystem()) {
            isPlaying = true
            return
        }
        isPlaying = startSynth(if (sound == SoundType.SYSTEM) SoundType.SIREN else sound)
    }

    fun pause() {
        if (!isPlaying) return
        runCatching { media?.pause() }
        runCatching { track?.pause() }
        isPlaying = false
    }

    fun release() {
        runCatching { media?.stop() }
        runCatching { media?.release() }
        runCatching { track?.stop() }
        runCatching { track?.release() }
        media = null
        track = null
        isPlaying = false
    }

    private fun startSystem(): Boolean = runCatching {
        val uri = ringtoneUri?.let(Uri::parse)
            ?: RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: return false
        val player = MediaPlayer()
        player.setAudioAttributes(alarmAttributes)
        player.setDataSource(context, uri)
        player.isLooping = true
        player.prepare()
        player.start()
        media = player
        true
    }.getOrElse {
        media?.release()
        media = null
        false
    }

    private fun startSynth(type: SoundType): Boolean = runCatching {
        val pcm = ToneSynth.generate(type)
        val t = AudioTrack.Builder()
            .setAudioAttributes(alarmAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(ToneSynth.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        t.write(pcm, 0, pcm.size)
        t.setLoopPoints(0, pcm.size, -1)
        t.setVolume(AudioTrack.getMaxVolume())
        t.play()
        track = t
        true
    }.getOrDefault(false)
}

/** 알람 스트림 볼륨을 최대로 고정하고, 끝나면 원래대로 돌려놓는다. */
class VolumeEnforcer(context: Context, initialSaved: Int? = null) {
    private val audio: AudioManager = context.service()
    private var saved: Int? = initialSaved

    /** 최대로 바꾸기 전의 원래 볼륨 (울림이 끊겨도 복구할 수 있게 저장). */
    val savedVolume: Int? get() = saved

    fun enforceMax() {
        runCatching {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val current = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            if (saved == null) saved = current
            if (current < max) audio.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
        }
    }

    fun restore() {
        val v = saved ?: return
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, v, 0) }
        saved = null
    }

    companion object {
        fun restoreTo(context: Context, volume: Int?) {
            val v = volume ?: return
            runCatching { context.service<AudioManager>().setStreamVolume(AudioManager.STREAM_ALARM, v, 0) }
        }
    }
}

class AlarmVibrator(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm: VibratorManager? = context.getSystemService(VibratorManager::class.java)
        vm?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }
    private var on = false

    fun start() {
        val v = vibrator ?: return
        if (on || !v.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 900, 300, 900, 300), intArrayOf(0, 255, 0, 255, 0), 0)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect, alarmAttributes)
            }
            on = true
        }
    }

    fun stop() {
        if (!on) return
        runCatching { vibrator?.cancel() }
        on = false
    }
}

/** 카메라 플래시를 빠르게 깜빡인다 (권한 불필요). */
class TorchStrobe(context: Context) {
    private val camera: CameraManager? = context.getSystemService(CameraManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val cameraId: String? = runCatching {
        val cm = camera ?: return@runCatching null
        cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrNull()
    private var running = false
    private var lit = false

    private val blink = object : Runnable {
        override fun run() {
            if (!running) return
            lit = !lit
            setTorch(lit)
            handler.postDelayed(this, 250)
        }
    }

    fun start() {
        if (running || cameraId == null) return
        running = true
        handler.post(blink)
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(blink)
        lit = false
        setTorch(false)
    }

    private fun setTorch(on: Boolean) {
        val id = cameraId ?: return
        runCatching { camera?.setTorchMode(id, on) }
    }
}
