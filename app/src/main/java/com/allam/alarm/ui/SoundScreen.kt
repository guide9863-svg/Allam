package com.allam.alarm.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.allam.alarm.alarm.AlarmSoundPlayer
import com.allam.alarm.audio.AudioDecoder
import com.allam.alarm.audio.ClipExporter
import com.allam.alarm.audio.SoundFiles
import com.allam.alarm.core.AppData
import com.allam.alarm.core.ClipBoost
import com.allam.alarm.core.ClipMath
import com.allam.alarm.core.CustomSound
import com.allam.alarm.core.PeakBins
import com.allam.alarm.core.SoundType
import com.allam.alarm.data.AlarmStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/** 알람음 탭: 내 음악 파일을 불러와 잘라서 알람음으로 저장하고, 알람에 적용한다. */
@Composable
fun SoundScreen(data: AppData, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var editingUri by remember { mutableStateOf<Uri?>(null) }
    var playingId by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<AlarmSoundPlayer?>(null) }
    var applying by remember { mutableStateOf<CustomSound?>(null) }
    var deleting by remember { mutableStateOf<CustomSound?>(null) }

    fun stopPlayback() {
        player?.release()
        player = null
        playingId = null
    }
    DisposableEffect(Unit) { onDispose { player?.release() } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            stopPlayback()
            editingUri = uri
        }
    }

    val uri = editingUri
    if (uri != null) {
        BackHandler { editingUri = null }
        ClipEditor(uri = uri, modifier = modifier, onDone = { editingUri = null })
        return
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("알람음", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        Section("내 음악으로 알람음 만들기") {
            Text(
                "휴대폰에 있는 mp3 등 음악 파일을 불러와 원하는 구간만 잘라 알람음으로 저장합니다. " +
                    "저장한 알람음은 알람이 울리는 동안 계속 반복 재생됩니다.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { runCatching { picker.launch(arrayOf("audio/*")) } }) { Text("음악 파일 불러오기") }
        }

        Section("내 알람음") {
            if (data.customSounds.isEmpty()) {
                Text("아직 만든 알람음이 없어요.", style = MaterialTheme.typography.bodySmall)
            } else {
                data.customSounds.forEach { s ->
                    val users = data.alarms.count { it.sound == SoundType.CUSTOM && it.customSoundId == s.id }
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(s.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${ClipMath.formatMs(s.durationMs)} · " + if (users > 0) "알람 ${users}개에서 사용 중" else "사용하는 알람 없음",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = {
                                if (playingId == s.id) stopPlayback()
                                else {
                                    stopPlayback()
                                    val p = AlarmSoundPlayer(context, SoundType.CUSTOM, null, s.id)
                                    p.start()
                                    player = p
                                    playingId = s.id
                                }
                            }) { Text(if (playingId == s.id) "정지" else "듣기") }
                            TextButton(onClick = { applying = s }) { Text("알람에 적용") }
                            TextButton(onClick = { deleting = s }) {
                                Text("삭제", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    HorizontalDivider()
                }
                Text(
                    "듣기는 현재 알람 볼륨으로 재생돼요. 알람 편집 화면의 '소리 · 진동'에서도 고를 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    applying?.let { s ->
        ApplyDialog(data = data, sound = s, onClose = { applying = null })
    }
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("'${s.name}' 삭제") },
            text = { Text("이 알람음을 쓰던 알람은 '초강력 사이렌'으로 바뀝니다.") },
            confirmButton = {
                TextButton(onClick = {
                    if (playingId == s.id) stopPlayback()
                    AlarmStore.deleteCustomSound(context, s.id)
                    deleting = null
                }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("취소") } },
        )
    }
}

@Composable
private fun ApplyDialog(data: AppData, sound: CustomSound, onClose: () -> Unit) {
    val context = LocalContext.current
    var selected by remember {
        mutableStateOf(
            data.alarms.filter { it.sound == SoundType.CUSTOM && it.customSoundId == sound.id }.map { it.id }.toSet(),
        )
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("'${sound.name}' 을(를) 쓸 알람") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (data.alarms.isEmpty()) {
                    Text("알람이 없어요. 알람 탭에서 먼저 알람을 추가하세요.")
                } else {
                    TextButton(onClick = { selected = data.alarms.map { it.id }.toSet() }) { Text("모두 선택") }
                }
                data.alarms.forEach { a ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { selected = if (a.id in selected) selected - a.id else selected + a.id },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = a.id in selected, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                        Text(formatTime(a.hour, a.minute) + if (a.label.isBlank()) "" else "  ${a.label}")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                AlarmStore.applyCustomSound(context, sound.id, selected)
                onClose()
            }) { Text("적용") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("취소") } },
    )
}

private const val WAVE_BINS = 160

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClipEditor(uri: Uri, modifier: Modifier, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var durationMs by remember { mutableLongStateOf(0) }
    var loadFailed by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var startMs by remember { mutableLongStateOf(0) }
    var endMs by remember { mutableLongStateOf(0) }
    var peaks by remember { mutableStateOf(FloatArray(WAVE_BINS)) }
    var waveProgress by remember { mutableFloatStateOf(0f) }
    var fadeInSec by remember { mutableStateOf(0) }
    var fadeOutSec by remember { mutableStateOf(0) }
    var boost by remember { mutableStateOf(ClipBoost.MAX) }
    var saving by remember { mutableStateOf(false) }
    var saveProgress by remember { mutableFloatStateOf(0f) }

    // 원본 파일 미리 듣기 (미디어 볼륨)
    var preview by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var posMs by remember { mutableLongStateOf(-1) }
    DisposableEffect(uri) {
        onDispose {
            runCatching { preview?.release() }
            preview = null
        }
    }

    fun stopPreview() {
        playing = false
        posMs = -1
        runCatching { preview?.takeIf { it.isPlaying }?.pause() }
    }

    fun playFrom(fromMs: Long) {
        stopPreview()
        val p = preview ?: runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                setDataSource(context, uri)
                prepare()
                // 탐색이 끝난 뒤에 재생해야 현재 위치가 정확하다
                setOnSeekCompleteListener {
                    runCatching { it.start() }
                    playing = true
                }
                setOnCompletionListener { playing = false }
            }
        }.getOrNull()?.also { preview = it }
        if (p == null) {
            Toast.makeText(context, "이 파일은 미리 들을 수 없어요", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { p.seekTo(fromMs.coerceAtLeast(0), MediaPlayer.SEEK_CLOSEST) }
    }

    LaunchedEffect(playing) {
        while (playing) {
            val p = preview ?: break
            val pos = runCatching { p.currentPosition.toLong() }.getOrDefault(-1L)
            posMs = pos
            if (pos >= endMs) {
                stopPreview()
                break
            }
            delay(30)
        }
        if (!playing) posMs = -1
    }

    // 길이·제목을 읽고, 백그라운드에서 파형을 그린다
    LaunchedEffect(uri) {
        val info = withContext(Dispatchers.IO) { AudioDecoder.probe(context, uri) }
        if (info == null || info.durationMs < ClipMath.MIN_CLIP_MS) {
            loadFailed = true
            return@LaunchedEffect
        }
        name = info.title.take(40)
        endMs = minOf(info.durationMs, 30_000L)
        durationMs = info.durationMs
        withContext(Dispatchers.Default) {
            val job = coroutineContext[Job]
            val bins = PeakBins(WAVE_BINS, info.durationMs * 1000)
            var lastShown = 0L
            try {
                AudioDecoder.decodeMono(context, uri, 0, Long.MAX_VALUE, { job?.isActive != false }) { buf, n, sr, us ->
                    bins.add(buf, n, sr, us)
                    val now = System.nanoTime()
                    if (now - lastShown > 250_000_000L) {
                        lastShown = now
                        peaks = bins.snapshot()
                        waveProgress = (us / 1000f / info.durationMs).coerceIn(0f, 1f)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 파형 없이도 편집은 가능
            }
            peaks = bins.snapshot()
            waveProgress = 1f
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "알람음 편집",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDone, enabled = !saving) { Text("취소") }
        }

        if (loadFailed) {
            Text("이 파일은 열 수 없어요. 다른 음악 파일을 골라 주세요.", color = MaterialTheme.colorScheme.error)
            return@Column
        }
        if (durationMs == 0L) {
            Text("파일을 읽는 중…")
            LinearProgressIndicator(Modifier.fillMaxWidth())
            return@Column
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            label = { Text("알람음 이름") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Section("구간 고르기") {
            Waveform(peaks, durationMs, startMs, endMs, posMs)
            if (waveProgress < 1f) {
                LinearProgressIndicator(progress = { waveProgress }, modifier = Modifier.fillMaxWidth())
            }
            RangeSlider(
                value = startMs.toFloat()..endMs.toFloat(),
                onValueChange = { r ->
                    val (s, e) = ClipMath.adjustRange(startMs, endMs, r.start.toLong(), r.endInclusive.toLong(), durationMs)
                    startMs = s
                    endMs = e
                },
                valueRange = 0f..durationMs.toFloat(),
                enabled = !saving,
            )
            Text(
                "길이 ${ClipMath.formatMs(endMs - startMs)}  (전체 ${ClipMath.formatMs(durationMs)})",
                fontWeight = FontWeight.Bold,
            )
            Nudge("시작", startMs, !saving) { delta ->
                val (s, e) = ClipMath.adjustRange(startMs, endMs, startMs + delta, endMs, durationMs)
                startMs = s
                endMs = e
            }
            Nudge("끝", endMs, !saving) { delta ->
                val (s, e) = ClipMath.adjustRange(startMs, endMs, startMs, endMs + delta, durationMs)
                startMs = s
                endMs = e
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (playing) {
                    OutlinedButton(onClick = { stopPreview() }) { Text("정지") }
                } else {
                    OutlinedButton(onClick = { playFrom(startMs) }, enabled = !saving) { Text("구간 듣기") }
                    OutlinedButton(onClick = { playFrom(maxOf(startMs, endMs - 3000)) }, enabled = !saving) { Text("끝 3초 듣기") }
                }
            }
            Text(
                "미리 듣기는 미디어 볼륨으로 원본을 재생해요. 최대 ${ClipMath.MAX_CLIP_MS / 60_000}분까지 자를 수 있습니다.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Section("음량 · 페이드") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ClipBoost.entries.forEachIndexed { i, b ->
                    SegmentedButton(
                        selected = boost == b,
                        onClick = { boost = b },
                        shape = SegmentedButtonDefaults.itemShape(i, ClipBoost.entries.size),
                        enabled = !saving,
                    ) { Text(b.title) }
                }
            }
            Text(
                when (boost) {
                    ClipBoost.ORIGINAL -> "파일의 음량 그대로 저장합니다."
                    ClipBoost.MAX -> "소리가 깨지지 않는 한도에서 가장 크게 키웁니다. (권장)"
                    ClipBoost.EXTREME -> "소리가 찢어질 만큼 과하게 키웁니다. 음질은 나빠지지만 훨씬 시끄럽습니다."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            FadeRow("서서히 커지기 (페이드 인)", fadeInSec, !saving) { fadeInSec = it }
            FadeRow("서서히 작아지기 (페이드 아웃)", fadeOutSec, !saving) { fadeOutSec = it }
        }

        if (saving) LinearProgressIndicator(progress = { saveProgress }, modifier = Modifier.fillMaxWidth())
        Button(
            enabled = !saving,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                stopPreview()
                saving = true
                saveProgress = 0f
                val id = "s${System.currentTimeMillis()}"
                val clipMs = endMs - startMs
                // 페이드가 구간의 절반을 넘지 않게
                val fadeIn = (fadeInSec * 1000L).coerceAtMost(clipMs / 2)
                val fadeOut = (fadeOutSec * 1000L).coerceAtMost(clipMs / 2)
                scope.launch {
                    val saved = try {
                        withContext(Dispatchers.IO) {
                            val job = coroutineContext[Job]
                            ClipExporter.export(
                                context, uri, startMs, endMs, fadeIn, fadeOut, boost,
                                SoundFiles.file(context, id),
                                isActive = { job?.isActive != false },
                                onProgress = { saveProgress = it },
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    saving = false
                    if (saved == null) {
                        Toast.makeText(context, "저장하지 못했어요. 다른 파일이나 구간으로 다시 해 보세요.", Toast.LENGTH_LONG).show()
                    } else {
                        AlarmStore.addCustomSound(context, CustomSound(id, name.trim().ifBlank { "내 알람음" }, saved))
                        Toast.makeText(context, "저장했어요. '알람에 적용'으로 알람에 연결하세요.", Toast.LENGTH_LONG).show()
                        onDone()
                    }
                }
            },
        ) { Text(if (saving) "저장하는 중…" else "알람음으로 저장") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Waveform(peaks: FloatArray, durationMs: Long, startMs: Long, endMs: Long, posMs: Long) {
    val selectedColor = MaterialTheme.colorScheme.primary
    val restColor = MaterialTheme.colorScheme.outlineVariant
    val cursorColor = MaterialTheme.colorScheme.error
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(88.dp),
    ) {
        val n = peaks.size
        if (n == 0 || durationMs <= 0) return@Canvas
        val barW = size.width / n
        for (i in 0 until n) {
            val midMs = (i + 0.5f) / n * durationMs
            val h = (peaks[i] * size.height).coerceAtLeast(2f)
            drawRect(
                color = if (midMs >= startMs && midMs <= endMs) selectedColor else restColor,
                topLeft = Offset(i * barW, (size.height - h) / 2),
                size = Size((barW * 0.7f).coerceAtLeast(1f), h),
            )
        }
        if (posMs >= 0) {
            val x = posMs.toFloat() / durationMs * size.width
            drawLine(cursorColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
        }
    }
}

/** 시작/끝 지점을 0.1초·1초 단위로 미세 조정. */
@Composable
private fun Nudge(title: String, valueMs: Long, enabled: Boolean, onDelta: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$title ${ClipMath.formatMs(valueMs)}", modifier = Modifier.weight(1f))
        listOf(-1000L to "−1초", -100L to "−0.1", 100L to "+0.1", 1000L to "+1초").forEach { (delta, label) ->
            TextButton(onClick = { onDelta(delta) }, enabled = enabled) { Text(label) }
        }
    }
}

@Composable
private fun FadeRow(title: String, seconds: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f))
        TextButton(onClick = { onChange((seconds - 1).coerceAtLeast(0)) }, enabled = enabled) { Text("−") }
        Text(if (seconds == 0) "없음" else "${seconds}초", color = if (seconds == 0) Color.Gray else Color.Unspecified)
        TextButton(onClick = { onChange((seconds + 1).coerceAtMost(10)) }, enabled = enabled) { Text("+") }
    }
}
