package com.lanplay.player.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lanplay.player.data.db.WatchState
import com.lanplay.player.smb.SmbEntry
import com.lanplay.player.ui.decodeArtwork
import com.lanplay.player.ui.actors.ActorScreen
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun BrowseScreen(
    initialActorScreen: Boolean = false,
    onInitialActorConsumed: () -> Unit = {},
    viewModel: BrowseViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.configured) {
        ConnectionScreen(state, viewModel)
    } else {
        BrowserContent(state, viewModel, initialActorScreen, onInitialActorConsumed)
    }
}

@Composable
private fun ConnectionScreen(state: BrowseViewModel.UiState, viewModel: BrowseViewModel) {
    val editing = state.editingServer
    var host by remember(editing?.id) {
        mutableStateOf(editing?.target?.host.orEmpty())
    }
    var port by remember(editing?.id) {
        mutableStateOf((editing?.target?.port ?: 445).toString())
    }
    var share by remember(editing?.id) {
        mutableStateOf(editing?.target?.share.orEmpty())
    }
    var user by remember(editing?.id) {
        mutableStateOf(editing?.target?.username.orEmpty())
    }
    var password by remember(editing?.id) {
        mutableStateOf(editing?.target?.password.orEmpty())
    }
    var displayName by remember(editing?.id) {
        mutableStateOf(editing?.displayName ?: "家里电脑")
    }
    var path by remember(editing?.id) {
        mutableStateOf(editing?.defaultPath.orEmpty())
    }
    LaunchedEffect(editing?.id) {
        if (editing == null && state.discovered.isEmpty() && !state.scanning) {
            viewModel.scan()
        } else if (editing != null && !state.discoveringShares) {
            viewModel.discoverShares(host, port, user, password)
        }
    }
    LaunchedEffect(state.setupShare, state.setupFolderPath) {
        if (state.setupShare.isNotEmpty()) {
            share = state.setupShare
            path = state.setupFolderPath
        }
    }
    LaunchedEffect(state.availableShares) {
        val preferredShare = when {
            share in state.availableShares -> share
            state.availableShares.size == 1 -> state.availableShares.first()
            else -> null
        }
        if (preferredShare != null &&
            state.setupShare != preferredShare &&
            !state.browsingSetupFolders
        ) {
            share = preferredShare
            path = if (preferredShare == editing?.target?.share) {
                editing.defaultPath
            } else {
                ""
            }
            viewModel.browseSetupFolder(host, port, share, user, password, path)
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.fillMaxHeight().widthIn(max = 760.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Spacer(Modifier.height(18.dp))
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(64.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.Movie,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    if (editing == null) "连接你的电脑" else "编辑服务器",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    "选择局域网电脑，再展开并选择里面的共享文件夹。凭据会加密保存在本机。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item {
                FilledTonalButton(
                    onClick = viewModel::scan,
                    enabled = !state.scanning,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    if (state.scanning) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("正在扫描 ${state.scanDone}/${state.scanTotal.coerceAtLeast(1)}")
                    } else {
                        Icon(Icons.Rounded.Search, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("扫描局域网")
                    }
                }
                if (state.scanning) {
                    val total = state.scanTotal.coerceAtLeast(1)
                    LinearProgressIndicator(
                        progress = { state.scanDone.coerceIn(0, total) / total.toFloat() },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
            if (state.discovered.isNotEmpty()) {
                item {
                    Text("发现的电脑", style = MaterialTheme.typography.titleMedium)
                    Column(
                        Modifier.padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.discovered.forEach { item ->
                            Surface(
                                onClick = {
                                    host = item.address
                                    share = ""
                                    path = ""
                                    viewModel.discoverShares(item.address, port, user, password)
                                },
                                shape = RoundedCornerShape(14.dp),
                                color = if (host == item.address) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Rounded.Computer, contentDescription = null)
                                    Column(Modifier.padding(start = 12.dp)) {
                                        Text(item.hostName ?: "局域网电脑")
                                        Text(
                                            item.address,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (state.scanCompleted && state.error == null) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("没有自动发现电脑", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "部分路由器会拦截局域网扫描，可以在下方填写电脑地址后展开共享文件夹。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 5.dp),
                            )
                        }
                    }
                }
            }
            item {
                Text("连接信息", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("电脑地址") },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("端口") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = user,
                        onValueChange = { user = it },
                        label = { Text("用户名") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "电脑允许免密码访问时可留空",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
            item {
                FilledTonalButton(
                    onClick = { viewModel.discoverShares(host, port, user, password) },
                    enabled = !state.discoveringShares,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    if (state.discoveringShares) {
                        CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在读取共享")
                    } else {
                        Icon(Icons.Rounded.Dns, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("展开这台电脑的共享文件夹")
                    }
                }
            }
            if (state.availableShares.isNotEmpty()) {
                item {
                    Text("选择共享文件夹", style = MaterialTheme.typography.titleMedium)
                    Column(
                        Modifier.padding(top = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        state.availableShares.forEach { name ->
                            Surface(
                                onClick = {
                                    share = name
                                    path = ""
                                    viewModel.browseSetupFolder(
                                        host, port, name, user, password, ""
                                    )
                                },
                                color = if (share == name) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainer
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Rounded.Folder, contentDescription = null)
                                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                        Text(name)
                                        Text(
                                            "打开并选择此共享内的文件夹",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (share == name) {
                                        Icon(Icons.Rounded.CheckCircle, contentDescription = "已选择")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (share.isNotEmpty()) {
                item {
                    Text("共享文件夹路径", style = MaterialTheme.typography.titleMedium)
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                    ) {
                        Row(
                            Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(
                                    "\\\\$host\\$share${path.takeIf { it.isNotEmpty() }?.let { "\\${it.replace('/', '\\')}" }.orEmpty()}",
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.MiddleEllipsis,
                                )
                                Text(
                                    "这里将作为视频列表的起点",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                                )
                            }
                            if (path.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        val parent = path.substringBeforeLast('/', "")
                                        path = parent
                                        viewModel.browseSetupFolder(
                                            host, port, share, user, password, parent
                                        )
                                    },
                                ) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回上层文件夹")
                                }
                            }
                        }
                    }
                }
                if (state.browsingSetupFolders) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(
                                "正在展开文件夹",
                                modifier = Modifier.padding(start = 9.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else if (state.setupFolders.isNotEmpty()) {
                    item {
                        Text(
                            "继续进入子文件夹，或直接使用当前路径",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(
                            Modifier.padding(top = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            state.setupFolders.forEach { folder ->
                                Surface(
                                    onClick = {
                                        path = folder.relativePath
                                        viewModel.browseSetupFolder(
                                            host,
                                            port,
                                            share,
                                            user,
                                            password,
                                            folder.relativePath,
                                        )
                                    },
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Rounded.Folder, contentDescription = null)
                                        Text(
                                            folder.name,
                                            Modifier.weight(1f).padding(start = 10.dp),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Icon(
                                            Icons.AutoMirrored.Rounded.ArrowBack,
                                            contentDescription = null,
                                            modifier = Modifier.graphicsLayer(rotationZ = 180f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("这台电脑在应用里的名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.error != null) {
                item {
                    Text(
                        state.error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            item {
                Button(
                    onClick = {
                        viewModel.saveServer(
                            host, port, share, user, password, displayName, path
                        )
                    },
                    enabled = !state.loading &&
                        !state.discoveringShares &&
                        !state.browsingSetupFolders &&
                        share.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                ) {
                    if (state.loading) {
                        CircularProgressIndicator(
                            Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("正在测试连接")
                    } else {
                        Text(if (editing == null) "连接并开始使用" else "保存并重新连接")
                    }
                }
                if (state.savedServers.isNotEmpty()) {
                    TextButton(
                        onClick = viewModel::cancelConfiguration,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("取消")
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserContent(
    state: BrowseViewModel.UiState,
    viewModel: BrowseViewModel,
    initialActorScreen: Boolean,
    onInitialActorConsumed: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    var serverDialog by remember { mutableStateOf(false) }
    var showActors by remember { mutableStateOf(initialActorScreen) }
    LaunchedEffect(initialActorScreen) {
        if (initialActorScreen) onInitialActorConsumed()
    }
    var showStudioFilter by remember { mutableStateOf(false) }
    var showSeriesFilter by remember { mutableStateOf(false) }
    var showYearFilter by remember { mutableStateOf(false) }
    var showActorFilter by remember { mutableStateOf(false) }
    var toolsExpanded by remember(state.server?.id, state.path, state.mergedMode) {
        mutableStateOf(false)
    }
    if (showActors) {
        ActorScreen(onExit = { showActors = false })
        return
    }
    BackHandler(
        enabled = state.selectedPaths.isNotEmpty() ||
            state.query.isNotEmpty() ||
            state.path.isNotEmpty() ||
            state.mergedMode,
    ) {
        when {
            state.selectedPaths.isNotEmpty() -> viewModel.clearSelection()
            state.query.isNotEmpty() -> viewModel.setQuery("")
            else -> viewModel.up()
        }
    }
    LaunchedEffect(state.error, state.notice) {
        val text = state.error ?: state.notice
        if (text != null) {
            snackbar.showSnackbar(text)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (state.selectedPaths.isNotEmpty()) {
                SelectionBar(state, viewModel)
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (state.mergedMode) "合并浏览"
                            else state.server?.displayName ?: "LanPlay",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (state.mergedMode) "${state.mergedDirectories.size} 个收藏目录"
                            else "/" + state.path,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                        )
                    }
                },
                navigationIcon = {
                    if (state.path.isNotEmpty() || state.mergedMode) {
                        IconButton(onClick = { viewModel.up() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回上级")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { serverDialog = true }) {
                        Icon(Icons.Rounded.Dns, contentDescription = "管理服务器")
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                ),
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
        Column(Modifier.fillMaxSize()) {
            CompactLibraryHeader(
                state = state,
                expanded = toolsExpanded,
                onToggle = { toolsExpanded = !toolsExpanded },
            )
            AnimatedVisibility(visible = toolsExpanded) {
                Column {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::setQuery,
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        label = { Text("搜索文件名、番号或演员") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    if (state.path.isNotEmpty() && !state.mergedMode) {
                        val parts = state.path.split('/').filter(String::isNotBlank)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            AssistChip(
                                onClick = { viewModel.navigateTo("") },
                                label = { Text("共享根目录") },
                            )
                            parts.forEachIndexed { index, part ->
                                AssistChip(
                                    onClick = {
                                        viewModel.navigateTo(parts.take(index + 1).joinToString("/"))
                                    },
                                    label = { Text(part, maxLines = 1) },
                                    leadingIcon = if (index == parts.lastIndex) {
                                        { Icon(Icons.Rounded.CheckCircle, null, Modifier.size(16.dp)) }
                                    } else null,
                                )
                            }
                        }
                    }
                    LibraryToolsBar(
                        state = state,
                        viewModel = viewModel,
                        onShowActors = { showActors = true },
                        onStudioFilter = { showStudioFilter = true },
                        onSeriesFilter = { showSeriesFilter = true },
                        onYearFilter = { showYearFilter = true },
                        onActorFilter = { showActorFilter = true },
                    )
                    SortBar(state, viewModel)
                }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                state.items.isEmpty() && state.loading -> Unit
                state.items.isEmpty() -> EmptyLibrary(state, viewModel)
                else -> when (state.viewMode) {
                    ViewMode.LIST -> FileList(state, viewModel) { toolsExpanded = false }
                    ViewMode.GRID -> GalleryGrid(state, viewModel, compact = true) {
                        toolsExpanded = false
                    }
                    ViewMode.GALLERY -> GalleryGrid(state, viewModel, compact = false) {
                        toolsExpanded = false
                    }
                }
            }
        }
        }
    }
    if (serverDialog) {
        ServerDialog(
            state = state,
            onDismiss = { serverDialog = false },
            onSelect = {
                serverDialog = false
                viewModel.switchServer(it)
            },
            onAdd = {
                serverDialog = false
                viewModel.addServer()
            },
            onDelete = viewModel::deleteServer,
            onEdit = {
                serverDialog = false
                viewModel.editServer(it)
            },
            onMove = viewModel::moveServer,
        )
    }
    if (showStudioFilter) {
        MetadataChoiceDialog(
            title = "按片商筛选",
            choices = state.availableStudios,
            selected = state.studioFilter,
            onDismiss = { showStudioFilter = false },
            onSelect = {
                showStudioFilter = false
                viewModel.setStudioFilter(it)
            },
        )
    }
    if (showSeriesFilter) {
        MetadataChoiceDialog(
            title = "按系列筛选",
            choices = state.availableSeries,
            selected = state.seriesFilter,
            onDismiss = { showSeriesFilter = false },
            onSelect = {
                showSeriesFilter = false
                viewModel.setSeriesFilter(it)
            },
        )
    }
    if (showYearFilter) {
        MetadataChoiceDialog(
            title = "按年份筛选",
            choices = state.availableYears,
            selected = state.yearFilter,
            onDismiss = { showYearFilter = false },
            onSelect = {
                showYearFilter = false
                viewModel.setYearFilter(it)
            },
        )
    }
    if (showActorFilter) {
        MetadataChoiceDialog(
            title = "按演员筛选",
            choices = state.availableActors,
            selected = state.actorFilter,
            onDismiss = { showActorFilter = false },
            onSelect = {
                showActorFilter = false
                viewModel.setActorFilter(it)
            },
        )
    }
}

@Composable
private fun CompactLibraryHeader(
    state: BrowseViewModel.UiState,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val activeFilter = state.query.isNotEmpty() ||
        state.watchFilter != WatchFilter.ALL ||
        state.recentOnly ||
        state.selectedTagId != null ||
        state.actorFilter != null ||
        state.studioFilter != null ||
        state.seriesFilter != null ||
        state.yearFilter != null
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${state.directoryVideoCount} 部影片 · 已看完 ${state.directoryCompletedCount} 部",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        state.query.isNotEmpty() -> "正在搜索“${state.query}”"
                        activeFilter -> "已启用筛选条件"
                        else -> "${formatSize(state.directoryTotalBytes)} · 视频内容优先显示"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (activeFilter) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FilledTonalButton(onClick = onToggle) {
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (expanded) "收起" else "搜索与筛选")
            }
        }
    }
}

@Composable
private fun LibraryToolsBar(
    state: BrowseViewModel.UiState,
    viewModel: BrowseViewModel,
    onShowActors: () -> Unit,
    onStudioFilter: () -> Unit,
    onSeriesFilter: () -> Unit,
    onYearFilter: () -> Unit,
    onActorFilter: () -> Unit,
) {
    Column {
        Text(
            "${state.directoryVideoCount} 部影片 · ${formatSize(state.directoryTotalBytes)} · " +
                "已看完 ${state.directoryCompletedCount} 部",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AssistChip(
                onClick = onShowActors,
                label = { Text("演员索引") },
                leadingIcon = {
                    Icon(Icons.Rounded.People, null, Modifier.size(18.dp))
                },
            )
            AssistChip(
                onClick = viewModel::toggleViewMode,
                label = {
                    Text(
                        when (state.viewMode) {
                            ViewMode.LIST -> "列表视图"
                            ViewMode.GRID -> "紧凑海报"
                            ViewMode.GALLERY -> "大幅海报"
                        }
                    )
                },
                leadingIcon = {
                    Icon(
                        if (state.viewMode == ViewMode.GALLERY) Icons.AutoMirrored.Rounded.ViewList
                        else Icons.Rounded.GridView,
                        null,
                        Modifier.size(18.dp),
                    )
                },
            )
            AssistChip(
                onClick = { viewModel.setShowAllFiles(!state.showAllFiles) },
                label = { Text(if (state.showAllFiles) "显示全部文件" else "只显示视频") },
                leadingIcon = {
                    Icon(
                        if (state.showAllFiles) Icons.Rounded.Visibility
                        else Icons.Rounded.VisibilityOff,
                        null,
                        Modifier.size(18.dp),
                    )
                },
            )
            if (!state.mergedMode) {
                AssistChip(
                    onClick = viewModel::toggleCurrentMergedDirectory,
                    label = {
                        Text(
                            if (state.path in state.mergedDirectories) "已加入合并"
                            else "加入合并"
                        )
                    },
                    leadingIcon = {
                        Icon(
                            if (state.path in state.mergedDirectories) {
                                Icons.Rounded.CheckCircle
                            } else Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
            if (state.mergedDirectories.isNotEmpty()) {
                AssistChip(
                    onClick = viewModel::openMerged,
                    label = { Text("合并浏览 ${state.mergedDirectories.size}") },
                    leadingIcon = {
                        Icon(Icons.Rounded.Folder, null, Modifier.size(18.dp))
                    },
                )
            }
            AssistChip(
                onClick = onActorFilter,
                enabled = state.availableActors.isNotEmpty(),
                label = { Text(state.actorFilter ?: "演员") },
                leadingIcon = if (state.actorFilter != null) {
                    { Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp)) }
                } else null,
            )
            AssistChip(
                onClick = onStudioFilter,
                enabled = state.availableStudios.isNotEmpty(),
                label = { Text(state.studioFilter ?: "片商") },
                leadingIcon = if (state.studioFilter != null) {
                    { Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp)) }
                } else null,
            )
            AssistChip(
                onClick = onSeriesFilter,
                enabled = state.availableSeries.isNotEmpty(),
                label = { Text(state.seriesFilter ?: "系列") },
                leadingIcon = if (state.seriesFilter != null) {
                    { Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp)) }
                } else null,
            )
            AssistChip(
                onClick = onYearFilter,
                enabled = state.availableYears.isNotEmpty(),
                label = { Text(state.yearFilter ?: "年份") },
                leadingIcon = if (state.yearFilter != null) {
                    { Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp)) }
                } else null,
            )
            if (
                state.actorFilter != null ||
                state.studioFilter != null ||
                state.seriesFilter != null ||
                state.yearFilter != null
            ) {
                AssistChip(
                    onClick = viewModel::clearMetadataFilters,
                    label = { Text("清除元数据筛选") },
                    leadingIcon = { Icon(Icons.Rounded.Close, null, Modifier.size(18.dp)) },
                )
            }
        }
    }
}

@Composable
private fun MetadataChoiceDialog(
    title: String,
    choices: List<String>,
    selected: String?,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChoiceRow("全部", selected == null) { onSelect(null) }
                choices.forEach { choice ->
                    FilterChoiceRow(choice, selected == choice) { onSelect(choice) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun FilterChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.weight(1f))
            if (selected) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = "已选择")
            }
        }
    }
}

@Composable
private fun ServerDialog(
    state: BrowseViewModel.UiState,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit,
    onAdd: () -> Unit,
    onDelete: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onMove: (Long, Int) -> Unit,
) {
    var deleteTarget by remember { mutableStateOf<com.lanplay.player.data.SavedServer?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Dns, contentDescription = null) },
        title = { Text("服务器与共享") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.savedServers.forEach { server ->
                    Surface(
                        onClick = { onSelect(server.id) },
                        color = if (server.id == state.server?.id) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(server.displayName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${server.target.host} / ${server.target.share}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onMove(server.id, -1) }) {
                                Icon(Icons.Rounded.ArrowUpward, contentDescription = "上移")
                            }
                            IconButton(onClick = { onMove(server.id, 1) }) {
                                Icon(Icons.Rounded.ArrowDownward, contentDescription = "下移")
                            }
                            IconButton(onClick = { onEdit(server.id) }) {
                                Icon(Icons.Rounded.Edit, contentDescription = "编辑服务器")
                            }
                            IconButton(onClick = { deleteTarget = server }) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = "删除服务器")
                            }
                        }
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        confirmButton = {
            Button(onClick = onAdd) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("添加服务器")
            }
        },
    )
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除服务器配置？") },
            text = {
                Text(
                    "将删除“${target.displayName}”的连接配置和对应本机观看记录、元数据与缓存索引，" +
                        "不会删除电脑上的视频。若回收站还有项目，需要先处理回收站。"
                )
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
            confirmButton = {
                Button(onClick = {
                    deleteTarget = null
                    onDelete(target.id)
                }) { Text("删除配置") }
            },
        )
    }
}

@Composable
private fun SelectionBar(state: BrowseViewModel.UiState, viewModel: BrowseViewModel) {
    var confirmDelete by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            state.batchDelete?.let { progress ->
                if (progress.running) {
                    LinearProgressIndicator(
                        progress = {
                            if (progress.total == 0) 0f
                            else progress.completed / progress.total.toFloat()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "正在处理 ${progress.completed}/${progress.total}" +
                            (progress.currentFileName?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 5.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (progress.failures.isNotEmpty()) {
                    Text(
                        "以下 ${progress.failures.size} 项仍未移动，可直接重试",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
                    )
                    progress.failures.take(3).forEach { failure ->
                        Text(
                            "${failure.fileName}：${failure.reason}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "已选 ${state.selectedPaths.size} 项 · ${
                        formatSize(
                            state.items
                                .filter { it.entry.relativePath in state.selectedPaths }
                                .sumOf { it.entry.size }
                        )
                    }",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                if (state.batchDelete?.running != true) {
                    TextButton(onClick = viewModel::selectAllVisible) { Text("全选") }
                    TextButton(onClick = viewModel::invertSelection) { Text("反选") }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(
                            Icons.Rounded.DeleteOutline,
                            contentDescription = if (state.batchDelete?.failures?.isNotEmpty() == true) {
                                "重试失败项"
                            } else {
                                "批量移入回收站"
                            },
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                    IconButton(onClick = viewModel::clearSelection) {
                        Icon(Icons.Rounded.Close, contentDescription = "取消选择")
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = {
                Text(
                    if (state.batchDelete?.failures?.isNotEmpty() == true) "重试失败项？"
                    else "移入回收站？"
                )
            },
            text = {
                Text(
                    "将把选中的 ${state.selectedPaths.size} 个视频及匹配字幕移入回收站。" +
                        "视频和字幕可还原；观看进度、收藏、标签与书签会立即从本机删除，且不会随文件还原。"
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = false
                    viewModel.deleteSelected()
                }) {
                    Text(
                        if (state.batchDelete?.failures?.isNotEmpty() == true) "确认重试"
                        else "确认移入"
                    )
                }
            },
        )
    }
}

@Composable
private fun SortBar(state: BrowseViewModel.UiState, viewModel: BrowseViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SortField.entries.forEach { field ->
            AssistChip(
                onClick = { viewModel.setSort(field) },
                label = {
                    Text(
                        when (field) {
                            SortField.NAME -> "名称"
                            SortField.SIZE -> "大小"
                            SortField.LAST_MODIFIED -> "修改时间"
                            SortField.DURATION -> "时长"
                            SortField.LAST_WATCHED -> "最后观看"
                            SortField.RATING -> "评分"
                        }
                    )
                },
                leadingIcon = if (state.sortField == field) {
                    { Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp)) }
                } else null,
            )
        }
        IconButton(onClick = viewModel::toggleSortDirection) {
            Icon(
                if (state.sortAscending) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                contentDescription = if (state.sortAscending) "升序" else "降序",
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WatchFilter.entries.forEach { filter ->
            AssistChip(
                onClick = { viewModel.setWatchFilter(filter) },
                label = {
                    Text(
                        when (filter) {
                            WatchFilter.ALL -> "全部"
                            WatchFilter.UNWATCHED -> "未看"
                            WatchFilter.IN_PROGRESS -> "看了一半"
                            WatchFilter.COMPLETED -> "已看完"
                            WatchFilter.FAVORITES -> "收藏"
                        }
                    )
                },
                leadingIcon = if (state.watchFilter == filter) {
                    { Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp)) }
                } else null,
            )
        }
        AssistChip(
            onClick = viewModel::toggleRecentOnly,
            label = { Text("最近新增") },
            leadingIcon = {
                Icon(
                    if (state.recentOnly) Icons.Rounded.CheckCircle else Icons.Rounded.NewReleases,
                    null,
                    Modifier.size(18.dp),
                )
            },
        )
        if (state.tags.isNotEmpty()) {
            AssistChip(
                onClick = { viewModel.setTagFilter(null) },
                label = { Text("全部标签") },
                leadingIcon = if (state.selectedTagId == null) {
                    { Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp)) }
                } else null,
            )
            state.tags.forEach { tag ->
                AssistChip(
                    onClick = { viewModel.setTagFilter(tag.id) },
                    label = { Text(tag.name) },
                    leadingIcon = if (state.selectedTagId == tag.id) {
                        { Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp)) }
                    } else null,
                )
            }
        }
        AssistChip(
            onClick = viewModel::playRandomUnwatched,
            label = { Text("随机抽一部") },
            leadingIcon = { Icon(Icons.Rounded.Casino, null, Modifier.size(18.dp)) },
        )
    }
}

@Composable
private fun FileList(
    state: BrowseViewModel.UiState,
    viewModel: BrowseViewModel,
    onContentScroll: () -> Unit,
) {
    val listState = androidx.compose.runtime.key(
        state.server?.id,
        state.path,
        state.mergedMode,
    ) { rememberLazyListState(state.scrollIndex, state.scrollOffset) }
    LaunchedEffect(state.path, state.mergedMode, listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                viewModel.saveScroll(index, offset)
                if (index > 0 || offset > 16) onContentScroll()
            }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = 6.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            count = state.items.size,
            key = { state.items[it].entry.relativePath },
            contentType = { if (state.items[it].entry.isDirectory) "dir" else "file" },
        ) { index ->
            EntryRow(state.items[index], state, viewModel)
        }
    }
}

@Composable
private fun GalleryGrid(
    state: BrowseViewModel.UiState,
    viewModel: BrowseViewModel,
    compact: Boolean,
    onContentScroll: () -> Unit,
) {
    val gridState = androidx.compose.runtime.key(
        state.server?.id,
        state.path,
        state.mergedMode,
    ) { rememberLazyGridState(state.scrollIndex, state.scrollOffset) }
    LaunchedEffect(state.path, state.mergedMode, gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                viewModel.saveScroll(index, offset)
                if (index > 0 || offset > 16) onContentScroll()
            }
    }
    LazyVerticalGrid(
        columns = if (compact) GridCells.Adaptive(150.dp) else GridCells.Fixed(2),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(
            count = state.items.size,
            key = { state.items[it].entry.relativePath },
            contentType = { if (state.items[it].entry.isDirectory) "dir" else "movie" },
        ) { index ->
            GalleryCard(state.items[index], state, viewModel, state.blurArtwork, compact)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryCard(
    item: BrowseItem,
    state: BrowseViewModel.UiState,
    viewModel: BrowseViewModel,
    blurArtwork: Boolean,
    compact: Boolean,
) {
    val entry = item.entry
    var confirmDelete by remember { mutableStateOf(false) }
    var showActions by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    var artworkRevealed by remember(item.entry.relativePath) { mutableStateOf(false) }
    val selected = entry.relativePath in state.selectedPaths
    val artworkPath = item.movie?.posterFile?.absolutePath ?: item.meta?.thumbnailPath
    val loadArtwork = !blurArtwork || artworkRevealed
    val poster by produceState<ImageBitmap?>(null, artworkPath, loadArtwork) {
        value = if (loadArtwork) withContext(Dispatchers.IO) {
            decodeArtwork(artworkPath, 480, 720)
        } else null
    }
    Column(
        modifier = Modifier
            .alpha(
                if (item.watch?.watchState == WatchState.COMPLETED && !selected) 0.65f else 1f
            )
            .combinedClickable(
            onClick = {
                if (state.selectedPaths.isNotEmpty() && !entry.isDirectory) {
                    viewModel.toggleSelection(item)
                } else if (entry.isDirectory) {
                    viewModel.enter(entry)
                } else {
                    viewModel.play(item)
                }
            },
            onLongClick = {
                if (!entry.isDirectory) {
                    if (blurArtwork && !artworkRevealed) artworkRevealed = true
                    else viewModel.toggleSelection(item)
                }
            },
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = if (entry.isDirectory) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth().aspectRatio(if (compact) 16f / 9f else 2f / 3f),
        ) {
            Box(Modifier.fillMaxSize()) {
                when {
                    blurArtwork && !artworkRevealed && !entry.isDirectory -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center).padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                Icons.Rounded.VisibilityOff,
                                contentDescription = null,
                                modifier = Modifier.size(42.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                "封面已隐藏",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 9.dp),
                            )
                        }
                    }
                    poster != null -> Image(
                        bitmap = poster!!,
                        contentDescription = item.movie?.title ?: entry.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    entry.isDirectory -> Icon(
                        Icons.Rounded.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(62.dp).align(Alignment.Center),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    else -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center).padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                Icons.Rounded.Movie,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                item.movie?.code ?: entry.name.substringBeforeLast('.'),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                }
                if (item.watch?.watchState == WatchState.COMPLETED) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                    ) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = "已看完",
                            modifier = Modifier.padding(6.dp).size(19.dp),
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
                if (!entry.isDirectory &&
                    entry.lastModified >= System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1_000 &&
                    item.watch?.watchState != WatchState.COMPLETED
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                    ) {
                        Text(
                            "NEW",
                            color = MaterialTheme.colorScheme.onTertiary,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                        )
                    }
                }
                if (!entry.isDirectory && !selected && state.selectedPaths.isEmpty()) {
                    IconButton(
                        onClick = { showActions = true },
                        modifier = Modifier.align(Alignment.TopEnd),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
                        ) {
                            Icon(
                                Icons.Rounded.MoreVert,
                                contentDescription = "更多操作",
                                modifier = Modifier.padding(5.dp),
                            )
                        }
                    }
                }
                if (item.duplicateCount > 1) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                    ) {
                        Text(
                            "重复 ${item.duplicateCount}",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                        )
                    }
                }
                if (selected) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                    ) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = "已选择",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(6.dp).size(20.dp),
                        )
                    }
                }
                val actors = item.movie?.actorNames.orEmpty()
                if (actors.isNotEmpty() && !compact) {
                    Row(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy((-6).dp),
                    ) {
                        actors.take(3).forEachIndexed { index, name ->
                            ActorAvatar(
                                name = name,
                                filePath = item.movie?.actorAvatarFiles?.getOrNull(index)?.absolutePath,
                                blurred = blurArtwork && !artworkRevealed,
                            )
                        }
                    }
                }
                if (item.watch?.watchState == WatchState.IN_PROGRESS) {
                    LinearProgressIndicator(
                        progress = { item.watch.progressPercent.coerceIn(0f, 1f) },
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(5.dp),
                    )
                }
                if (blurArtwork && !artworkRevealed && poster != null) {
                    Surface(
                        color = Color(0xB8000000),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.align(Alignment.Center),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.VisibilityOff,
                                null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                "长按查看",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
            }
        }
        Text(
            item.movie?.code ?: entry.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = if (compact) 1 else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 9.dp),
        )
        item.movie?.actorNames?.takeIf { it.isNotEmpty() && !compact }?.let { actors ->
            Text(
                if (actors.size == 1) actors.first() else "${actors.first()} 等${actors.size}人",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
    if (confirmDelete) {
        DeleteDialog(
            entry = entry,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                viewModel.delete(item)
            },
        )
    }
    if (showActions) {
        FileActionsDialog(
            item = item,
            onDismiss = { showActions = false },
            onCompleted = {
                showActions = false
                viewModel.markCompleted(item)
            },
            onUnwatched = {
                showActions = false
                viewModel.resetUnwatched(item)
            },
            onDelete = {
                showActions = false
                confirmDelete = true
            },
            onDetails = {
                showActions = false
                showDetails = true
            },
        )
    }
    if (showDetails) {
        MovieDetailsDialog(
            item = item,
            onDismiss = { showDetails = false },
            onPlay = {
                showDetails = false
                viewModel.play(item)
            },
        )
    }
}

@Composable
private fun ActorAvatar(name: String, filePath: String?, blurred: Boolean = false) {
    val bitmap by produceState<ImageBitmap?>(null, filePath, blurred) {
        value = if (!blurred) withContext(Dispatchers.IO) {
            decodeArtwork(filePath, 128, 128)
        } else null
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        tonalElevation = 3.dp,
        modifier = Modifier.size(34.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (bitmap != null && !blurred) {
                Image(
                    bitmap = bitmap!!,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    name.take(1),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    item: BrowseItem,
    state: BrowseViewModel.UiState,
    viewModel: BrowseViewModel,
) {
    val entry = item.entry
    var confirmDelete by remember { mutableStateOf(false) }
    var showActions by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    val selected = entry.relativePath in state.selectedPaths
    val artworkPath = if (entry.isDirectory) null
    else item.movie?.posterFile?.absolutePath ?: item.meta?.thumbnailPath
    val poster by produceState<ImageBitmap?>(null, artworkPath, state.blurArtwork) {
        value = if (!state.blurArtwork) withContext(Dispatchers.IO) {
            decodeArtwork(artworkPath, 240, 360)
        } else null
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(
                if (item.watch?.watchState == WatchState.COMPLETED && !selected) 0.65f else 1f
            )
            .combinedClickable(
                onClick = {
                    if (state.selectedPaths.isNotEmpty() && !entry.isDirectory) {
                        viewModel.toggleSelection(item)
                    } else if (entry.isDirectory) {
                        viewModel.enter(entry)
                    } else {
                        viewModel.play(item)
                    }
                },
                onLongClick = { if (!entry.isDirectory) viewModel.toggleSelection(item) },
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (entry.isDirectory) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else MaterialTheme.colorScheme.primaryContainer,
                modifier = if (entry.isDirectory) {
                    Modifier.size(52.dp)
                } else {
                    Modifier.size(width = 72.dp, height = 96.dp)
                },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (poster != null && !state.blurArtwork) {
                        Image(
                            bitmap = poster!!,
                            contentDescription = item.movie?.title ?: entry.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            when {
                                entry.isDirectory -> Icons.Rounded.Folder
                                state.blurArtwork -> Icons.Rounded.VisibilityOff
                                else -> Icons.Rounded.PlayArrow
                            },
                            contentDescription = null,
                            tint = if (entry.isDirectory) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    item.movie?.code ?: entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!entry.isDirectory) {
                    item.movie?.title?.takeIf { it.isNotBlank() }?.let { title ->
                        Text(
                            title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    Row(
                        Modifier.padding(top = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            formatSize(entry.size),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        when (item.watch?.watchState) {
                            WatchState.IN_PROGRESS -> Text(
                                "${(item.watch.progressPercent * 100).toInt()}% · 继续观看",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            WatchState.COMPLETED -> Text(
                                "已看完",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            else -> Unit
                        }
                    }
                    if (item.watch?.watchState == WatchState.IN_PROGRESS) {
                        LinearProgressIndicator(
                            progress = { item.watch.progressPercent.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(3.dp),
                        )
                    }
                    if (item.duplicateCount > 1) {
                        Text(
                            "检测到 ${item.duplicateCount} 个同番号版本",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            if (!entry.isDirectory) {
                IconButton(
                    onClick = {
                        if (state.selectedPaths.isNotEmpty()) viewModel.toggleSelection(item)
                        else showActions = true
                    }
                ) {
                    Icon(
                        if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.MoreVert,
                        contentDescription = if (selected) "已选择" else "更多操作",
                    )
                }
            }
        }
    }
    if (confirmDelete) {
        DeleteDialog(
            entry = entry,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                viewModel.delete(item)
            },
        )
    }
    if (showActions) {
        FileActionsDialog(
            item = item,
            onDismiss = { showActions = false },
            onCompleted = {
                showActions = false
                viewModel.markCompleted(item)
            },
            onUnwatched = {
                showActions = false
                viewModel.resetUnwatched(item)
            },
            onDelete = {
                showActions = false
                confirmDelete = true
            },
            onDetails = {
                showActions = false
                showDetails = true
            },
        )
    }
    if (showDetails) {
        MovieDetailsDialog(
            item = item,
            onDismiss = { showDetails = false },
            onPlay = {
                showDetails = false
                viewModel.play(item)
            },
        )
    }
}

@Composable
private fun FileActionsDialog(
    item: BrowseItem,
    onDismiss: () -> Unit,
    onCompleted: () -> Unit,
    onUnwatched: () -> Unit,
    onDelete: () -> Unit,
    onDetails: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(item.entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onCompleted, modifier = Modifier.fillMaxWidth()) {
                    Text("标记为已看完")
                }
                TextButton(onClick = onUnwatched, modifier = Modifier.fillMaxWidth()) {
                    Text("重置为未看")
                }
                TextButton(onClick = onDetails, modifier = Modifier.fillMaxWidth()) {
                    Text("查看影片详情")
                }
                TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                    Text("移入回收站", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun MovieDetailsDialog(
    item: BrowseItem,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
) {
    val movie = item.movie
    val artworkPath = movie?.posterFile?.absolutePath ?: item.meta?.thumbnailPath
    val poster by produceState<ImageBitmap?>(null, artworkPath) {
        value = withContext(Dispatchers.IO) {
            decodeArtwork(artworkPath, 720, 1080)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(movie?.code ?: item.entry.name.substringBeforeLast('.'))
                movie?.title?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (poster != null) {
                    Image(
                        poster!!,
                        contentDescription = movie?.title ?: item.entry.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.65f)
                            .clip(RoundedCornerShape(16.dp)),
                    )
                }
                DetailLine("演员", movie?.actorNames?.joinToString("、"))
                DetailLine("发行日期", movie?.releaseDate)
                DetailLine(
                    "时长",
                    movie?.runtimeMin?.let { "$it 分钟" } ?: item.meta?.durationMs?.let {
                        val total = it / 1_000
                        "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60)
                    },
                )
                DetailLine("片商", movie?.studio)
                DetailLine("厂牌", movie?.label)
                DetailLine("系列", movie?.series)
                DetailLine("类别", movie?.genres?.joinToString("、"))
                HorizontalDivider()
                DetailLine("文件", item.entry.name)
                DetailLine("大小", formatSize(item.entry.size))
                DetailLine(
                    "修改时间",
                    item.entry.lastModified.takeIf { it > 0L }?.let {
                        DateFormat.getDateTimeInstance().format(Date(it))
                    },
                )
                DetailLine(
                    "分辨率",
                    item.meta?.let { meta ->
                        if (meta.width != null && meta.height != null) {
                            "${meta.width}×${meta.height}"
                        } else null
                    },
                )
                if (item.duplicateVariants.size > 1) {
                    HorizontalDivider()
                    Text(
                        "同番号版本（${item.duplicateVariants.size}）",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    item.duplicateVariants.forEach { variant ->
                        val resolution = if (variant.width != null && variant.height != null) {
                            " · ${variant.width}×${variant.height}"
                        } else ""
                        Text(
                            "${variant.path}\n${formatSize(variant.size)}$resolution",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item.watch?.let {
                    DetailLine(
                        "观看进度",
                        "${(it.progressPercent * 100).toInt()}% · ${it.playCount} 次",
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        confirmButton = {
            Button(onClick = onPlay) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                Text("播放", Modifier.padding(start = 6.dp))
            }
        },
    )
}

@Composable
private fun DetailLine(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(68.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun DeleteDialog(entry: SmbEntry, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
        title = { Text("移入回收站？") },
        text = {
            Text(
                "${entry.name}\n\n${formatSize(entry.size)}，匹配字幕会一并移入。" +
                    "视频和字幕可还原；观看进度、收藏、标签与书签会立即删除，且不会随文件还原。"
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = { Button(onClick = onConfirm) { Text("移入回收站") } },
    )
}

@Composable
private fun EmptyLibrary(
    state: BrowseViewModel.UiState,
    viewModel: BrowseViewModel,
) {
    val content: Triple<String, String, Pair<String, () -> Unit>> = when {
        state.query.isNotBlank() -> Triple(
            "没有搜索结果",
            "换个文件名、番号或演员名试试",
            "清除搜索" to { viewModel.setQuery("") },
        )
        state.watchFilter == WatchFilter.FAVORITES -> Triple(
            "还没有收藏",
            "播放时点亮星标，喜欢的影片会集中在这里",
            "查看全部" to { viewModel.setWatchFilter(WatchFilter.ALL) },
        )
        state.watchFilter != WatchFilter.ALL ||
            state.recentOnly ||
            state.selectedTagId != null ||
            state.actorFilter != null ||
            state.studioFilter != null ||
            state.seriesFilter != null ||
            state.yearFilter != null -> Triple(
            "没有符合筛选的影片",
            "当前目录有内容，只是没有匹配这些条件",
            "清除筛选" to {
                viewModel.setWatchFilter(WatchFilter.ALL)
                if (state.recentOnly) viewModel.toggleRecentOnly()
                viewModel.setTagFilter(null)
                viewModel.clearMetadataFilters()
            },
        )
        state.showAllFiles -> Triple(
            "这个文件夹是空的",
            "返回上级目录，或下拉刷新后再试",
            "重新加载" to { viewModel.refresh() },
        )
        else -> Triple(
            "没有找到视频文件",
            "可以显示全部文件，确认是否选对了目录",
            "显示全部文件" to { viewModel.setShowAllFiles(true) },
        )
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 28.dp),
        ) {
            Icon(
                Icons.Rounded.Folder,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            )
            Text(
                content.first,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 18.dp),
            )
            Text(
                content.second,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            FilledTonalButton(
                onClick = content.third.second,
                modifier = Modifier.padding(top = 18.dp),
            ) {
                Text(content.third.first)
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
