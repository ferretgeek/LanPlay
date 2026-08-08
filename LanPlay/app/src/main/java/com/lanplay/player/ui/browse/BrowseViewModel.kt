package com.lanplay.player.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanplay.player.data.SavedServer
import com.lanplay.player.core.log.Metric
import com.lanplay.player.data.MetadataRepository
import com.lanplay.player.data.MovieDisplay
import com.lanplay.player.data.ServerRepository
import com.lanplay.player.data.TrashRepository
import com.lanplay.player.data.WatchRepository
import com.lanplay.player.data.db.BrowseStateDao
import com.lanplay.player.data.db.BrowseStateEntity
import com.lanplay.player.data.db.DirectoryEntryCacheDao
import com.lanplay.player.data.db.DirectoryEntryCacheEntity
import com.lanplay.player.data.db.MediaMetaDao
import com.lanplay.player.data.db.MediaMetaEntity
import com.lanplay.player.data.db.WatchRecordEntity
import com.lanplay.player.data.db.TagDao
import com.lanplay.player.data.db.TagEntity
import com.lanplay.player.data.prefs.SettingsRepository
import com.lanplay.player.player.PlaybackController
import com.lanplay.player.smb.AuthMode
import com.lanplay.player.smb.DiscoveredHost
import com.lanplay.player.smb.LanScanner
import com.lanplay.player.smb.SmbConnectionManager
import com.lanplay.player.smb.SmbEntry
import com.lanplay.player.smb.SmbException
import com.lanplay.player.smb.SmbFileRepository
import com.lanplay.player.smb.SmbShareDiscovery
import com.lanplay.player.smb.SmbTarget
import com.lanplay.player.smb.VIDEO_EXTENSIONS
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SortField { NAME, SIZE, LAST_MODIFIED, DURATION, LAST_WATCHED, RATING }
enum class ViewMode { LIST, GRID, GALLERY }
enum class WatchFilter { ALL, UNWATCHED, IN_PROGRESS, COMPLETED, FAVORITES }

data class BrowseItem(
    val entry: SmbEntry,
    val watch: WatchRecordEntity? = null,
    val durationMs: Long? = null,
    val meta: MediaMetaEntity? = null,
    val movie: MovieDisplay? = null,
    val duplicateCount: Int = 0,
    val duplicateVariants: List<DuplicateVariant> = emptyList(),
)

data class DuplicateVariant(
    val path: String,
    val size: Long,
    val width: Int?,
    val height: Int?,
)

data class BatchDeleteFailure(
    val path: String,
    val fileName: String,
    val reason: String,
)

data class BatchDeleteProgress(
    val completed: Int,
    val total: Int,
    val currentFileName: String? = null,
    val failures: List<BatchDeleteFailure> = emptyList(),
    val warnings: List<String> = emptyList(),
    val running: Boolean = true,
)

