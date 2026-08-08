package com.lanplay.player.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Switch
import androidx.compose.material3.AssistChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lanplay.player.data.db.WatchState
import java.text.DateFormat
import java.util.Date

enum class ToolsMode { CLEANUP, TRASH }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryToolsScreen(
    mode: ToolsMode,
    onBrowse: () -> Unit = {},
    viewModel: LibraryToolsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error, state.notice) {
        val text = state.error ?: state.notice
        if (text != null) {
            snackbar.showSnackbar(text)
            viewModel.clearMessage()
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (mode == ToolsMode.CLEANUP) "批量清理" else "回收站") }
            )
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(Modifier.fillMaxHeight().widthIn(max = 1000.dp)) {
                if (mode == ToolsMode.CLEANUP) CleanupContent(state, viewModel)
                else TrashContent(state, viewModel, onBrowse)
            }
        }
    }
}

@Composable
private fun CleanupContent(state: LibraryToolsViewModel.UiState, viewModel: LibraryToolsViewModel) {
    var confirm by remember { mutableStateOf(false) }
    if (state.preview.isEmpty() && !state.running) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            // 横屏手机高度有限，保护条件与三个入口必须仍可完整到达。
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Icon(
                        Icons.Rounded.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "先预览，再清理",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                    Text(
                        "这里不会直接删除共享文件。你会先看到完整清单并可逐项取消；" +
                            "视频和字幕会移入回收站，但对应观看进度、收藏、标签与书签会立即从本机删除且不随文件还原。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 7.dp),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("保护收藏", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "默认不把已收藏视频列入清理",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.excludeFavorite,
                    onCheckedChange = viewModel::setExcludeFavorite,
                )
            }
            Text("只清理多久以前看过的", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0 to "不限", 7 to "7 天", 30 to "30 天", 90 to "90 天").forEach {
                    AssistChip(
                        onClick = { viewModel.setOlderThanDays(it.first) },
                        label = { Text(it.second) },
                        leadingIcon = if (state.olderThanDays == it.first) {
                            { Icon(Icons.Rounded.CheckCircle, null, Modifier.size(16.dp)) }
                        } else null,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("仅默认媒体目录", style = MaterialTheme.typography.titleSmall)
                    Text(
                        state.server?.defaultPath?.ifBlank { "共享根目录" } ?: "共享根目录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.currentDirectoryOnly,
                    onCheckedChange = viewModel::setCurrentDirectoryOnly,
                )
            }
            FilledTonalButton(
                onClick = { viewModel.preview(setOf(WatchState.COMPLETED)) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("预览已看完") }
            FilledTonalButton(
                onClick = { viewModel.preview(setOf(WatchState.IN_PROGRESS)) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("预览看了一半") }
            Button(
                onClick = {
                    viewModel.preview(setOf(WatchState.COMPLETED, WatchState.IN_PROGRESS))
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("预览两者") }
            if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            if (!state.loading && state.previewTitle.isNotEmpty()) {
                EmptyCleanup()
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        if (state.running) {
            val progress = if (state.total == 0) 0f else state.completed / state.total.toFloat()
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text(
                buildString {
                    append("正在处理 ${state.completed}/${state.total}")
                    state.currentFileName?.let { append("\n$it") }
                },
                modifier = Modifier.padding(16.dp),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${state.previewTitle} · ${state.selectedCount} 项")
                    Text(
                        formatSize(state.selectedBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { viewModel.selectAll(state.selectedCount != state.preview.size) }) {
                    Text(if (state.selectedCount == state.preview.size) "取消全选" else "全选")
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.preview, key = { it.record.id }) { item ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = item.selected,
                            onCheckedChange = { viewModel.toggle(item.record.id) },
                            enabled = !state.running,
                        )
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(
                                item.record.fileName,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                "${formatSize(item.record.fileSize)} · " +
                                    "${(item.record.progressPercent * 100).toInt()}% · " +
                                    DateFormat.getDateTimeInstance().format(Date(item.record.lastWatchedAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            item.failureReason?.let { reason ->
                                Text(
                                    reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        if (!state.running) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(onClick = viewModel::clearPreview, modifier = Modifier.weight(1f)) {
                    Text("放弃")
                }
                Button(
                    onClick = { confirm = true },
                    enabled = state.selectedCount > 0,
                    modifier = Modifier.weight(2f),
                ) {
                    Text(
                        if (state.preview.any { it.failureReason != null }) {
                            "重试失败项 · ${state.selectedCount} 项"
                        } else {
                            "移入回收站 · ${state.selectedCount} 项"
                        }
                    )
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("确认移入回收站？") },
            text = {
                Text(
                    "将 ${state.selectedCount} 个视频（${formatSize(state.selectedBytes)}）移入回收站。" +
                        "视频和字幕可还原；对应个人观看数据会立即删除且不会随文件还原。"
                )
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
            confirmButton = {
                Button(onClick = {
                    confirm = false
                    viewModel.executeCleanup()
                }) { Text("移入回收站") }
            },
        )
    }
}

@Composable
private fun TrashContent(
    state: LibraryToolsViewModel.UiState,
    viewModel: LibraryToolsViewModel,
    onBrowse: () -> Unit,
) {
    if (state.trashGroups.isEmpty()) {
        Box(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                )
                Text(
                    "回收站是空的",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp),
                    textAlign = TextAlign.Center,
                )
                Text(
                    "删除的视频和字幕会先放在这里；个人观看数据不会随文件还原",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    textAlign = TextAlign.Center,
                )
                FilledTonalButton(
                    onClick = onBrowse,
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Text("浏览媒体库")
                }
            }
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        items(state.trashGroups, key = { it.groupId }) { group ->
            var deleteConfirm by remember { mutableStateOf(false) }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            group.video.fileName,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "${formatSize(group.totalSize)} · ${group.items.size} 个关联文件\n" +
                                "删除时间：${
                                    DateFormat.getDateTimeInstance().format(Date(group.video.deletedAt))
                                }\n原位置：/${group.video.originalPath}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 5.dp),
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { viewModel.requestRestore(group.groupId) }) {
                        Icon(Icons.Rounded.Restore, "还原")
                    }
                    IconButton(onClick = { deleteConfirm = true }) {
                        Icon(Icons.Rounded.DeleteForever, "彻底删除", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (deleteConfirm) {
                AlertDialog(
                    onDismissRequest = { deleteConfirm = false },
                    icon = {
                        Icon(
                            Icons.Rounded.DeleteForever,
                            null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    title = { Text("彻底删除？") },
                    text = { Text("${group.video.fileName}\n\n此操作不可恢复。") },
                    dismissButton = {
                        TextButton(onClick = { deleteConfirm = false }) { Text("取消") }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                deleteConfirm = false
                                viewModel.permanentlyDelete(group.groupId)
                            },
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                            ),
                        ) { Text("彻底删除") }
                    },
                )
            }
        }
    }
    state.restoreConflictGroupId?.let { groupId ->
        val group = state.trashGroups.firstOrNull { it.groupId == groupId }
        AlertDialog(
            onDismissRequest = viewModel::cancelRestoreConflict,
            title = { Text("原位置已有同名文件") },
            text = {
                Text(
                    "“${group?.video?.fileName ?: "这个视频"}”无法直接覆盖。可以保留现有文件，并把回收站内容自动改名为“（已还原）”后再放回原目录。"
                )
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRestoreConflict) { Text("取消") }
            },
            confirmButton = {
                Button(onClick = viewModel::restoreRenamed) { Text("改名后还原") }
            },
        )
    }
}

@Composable
private fun EmptyCleanup() {
    Column(
        Modifier.fillMaxWidth().padding(top = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("没有符合条件的视频", style = MaterialTheme.typography.titleMedium)
        Text(
            "换一个清理类型试试",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.0f KB".format(bytes / 1024.0)
}
