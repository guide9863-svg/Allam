package com.allam.alarm.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allam.alarm.alarm.AlarmScheduler
import com.allam.alarm.core.Alarm
import com.allam.alarm.core.RepeatRule
import com.allam.alarm.data.AlarmRepository

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlarmRepository.init(this)
        enableEdgeToEdge()
        setContent {
            AllamTheme {
                AllamRoot()
            }
        }
    }
}

private enum class Tab(val title: String) { ALARMS("알람"), SHIFT("교대표"), SETTINGS("설정") }

@Composable
private fun AllamRoot() {
    val context = LocalContext.current
    val data by AlarmRepository.data.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.ALARMS) }
    var editing by remember { mutableStateOf<Alarm?>(null) }
    var permTick by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permTick++ }

    LaunchedEffect(Unit) {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACTIVITY_RECOGNITION)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permTick++
        // 권한이 바뀌었을 수 있으니 알람을 다시 등록
        AlarmScheduler.rescheduleAll(context)
    }

    val permissions = remember(permTick) { PermissionStatus.of(context) }

    val current = editing
    if (current != null) {
        BackHandler { editing = null }
        AlarmEditScreen(
            initial = current,
            schedule = data.schedule,
            onClose = { editing = null },
        )
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.ALARMS -> Icons.Filled.Notifications
                                    Tab.SHIFT -> Icons.Filled.DateRange
                                    Tab.SETTINGS -> Icons.Filled.Settings
                                },
                                contentDescription = t.title,
                            )
                        },
                        label = { Text(t.title) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            Tab.ALARMS -> AlarmListScreen(
                data = data,
                permissions = permissions,
                modifier = modifier,
                onEdit = { editing = it },
                onAdd = {
                    editing = Alarm(
                        id = 0,
                        hour = 7,
                        minute = 0,
                        repeat = if (data.schedule.isConfigured) {
                            RepeatRule.Shift(data.schedule.cycle.firstOrNull()?.let { setOf(it) } ?: emptySet())
                        } else RepeatRule.Once,
                    )
                },
                onOpenSettings = { tab = Tab.SETTINGS },
                onOpenShift = { tab = Tab.SHIFT },
            )
            Tab.SHIFT -> ShiftScreen(data = data, modifier = modifier)
            Tab.SETTINGS -> SettingsScreen(
                data = data,
                permissions = permissions,
                modifier = modifier,
                onRequestRuntimePermissions = { perms -> permissionLauncher.launch(perms.toTypedArray()) },
            )
        }
    }
}
