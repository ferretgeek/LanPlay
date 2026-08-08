package com.lanplay.player.ui.actors

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanplay.player.data.MetadataRepository
import com.lanplay.player.data.MovieDisplay
import com.lanplay.player.data.SavedServer
import com.lanplay.player.data.ServerRepository
import com.lanplay.player.data.db.ActorDao
import com.lanplay.player.data.db.ActorStatsRow
import com.lanplay.player.data.db.MovieInfoDao
import com.lanplay.player.player.PlaybackController
import com.lanplay.player.data.prefs.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ActorSort { WORKS, NAME }

data class ActorCard(val stats: ActorStatsRow, val avatar: File?)

@HiltViewModel
class ActorViewModel @Inject constructor(
    private val servers: ServerRepository,
    private val actorDao: ActorDao,
    private val movieDao: MovieInfoDao,
    private val metadata: MetadataRepository,
    private val playback: PlaybackController,
    settings: SettingsRepository,
) : ViewModel() {
    data class UiState(
        val loading: Boolean = true,
        val query: String = "",
        val sort: ActorSort = ActorSort.WORKS,
        val actors: List<ActorCard> = emptyList(),
        val selected: ActorCard? = null,
        val movies: Map<String, MovieDisplay> = emptyMap(),
        val moviePaths: List<String> = emptyList(),
        val error: String? = null,
    ) {
        val visibleActors: List<ActorCard>
            get() {
                val filtered = actors.filter {
                    query.isBlank() ||
                        it.stats.name.contains(query, true) ||
                        it.stats.nameZh?.contains(query, true) == true
                }
                return when (sort) {
                    ActorSort.WORKS -> filtered.sortedByDescending { it.stats.movieCount }
                    ActorSort.NAME -> filtered.sortedBy { it.stats.nameZh ?: it.stats.name }
                }
            }
    }

    val blurArtwork = settings.privacySettings.map { it.blurArtwork }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var server: SavedServer? = null
    private var detailJob: Job? = null

    init {
        viewModelScope.launch {
            servers.observeCurrent().collectLatest { current ->
                if (current == null) {
                    server = null
                    _state.value = UiState(loading = false, error = "尚未配置服务器")
                    return@collectLatest
                }
                server = current
                detailJob?.cancel()
                _state.value = UiState(loading = true)
                try {
                    metadata.refresh(current)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                }
                actorDao.observeStats(current.id).collectLatest { rows ->
                val cards = rows.map { row ->
                    ActorCard(row, metadata.artwork(current, row.avatarRelPath))
                }
                val selectedId = _state.value.selected?.stats?.id
                    _state.value = _state.value.copy(
                        loading = false,
                        actors = cards,
                        selected = cards.firstOrNull { it.stats.id == selectedId },
                    )
                }
            }
        }
    }

    fun setQuery(value: String) {
        _state.value = _state.value.copy(query = value)
    }

    fun setSort(value: ActorSort) {
        _state.value = _state.value.copy(sort = value)
    }

    fun select(card: ActorCard) {
        val current = server ?: return
        _state.value = _state.value.copy(selected = card, loading = true)
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            runCatching {
                val movies = movieDao.listForActor(current.id, card.stats.id)
                val paths = movies.map { it.fullPath }
                paths to metadata.displays(current, paths)
            }.onSuccess { (paths, displays) ->
                if (server?.id != current.id || _state.value.selected?.stats?.id != card.stats.id) {
                    return@onSuccess
                }
                _state.value = _state.value.copy(
                    loading = false,
                    moviePaths = paths,
                    movies = displays,
                )
            }.onFailure {
                if (it is CancellationException) return@onFailure
                if (server?.id != current.id || _state.value.selected?.stats?.id != card.stats.id) {
                    return@onFailure
                }
                _state.value = _state.value.copy(
                    loading = false,
                    error = it.message ?: "无法读取演员作品",
                )
            }
        }
    }

    fun closeActor() {
        _state.value = _state.value.copy(
            selected = null,
            moviePaths = emptyList(),
            movies = emptyMap(),
        )
    }

    fun toggleFollow() {
        val actor = _state.value.selected ?: return
        viewModelScope.launch {
            actorDao.setFollowed(actor.stats.id, !actor.stats.isFollowed)
        }
    }

    fun play(path: String) {
        val serverId = server?.id ?: return
        viewModelScope.launch {
            runCatching { playback.open(path, serverId = serverId) }
                .onFailure {
                    _state.value = _state.value.copy(error = it.message ?: "无法播放")
                }
        }
    }
}
