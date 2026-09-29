package com.ticksync.clock.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ticksync.clock.BuildConfig
import com.ticksync.clock.R
import com.ticksync.clock.ui.ClockViewModel
import com.ticksync.clock.ui.theme.StatusFailed
import com.ticksync.clock.ui.theme.StatusOk
import com.ticksync.clock.util.PermissionUtils
import kotlin.math.roundToInt

/**
 * 设置页。
 *
 * 分三组：悬浮窗、权限状态、关于。
 * 权限状态组直接展示当前授予情况并提供跳转入口——这类特殊权限无法自动申请，
 * 只能把用户送到系统设置页，因此入口必须显眼且状态实时。
 *
 * 时间源已独立成页（[com.ticksync.clock.ui.TimeSourceScreen]）：
 * 抢票时要频繁开关平台源，混在这个长列表里操作成本太高。
 */
@Composable
fun SettingsScreen(
    viewModel: ClockViewModel,
    permissionTick: Int,
    onGrantOverlayPermission: () -> Unit,
    onGrantNotificationPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    val hasOverlayPermission = remember(permissionTick) {
        PermissionUtils.hasOverlayPermission(context)
    }
    val hasNotificationPermission = remember(permissionTick) {
        PermissionUtils.hasNotificationPermission(context)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionTitle(stringResource(R.string.settings_section_overlay))
        SettingsCard {
            SettingLabel(stringResource(R.string.settings_overlay_size))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OverlaySize.entries.forEach { size ->
                    FilterChip(
                        selected = settings.overlaySize == size,
                        onClick = { viewModel.setOverlaySize(size) },
                        label = { Text(size.shortLabel()) }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = settings.overlaySize.fullLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(14.dp))
            AlphaSlider(
                alpha = settings.overlayAlpha,
                onCommit = viewModel::setOverlayAlpha
            )

            Spacer(Modifier.height(8.dp))
            SwitchRow(
                title = stringResource(R.string.settings_show_millis),
                checked = settings.showMillis,
                onCheckedChange = viewModel::setShowMillis
            )

            SwitchRow(
                title = stringResource(R.string.settings_passthrough),
                subtitle = stringResource(R.string.settings_passthrough_desc) + " · " +
                    stringResource(R.string.settings_passthrough_warning),
                checked = settings.passthroughMode,
                onCheckedChange = viewModel::setPassthroughMode
            )

            SwitchRow(
                title = stringResource(R.string.settings_restore_on_boot),
                checked = settings.restoreOnBoot,
                onCheckedChange = viewModel::setRestoreOnBoot
            )

            Spacer(Modifier.height(10.dp))
            SettingLabel(stringResource(R.string.settings_auto_sync_interval))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppSettings.SYNC_INTERVAL_OPTIONS.forEach { minutes ->
                    FilterChip(
                        selected = settings.autoSyncIntervalMin == minutes,
                        onClick = { viewModel.setAutoSyncInterval(minutes) },
                        label = { Text(stringResource(R.string.interval_minutes, minutes)) }
                    )
                }
            }
        }

        SectionTitle(stringResource(R.string.settings_section_permission))
        SettingsCard {
            PermissionRow(
                title = stringResource(R.string.settings_overlay_permission),
                granted = hasOverlayPermission,
                onGrant = onGrantOverlayPermission
            )
            Spacer(Modifier.height(10.dp))
            PermissionRow(
                title = stringResource(R.string.settings_notification_permission),
                granted = hasNotificationPermission,
                onGrant = onGrantNotificationPermission
            )
        }

        SectionTitle(stringResource(R.string.settings_section_about))
        SettingsCard {
            Text(
                text = stringResource(R.string.about_principle_title),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.about_principle_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.settings_version),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 透明度滑块。
 *
 * 拖动过程只更新本地状态，松手才落盘——DataStore 写入是 IO 操作，
 * 逐帧写入既浪费又会导致滑动卡顿。
 */
@Composable
private fun AlphaSlider(alpha: Float, onCommit: (Float) -> Unit) {
    var localAlpha by remember(alpha) { mutableFloatStateOf(alpha) }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SettingLabel(stringResource(R.string.settings_overlay_alpha))
            Text(
                text = "${(localAlpha * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Slider(
            value = localAlpha,
            onValueChange = { localAlpha = it },
            onValueChangeFinished = { onCommit(localAlpha) },
            valueRange = SettingsRepository.MIN_ALPHA..1f,
            steps = 7
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PermissionRow(title: String, granted: Boolean, onGrant: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = stringResource(
                if (granted) R.string.settings_permission_granted
                else R.string.settings_permission_denied
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = if (granted) StatusOk else StatusFailed
        )
        if (!granted) {
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onGrant) {
                Text(stringResource(R.string.settings_permission_go))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
    )
}

@Composable
private fun SettingLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun OverlaySize.shortLabel(): String = when (this) {
    OverlaySize.SMALL -> stringResource(R.string.size_short_small)
    OverlaySize.MEDIUM -> stringResource(R.string.size_short_medium)
    OverlaySize.LARGE -> stringResource(R.string.size_short_large)
}

@Composable
private fun OverlaySize.fullLabel(): String = when (this) {
    OverlaySize.SMALL -> stringResource(R.string.size_small)
    OverlaySize.MEDIUM -> stringResource(R.string.size_medium)
    OverlaySize.LARGE -> stringResource(R.string.size_large)
}
