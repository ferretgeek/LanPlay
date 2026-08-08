package com.lanplay.player.ui.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lanplay.player.data.MovieDisplay
import com.lanplay.player.data.db.WatchRecordEntity
import com.lanplay.player.ui.decodeArtwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBrowse: () -> Unit = {},
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val blurArtwork by viewModel.blurArtwork.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    var detailsExpanded by remember { mutableStateOf(false) }
    val historyListState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }
    LaunchedEffect(historyListState) {
        snapshotFlow {
            historyListState.firstVisibleItemIndex to
                historyListState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                if (index > 0 || offset > 16) detailsExpanded = false
            }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("观看历史") },
                actions = {
                    if (state.records.isNotEmpty()) {
                        TextButton(onClick = { confirmClear = true }) { Text("清空") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (state.selectedIds.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "已选择 ${state.selectedIds.size} 项",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        IconButton(onClick = viewModel::selectAllVisible) {
                            Icon(Icons.Rounded.SelectAll, "全选")
                        }
                        IconButton(onClick = viewModel::deleteSelected) {
                            Icon(
                                Icons.Rounded.DeleteOutline,
                                "删除所选历史",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Rounded.Close, "取消选择")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
        Column(Modifier.fillMaxHeight().widthIn(max = 1000.dp)) {
            if (state.records.isNotEmpty()) {
                HistoryCompactHeader(
                    count = state.visible.size,
                    filter = state.filter,
                    sort = state.sort,
                    expanded = detailsExpanded,
                    onToggle = { detailsExpanded = !detailsExpanded },
                )
            }
            AnimatedVisibility(
                visible = state.records.isNotEmpty() && detailsExpanded,
            ) {
                Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        HistoryFilter.ALL to "全部",
                        HistoryFilter.IN_PROGRESS to "继续观看",
                        HistoryFilter.COMPLETED to "已看完",
                        HistoryFilter.FAVORITES to "收藏",
                    ).forEach { (filter, label) ->
                        AssistChip(
                            onClick = { viewModel.setFilter(filter) },
                            label = { Text(label) },
                            leadingIcon = if (state.filter == filter) {
                                { Icon(Icons.Rounded.History, null, Modifier.size(16.dp)) }
                            } else null,
                        )
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        HistorySort.LAST_WATCHED to "最近观看",
                        HistorySort.PROGRESS to "进度",
                        HistorySort.PLAY_COUNT to "次数",
                        HistorySort.DURATION to "时长",
                        HistorySort.RATING to "评分",
                    ).forEach { (sort, label) ->
                        AssistChip(
                            onClick = { viewModel.setSort(sort) },
                            label = { Text(label) },
                            leadingIcon = if (state.sort == sort) {
                                { Icon(Icons.Rounded.History, null, Modifier.size(16.dp)) }
                            } else null,
                        )
                    }
                }
            }
            }
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.visible.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Rounded.History,
                            null,
                            Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        )
                        Text(
                            if (state.records.isEmpty()) "还没有观看记录" else "这个分类暂无内容",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 14.dp),
                        )
                        Text(
                            if (state.records.isEmpty()) {
                                "开始播放后，进度、次数和收藏会自动出现在这里"
                            } else {
                                "换一个分类，或查看全部历史"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        FilledTonalButton(
                            onClick = {
                                if (state.records.isEmpty()) onBrowse()
                                else viewModel.setFilter(HistoryFilter.ALL)
                            },
                            modifier = Modifier.padding(top = 16.dp),
                        ) {
                            Text(if (state.records.isEmpty()) "浏览媒体库" else "查看全部")
                        }
                    }
                }
                else -> LazyColumn(
                    state = historyListState,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.visible, key = { it.id }) { record ->
                        HistoryCard(
                            record = record,
                            movie = state.displays[record.fullPath],
                            blurArtwork = blurArtwork,
                            onPlay = { viewModel.play(record) },
                            onFavorite = { viewModel.toggleFavorite(record) },
                            onDelete = { viewModel.delete(record) },
                            selected = record.id in state.selectedIds,
                            selectionMode = state.selectedIds.isNotEmpty(),
                            onSelect = { viewModel.toggleSelection(record.id) },
                        )
                    }
                }
            }
        }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空全部观看历史？") },
            text = { Text("只删除观看记录，不会删除 SMB 里的视频、字幕或元数据。") },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
            confirmButton = {
                Button(onClick = {
                    confirmClear = false
                    viewModel.clearAll()
                }) { Text("清空历史") }
            },
        )
    }
}

