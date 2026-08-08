package com.lanplay.player.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanplay.player.data.MetadataRepository
import com.lanplay.player.data.MovieDisplay
import com.lanplay.player.data.ServerRepository
import com.lanplay.player.data.db.WatchRecordDao
import com.lanplay.player.data.db.WatchRecordEntity
import com.lanplay.player.data.db.WatchState
import com.lanplay.player.data.db.TagDao
import com.lanplay.player.player.PlaybackController
import com.lanplay.player.data.prefs.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

enum class HistoryFilter { ALL, IN_PROGRESS, COMPLETED, FAVORITES }
enum class HistorySort { LAST_WATCHED, PROGRESS, PLAY_COUNT, DURATION, RATING }

data class MonthlyWatchStat(
    val month: String,
    val watchedMs: Long,
    val movieCount: Int,
)

data class HistoryReport(
    val topActor: String? = null,
    val topStudio: String? = null,
    val topTag: String? = null,
    val months: List<MonthlyWatchStat> = emptyList(),
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val servers: ServerRepository,
    private val watchDao: WatchRecordDao,
    private val metadata: MetadataRepository,
    private val playback: PlaybackController,
    private val tagDao: TagDao,
    settings: SettingsRepository,
) : ViewModel() {
    data class UiState(
        val records: List<WatchRecordEntity> = emptyList(),
        val displays: Map<String, MovieDisplay> = emptyMap(),
        val filter: HistoryFilter = HistoryFilter.ALL,
        val loading: Boolean = true,
        val error: String? = null,
        val sort: HistorySort = HistorySort.LAST_WATCHED,
        val selectedIds: Set<Long> = emptySet(),
        val report: HistoryReport = HistoryReport(),
    ) {
        val visible: List<WatchRecordEntity>
            get() {
                val filtered = records.filter {
                when (filter) {
                    HistoryFilter.ALL -> true
                    HistoryFilter.IN_PROGRESS -> it.watchState == WatchState.IN_PROGRESS
                    HistoryFilter.COMPLETED -> it.watchState == WatchState.COMPLETED
                    HistoryFilter.FAVORITES -> it.isFavorite
                }
            }
                return when (sort) {
                    HistorySort.LAST_WATCHED -> filtered.sortedByDescending { it.lastWatchedAt }
                    HistorySort.PROGRESS -> filtered.sortedByDescending { it.progressPercent }
                    HistorySort.PLAY_COUNT -> filtered.sortedByDescending { it.playCount }
                    HistorySort.DURATION -> filtered.sortedByDescending { it.durationMs }
                    HistorySort.RATING -> filtered.sortedByDescending { it.rating }
                }
            }
    }

    val blurArtwork = settings.privacySettings.map { it.blurArtwork }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var activeServerId: Long? = null

    init {
        viewModelScope.launch {
            servers.observeCurrent().collectLatest { server ->
                if (server == null) {
                    activeServerId = null
                    _state.value = UiState(loading = false)
                    return@collectLatest
                }
                activeServerId = server.id
                _state.value = UiState(loading = true)
                watchDao.observeServer(server.id).collectLatest { records ->
                val displayRecords = records.sortedByDescending { it.lastWatchedAt }.take(300)
                val displays = metadata.displays(server, displayRecords.map { it.fullPath })
                val links = tagDao.listAllLinks()
                val tags = tagDao.listAll().associateBy { it.id }
                val recordIds = records.mapTo(hashSetOf()) { it.id }
                val topTag = links.asSequence()
                    .filter { it.recordId in recordIds }
                    .mapNotNull { tags[it.tagId]?.name }
                    .filterNot { it.startsWith(COLLECTION_PREFIX) }
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key
                val topActor = displays.values
                    .flatMap { it.actorNames.distinct() }
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key
                val topStudio = displays.values
                    .mapNotNull { it.studio?.trim()?.ifBlank { null } }
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key
                val monthFormat = SimpleDateFormat("yyyy-MM", Locale.CHINA)
                val months = records
                    .filter { it.lastWatchedAt > 0L }
                    .groupBy { monthFormat.format(Date(it.lastWatchedAt)) }
                    .map { (month, values) ->
                        MonthlyWatchStat(
                            month = month,
                            watchedMs = values.sumOf { it.totalWatchedMs },
                            movieCount = values.size,
                        )
                    }
                    .sortedByDescending { it.month }
                    .take(6)
                    .reversed()
                    _state.value = _state.value.copy(
                        records = records,
                        displays = displays,
                        report = HistoryReport(topActor, topStudio, topTag, months),
                        loading = false,
                        error = null,
                    )
                }
            }
        }
    }

    private companion object {
        const val COLLECTION_PREFIX = "收藏夹 · "
    }

    fun setFilter(filter: HistoryFilter) {
        _state.value = _state.value.copy(filter = filter, selectedIds = emptySet())
    }

    fun setSort(sort: HistorySort) {
        _state.value = _state.value.copy(sort = sort)
    }

    fun toggleSelection(id: Long) {
        val selected = _state.value.selectedIds.toMutableSet()
        if (!selected.add(id)) selected.remove(id)
        _state.value = _state.value.copy(selectedIds = selected)
    }

    fun selectAllVisible() {
        _state.value = _state.value.copy(selectedIds = _state.value.visible.mapTo(mutableSetOf()) { it.id })
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selectedIds = emptySet())
    }

    fun deleteSelected() = viewModelScope.launch {
        val ids = _state.value.selectedIds
        _state.value.records.filter { it.id in ids }.forEach {
            watchDao.deleteWithRelations(it.serverId, it.fullPath)
        }
        _state.value = _state.value.copy(selectedIds = emptySet())
    }

    fun play(record: WatchRecordEntity) = viewModelScope.launch {
        runCatching { playback.open(record.fullPath, serverId = record.serverId) }
            .onFailure {
                _state.value = _state.value.copy(error = it.message ?: "无法继续播放")
            }
    }

    fun toggleFavorite(record: WatchRecordEntity) = viewModelScope.launch {
        watchDao.setFavorite(record.id, !record.isFavorite)
    }

    fun delete(record: WatchRecordEntity) = viewModelScope.launch {
        watchDao.deleteWithRelations(record.serverId, record.fullPath)
    }

    fun clearAll() = viewModelScope.launch {
        activeServerId?.let { watchDao.clearServerWithRelations(it) }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}
