package com.ticksync.clock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ticksync.clock.settings.SettingsScreen
import com.ticksync.clock.ui.ClockScreen
import com.ticksync.clock.ui.ClockViewModel
import com.ticksync.clock.ui.TimeSourceScreen
import com.ticksync.clock.ui.theme.TickSyncTheme
import com.ticksync.clock.util.PermissionUtils

/**
 * 主界面容器：底部三个 Tab（时钟 / 时间源 / 设置）。
 *
 * 权限状态无法自动感知，只能在用户从系统设置页返回时重新检测，
 * 因此这里监听生命周期 ON_RESUME 并递增 [permissionTick] 触发重算。
 */
class MainActivity : ComponentActivity() {

    /** 启动系统悬浮窗授权页；返回后由 ON_RESUME 触发权限重算 */
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* 结果在 onResume 统一处理，此处无需额外动作 */ }

    /** Android 13+ 通知权限运行时申请 */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 同上 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            TickSyncTheme {
                val viewModel: ClockViewModel = viewModel()
                val lifecycleOwner = LocalLifecycleOwner.current
                var permissionTick by remember { mutableIntStateOf(0) }

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            permissionTick++
                            viewModel.refreshNetworkState()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                MainScreen(
                    viewModel = viewModel,
                    permissionTick = permissionTick,
                    onGrantOverlayPermission = ::requestOverlayPermission,
                    onGrantNotificationPermission = ::requestNotificationPermission
                )
            }
        }
    }

    private fun requestOverlayPermission() {
        runCatching {
            overlayPermissionLauncher.launch(PermissionUtils.overlayPermissionIntent(this))
        }
    }

    private fun requestNotificationPermission() {
        if (!PermissionUtils.needsNotificationRequest()) return
        runCatching {
            notificationPermissionLauncher.launch(PermissionUtils.notificationPermission)
        }
    }
}

@Composable
private fun MainScreen(
    viewModel: ClockViewModel,
    permissionTick: Int,
    onGrantOverlayPermission: () -> Unit,
    onGrantNotificationPermission: () -> Unit
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_CLOCK) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == TAB_CLOCK,
                    onClick = { selectedTab = TAB_CLOCK },
                    icon = { Icon(Icons.Filled.AccessTime, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_clock)) }
                )
                NavigationBarItem(
                    selected = selectedTab == TAB_SOURCES,
                    onClick = { selectedTab = TAB_SOURCES },
                    icon = { Icon(Icons.Filled.Dns, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_sources)) }
                )
                NavigationBarItem(
                    selected = selectedTab == TAB_SETTINGS,
                    onClick = { selectedTab = TAB_SETTINGS },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_settings)) }
                )
            }
        }
    ) { innerPadding ->
        val contentModifier = Modifier.padding(innerPadding)
        when (selectedTab) {
            TAB_CLOCK -> ClockScreen(
                viewModel = viewModel,
                permissionTick = permissionTick,
                onGrantOverlayPermission = onGrantOverlayPermission,
                onGrantNotificationPermission = onGrantNotificationPermission,
                modifier = contentModifier
            )

            TAB_SOURCES -> TimeSourceScreen(
                viewModel = viewModel,
                modifier = contentModifier
            )

            else -> SettingsScreen(
                viewModel = viewModel,
                permissionTick = permissionTick,
                onGrantOverlayPermission = onGrantOverlayPermission,
                onGrantNotificationPermission = onGrantNotificationPermission,
                modifier = contentModifier
            )
        }
    }
}

private const val TAB_CLOCK = 0
private const val TAB_SOURCES = 1
private const val TAB_SETTINGS = 2