@Composable
private fun HistoryCompactHeader(
    count: Int,
    filter: HistoryFilter,
    sort: HistorySort,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val filterLabel = when (filter) {
        HistoryFilter.ALL -> "全部记录"
        HistoryFilter.IN_PROGRESS -> "继续观看"
        HistoryFilter.COMPLETED -> "已看完"
        HistoryFilter.FAVORITES -> "收藏"
    }
    val sortLabel = when (sort) {
        HistorySort.LAST_WATCHED -> "最近观看"
        HistorySort.PROGRESS -> "按进度"
        HistorySort.PLAY_COUNT -> "按次数"
        HistorySort.DURATION -> "按时长"
        HistorySort.RATING -> "按评分"
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "$filterLabel · $count 条",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    sortLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = onToggle) {
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    if (expanded) "收起" else "筛选排序",
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun HistoryReportCard(report: HistoryReport) {
    if (
        report.topActor == null &&
        report.topStudio == null &&
        report.topTag == null &&
        report.months.isEmpty()
    ) return
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("观看统计", style = MaterialTheme.typography.titleMedium)
            report.topActor?.let { Text("最常看演员 · $it") }
            report.topStudio?.let { Text("最常看片商 · $it") }
            report.topTag?.let { Text("最常用标签 · $it") }
            if (report.months.isNotEmpty()) {
                Text(
                    "近 6 个月回顾",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                val max = report.months.maxOf { it.watchedMs }.coerceAtLeast(1L)
                report.months.forEach { month ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            month.month,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.size(width = 64.dp, height = 20.dp),
                        )
                        LinearProgressIndicator(
                            progress = { month.watchedMs.toFloat() / max },
                            modifier = Modifier.weight(1f).height(7.dp),
                        )
                        Text(
                            "${formatWatchTotal(month.watchedMs)} · ${month.movieCount} 部",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private fun formatWatchTotal(ms: Long): String {
    val totalMinutes = (ms / 60_000L).coerceAtLeast(0L)
    return when {
        totalMinutes >= 60 -> "${totalMinutes / 60} 小时"
        totalMinutes > 0 -> "$totalMinutes 分钟"
        else -> "不足 1 分钟"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryCard(
    record: WatchRecordEntity,
    movie: MovieDisplay?,
    blurArtwork: Boolean,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
    selected: Boolean,
    selectionMode: Boolean,
    onSelect: () -> Unit,
) {
    val poster by produceState<ImageBitmap?>(
        null,
        movie?.posterFile?.absolutePath,
        blurArtwork,
    ) {
        value = if (!blurArtwork) withContext(Dispatchers.IO) {
            movie?.posterFile?.takeIf { it.isFile }?.let {
                decodeArtwork(it.absolutePath, 200, 300)
            }
        } else null
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = { if (selectionMode) onSelect() else onPlay() },
            onLongClick = onSelect,
        ),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(width = 76.dp, height = 108.dp),
            ) {
                if (poster != null && !blurArtwork) {
                    Image(
                        poster!!,
                        contentDescription = movie?.title ?: record.fileName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(34.dp))
                    }
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    movie?.code ?: record.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                movie?.title?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Text(
                    "${formatDuration(record.positionMs)} / ${formatDuration(record.durationMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 7.dp),
                )
                LinearProgressIndicator(
                    progress = { record.progressPercent.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp).padding(top = 2.dp),
                )
                Text(
                    "上次观看 ${formatDate(record.lastWatchedAt)} · ${record.playCount} 次",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 7.dp),
                )
            }
            Column {
                IconButton(onClick = onFavorite) {
                    Icon(
                        if (record.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        contentDescription = if (record.isFavorite) "取消收藏" else "收藏",
                        tint = if (record.isFavorite) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "删除这条记录")
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val total = (ms / 1_000).coerceAtLeast(0)
    val hours = total / 3_600
    val minutes = total / 60 % 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}

private fun formatDate(ms: Long): String =
    if (ms <= 0L) "未知" else SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(Date(ms))
