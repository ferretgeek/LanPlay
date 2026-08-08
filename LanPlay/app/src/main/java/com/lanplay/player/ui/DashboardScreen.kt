package com.lanplay.player.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.lanplay.player.data.MetadataRepository
import com.lanplay.player.data.MovieDisplay
import com.lanplay.player.data.ServerRepository
import com.lanplay.player.data.db.WatchRecordDao
import com.lanplay.player.data.db.WatchRecordEntity
import com.lanplay.player.data.db.WatchState
import com.lanplay.player.data.db.ActorDao
import com.lanplay.player.data.db.MovieInfoDao
import com.lanplay.player.data.prefs.HomeLayout
import com.lanplay.player.data.prefs.SettingsRepository
import com.lanplay.player.player.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DashboardItem(val record: WatchRecordEntity, val movie: MovieDisplay?)
data class FollowedActorMovie(val serverId: Long, val path: String, val movie: MovieDisplay)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    settings: SettingsRepository,
    private val servers: ServerRepository,
    private val watchDao: WatchRecordDao,
    private val metadata: MetadataRepository,
    private val playback: PlaybackController,
    private val actorDao: ActorDao,
    private val movieDao: MovieInfoDao,
) : ViewModel() {
    data class UiState(
        val continueWatching: List<DashboardItem> = emptyList(),
        val favorites: List<DashboardItem> = emptyList(),
        val followedActorMovies: List<FollowedActorMovie> = emptyList(),
    )

    val homeLayout = settings.appearanceSettings.map { it.homeLayout }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeLayout.GALLERY)
    val blurArtwork = settings.privacySettings.map { it.blurArtwork }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            servers.observeCurrent().collectLatest { server ->
                if (server == null) {
                    _state.value = UiState()
                    return@collectLatest
                }
                combine(
                    watchDao.observeServer(server.id),
                    actorDao.observeStats(server.id),
                ) { records, actors -> records to actors.any { it.isFollowed } }
                    .collectLatest { (records, hasFollowedActors) ->
                        val followed = if (hasFollowedActors) {
                            movieDao.listForFollowedActors(server.id)
                        } else emptyList()
                        val continueRecords = records
                            .filter { it.watchState == WatchState.IN_PROGRESS }
                            .sortedByDescending { it.lastWatchedAt }
                            .take(20)
                        val favoriteRecords = records
                            .filter { it.isFavorite }
                            .sortedByDescending { it.lastWatchedAt }
                            .take(50)
                        val displayPaths = (continueRecords.map { it.fullPath } +
                            favoriteRecords.map { it.fullPath } +
                            followed.map { it.fullPath }).distinct()
                        val displays = metadata.displays(server, displayPaths)
                        _state.value = UiState(
                            continueWatching = continueRecords
                                .map { DashboardItem(it, displays[it.fullPath]) },
                            favorites = favoriteRecords
                                .map { DashboardItem(it, displays[it.fullPath]) },
                            followedActorMovies = followed.mapNotNull {
                                displays[it.fullPath]?.let { display ->
                                    FollowedActorMovie(server.id, it.fullPath, display)
                                }
                            },
                        )
                    }
            }
        }
    }

    fun play(item: DashboardItem) = viewModelScope.launch {
        runCatching {
            playback.open(item.record.fullPath, serverId = item.record.serverId)
        }
    }

    fun random() {
        val pool = _state.value.continueWatching.ifEmpty { _state.value.favorites }
        pool.randomOrNull()?.let(::play)
    }

    fun playFollowed(item: FollowedActorMovie) = viewModelScope.launch {
        runCatching { playback.open(item.path, serverId = item.serverId) }
    }
}

@Composable
fun DashboardScreen(
    onOpenLibrary: () -> Unit,
    viewModel: DashboardViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val blurArtwork by viewModel.blurArtwork.collectAsStateWithLifecycle()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(bottomStart = 30.dp, bottomEnd = 30.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 24.dp)) {
                    Text("今晚继续看什么？", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "接着上次进度，或从收藏里随机抽一部。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Row(
                        Modifier.padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(onClick = onOpenLibrary) {
                            Icon(Icons.Rounded.VideoLibrary, null)
                            Text("打开媒体库", Modifier.padding(start = 7.dp))
                        }
                        FilledTonalButton(onClick = viewModel::random) {
                            Icon(Icons.Rounded.Casino, null)
                            Text("随机一部", Modifier.padding(start = 7.dp))
                        }
                    }
                }
            }
        }
        if (state.continueWatching.isNotEmpty()) {
            item { DashboardTitle("继续观看", "${state.continueWatching.size} 部") }
            item {
                DashboardRow(state.continueWatching, blurArtwork, viewModel::play)
            }
        }
        if (state.favorites.isNotEmpty()) {
            item { DashboardTitle("我的收藏", "${state.favorites.size} 部") }
            item {
                DashboardRow(state.favorites, blurArtwork, viewModel::play)
            }
        }
        if (state.followedActorMovies.isNotEmpty()) {
            item { DashboardTitle("关注演员新作", "${state.followedActorMovies.size} 部") }
            item {
                FollowedMovieRow(
                    state.followedActorMovies,
                    blurArtwork,
                    viewModel::playFollowed,
                )
            }
        }
        if (
            state.continueWatching.isEmpty() &&
            state.favorites.isEmpty() &&
            state.followedActorMovies.isEmpty()
        ) {
            item {
                Box(
                    Modifier.fillMaxWidth().padding(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "开始播放后，这里会出现继续观看与收藏。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FollowedMovieRow(
    items: List<FollowedActorMovie>,
    blurArtwork: Boolean,
    onPlay: (FollowedActorMovie) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = { it.path }) { item ->
            Column(Modifier.width(142.dp).clickable { onPlay(item) }) {
                val poster by produceState<ImageBitmap?>(
                    null,
                    item.movie.posterFile?.absolutePath,
                    blurArtwork,
                ) {
                    value = if (!blurArtwork) withContext(Dispatchers.IO) {
                        item.movie.posterFile?.absolutePath?.let {
                            decodeArtwork(it, 320, 480)
                        }
                    } else null
                }
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                ) {
                    if (poster != null && !blurArtwork) {
                        Image(
                            poster!!,
                            null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.PlayArrow, null)
                        }
                    }
                }
                Text(
                    item.movie.code,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 7.dp),
                )
                Text(
                    item.movie.actorNames.firstOrNull() ?: "关注演员",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun DashboardTitle(title: String, count: String) {
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Text(count, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DashboardRow(
    items: List<DashboardItem>,
    blurArtwork: Boolean,
    onPlay: (DashboardItem) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = { it.record.id }) { item ->
            Column(Modifier.width(142.dp).clickable { onPlay(item) }) {
                DashboardPoster(item, blurArtwork)
                Text(
                    item.movie?.code ?: item.record.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 7.dp),
                )
                Text(
                    "${(item.record.progressPercent * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun DashboardPoster(item: DashboardItem, blurArtwork: Boolean) {
    val poster by produceState<ImageBitmap?>(
        null,
        item.movie?.posterFile?.absolutePath,
        blurArtwork,
    ) {
        value = if (!blurArtwork) withContext(Dispatchers.IO) {
            item.movie?.posterFile?.absolutePath?.let { decodeArtwork(it, 320, 480) }
        } else null
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
    ) {
        if (poster != null && !blurArtwork) {
            Image(
                poster!!,
                null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, null)
            }
        }
    }
}
