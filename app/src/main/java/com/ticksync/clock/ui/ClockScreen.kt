package com.ticksync.clock.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ticksync.clock.R
import com.ticksync.clock.settings.AppSettings
import com.ticksync.clock.time.ClockEngine
import com.ticksync.clock.time.CountdownConfig
import com.ticksync.clock.time.SyncQuality
import com.ticksync.clock.time.SyncState
import com.ticksync.clock.time.TimeFormat
import com.ticksync.clock.ui.theme.StatusFailed
import com.ticksync.clock.ui.theme.StatusOk
import com.ticksync.clock.ui.theme.StatusStale
import com.ticksync.clock.util.PermissionUtils
import com.ticksync.clock.util.RomType
import com.ticksync.clock.util.RomUtils

/**
 * 时钟页：应用的主视图。
 *
 * 视觉焦点是超大等宽时间；紧跟其后的倒计时卡片回答"还有多久"；
 * 状态卡片回答"这个时间可信吗"；底部是仅有的两个动作——开启悬浮窗、立即校准。
 *
 * @param permissionTick 权限状态刷新信号，Activity onResume 时递增以重算权限
 */
@Composable
fun ClockScreen(
    viewModel: ClockViewModel,
    permissionTick: Int,
    onGrantOverlayPermission: () -> Unit,
    onGrantNotificationPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val quality by viewModel.quality.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val overlayRunning by viewModel.overlayRunning.collectAsStateWithLifecycle()

    var showCountdownDialog by remember { mutableStateOf(false) }

    val hasOverlayPermission = remember(permissionTick) {
        PermissionUtils.hasOverlayPermission(context)
    }
    val hasNotificationPermission = remember(permissionTick) {
        PermissionUtils.hasNotificationPermission(context)
    }
    val romType = remember { RomUtils.romType() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LiveClock(viewModel)
        Spacer(Modifier.height(16.dp))

        CountdownCard(
            viewModel = viewModel,
            onEdit = { showCountdownDialog = true },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        StatusCard(
            viewModel = viewModel,
            syncState = syncState,
            quality = quality,
            modifier = Modifier.fillMaxWidth()
        )

        if (!hasOverlayPermission) {
            Spacer(Modifier.height(16.dp))
            PermissionGuideCard(
                title = stringResource(R.string.settings_overlay_permission),
                body = stringResource(R.string.overlay_permission_desc),
                actionText = stringResource(R.string.settings_permission_go),
                onAction = onGrantOverlayPermission
            )
        } else if (!hasNotificationPermission) {
            Spacer(Modifier.height(16.dp))
            PermissionGuideCard(
                title = stringResource(R.string.settings_notification_permission),
                body = stringResource(R.string.rom_guide_battery),
                actionText = stringResource(R.string.settings_permission_go),
                onAction = onGrantNotificationPermission
            )
        }

        if (RomUtils.needsExtraGuide(romType)) {
            Spacer(Modifier.height(16.dp))
            RomGuideCard(romType)
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { if (overlayRunning) viewModel.stopOverlay() else viewModel.startOverlay() },
            enabled = hasOverlayPermission,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Icon(Icons.Filled.PictureInPicture, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(
                    if (overlayRunning) R.string.clock_action_close_overlay
                    else R.string.clock_action_open_overlay
                )
            )
        }

        Spacer(Modifier.height(10.dp))

        OutlinedButton(
            onClick = { viewModel.syncNow() },
            enabled = !isSyncing,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            if (isSyncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.clock_action_syncing))
            } else {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.clock_action_sync_now))
            }
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = stringResource(R.string.clock_footer_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }

    if (showCountdownDialog) {
        CountdownDialog(
            config = settings.countdown,
            onDismiss = { showCountdownDialog = false },
            onConfirm = { config ->
                viewModel.setCountdown(config)
                showCountdownDialog = false
            }
        )
    }
}

/**
 * 超大时间显示。
 *
 * 单独抽成 Composable，使 30fps 的重组范围限制在这里，
 * 不会引起整页重组。字号按可用宽度自适应，兼顾手机与平板。
 */
