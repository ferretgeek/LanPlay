package com.lanplay.player.ui.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanplay.player.data.SavedServer
import com.lanplay.player.data.ServerRepository
import com.lanplay.player.data.TrashRepository
import com.lanplay.player.data.db.TrashItemEntity
import com.lanplay.player.data.db.WatchRecordDao
import com.lanplay.player.data.db.WatchRecordEntity
import com.lanplay.player.data.db.WatchState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TrashGroup(
    val groupId: String,
    val items: List<TrashItemEntity>,
) {
    val video: TrashItemEntity get() = items.firstOrNull { it.itemType.name == "VIDEO" } ?: items.first()
    val totalSize: Long get() = items.sumOf { it.fileSize }
}

data class CleanupItem(
    val record: WatchRecordEntity,
    val selected: Boolean = true,
    val failureReason: String? = null,
)

@HiltViewModel
class LibraryToolsViewModel @Inject constructor(
    private val servers: ServerRepository,
    private val trashRepository: TrashRepository,
    private val watchDao: WatchRecordDao,
) : ViewModel() {
    data class UiState(
        val server: SavedServer? = null,
        val trashGroups: List<TrashGroup> = emptyList(),
        val preview: List<CleanupItem> = emptyList(),
        val previewTitle: String = "",
        val loading: Boolean = false,
        val running: Boolean = false,
        val completed: Int = 0,
        val total: Int = 0,
        val currentFileName: String? = null,
        val error: String? = null,
        val notice: String? = null,
        val restoreConflictGroupId: String? = null,
        val excludeFavorite: Boolean = true,
        val olderThanDays: Int = 0,
        val currentDirectoryOnly: Boolean = false,
    ) {
        val selectedCount: Int get() = preview.count { it.selected }
        val selectedBytes: Long get() = preview.filter { it.selected }.sumOf { it.record.fileSize }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var previewJob: Job? = null

    init {
        viewModelScope.launch {
            combine(servers.observeCurrent(), trashRepository.observeAll()) { server, items ->
                server to items
            }.collect { (server, items) ->
                val serverChanged = _state.value.server?.id != server?.id
                if (serverChanged) previewJob?.cancel()
                _state.value = _state.value.copy(
                    server = server,
                    loading = if (serverChanged) false else _state.value.loading,
                    preview = if (serverChanged) emptyList() else _state.value.preview,
                    trashGroups = items.groupBy { it.groupId }
                        .map { TrashGroup(it.key, it.value) }
                        .sortedByDescending { it.video.deletedAt },
                )
            }
        }
    }

    fun preview(states: Set<WatchState>) {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            val snapshot = _state.value
            val server = snapshot.server ?: return@launch
            val excludeFavorite = snapshot.excludeFavorite
            val olderThanDays = snapshot.olderThanDays
            val currentDirectoryOnly = snapshot.currentDirectoryOnly
            _state.value = snapshot.copy(loading = true, error = null)
            runCatching {
                watchDao.listByStates(
                    serverId = server.id,
                    states = states.toList(),
                    excludeFavorite = excludeFavorite,
                    beforeMs = if (olderThanDays <= 0) 0 else {
                        System.currentTimeMillis() - olderThanDays * 24L * 60L * 60L * 1_000L
                    },
                ).filter {
                    !currentDirectoryOnly ||
                        server.defaultPath.isBlank() ||
                        it.fullPath.startsWith(server.defaultPath.trimEnd('/') + "/")
                }
            }.onSuccess { records ->
                if (_state.value.server?.id != server.id) return@onSuccess
                _state.value = _state.value.copy(
                    loading = false,
                    previewTitle = when (states) {
                        setOf(WatchState.COMPLETED) -> "已看完"
                        setOf(WatchState.IN_PROGRESS) -> "看了一半"
                        else -> "已看完与看了一半"
                    },
                    preview = records.map { CleanupItem(it) },
                )
            }.onFailure {
                if (_state.value.server?.id == server.id) {
                    _state.value = _state.value.copy(
                        loading = false,
                        error = it.message ?: "无法生成预览",
                    )
                }
            }
        }
    }

    fun setExcludeFavorite(value: Boolean) {
        _state.value = _state.value.copy(excludeFavorite = value, preview = emptyList())
    }

    fun setOlderThanDays(value: Int) {
        if (value !in intArrayOf(0, 7, 30, 90)) return
        _state.value = _state.value.copy(olderThanDays = value, preview = emptyList())
    }

    fun setCurrentDirectoryOnly(value: Boolean) {
        _state.value = _state.value.copy(currentDirectoryOnly = value, preview = emptyList())
    }

    fun toggle(recordId: Long) {
        _state.value = _state.value.copy(
            preview = _state.value.preview.map {
                if (it.record.id == recordId) it.copy(selected = !it.selected) else it
            }
        )
    }

    fun selectAll(selected: Boolean) {
        _state.value = _state.value.copy(preview = _state.value.preview.map { it.copy(selected = selected) })
    }

    fun clearPreview() {
        _state.value = _state.value.copy(preview = emptyList(), previewTitle = "")
    }

    fun executeCleanup() {
        if (_state.value.running) return
        viewModelScope.launch {
            val server = _state.value.server ?: return@launch
            val selected = _state.value.preview.filter {
                it.selected && it.record.serverId == server.id
            }
            _state.value = _state.value.copy(
                running = true,
                completed = 0,
                total = selected.size,
                currentFileName = selected.firstOrNull()?.record?.fileName,
                error = null,
            )
            val failures = mutableListOf<CleanupItem>()
            val warnings = mutableListOf<String>()
            selected.forEachIndexed { index, item ->
                currentCoroutineContext().ensureActive()
                _state.value = _state.value.copy(currentFileName = item.record.fileName)
                try {
                    val result = trashRepository.moveToTrash(
                        server.id,
                        server.target,
                        item.record.fullPath,
                    )
                    if (!result.videoMoved) {
                        failures += item.copy(
                            selected = true,
                            failureReason = result.primaryFailure?.reason ?: "视频未能移入回收站",
                        )
                    } else if (result.isPartial) {
                        warnings += "${item.record.fileName}：${result.failures.size} 个关联字幕未移动"
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    failures += item.copy(
                        selected = true,
                        failureReason = t.message ?: "处理失败",
                    )
                }
                _state.value = _state.value.copy(completed = index + 1)
            }
            _state.value = _state.value.copy(
                running = false,
                currentFileName = null,
                preview = failures,
                previewTitle = if (failures.isEmpty()) "" else "失败项",
                notice = when {
                    failures.isEmpty() && warnings.isEmpty() ->
                        "已将 ${selected.size} 个视频移入回收站"
                    failures.isEmpty() ->
                        "已移入 ${selected.size} 个视频，${warnings.size} 项有关联字幕未移动"
                    else -> "成功 ${selected.size - failures.size}，失败 ${failures.size}"
                },
                error = failures.firstOrNull()?.let {
                    "${it.record.fileName}：${it.failureReason}"
                } ?: warnings.firstOrNull(),
            )
        }
    }

    fun requestRestore(groupId: String) {
        viewModelScope.launch {
            val serverId = _state.value.trashGroups
                .firstOrNull { it.groupId == groupId }?.video?.serverId ?: return@launch
            val server = servers.getById(serverId) ?: return@launch
            runCatching { trashRepository.hasRestoreConflict(server.id, server.target, groupId) }
                .onSuccess { conflict ->
                    if (conflict) {
                        _state.value = _state.value.copy(restoreConflictGroupId = groupId)
                    } else {
                        restoreNow(groupId, renameOnConflict = false)
                    }
                }
                .onFailure {
                    _state.value = _state.value.copy(error = it.message ?: "无法检查原位置")
                }
        }
    }

    fun restoreRenamed() {
        val groupId = _state.value.restoreConflictGroupId ?: return
        _state.value = _state.value.copy(restoreConflictGroupId = null)
        restoreNow(groupId, renameOnConflict = true)
    }

    fun cancelRestoreConflict() {
        _state.value = _state.value.copy(restoreConflictGroupId = null)
    }

    private fun restoreNow(groupId: String, renameOnConflict: Boolean) {
        viewModelScope.launch {
            val serverId = _state.value.trashGroups
                .firstOrNull { it.groupId == groupId }?.video?.serverId ?: return@launch
            val server = servers.getById(serverId) ?: return@launch
            runCatching {
                trashRepository.restore(server.id, server.target, groupId, renameOnConflict)
            }.onSuccess {
                _state.value = _state.value.copy(
                    notice = "视频和字幕已还原；原观看进度等个人数据不会恢复",
                )
            }.onFailure {
                _state.value = _state.value.copy(error = it.message ?: "还原失败")
            }
        }
    }

    fun permanentlyDelete(groupId: String) {
        viewModelScope.launch {
            val serverId = _state.value.trashGroups
                .firstOrNull { it.groupId == groupId }?.video?.serverId ?: return@launch
            val server = servers.getById(serverId) ?: return@launch
            runCatching { trashRepository.permanentlyDelete(server.id, server.target, groupId) }
                .onSuccess { _state.value = _state.value.copy(notice = "已彻底删除") }
                .onFailure { _state.value = _state.value.copy(error = it.message ?: "彻底删除失败") }
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(error = null, notice = null)
    }
}
