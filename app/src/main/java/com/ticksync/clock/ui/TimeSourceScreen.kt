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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ticksync.clock.R
import com.ticksync.clock.time.TimeSource
import com.ticksync.clock.time.TimeSourceType

/**
 * 时间源页。
 *
 * 独立成页而不是塞进设置页：抢票时最常做的动作就是"临时开一个平台源、把别的关掉"，
 * 这个页面需要在两次点击内完成，混在设置的长列表里做不到。
 *
 * 列表同时呈现"已启用/已停用"的全部源，而不是只列启用的：
 * 预置的平台源默认停用，必须让用户看得见它们的存在，否则等于没提供。
 */
@Composable
fun TimeSourceScreen(
    viewModel: ClockViewModel,
    modifier: Modifier = Modifier
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val sources = settings.timeSources

    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TimeSource?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.time_source_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            sources.forEachIndexed { index, source ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                TimeSourceRow(
                    source = source,
                    onToggle = { enabled -> viewModel.setTimeSourceEnabled(source.id, enabled) },
                    onEdit = { if (!source.isDevice) editing = source },
                    onDelete = { viewModel.removeTimeSource(source.id) }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = { adding = true }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.time_source_add))
            }
            TextButton(onClick = viewModel::resetTimeSources) {
                Text(stringResource(R.string.time_source_reset))
            }
        }

        Text(
            text = stringResource(R.string.time_source_footer),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(12.dp))
    }

    if (adding) {
        TimeSourceDialog(
            initial = null,
            onDismiss = { adding = false },
            onConfirm = { source ->
                viewModel.addTimeSource(source)
                adding = false
            }
        )
    }

    editing?.let { target ->
        TimeSourceDialog(
            initial = target,
            onDismiss = { editing = null },
            onConfirm = { source ->
                viewModel.replaceTimeSource(target.id, source)
                editing = null
            }
        )
    }
}

/**
 * 单个时间源行。
 *
 * 点击名称区域进入编辑（预置源可改名改地址）；删除只对用户自建源开放，
 * 预置源删掉后无从恢复，需要时用「恢复默认」整体重置即可。
 */
@Composable
private fun TimeSourceRow(
    source: TimeSource,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = source.type.icon(),
            contentDescription = null,
            tint = if (source.enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(enabled = !source.isDevice, onClick = onEdit)
        ) {
            Text(
                text = source.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val detail = if (source.isDevice) {
                stringResource(R.string.time_source_device_detail)
            } else {
                source.endpoint
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (!source.builtin) {
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Switch(checked = source.enabled, onCheckedChange = onToggle)
    }
}

/**
 * 新增 / 编辑时间源对话框。
 *
 * @param initial 为 null 表示新增
 */
@Composable
private fun TimeSourceDialog(
    initial: TimeSource?,
    onDismiss: () -> Unit,
    onConfirm: (TimeSource) -> Unit
) {
    var type by remember { mutableStateOf(initial?.type ?: TimeSourceType.NTP) }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var endpoint by remember { mutableStateOf(initial?.endpoint.orEmpty()) }

    val endpointValid = remember(type, endpoint) { isEndpointValid(type, endpoint) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial == null) R.string.time_source_add else R.string.time_source_edit
                )
            )
        },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = type == TimeSourceType.NTP,
                        onClick = { type = TimeSourceType.NTP },
                        label = { Text(stringResource(R.string.time_source_type_ntp)) }
                    )
                    FilterChip(
                        selected = type == TimeSourceType.HTTP,
                        onClick = { type = TimeSourceType.HTTP },
                        label = { Text(stringResource(R.string.time_source_type_http)) }
                    )
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.time_source_name_label)) },
                    placeholder = { Text(stringResource(R.string.time_source_name_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.time_source_endpoint_label)) },
                    placeholder = {
                        Text(
                            stringResource(
                                if (type == TimeSourceType.NTP) R.string.time_source_endpoint_hint_ntp
                                else R.string.time_source_endpoint_hint_http
                            )
                        )
                    },
                    isError = endpoint.isNotEmpty() && !endpointValid,
                    supportingText = if (endpoint.isNotEmpty() && !endpointValid) {
                        { Text(stringResource(R.string.time_source_endpoint_invalid)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        TimeSource(
                            type = type,
                            name = name.trim(),
                            endpoint = endpoint.trim(),
                            enabled = initial?.enabled ?: true,
                            builtin = initial?.builtin ?: false
                        )
                    )
                },
                enabled = name.isNotBlank() && endpointValid
            ) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * 地址合法性校验。
 *
 * 只做能立刻判断的形态检查，不试探网络——抢票时手速比"能不能连通"更重要，
 * 真正不可达的源会在校准结果里如实表现为失败。
 */
private fun isEndpointValid(type: TimeSourceType, endpoint: String): Boolean {
    val trimmed = endpoint.trim()
    if (trimmed.isEmpty()) return false
    return when (type) {
        TimeSourceType.NTP -> !trimmed.contains('/') && !trimmed.contains(' ')
        TimeSourceType.HTTP -> trimmed.startsWith("https://") || trimmed.startsWith("http://")
        TimeSourceType.DEVICE -> true
    }
}

private fun TimeSourceType.icon(): ImageVector = when (this) {
    TimeSourceType.NTP -> Icons.Filled.Dns
    TimeSourceType.HTTP -> Icons.Filled.ShoppingCart
    TimeSourceType.DEVICE -> Icons.Filled.PhoneAndroid
}
