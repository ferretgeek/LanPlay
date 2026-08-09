package com.lanplay.player.ui.actors

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lanplay.player.data.MovieDisplay
import com.lanplay.player.ui.decodeArtwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActorScreen(
    onExit: () -> Unit,
    viewModel: ActorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val blurArtwork by viewModel.blurArtwork.collectAsStateWithLifecycle()
    val selected = state.selected
    BackHandler { if (selected != null) viewModel.closeActor() else onExit() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selected?.let { it.stats.nameZh ?: it.stats.name } ?: "演员索引") },
                navigationIcon = {
                    IconButton(
                        onClick = { if (selected != null) viewModel.closeActor() else onExit() }
                    ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
                },
                actions = {
                    if (selected != null) {
                        IconButton(onClick = viewModel::toggleFollow) {
                            Icon(
                                if (selected.stats.isFollowed) Icons.Rounded.Star
                                else Icons.Rounded.StarBorder,
                                if (selected.stats.isFollowed) "取消关注" else "关注演员",
                                tint = if (selected.stats.isFollowed)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (selected == null) {
            ActorIndex(
                state = state,
                onQuery = viewModel::setQuery,
                onSort = viewModel::setSort,
                onSelect = viewModel::select,
                onExit = onExit,
                blurArtwork = blurArtwork,
                modifier = Modifier.padding(padding),
            )
        } else {
            ActorDetail(
                actor = selected,
                paths = state.moviePaths,
                displays = state.movies,
                loading = state.loading,
                onPlay = viewModel::play,
                blurArtwork = blurArtwork,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun ActorIndex(
    state: ActorViewModel.UiState,
    onQuery: (String) -> Unit,
    onSort: (ActorSort) -> Unit,
    onSelect: (ActorCard) -> Unit,
    onExit: () -> Unit,
    blurArtwork: Boolean,
    modifier: Modifier,
) {
    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQuery,
            label = { Text("搜索演员") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(ActorSort.WORKS to "作品数", ActorSort.NAME to "姓名").forEach {
                AssistChip(
                    onClick = { onSort(it.first) },
                    label = { Text(it.second) },
                    leadingIcon = if (state.sort == it.first) {
                        { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
                    } else null,
                )
            }
        }
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.visibleActors.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.People,
                        null,
                        Modifier.size(68.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    )
                    Text(
                        if (state.query.isBlank()) "暂无演员资料" else "没有匹配的演员",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(
                        if (state.query.isBlank()) {
                            "在电脑上运行刮削工具并刷新媒体库后，这里会显示演员头像墙"
                        } else {
                            "换一个名字，或清除搜索后查看全部演员"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 7.dp),
                    )
                    FilledTonalButton(
                        onClick = { if (state.query.isBlank()) onExit() else onQuery("") },
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text(if (state.query.isBlank()) "返回媒体库" else "清除搜索")
                    }
                }
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                contentPadding = PaddingValues(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.visibleActors, key = { it.stats.id }) { card ->
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.clickable { onSelect(card) },
                    ) {
                        Column(
                            Modifier.padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Artwork(
                                card.avatar?.absolutePath,
                                Modifier.size(82.dp),
                                circle = true,
                                blurred = blurArtwork,
                            )
                            Text(
                                card.stats.nameZh ?: card.stats.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            Text(
                                "${card.stats.movieCount} 部 · 已看 ${card.stats.watchedCount}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActorDetail(
    actor: ActorCard,
    paths: List<String>,
    displays: Map<String, MovieDisplay>,
    loading: Boolean,
    onPlay: (String) -> Unit,
    blurArtwork: Boolean,
    modifier: Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(
                actor.avatar?.absolutePath,
                Modifier.size(92.dp),
                circle = true,
                blurred = blurArtwork,
            )
            Column(Modifier.padding(start = 14.dp)) {
                Text(
                    actor.stats.nameZh ?: actor.stats.name,
                    style = MaterialTheme.typography.headlineSmall,
                )
                if (actor.stats.nameZh != null) {
                    Text(actor.stats.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${actor.stats.movieCount} 部作品 · 已看 ${actor.stats.watchedCount} 部")
            }
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(paths, key = { it }) { path ->
                    val movie = displays[path]
                    Column(Modifier.clickable { onPlay(path) }) {
                        Artwork(
                            movie?.posterFile?.absolutePath,
                            Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                            circle = false,
                            blurred = blurArtwork,
                        )
                        Text(
                            movie?.code ?: path.substringAfterLast('/'),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Artwork(
    path: String?,
    modifier: Modifier,
    circle: Boolean,
    blurred: Boolean,
) {
    val image by produceState<ImageBitmap?>(null, path, blurred) {
        value = if (!blurred) withContext(Dispatchers.IO) {
            path?.let { decodeArtwork(it, 360, 540) }
        } else null
    }
    Surface(
        modifier = modifier,
        shape = if (circle) CircleShape else RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        if (image != null && !blurred) {
            Image(
                image!!,
                null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.People, null)
            }
        }
    }
}