@Composable
private fun LiveClock(viewModel: ClockViewModel) {
    val nowMs by viewModel.nowMs.collectAsStateWithLifecycle()
    val fontSize = rememberAdaptiveClockSize()

    Text(
        text = TimeFormat.hmsS(nowMs),
        fontSize = fontSize,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * 按可用宽度计算时钟字号。
 *
 * 等宽字体字符宽度约为字号的 0.62 倍，据此反推满宽所需的字号，
 * 并夹在合理区间内，使同一套代码在手机与平板上都不溢出、不显小。
 */
@Composable
private fun rememberAdaptiveClockSize(): TextUnit {
    val configuration = LocalConfiguration.current
    val fontScale = LocalDensity.current.fontScale
    val availableWidthDp = configuration.screenWidthDp - 40 // 扣除左右各 20dp 内边距
    val rawSp = availableWidthDp / (CLOCK_CHAR_COUNT * MONO_CHAR_WIDTH_RATIO) / fontScale
    return rawSp.coerceIn(MIN_CLOCK_SP, MAX_CLOCK_SP).sp
}

/**
 * 倒计时卡片。
 *
 * 抢票时用户看的是"还差多久"，所以它紧贴时间显示，字号仅次于时钟本身；
 * 进入最后一分钟、最后十秒时颜色递进，余光扫一眼就能感知紧迫程度。
 */
@Composable
private fun CountdownCard(
    viewModel: ClockViewModel,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val remainMs by viewModel.countdownRemainMs.collectAsStateWithLifecycle()
    val config = settings.countdown
    val active = config.enabled && remainMs >= 0L

    Card(
        modifier = modifier.clickable(onClick = onEdit),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Timer,
                    contentDescription = null,
                    tint = if (active) countdownColor(remainMs) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = config.label.ifBlank { stringResource(R.string.countdown_title) },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (config.enabled) {
                        stringResource(R.string.countdown_target_at, config.timeText)
                    } else {
                        stringResource(R.string.countdown_not_set)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (active) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = TimeFormat.countdown(remainMs),
                    fontSize = COUNTDOWN_FONT_SP,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = countdownColor(remainMs),
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.countdown_tap_to_set),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 倒计时配色：最后一分钟转橙，最后十秒转红。
 *
 * 用颜色而不是文字提醒，是因为归零前用户的目光集中在抢票按钮上，
 * 余光能感知到的只有颜色变化。
 */
@Composable
private fun countdownColor(remainMs: Long): Color = when {
    remainMs <= COUNTDOWN_CRITICAL_MS -> StatusFailed
    remainMs <= COUNTDOWN_SOON_MS -> StatusStale
    else -> MaterialTheme.colorScheme.onSurface
}

/**
 * 倒计时设置对话框。
 *
 * 目标时刻用三个数字输入框而不是系统时间选择器：在这里用户是"抄"一个已知的开抢时间
 * （页面或海报上写死的 10:00:00），逐段输入比在一个偌大的表盘上拨动更快更准。
 */
@Composable
private fun CountdownDialog(
    config: CountdownConfig,
    onDismiss: () -> Unit,
    onConfirm: (CountdownConfig) -> Unit
) {
    var label by remember { mutableStateOf(config.label) }
    var hour by remember { mutableStateOf(config.hour.toString().padStart(2, '0')) }
    var minute by remember { mutableStateOf(config.minute.toString().padStart(2, '0')) }
    var second by remember { mutableStateOf(config.second.toString().padStart(2, '0')) }

    val valid = isValidHour(hour) && isValidMinuteOrSecond(minute) && isValidMinuteOrSecond(second)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.countdown_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.countdown_label)) },
                    placeholder = { Text(stringResource(R.string.countdown_label_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.countdown_time_label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeField(hour, { hour = it }, stringResource(R.string.countdown_unit_hour), Modifier.weight(1f))
                    TimeField(minute, { minute = it }, stringResource(R.string.countdown_unit_minute), Modifier.weight(1f))
                    TimeField(second, { second = it }, stringResource(R.string.countdown_unit_second), Modifier.weight(1f))
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.countdown_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        CountdownConfig(
                            // 点确定即视为启用，省掉一个"再打开开关"的步骤
                            enabled = true,
                            label = label.trim(),
                            hour = hour.toIntOrNull() ?: 0,
                            minute = minute.toIntOrNull() ?: 0,
                            second = second.toIntOrNull() ?: 0
                        )
                    )
                },
                enabled = valid
            ) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            Row {
                if (config.enabled) {
                    TextButton(onClick = { onConfirm(config.copy(enabled = false)) }) {
                        Text(stringResource(R.string.countdown_disable))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    )
}

/** 两段式数字输入：只接受数字并限长两位，避免用户输入越界值后才看到报错 */
@Composable
private fun TimeField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onValueChange(input.filter { it.isDigit() }.take(2)) },
        singleLine = true,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

private fun isValidHour(value: String): Boolean = value.toIntOrNull()?.let { it in 0..23 } == true

private fun isValidMinuteOrSecond(value: String): Boolean = value.toIntOrNull()?.let { it in 0..59 } == true

/** 校准状态卡片：回答"当前时间可信吗" */
@Composable
private fun StatusCard(
    viewModel: ClockViewModel,
    syncState: SyncState,
    quality: SyncQuality,
    modifier: Modifier = Modifier
) {
    val statusColor = quality.color()
    val statusText = when (quality) {
        SyncQuality.TRUSTED -> stringResource(R.string.clock_status_calibrated)
        SyncQuality.STALE -> stringResource(R.string.clock_status_stale)
        SyncQuality.OFFLINE -> stringResource(R.string.clock_status_offline)
        SyncQuality.UNSYNCED -> stringResource(R.string.clock_status_failed)
        SyncQuality.DEVICE -> stringResource(R.string.clock_status_device)
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Bolt,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = statusText,
                    color = statusColor,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(12.dp))

            StatusRow(stringResource(R.string.clock_label_offset)) { LiveOffsetText(viewModel) }
            StatusRow(stringResource(R.string.clock_label_source)) {
                StatusValue(syncState.serverName.ifEmpty { stringResource(R.string.clock_value_unknown) })
            }
            StatusRow(stringResource(R.string.clock_label_rtt)) {
                StatusValue(
                    if (syncState.rttMs > 0L) "${syncState.rttMs}ms"
                    else stringResource(R.string.clock_value_unknown)
                )
            }
            StatusRow(stringResource(R.string.clock_label_network)) { LiveNetworkSpeedText(viewModel) }
            if (syncState.precisionMs > 1L) {
                StatusRow(stringResource(R.string.clock_label_precision)) {
                    StatusValue(stringResource(R.string.clock_value_second_level))
                }
            }
            StatusRow(stringResource(R.string.clock_label_last_sync)) {
                LiveLastSyncText(viewModel, syncState)
            }
        }
    }
}