@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val servers: ServerRepository,
    private val files: SmbFileRepository,
    private val playback: PlaybackController,
    private val settings: SettingsRepository,
    private val watchRepository: WatchRepository,
    private val browseStateDao: BrowseStateDao,
    private val directoryCacheDao: DirectoryEntryCacheDao,
    private val mediaMetaDao: MediaMetaDao,
    private val trashRepository: TrashRepository,
    private val scanner: LanScanner,
    private val connections: SmbConnectionManager,
    private val shareDiscovery: SmbShareDiscovery,
    private val metadataRepository: MetadataRepository,
    private val tagDao: TagDao,
) : ViewModel() {

    data class UiState(
        val configured: Boolean = true,
        val server: SavedServer? = null,
        val savedServers: List<SavedServer> = emptyList(),
        val loading: Boolean = false,
        val path: String = "",
        val items: List<BrowseItem> = emptyList(),
        val showAllFiles: Boolean = false,
        val sortField: SortField = SortField.LAST_MODIFIED,
        val sortAscending: Boolean = false,
        val viewMode: ViewMode = ViewMode.LIST,
        val query: String = "",
        val watchFilter: WatchFilter = WatchFilter.ALL,
        val scrollIndex: Int = 0,
        val scrollOffset: Int = 0,
        val scanning: Boolean = false,
        val scanDone: Int = 0,
        val scanTotal: Int = 0,
        val scanCompleted: Boolean = false,
        val discovered: List<DiscoveredHost> = emptyList(),
        val error: String? = null,
        val notice: String? = null,
        val blurArtwork: Boolean = false,
        val editingServer: SavedServer? = null,
        val discoveringShares: Boolean = false,
        val availableShares: List<String> = emptyList(),
        val browsingSetupFolders: Boolean = false,
        val setupFolders: List<SmbEntry> = emptyList(),
        val setupShare: String = "",
        val setupFolderPath: String = "",
        val selectedPaths: Set<String> = emptySet(),
        val batchDelete: BatchDeleteProgress? = null,
        val recentOnly: Boolean = false,
        val tags: List<TagEntity> = emptyList(),
        val selectedTagId: Long? = null,
        val mergedDirectories: Set<String> = emptySet(),
        val mergedMode: Boolean = false,
        val availableStudios: List<String> = emptyList(),
        val availableSeries: List<String> = emptyList(),
        val availableYears: List<String> = emptyList(),
        val studioFilter: String? = null,
        val seriesFilter: String? = null,
        val yearFilter: String? = null,
        val availableActors: List<String> = emptyList(),
        val actorFilter: String? = null,
        val directoryVideoCount: Int = 0,
        val directoryTotalBytes: Long = 0L,
        val directoryCompletedCount: Int = 0,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var rawEntries: List<SmbEntry> = emptyList()
    private var loadJob: Job? = null
    private var loadGeneration = 0L
    private var rebuildGeneration = 0L
    private var scanJob: Job? = null
    private var probeJob: Job? = null
    private var queryJob: Job? = null
    private var scrollSaveJob: Job? = null

    init {
        viewModelScope.launch {
            val current = servers.current()
            if (current == null) {
                _state.value = UiState(configured = false)
            } else {
                _state.value = _state.value.copy(savedServers = servers.listAll())
                load(current.defaultPath, current)
            }
        }
        viewModelScope.launch {
            settings.privacySettings.collect { privacy ->
                _state.value = _state.value.copy(blurArtwork = privacy.blurArtwork)
            }
        }
        viewModelScope.launch {
            tagDao.observeAll().collect { tags ->
                _state.value = _state.value.copy(tags = tags)
                rebuildItems()
            }
        }
    }

    fun refresh() = viewModelScope.launch {
        val server = _state.value.server ?: servers.current() ?: return@launch
        if (_state.value.mergedMode) loadMerged(server) else {
            load(_state.value.path, server, forceProbe = true)
        }
    }

    fun switchServer(id: Long) {
        viewModelScope.launch {
            val server = servers.getById(id) ?: return@launch
            servers.activate(id)
            _state.value = _state.value.copy(savedServers = servers.listAll())
            load(server.defaultPath, server)
        }
    }

    fun addServer() {
        _state.value = _state.value.copy(
            configured = false,
            editingServer = null,
            availableShares = emptyList(),
            setupFolders = emptyList(),
            setupShare = "",
            setupFolderPath = "",
            error = null,
        )
    }

    fun editServer(id: Long) {
        viewModelScope.launch {
            val server = servers.getById(id) ?: return@launch
            _state.value = _state.value.copy(
                configured = false,
                editingServer = server,
                availableShares = emptyList(),
                setupFolders = emptyList(),
                setupShare = server.target.share,
                setupFolderPath = server.defaultPath,
                error = null,
            )
        }
    }

    fun cancelConfiguration() {
        if (_state.value.savedServers.isNotEmpty()) {
            _state.value = _state.value.copy(
                configured = true,
                editingServer = null,
                availableShares = emptyList(),
                setupFolders = emptyList(),
                setupShare = "",
                setupFolderPath = "",
                error = null,
            )
        }
    }

    fun moveServer(id: Long, delta: Int) {
        viewModelScope.launch {
            servers.move(id, delta)
            _state.value = _state.value.copy(savedServers = servers.listAll())
        }
    }

    fun deleteServer(id: Long) {
        viewModelScope.launch {
            try {
                servers.delete(id)
                val remaining = servers.listAll()
                if (remaining.isEmpty()) {
                    _state.value = UiState(configured = false)
                } else {
                    servers.activate(remaining.first().id)
                    _state.value = _state.value.copy(savedServers = remaining)
                    load(remaining.first().defaultPath, remaining.first())
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.value = _state.value.copy(error = t.message ?: "无法删除服务器")
            }
        }
    }

    fun enter(entry: SmbEntry) {
        if (!entry.isDirectory || _state.value.mergedMode) return
        viewModelScope.launch {
            val server = _state.value.server ?: return@launch
            load(entry.relativePath, server)
        }
    }

    /** 返回上一级；已在共享根返回 false，让 Activity 处理退出。 */
    fun up(): Boolean {
        if (_state.value.mergedMode) {
            viewModelScope.launch {
                val server = _state.value.server ?: return@launch
                load(_state.value.path, server)
            }
            return true
        }
        val path = _state.value.path
        if (path.isEmpty()) return false
        val parent = path.substringBeforeLast('/', "")
        viewModelScope.launch {
            val server = _state.value.server ?: return@launch
            load(parent, server)
        }
        return true
    }

    /** B-07：面包屑可直接跳回任意父级，不必逐级连按返回。 */
    fun navigateTo(path: String) {
        viewModelScope.launch {
            val server = _state.value.server ?: return@launch
            load(path.trim('/'), server)
        }
    }

    fun play(item: BrowseItem) {
        if (item.entry.isDirectory || item.entry.extension !in VIDEO_EXTENSIONS) {
            _state.value = _state.value.copy(error = "这个文件不是支持的视频，仅供浏览查看")
            return
        }
        val serverId = _state.value.server?.id ?: return
        viewModelScope.launch {
            try {
                playback.open(item.entry.relativePath, serverId = serverId)
            } catch (e: SmbException) {
                _state.value = _state.value.copy(error = e.message)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(error = t.message ?: "无法播放这个视频")
            }
        }
    }

    fun setShowAllFiles(value: Boolean) {
        viewModelScope.launch {
            settings.setShowAllFiles(value)
            _state.value = _state.value.copy(showAllFiles = value)
            rebuildItems()
        }
    }

    fun setSort(field: SortField, ascending: Boolean = _state.value.sortAscending) {
        _state.value = _state.value.copy(sortField = field, sortAscending = ascending)
        viewModelScope.launch {
            persistBrowseState()
            rebuildItems()
        }
    }

    fun toggleSortDirection() = setSort(_state.value.sortField, !_state.value.sortAscending)

    fun toggleViewMode() {
        _state.value = _state.value.copy(
            viewMode = when (_state.value.viewMode) {
                ViewMode.LIST -> ViewMode.GRID
                ViewMode.GRID -> ViewMode.GALLERY
                ViewMode.GALLERY -> ViewMode.LIST
            },
            scrollIndex = 0,
            scrollOffset = 0,
        )
        viewModelScope.launch { persistBrowseState() }
    }

    fun setQuery(value: String) {
        _state.value = _state.value.copy(query = value)
        queryJob?.cancel()
        queryJob = viewModelScope.launch {
            delay(150)
            rebuildItems()
        }
    }

    fun setWatchFilter(value: WatchFilter) {
        _state.value = _state.value.copy(
            watchFilter = value,
            scrollIndex = 0,
            scrollOffset = 0,
        )
        viewModelScope.launch { rebuildItems() }
    }

    fun toggleRecentOnly() {
        _state.value = _state.value.copy(
            recentOnly = !_state.value.recentOnly,
            scrollIndex = 0,
            scrollOffset = 0,
        )
        viewModelScope.launch { rebuildItems() }
    }

    fun setTagFilter(tagId: Long?) {
        _state.value = _state.value.copy(
            selectedTagId = tagId,
            scrollIndex = 0,
            scrollOffset = 0,
        )
        viewModelScope.launch { rebuildItems() }
    }

    fun setStudioFilter(studio: String?) {
        _state.value = _state.value.copy(
            studioFilter = studio,
            scrollIndex = 0,
            scrollOffset = 0,
        )
        viewModelScope.launch { rebuildItems() }
    }

    fun setSeriesFilter(series: String?) {
        _state.value = _state.value.copy(
            seriesFilter = series,
            scrollIndex = 0,
            scrollOffset = 0,
        )
        viewModelScope.launch { rebuildItems() }
    }

    fun setYearFilter(year: String?) {
        _state.value = _state.value.copy(
            yearFilter = year,
            scrollIndex = 0,
            scrollOffset = 0,
        )
        viewModelScope.launch { rebuildItems() }
    }

    fun setActorFilter(actor: String?) {
        _state.value = _state.value.copy(
            actorFilter = actor,
            scrollIndex = 0,
            scrollOffset = 0,
        )
        viewModelScope.launch { rebuildItems() }
    }

    fun clearMetadataFilters() {
        _state.value = _state.value.copy(
            studioFilter = null,
            seriesFilter = null,
            yearFilter = null,
            actorFilter = null,
            scrollIndex = 0,
            scrollOffset = 0,
        )
        viewModelScope.launch { rebuildItems() }
    }

    fun toggleCurrentMergedDirectory() {
        val state = _state.value
        val server = state.server ?: return
        val path = state.path
        val enabled = path !in state.mergedDirectories
        viewModelScope.launch {
            settings.setMergedDirectory(server.id, path, enabled)
            val directories = settings.mergedDirectories(server.id)
            _state.value = _state.value.copy(
                mergedDirectories = directories,
                notice = if (enabled) "已把当前目录加入合并浏览" else "已从合并浏览移除",
            )
        }
    }

    fun openMerged() {
        viewModelScope.launch {
            val server = _state.value.server ?: return@launch
            if (_state.value.mergedDirectories.size < 2) {
                _state.value = _state.value.copy(notice = "请先加入至少两个目录")
                return@launch
            }
            loadMerged(server)
        }
    }

    fun playRandomUnwatched() {
        val choices = _state.value.items.filter {
            !it.entry.isDirectory &&
                (it.watch == null ||
                    it.watch.watchState == com.lanplay.player.data.db.WatchState.UNWATCHED)
        }
        choices.randomOrNull()?.let(::play) ?: run {
            _state.value = _state.value.copy(notice = "当前结果里没有未看视频")
        }
    }

    fun markCompleted(item: BrowseItem) {
        viewModelScope.launch {
            val server = _state.value.server ?: return@launch
            watchRepository.mark(
                server.id,
                server.target,
                item.entry,
                com.lanplay.player.data.db.WatchState.COMPLETED,
            )
            rebuildItems()
        }
    }

    fun resetUnwatched(item: BrowseItem) {
        viewModelScope.launch {
            val server = _state.value.server ?: return@launch
            watchRepository.reset(server.id, item.entry.relativePath)
            rebuildItems()
        }
    }

    fun saveScroll(index: Int, offset: Int) {
        if (_state.value.scrollIndex == index && _state.value.scrollOffset == offset) return
        val snapshot = _state.value.copy(scrollIndex = index, scrollOffset = offset)
        _state.value = snapshot
        scrollSaveJob?.cancel()
        scrollSaveJob = viewModelScope.launch {
            delay(300)
            persistBrowseState(snapshot)
        }
    }

    fun delete(item: BrowseItem) {
        if (item.entry.isDirectory || item.entry.extension !in VIDEO_EXTENSIONS) {
            _state.value = _state.value.copy(error = "只能把视频及其关联字幕移入回收站")
            return
        }
        viewModelScope.launch {
            val snapshot = _state.value
            val server = snapshot.server ?: return@launch
            val startPath = snapshot.path
            val startMerged = snapshot.mergedMode
            runCatching {
                trashRepository.moveToTrash(server.id, server.target, item.entry.relativePath)
            }.onSuccess { result ->
                val current = _state.value
                if (
                    current.server?.id == server.id &&
                    current.path == startPath &&
                    current.mergedMode == startMerged
                ) {
                    if (result.videoMoved) {
                        if (startMerged) loadMerged(server) else load(startPath, server)
                        _state.value = _state.value.copy(
                            notice = if (result.isPartial) {
                                "视频已移入回收站；${result.failures.size} 个关联字幕未能移动"
                            } else {
                                "视频和字幕已移入回收站；个人观看数据已删除"
                            },
                            error = result.primaryFailure?.reason,
                        )
                    } else {
                        _state.value = current.copy(
                            error = result.primaryFailure?.reason ?: "视频未能移入回收站",
                        )
                    }
                }
            }.onFailure {
                if (_state.value.server?.id == server.id) {
                    _state.value = _state.value.copy(error = it.message ?: "删除失败")
                }
            }
        }
    }

    fun toggleSelection(item: BrowseItem) {
        if (item.entry.isDirectory || item.entry.extension !in VIDEO_EXTENSIONS) return
        val path = item.entry.relativePath
        val selected = _state.value.selectedPaths.toMutableSet()
        if (!selected.add(path)) selected.remove(path)
        _state.value = _state.value.copy(selectedPaths = selected)
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selectedPaths = emptySet(), batchDelete = null)
    }

    fun selectAllVisible() {
        _state.value = _state.value.copy(
            selectedPaths = _state.value.items
                .filter {
                    !it.entry.isDirectory && it.entry.extension in VIDEO_EXTENSIONS
                }
                .mapTo(linkedSetOf()) { it.entry.relativePath }
        )
    }

    fun invertSelection() {
        val visible = _state.value.items
            .filter {
                !it.entry.isDirectory && it.entry.extension in VIDEO_EXTENSIONS
            }
            .mapTo(linkedSetOf()) { it.entry.relativePath }
        _state.value = _state.value.copy(
            selectedPaths = visible - _state.value.selectedPaths
        )
    }

    fun deleteSelected() {
        val selected = _state.value.selectedPaths
        if (selected.isEmpty()) return
        viewModelScope.launch {
            val snapshot = _state.value
            val server = snapshot.server ?: return@launch
            val startPath = snapshot.path
            val startMerged = snapshot.mergedMode
            val targets = snapshot.items.filter { it.entry.relativePath in selected }
            _state.value = _state.value.copy(
                loading = true,
                batchDelete = BatchDeleteProgress(completed = 0, total = targets.size),
                error = null,
            )
            var deleted = 0
            val failures = mutableListOf<BatchDeleteFailure>()
            val warnings = mutableListOf<String>()
            targets.forEachIndexed { index, item ->
                currentCoroutineContext().ensureActive()
                _state.value = _state.value.copy(
                    batchDelete = BatchDeleteProgress(
                        completed = index,
                        total = targets.size,
                        currentFileName = item.entry.name,
                        failures = failures.toList(),
                        warnings = warnings.toList(),
                    )
                )
                try {
                    val result = trashRepository.moveToTrash(
                        server.id,
                        server.target,
                        item.entry.relativePath,
                    )
                    if (result.videoMoved) {
                        deleted++
                        if (result.isPartial) {
                            warnings += "${item.entry.name}：${result.failures.size} 个关联字幕未移动"
                        }
                    } else {
                        failures += BatchDeleteFailure(
                            path = item.entry.relativePath,
                            fileName = item.entry.name,
                            reason = result.primaryFailure?.reason ?: "视频未能移入回收站",
                        )
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    failures += BatchDeleteFailure(
                        path = item.entry.relativePath,
                        fileName = item.entry.name,
                        reason = t.message ?: "处理失败",
                    )
                }
                _state.value = _state.value.copy(
                    batchDelete = BatchDeleteProgress(
                        completed = index + 1,
                        total = targets.size,
                        failures = failures.toList(),
                        warnings = warnings.toList(),
                    )
                )
            }
            val current = _state.value
            if (
                current.server?.id != server.id ||
                current.path != startPath ||
                current.mergedMode != startMerged
            ) return@launch
            if (startMerged) loadMerged(server) else load(startPath, server)
            _state.value = _state.value.copy(
                selectedPaths = failures.mapTo(linkedSetOf()) { it.path },
                batchDelete = BatchDeleteProgress(
                    completed = targets.size,
                    total = targets.size,
                    failures = failures,
                    warnings = warnings,
                    running = false,
                ),
                notice = when {
                    failures.isEmpty() && warnings.isEmpty() -> "已将 $deleted 个视频移入回收站"
                    failures.isEmpty() -> "已移入 $deleted 个视频，${warnings.size} 项有关联字幕未移动"
                    deleted == 0 -> null
                    else -> "已移入 $deleted 个，另有 ${failures.size} 个处理失败"
                },
                error = if (failures.isNotEmpty()) failures.first().let {
                    "${it.fileName}：${it.reason}"
                }
                else _state.value.error,
            )
        }
    }

    fun scan() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                scanning = true,
                scanDone = 0,
                scanTotal = 0,
                scanCompleted = false,
                discovered = emptyList(),
                error = null,
            )
            val result = runCatching {
                scanner.scan { done, total, found ->
                    _state.value = _state.value.copy(
                        scanDone = done,
                        scanTotal = total,
                        discovered = found,
                    )
                }
            }
            result.onSuccess {
                _state.value = _state.value.copy(
                    scanning = false,
                    scanCompleted = true,
                    discovered = it,
                )
            }.onFailure {
                if (it is CancellationException) throw it
                _state.value = _state.value.copy(scanning = false, error = "扫描失败，请手动填写地址")
            }
        }
    }

    fun discoverShares(host: String, portText: String, username: String, password: String) {
        if (_state.value.discoveringShares) return
        viewModelScope.launch {
            if (host.isBlank()) {
                _state.value = _state.value.copy(error = "请先填写或选择电脑地址")
                return@launch
            }
            _state.value = _state.value.copy(
                discoveringShares = true,
                availableShares = emptyList(),
                setupFolders = emptyList(),
                setupShare = "",
                setupFolderPath = "",
                error = null,
            )
            runCatching {
                val port = portText.toIntOrNull()
                    ?.takeIf { it in 1..65535 }
                    ?: throw IllegalArgumentException("端口必须为 1 到 65535")
                shareDiscovery.list(
                    host = host.trim(),
                    username = username.trim(),
                    password = password,
                    port = port,
                )
            }.onSuccess { shares ->
                _state.value = _state.value.copy(
                    discoveringShares = false,
                    availableShares = shares,
                    error = if (shares.isEmpty()) {
                        "没有发现可访问的共享文件夹，请检查这台电脑的共享权限"
                    } else null,
                )
            }.onFailure {
                _state.value = _state.value.copy(
                    discoveringShares = false,
                    error = "无法读取共享文件夹，请检查用户名、密码和电脑共享权限",
                )
            }
        }
    }

    fun browseSetupFolder(
        host: String,
        portText: String,
        share: String,
        username: String,
        password: String,
        relativePath: String,
    ) {
        if (_state.value.browsingSetupFolders) return
        viewModelScope.launch {
            val cleanHost = host.trim()
            val cleanShare = share.trim().trim('\\', '/')
            val cleanPath = relativePath.trim().trim('\\', '/').replace('\\', '/')
            val port = portText.trim().toIntOrNull()
            if (cleanHost.isEmpty() || cleanShare.isEmpty()) {
                _state.value = _state.value.copy(error = "请先选择电脑和共享文件夹")
                return@launch
            }
            if (port == null || port !in 1..65535) {
                _state.value = _state.value.copy(error = "端口应为 1～65535 的数字")
                return@launch
            }
            val target = SmbTarget(
                host = cleanHost,
                port = port,
                share = cleanShare,
                username = username.trim(),
                password = password,
                authMode = if (username.isBlank()) AuthMode.GUEST else AuthMode.ACCOUNT,
            )
            _state.value = _state.value.copy(
                browsingSetupFolders = true,
                setupShare = cleanShare,
                setupFolderPath = cleanPath,
                setupFolders = emptyList(),
                error = null,
            )
            runCatching {
                files.list(target, cleanPath)
                    .filter { it.isDirectory }
            }.onSuccess { folders ->
                _state.value = _state.value.copy(
                    browsingSetupFolders = false,
                    setupFolders = folders,
                    setupShare = cleanShare,
                    setupFolderPath = cleanPath,
                )
            }.onFailure {
                _state.value = _state.value.copy(
                    browsingSetupFolders = false,
                    error = "无法打开这个共享文件夹，请检查访问权限",
                )
            }
        }
    }

    fun saveServer(
        host: String,
        portText: String,
        share: String,
        username: String,
        password: String,
        displayName: String,
        defaultPath: String,
    ) {
        viewModelScope.launch {
            val cleanHost = host.trim()
            val cleanShare = share.trim().trim('\\', '/')
            val port = portText.trim().toIntOrNull()
            if (cleanHost.isEmpty() || cleanShare.isEmpty()) {
                _state.value = _state.value.copy(error = "请选择电脑上的共享文件夹路径")
                return@launch
            }
            if (port == null || port !in 1..65535) {
                _state.value = _state.value.copy(error = "端口应为 1～65535 的数字，SMB 通常使用 445")
                return@launch
            }
            val target = SmbTarget(
                host = cleanHost,
                port = port,
                share = cleanShare,
                username = username.trim(),
                password = password,
                authMode = if (username.isBlank()) AuthMode.GUEST else AuthMode.ACCOUNT,
            )
            _state.value = _state.value.copy(loading = true, error = null)
            runCatching {
                connections.share(target, SmbConnectionManager.Channel.AUX)
                val id = servers.save(
                    target,
                    displayName = displayName.trim().ifEmpty { cleanHost },
                    defaultPath = defaultPath.trim().trim('/'),
                    editingId = _state.value.editingServer?.id,
                )
                servers.getById(id) ?: error("服务器保存失败")
            }.onSuccess { server ->
                servers.activate(server.id)
                _state.value = _state.value.copy(
                    configured = true,
                    server = server,
                    savedServers = servers.listAll(),
                    editingServer = null,
                    availableShares = emptyList(),
                    setupFolders = emptyList(),
                    setupShare = "",
                    setupFolderPath = "",
                )
                load(server.defaultPath, server)
            }.onFailure {
                _state.value = _state.value.copy(
                    loading = false,
                    error = it.message ?: "连接失败，请检查电脑地址、共享文件夹和凭据",
                )
            }
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(error = null, notice = null)
    }

    private suspend fun load(
        path: String,
        server: SavedServer,
        forceProbe: Boolean = false,
    ) {
        val owner = currentCoroutineContext()[Job]
        if (loadJob !== owner) {
            loadJob?.cancel()
            loadJob = owner
        }
        val requestId = ++loadGeneration
        val cacheStarted = System.nanoTime()
        val cached = directoryCacheDao.list(server.id, path)
        if (requestId != loadGeneration) return
        val cachedEntries = cached.map {
            SmbEntry(
                name = it.name,
                relativePath = it.relativePath,
                isDirectory = it.isDirectory,
                size = it.size,
                lastModified = it.lastModified,
            )
        }
        rawEntries = cachedEntries
        val quickItems = cachedEntries
            .filter {
                it.isDirectory || _state.value.showAllFiles || it.extension in VIDEO_EXTENSIONS
            }
            .map { BrowseItem(it) }
        _state.value = _state.value.copy(
            configured = true,
            server = server,
            loading = true,
            path = path,
            selectedPaths = emptySet(),
            error = null,
            notice = null,
            items = quickItems,
            mergedDirectories = settings.mergedDirectories(server.id),
            mergedMode = false,
        )
        if (cached.isNotEmpty()) {
            Metric.emit(
                "browse_cache",
                "path" to path,
                "items" to cached.size,
                "render_ms" to (System.nanoTime() - cacheStarted) / 1_000_000,
            )
        }

        // 排序、滚动位置、观看状态和海报紧接着补齐；这些不阻塞缓存内容首屏。
        val stored = browseStateDao.get(server.id, path)
        val playerSettings = settings.currentPlayerSettings()
        if (requestId != loadGeneration) return
        _state.value = _state.value.copy(
            showAllFiles = playerSettings.showAllFiles,
            sortField = stored?.sortField?.let {
                runCatching { SortField.valueOf(it) }.getOrDefault(SortField.LAST_MODIFIED)
            } ?: SortField.LAST_MODIFIED,
            sortAscending = stored?.sortAscending ?: false,
            viewMode = stored?.viewMode?.let {
                runCatching { ViewMode.valueOf(it) }.getOrDefault(ViewMode.LIST)
            } ?: ViewMode.LIST,
            scrollIndex = stored?.scrollIndex ?: 0,
            scrollOffset = stored?.scrollOffset ?: 0,
        )
        if (cached.isNotEmpty()) {
            rebuildItems()
        }
        try {
            val listedEntries = files.list(server.target, path)
            if (requestId != loadGeneration) return
            directoryCacheDao.replace(
                server.id,
                path,
                listedEntries.map {
                    DirectoryEntryCacheEntity(
                        serverId = server.id,
                        parentPath = path,
                        relativePath = it.relativePath,
                        name = it.name,
                        isDirectory = it.isDirectory,
                        size = it.size,
                        lastModified = it.lastModified,
                    )
                },
            )
            if (requestId != loadGeneration) return
            rawEntries = listedEntries
            _state.value = _state.value.copy(loading = false)
            rebuildItems()
            val metadataChanged = try {
                metadataRepository.refresh(server)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                false
            }
            if (requestId != loadGeneration) return
            if (metadataChanged) rebuildItems()
            val snapshot = listedEntries
            probeJob?.cancel()
            probeJob = viewModelScope.launch {
                if (metadataRepository.probeDirectory(server, snapshot, forceProbe) > 0 &&
                    _state.value.server?.id == server.id &&
                    _state.value.path == path
                ) {
                    rebuildItems()
                }
            }
        } catch (e: SmbException) {
            if (requestId == loadGeneration) {
                _state.value = _state.value.copy(loading = false, error = e.message)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (requestId == loadGeneration) {
                _state.value = _state.value.copy(loading = false, error = "读取目录失败")
            }
        }
    }

    private suspend fun loadMerged(server: SavedServer) {
        val owner = currentCoroutineContext()[Job]
        if (loadJob !== owner) {
            loadJob?.cancel()
            loadJob = owner
        }
        val requestId = ++loadGeneration
        val directories = settings.mergedDirectories(server.id)
        if (directories.size < 2) {
            _state.value = _state.value.copy(notice = "合并浏览至少需要两个目录")
            return
        }
        _state.value = _state.value.copy(
            loading = true,
            mergedMode = true,
            selectedPaths = emptySet(),
            error = null,
        )
        val result = withContext(Dispatchers.IO) {
            runCatching {
                directories.flatMap { directory ->
                    files.list(server.target, directory)
                        .filter { !it.isDirectory && it.extension in VIDEO_EXTENSIONS }
                }.distinctBy { it.relativePath }
            }
        }
        if (requestId != loadGeneration) return
        result.onSuccess { entries ->
            rawEntries = entries
            _state.value = _state.value.copy(loading = false, mergedMode = true)
            rebuildItems()
            runCatching { metadataRepository.refresh(server) }
            rebuildItems()
        }.onFailure {
            if (it is CancellationException) throw it
            _state.value = _state.value.copy(
                loading = false,
                mergedMode = true,
                error = it.message ?: "合并目录读取失败",
            )
        }
    }

    private suspend fun rebuildItems() {
        try {
            rebuildItemsUnsafe()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _state.value = _state.value.copy(error = "刷新本地资料失败，请重试")
            Metric.error("BROWSE_REBUILD", t.message ?: "浏览列表重建失败")
        }
    }

    private suspend fun rebuildItemsUnsafe() {
        val requestId = ++rebuildGeneration
        val state = _state.value
        val server = state.server ?: return
        val entries = rawEntries
        val visible = entries.filter {
            it.isDirectory || state.showAllFiles || it.extension in VIDEO_EXTENSIONS
        }
        val filePaths = visible
            .filter { !it.isDirectory && it.extension in VIDEO_EXTENSIONS }
            .map { it.relativePath }
        val watches = watchRepository.getMany(server.id, filePaths)
        val meta = filePaths.chunked(SQLITE_IN_BATCH_SIZE)
            .flatMap { mediaMetaDao.getMany(server.id, it) }
            .associateBy { it.fullPath }
        val movies = metadataRepository.displays(server, filePaths)
        val taggedRecordIds = state.selectedTagId?.let {
            tagDao.recordIdsForTag(it).toSet()
        }
        val baseItems = visible.map {
            BrowseItem(
                it,
                watches[it.relativePath],
                meta[it.relativePath]?.durationMs,
                meta[it.relativePath],
                movies[it.relativePath],
            )
        }
        val videoItems = baseItems.filter {
            !it.entry.isDirectory && it.entry.extension in VIDEO_EXTENSIONS
        }
        val duplicateGroups = baseItems
            .filterNot { it.entry.isDirectory }
            .mapNotNull { item -> item.movie?.code?.trim()?.uppercase()?.let { it to item } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
        val duplicateCounts = duplicateGroups.flatMap { (_, duplicates) ->
            duplicates.map { it.entry.relativePath to duplicates.size }
        }.toMap()
        val duplicateVariants = duplicateGroups.flatMap { (_, duplicates) ->
            val variants = duplicates.map {
                DuplicateVariant(
                    path = it.entry.relativePath,
                    size = it.entry.size,
                    width = it.meta?.width,
                    height = it.meta?.height,
                )
            }
            duplicates.map { it.entry.relativePath to variants }
        }.toMap()
        val studios = baseItems.mapNotNull { it.movie?.studio?.trim()?.ifBlank { null } }
            .distinct().sorted()
        val series = baseItems.mapNotNull { it.movie?.series?.trim()?.ifBlank { null } }
            .distinct().sorted()
        val years = baseItems.mapNotNull {
            it.movie?.releaseDate?.take(4)?.takeIf { year -> year.all(Char::isDigit) }
        }.distinct().sortedDescending()
        val actors = baseItems.flatMap { it.movie?.actorNames.orEmpty() }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
        val items = baseItems.map {
            it.copy(
                duplicateCount = duplicateCounts[it.entry.relativePath] ?: 0,
                duplicateVariants = duplicateVariants[it.entry.relativePath].orEmpty(),
            )
        }.filter { item ->
            val query = state.query.trim()
            val queryMatches = query.isEmpty() ||
                item.entry.name.contains(query, ignoreCase = true) ||
                item.movie?.code?.contains(query, ignoreCase = true) == true ||
                item.movie?.actorNames?.any { it.contains(query, ignoreCase = true) } == true
            val stateMatches = item.entry.isDirectory || when (state.watchFilter) {
                WatchFilter.ALL -> true
                WatchFilter.UNWATCHED -> item.watch == null ||
                    item.watch.watchState == com.lanplay.player.data.db.WatchState.UNWATCHED
                WatchFilter.IN_PROGRESS ->
                    item.watch?.watchState == com.lanplay.player.data.db.WatchState.IN_PROGRESS
                WatchFilter.COMPLETED ->
                    item.watch?.watchState == com.lanplay.player.data.db.WatchState.COMPLETED
                WatchFilter.FAVORITES -> item.watch?.isFavorite == true
            }
            val recentMatches = !state.recentOnly || item.entry.isDirectory ||
                item.entry.lastModified >= System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1_000
            val tagMatches = taggedRecordIds == null || item.watch?.id in taggedRecordIds
            val metadataMatches = item.entry.isDirectory ||
                (state.studioFilter == null || item.movie?.studio == state.studioFilter) &&
                (state.seriesFilter == null || item.movie?.series == state.seriesFilter) &&
                (state.yearFilter == null ||
                    item.movie?.releaseDate?.startsWith(state.yearFilter) == true) &&
                (state.actorFilter == null ||
                    item.movie?.actorNames?.contains(state.actorFilter) == true)
            queryMatches && stateMatches && recentMatches && tagMatches && metadataMatches
        }
        val valueComparator = when (state.sortField) {
            SortField.NAME -> compareBy<BrowseItem> { it.entry.name.lowercase() }
            SortField.SIZE -> compareBy { it.entry.size }
            SortField.LAST_MODIFIED -> compareBy { it.entry.lastModified }
            SortField.DURATION -> compareBy { it.durationMs ?: Long.MIN_VALUE }
            SortField.LAST_WATCHED -> compareBy { it.watch?.lastWatchedAt ?: Long.MIN_VALUE }
            SortField.RATING -> compareBy { it.watch?.rating ?: 0 }
        }
        val effectiveComparator = if (state.recentOnly) {
            compareBy<BrowseItem> { it.entry.lastModified }.reversed()
        } else if (state.sortAscending) {
            valueComparator
        } else {
            valueComparator.reversed()
        }
        val ordered = items.sortedWith(
            compareByDescending<BrowseItem> { it.entry.isDirectory }
                .then(effectiveComparator)
        )
        if (requestId != rebuildGeneration ||
            _state.value.server?.id != server.id ||
            _state.value.path != state.path ||
            _state.value.mergedMode != state.mergedMode
        ) return
        _state.value = _state.value.copy(
            items = ordered,
            availableStudios = studios,
            availableSeries = series,
            availableYears = years,
            availableActors = actors,
            directoryVideoCount = videoItems.size,
            directoryTotalBytes = videoItems.sumOf {
                it.entry.size
            },
            directoryCompletedCount = videoItems.count {
                it.watch?.watchState == com.lanplay.player.data.db.WatchState.COMPLETED
            },
        )
    }

    private suspend fun persistBrowseState(s: UiState = _state.value) {
        val server = s.server ?: return
        val previous = browseStateDao.get(server.id, s.path)
        browseStateDao.upsert(
            BrowseStateEntity(
                id = previous?.id ?: 0,
                serverId = server.id,
                dirPath = s.path,
                scrollIndex = s.scrollIndex,
                scrollOffset = s.scrollOffset,
                sortField = s.sortField.name,
                sortAscending = s.sortAscending,
                viewMode = s.viewMode.name,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    private companion object {
        const val SQLITE_IN_BATCH_SIZE = 900
    }
}