/** 实时网速。1 秒一次，重组同样隔离在这个小节点内。 */
@Composable
private fun LiveNetworkSpeedText(viewModel: ClockViewModel) {
    val speed by viewModel.networkSpeed.collectAsStateWithLifecycle()
    StatusValue(stringResource(R.string.network_speed_format, speed.downKBps, speed.upKBps))
}

/** 实时偏差。按秒刷新，把重组隔离在这个小节点内。 */
@Composable
private fun LiveOffsetText(viewModel: ClockViewModel) {
    val nowMs by viewModel.nowMs.collectAsStateWithLifecycle()
    val second = nowMs / 1000
    val offset = remember(second) { ClockEngine.currentOffsetMs() }
    val calibrated = remember(second) { ClockEngine.isCalibrated }

    StatusValue(
        if (calibrated) TimeFormat.offsetText(offset)
        else stringResource(R.string.clock_value_unknown)
    )
}

/** 「上次同步」相对时间，按秒刷新 */
@Composable
private fun LiveLastSyncText(viewModel: ClockViewModel, syncState: SyncState) {
    val nowMs by viewModel.nowMs.collectAsStateWithLifecycle()
    val text = remember(nowMs / 1000, syncState.syncTimeMs) {
        TimeFormat.agoText(ClockEngine.nowMs(), syncState.syncTimeMs)
    }
    StatusValue(text)
}

@Composable
private fun StatusRow(label: String, value: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        value()
    }
}

@Composable
private fun StatusValue(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun PermissionGuideCard(
    title: String,
    body: String,
    actionText: String,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = StatusStale
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onAction) { Text(actionText) }
        }
    }
}

/** 国产 ROM 额外权限引导：悬浮窗权限之外还常被「后台弹出界面」等开关拦住 */
@Composable
private fun RomGuideCard(romType: RomType) {
    val body = when (romType) {
        RomType.MIUI -> stringResource(R.string.rom_guide_miui)
        RomType.EMUI -> stringResource(R.string.rom_guide_huawei)
        RomType.COLOR_OS -> stringResource(R.string.rom_guide_oppo)
        RomType.ORIGIN_OS -> stringResource(R.string.rom_guide_vivo)
        else -> ""
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.rom_guide_title, RomUtils.displayName(romType)),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.rom_guide_battery),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun SyncQuality.color(): Color = when (this) {
    SyncQuality.TRUSTED -> StatusOk
    SyncQuality.STALE -> StatusStale
    SyncQuality.OFFLINE -> StatusStale
    SyncQuality.UNSYNCED -> StatusFailed
    SyncQuality.DEVICE -> StatusStale
}

/** "00:00:00.0" 共 10 个字符 */
private const val CLOCK_CHAR_COUNT = 10

/** 等宽字体字符宽 / 字号 的近似比值 */
private const val MONO_CHAR_WIDTH_RATIO = 0.62f

private const val MIN_CLOCK_SP = 24f
private const val MAX_CLOCK_SP = 64f

/** 倒计时字号：略小于时钟，但仍需在余光中可读 */
private val COUNTDOWN_FONT_SP = 38.sp

private const val COUNTDOWN_SOON_MS = 60_000L
private const val COUNTDOWN_CRITICAL_MS = 10_000L
