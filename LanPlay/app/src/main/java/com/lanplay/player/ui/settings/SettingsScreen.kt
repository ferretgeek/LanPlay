package com.lanplay.player.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lanplay.player.data.prefs.DarkMode
import com.lanplay.player.data.prefs.OrientationMode
import com.lanplay.player.data.prefs.ResumePolicy
import com.lanplay.player.data.prefs.SubtitleFont
import com.lanplay.player.data.prefs.HomeLayout
import com.lanplay.player.ui.theme.LanPlayThemeDefinition
import com.lanplay.player.ui.theme.LanPlayThemes
import com.lanplay.player.BuildConfig
import com.lanplay.player.data.db.TagEntity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.platform.LocalContext
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val backupMessage by viewModel.backupMessage.collectAsStateWithLifecycle()
    val errorLogs by viewModel.errorLogs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val exportBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(viewModel::exportBackup) }
    val importBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let(viewModel::importBackup) }
    LaunchedEffect(backupMessage) {
        backupMessage?.let {
            snackbar.showSnackbar(it)
            viewModel.clearBackupMessage()
        }
    }
    var showPinSetup by remember { mutableStateOf(false) }
    var showPinDisable by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var developerInfo by remember { mutableStateOf(false) }
    var confirmResetAll by remember { mutableStateOf(false) }
    var newTagName by remember { mutableStateOf("") }
    var newTagColor by remember { mutableStateOf("#6E93D6") }
    var editingTag by remember { mutableStateOf<TagEntity?>(null) }
    var showErrorLogs by remember { mutableStateOf(false) }
    var confirmClearTraces by remember { mutableStateOf(false) }
    var showCustomSeekRange by remember { mutableStateOf(false) }
    var customSeekRange by remember { mutableStateOf("") }
    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
        Column(
            Modifier
                .fillMaxHeight()
                .widthIn(max = 960.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SectionHeader(Icons.Rounded.Palette, "外观")
            Text(
                "主题预览",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
            )
            LanPlayThemes.selectable.chunked(2).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { theme ->
                        ThemePreview(
                            theme,
                            selected = state.appearance.themeId == theme.id,
                            onClick = { viewModel.setTheme(theme.id) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Text("明暗模式", style = MaterialTheme.typography.titleMedium)
            ChoiceRow(
                choices = listOf(
                    DarkMode.FOLLOW_SYSTEM to "跟随系统",
                    DarkMode.LIGHT to "浅色",
                    DarkMode.DARK to "墨黑",
                ),
                selected = state.appearance.darkMode,
                onSelect = viewModel::setDarkMode,
            )
            Text("首页布局", style = MaterialTheme.typography.titleMedium)
            ChoiceRow(
                choices = listOf(
                    HomeLayout.GALLERY to "画廊直达",
                    HomeLayout.DASHBOARD to "观影仪表盘",
                ),
                selected = state.appearance.homeLayout,
                onSelect = viewModel::setHomeLayout,
            )

            SectionHeader(Icons.Rounded.Movie, "播放")
            Text("进入播放器时的方向", style = MaterialTheme.typography.titleMedium)
            ChoiceRow(
                choices = listOf(
                    OrientationMode.AUTO to "自动",
                    OrientationMode.FORCE_LANDSCAPE to "横屏",
                    OrientationMode.FORCE_PORTRAIT to "竖屏",
                ),
                selected = state.player.orientationMode,
                onSelect = viewModel::setOrientation,
            )
            Text(
                "续播方式",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            ChoiceRow(
                choices = listOf(
                    ResumePolicy.ALWAYS to "自动续播",
                    ResumePolicy.ASK to "每次询问",
                    ResumePolicy.NEVER to "总是从头",
                ),
                selected = state.player.resumePolicy,
                onSelect = viewModel::setResumePolicy,
            )
            Text(
                "播完后的行为",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            ChoiceRow(
                choices = listOf(false to "停留末帧", true to "自动下一部"),
                selected = state.player.autoPlayNext,
                onSelect = viewModel::setAutoPlayNext,
            )
            SettingSwitch(
                title = "播放淡入淡出",
                detail = "进入时柔和显现，返回媒体库前以约 300 毫秒淡出到黑场",
                checked = state.player.fadePlayback,
                onCheckedChange = viewModel::setFadePlayback,
            )
            TextButton(onClick = viewModel::resetPlayback) { Text("恢复播放默认设置") }

            SectionHeader(Icons.Rounded.Swipe, "手势")
            Text("横滑最大跳转范围", style = MaterialTheme.typography.titleMedium)
            val seekPresets = listOf(30, 60, 90, 120, 180, 300)
            val selectedSeekRange = state.player.seekSensitivitySeconds
                .takeIf { it in seekPresets } ?: -1
            ChoiceRow(
                choices = seekPresets.map { seconds ->
                    seconds to "$seconds 秒"
                } + (-1 to "自定义"),
                selected = selectedSeekRange,
                onSelect = {
                    if (it == -1) {
                        customSeekRange = state.player.seekSensitivitySeconds.toString()
                        showCustomSeekRange = true
                    } else {
                        viewModel.setSeekSensitivity(it)
                    }
                },
            )
            Text(
                "小幅滑动会自动减速，只有接近滑满一屏时才达到最大范围。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Text("双击快退 / 快进", style = MaterialTheme.typography.titleMedium)
            ChoiceRow(
                choices = listOf(5, 10, 15, 30).map { it to "$it 秒" },
                selected = state.player.doubleTapSeconds,
                onSelect = viewModel::setDoubleTapSeconds,
            )
            Text("长按临时倍速", style = MaterialTheme.typography.titleMedium)
            ChoiceRow(
                choices = listOf(1.5f, 2f, 2.5f, 3f).map { it to "${it}×" },
                selected = state.player.longPressSpeed,
                onSelect = viewModel::setLongPressSpeed,
            )
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Text(
                    "播放器内：单指横滑调进度，左侧上下滑调亮度，右侧上下滑调音量；操作时会显示独立浮窗。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(14.dp),
                )
            }
            SettingSwitch(
                title = "中央双击播放 / 暂停",
                detail = "关闭后中央区域也按快进、快退处理",
                checked = state.player.doubleTapCenterPause,
                onCheckedChange = viewModel::setDoubleTapCenterPause,
            )
            SettingSwitch(
                title = "上下滑亮度 / 音量",
                detail = "左侧调亮度，右侧调媒体音量",
                checked = state.player.verticalAdjustEnabled,
                onCheckedChange = viewModel::setVerticalAdjust,
            )
            Text("音量手势灵敏度", style = MaterialTheme.typography.titleMedium)
            ChoiceRow(
                choices = listOf(50, 75, 100, 150).map { it to "$it%" },
                selected = state.player.volumeSensitivityPercent,
                onSelect = viewModel::setVolumeSensitivity,
            )
            Text("亮度手势灵敏度", style = MaterialTheme.typography.titleMedium)
            ChoiceRow(
                choices = listOf(50, 75, 100, 150).map { it to "$it%" },
                selected = state.player.brightnessSensitivityPercent,
                onSelect = viewModel::setBrightnessSensitivity,
            )
            Text("音量安全上限", style = MaterialTheme.typography.titleMedium)
            ChoiceRow(
                choices = listOf(0f, 0.4f, 0.5f, 0.6f, 0.7f).map {
                    it to if (it == 0f) "关闭" else "${(it * 100).toInt()}%"
                },
                selected = state.player.volumeSoftLimitPercent,
                onSelect = viewModel::setVolumeLimit,
            )
            Text("亮度安全上限", style = MaterialTheme.typography.titleMedium)
            ChoiceRow(
                choices = listOf(0f, 0.4f, 0.5f, 0.6f, 0.7f).map {
                    it to if (it == 0f) "关闭" else "${(it * 100).toInt()}%"
                },
                selected = state.player.brightnessSoftLimitPercent,
                onSelect = viewModel::setBrightnessLimit,
            )
            TextButton(onClick = viewModel::resetGestures) { Text("恢复手势默认设置") }

            SectionHeader(Icons.Rounded.Subtitles, "字幕")
            Text("字号", style = MaterialTheme.typography.titleMedium)
            ChoiceRow(
                choices = listOf(50, 75, 100, 125, 150, 200, 250).map {
                    it to if (it == 75) "75% · 推荐" else "$it%"
                },
                selected = state.subtitle.sizePercent,
                onSelect = viewModel::setSubtitleSize,
            )
            Text("文字与描边颜色", style = MaterialTheme.typography.titleMedium)
            ChoiceRow(
                choices = listOf(
                    ("#FFFFFF" to "#000000") to "白字黑边",
                    ("#FFE66D" to "#000000") to "黄字黑边",
                    ("#9FE7F5" to "#00151A") to "青字深边",
                    ("#000000" to "#FFFFFF") to "黑字白边",
                ),
                selected = state.subtitle.textColor to state.subtitle.edgeColor,
                onSelect = { viewModel.setSubtitleColors(it.first, it.second) },
            )
            Text("描边粗细", style = MaterialTheme.typography.titleMedium)
            ChoiceRow(
                choices = (0..4).map { it to if (it == 0) "关闭" else "$it 级" },
                selected = state.subtitle.edgeWidth,
                onSelect = viewModel::setSubtitleEdge,
            )
            Text("底部边距", style = MaterialTheme.typography.titleMedium)
            ChoiceRow(
                choices = listOf(0, 5, 8, 12, 18, 25, 35).map { it to "$it%" },
                selected = state.subtitle.bottomPaddingPercent,
                onSelect = viewModel::setSubtitleBottom,
            )
            Text("字体", style = MaterialTheme.typography.titleMedium)
            ChoiceRow(
                choices = listOf(
                    SubtitleFont.SANS to "清晰黑体",
                    SubtitleFont.SERIF to "衬线体",
                    SubtitleFont.MONOSPACE to "等宽体",
                ),
                selected = state.subtitle.font,
                onSelect = viewModel::setSubtitleFont,
            )
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("字幕背景框", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "在复杂画面上增加半透明深色底",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.subtitle.backgroundEnabled,
                    onCheckedChange = viewModel::setSubtitleBackground,
                )
            }

            SectionHeader(Icons.Rounded.LocalOffer, "个人整理")
            Text(
                "标签可在播放器里添加到视频，也可在媒体页按标签筛选。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (tags.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    tags.forEach { tag ->
                        AssistChip(
                            onClick = { editingTag = tag },
                            label = { Text("● ${tag.name}") },
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newTagName,
                    onValueChange = { newTagName = it.take(30) },
                    label = { Text("新标签") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        viewModel.createTag(newTagName, newTagColor)
                        newTagName = ""
                    },
                    enabled = newTagName.isNotBlank(),
                ) { Text("创建") }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("#6E93D6", "#4C9A72", "#D47760", "#B06DAD", "#C58A32").forEach { color ->
                    AssistChip(
                        onClick = { newTagColor = color },
                        label = { Text(if (newTagColor == color) "✓ $color" else color) },
                    )
                }
            }

            SectionHeader(Icons.Rounded.Speed, "连接检查")
            Text("连接诊断与实际测速", style = MaterialTheme.typography.titleMedium)
            Text(
                "依次检查主机、端口、SMB 协商、认证、列目录，并真实读取最多 100 MB 数据。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp, bottom = 8.dp),
            )
            FilledTonalButton(
                onClick = viewModel::runDiagnostics,
                enabled = !diagnostics.running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (diagnostics.running) "正在诊断…" else "开始连接诊断与测速")
            }
            if (diagnostics.running) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            if (diagnostics.steps.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        diagnostics.steps.forEach { step ->
                            Row(Modifier.fillMaxWidth()) {
                                Text(step.name, Modifier.width(122.dp))
                                Text(
                                    step.status,
                                    color = when (step.success) {
                                        true -> MaterialTheme.colorScheme.primary
                                        false -> MaterialTheme.colorScheme.error
                                        null -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }

            SectionHeader(Icons.Rounded.Security, "隐私")
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("应用锁", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "离开后用指纹、面容或系统锁屏密码重新进入",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.privacy.appLockEnabled,
                    onCheckedChange = {
                        if (it) showPinSetup = true else showPinDisable = true
                    },
                )
            }
            Text("离开后的宽限时间", style = MaterialTheme.typography.titleMedium)
            ChoiceRow(
                choices = listOf(0 to "立即", 60 to "1 分钟", 300 to "5 分钟"),
                selected = state.privacy.lockGraceSeconds,
                onSelect = viewModel::setLockGrace,
            )
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("模糊海报与头像", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "画廊默认遮挡敏感图片，长按卡片可临时查看",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.privacy.blurArtwork,
                    onCheckedChange = viewModel::setBlurArtwork,
                )
            }
            SettingSwitch(
                title = "伪装为计算工具",
                detail = "桌面名称和图标改为普通计算工具；应用内功能不变，可随时恢复",
                checked = state.privacy.disguiseEnabled,
                onCheckedChange = viewModel::setDisguiseEnabled,
            )
            Text(
                "正式版同时禁止系统截图和最近任务预览泄露；应用不会写入系统相册或媒体库。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(
                onClick = { confirmClearTraces = true },
                modifier = Modifier.padding(bottom = 24.dp),
            ) {
                Text("一键清空本机使用痕迹", color = MaterialTheme.colorScheme.error)
            }

            SectionHeader(Icons.Rounded.Storage, "存储与缓存")
            Text(
                "本机可用空间 ${formatCacheSize(state.cacheStats.availableBytes)}",
                color = if (state.cacheStats.availableBytes < 512L * 1024 * 1024) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Text("图片缓存上限", style = MaterialTheme.typography.titleMedium)
            ChoiceRow(
                choices = listOf(512 to "512 MB", 1024 to "1 GB", 2048 to "2 GB"),
                selected = state.cacheSettings.imageCacheLimitMb,
                onSelect = viewModel::setImageCacheLimit,
            )
            Text("回收站自动清理", style = MaterialTheme.typography.titleMedium)
            ChoiceRow(
                choices = listOf(0 to "从不", 7 to "7 天", 30 to "30 天", 90 to "90 天"),
                selected = state.cacheSettings.trashRetentionDays,
                onSelect = viewModel::setTrashRetention,
            )
            CacheRow(
                title = "海报与演员头像",
                bytes = state.cacheStats.artworkBytes,
                onClear = viewModel::clearArtworkCache,
            )
            CacheRow(
                title = "视频缩略图",
                bytes = state.cacheStats.thumbnailBytes,
                onClear = viewModel::clearThumbnailCache,
            )
            CacheRow(
                title = "Seek 预览雪碧图",
                bytes = state.cacheStats.spriteBytes,
                onClear = viewModel::clearSpriteCache,
            )
            CacheRow(
                title = "外挂字幕副本",
                bytes = state.cacheStats.subtitleBytes,
                onClear = viewModel::clearSubtitleCache,
            )
            CacheRow(
                title = "播放器截图",
                bytes = state.cacheStats.screenshotBytes,
                onClear = viewModel::clearScreenshots,
            )
            Text(
                "缓存只保存在应用私有目录。达到上限后按最近最少使用顺序自动淘汰，不影响 SMB 原文件。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 32.dp),
            )

            SectionHeader(Icons.Rounded.Info, "关于")
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { developerInfo = !developerInfo },
                    ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("LanPlay ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "构建日期 ${BuildConfig.BUILD_DATE} · Android ${android.os.Build.VERSION.RELEASE}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (developerInfo) {
                        Text(
                            "开发者信息已开启\n" +
                                "SMB：SMBJ + jcifs-ng · 播放：Media3 + libVLC\n" +
                                "数据：Room + DataStore · ABI：${android.os.Build.SUPPORTED_ABIS.joinToString()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    } else {
                        Text(
                            "长按版本信息可查看开发者详情",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            TextButton(onClick = { showLicenses = true }) { Text("开源许可") }
            TextButton(onClick = viewModel::replayOnboarding) { Text("重新查看首次引导") }
            TextButton(onClick = {
                viewModel.loadErrorLogs()
                showErrorLogs = true
            }) { Text("最近错误记录") }
            Text("数据备份与恢复", style = MaterialTheme.typography.titleMedium)
            Text(
                "备份设置、观看记录、评分、标签与书签。服务器密码不会导出；备份是未加密 JSON，请只保存到可信位置。新设备先添加同一共享再恢复即可。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp, bottom = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = {
                        val date = SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(Date())
                        exportBackup.launch("LanPlay备份-$date.json")
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("导出备份") }
                FilledTonalButton(
                    onClick = { importBackup.launch("application/json") },
                    modifier = Modifier.weight(1f),
                ) { Text("导入恢复") }
            }
            FilledTonalButton(
                onClick = { confirmResetAll = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("恢复全部体验设置") }
            Spacer(Modifier.height(24.dp))
        }
        }
    }
    if (showCustomSeekRange) {
        val seconds = customSeekRange.toIntOrNull()
        AlertDialog(
            onDismissRequest = { showCustomSeekRange = false },
            title = { Text("自定义横滑范围") },
            text = {
                Column {
                    OutlinedTextField(
                        value = customSeekRange,
                        onValueChange = {
                            customSeekRange = it.filter(Char::isDigit).take(5)
                        },
                        label = { Text("滑满一屏最多跳转多少秒") },
                        supportingText = { Text("可填写 10～14400 秒（最长 4 小时）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = customSeekRange.isNotEmpty() &&
                            (seconds == null || seconds !in 10..14_400),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomSeekRange = false }) { Text("取消") }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.setSeekSensitivity(seconds!!)
                        showCustomSeekRange = false
                    },
                    enabled = seconds != null && seconds in 10..14_400,
                ) { Text("保存") }
            },
        )
    }
    if (showPinSetup) {
        PinSetupDialog(
            onDismiss = { showPinSetup = false },
            onConfirm = {
                viewModel.configureAppLock(it)
                showPinSetup = false
            },
        )
    }
    if (showPinDisable) {
        PinVerifyDialog(
            onDismiss = { showPinDisable = false },
            onConfirm = { pin, done ->
                viewModel.disableAppLock(pin) { verified, retryMs ->
                    done(verified, retryMs)
                    if (verified) showPinDisable = false
                }
            },
        )
    }
    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text("开源许可") },
            text = {
                Text(
                    "本应用使用 AndroidX、Jetpack Compose、Media3、Room、Hilt、" +
                        "Kotlin Coroutines、SMBJ、jcifs-ng、NanoHTTPD 与 libVLC。\n\n" +
                        "各组件继续适用其 Apache-2.0、LGPL 或相应上游许可证；" +
                        "许可证文件已随依赖发布，可在对应项目官网查看完整文本。"
                )
            },
            confirmButton = {
                TextButton(onClick = { showLicenses = false }) { Text("关闭") }
            },
        )
    }
    if (confirmResetAll) {
        AlertDialog(
            onDismissRequest = { confirmResetAll = false },
            title = { Text("恢复全部体验设置？") },
            text = {
                Text("服务器、观看记录、标签、书签和应用锁不会删除；播放、手势、字幕、外观与缓存参数会恢复推荐默认值。")
            },
            dismissButton = {
                TextButton(onClick = { confirmResetAll = false }) { Text("取消") }
            },
            confirmButton = {
                Button(onClick = {
                    confirmResetAll = false
                    viewModel.resetAll()
                }) { Text("恢复默认") }
            },
        )
    }
    editingTag?.let { tag ->
        TagEditDialog(
            tag = tag,
            onDismiss = { editingTag = null },
            onRename = {
                viewModel.renameTag(tag, it)
                editingTag = null
            },
            onRecolor = { viewModel.recolorTag(tag, it) },
            onDelete = {
                viewModel.deleteTag(tag)
                editingTag = null
            },
        )
    }
    if (showErrorLogs) {
        AlertDialog(
            onDismissRequest = { showErrorLogs = false },
            title = { Text("最近错误记录") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (errorLogs.isEmpty()) {
                        Text("暂无错误记录")
                    } else {
                        errorLogs.asReversed().forEach {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = viewModel::clearErrorLogs,
                    enabled = errorLogs.isNotEmpty(),
                ) { Text("清空") }
            },
            confirmButton = {
                Row {
                    TextButton(
                        onClick = {
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("LanPlay 错误记录", errorLogs.joinToString("\n"))
                            )
                        },
                        enabled = errorLogs.isNotEmpty(),
                    ) { Text("复制") }
                    TextButton(onClick = { showErrorLogs = false }) { Text("关闭") }
                }
            },
        )
    }
    if (confirmClearTraces) {
        AlertDialog(
            onDismissRequest = { confirmClearTraces = false },
            title = { Text("清空本机使用痕迹？") },
            text = {
                Text(
                    "将删除观看历史、收藏评分、书签关联、海报头像、字幕副本、截图和元数据缓存。" +
                        "服务器配置、共享原文件和回收站记录会保留。此操作无法撤销。"
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmClearTraces = false }) { Text("取消") }
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmClearTraces = false
                        viewModel.clearAllLocalTraces()
                    }
                ) { Text("清空本机痕迹") }
            },
        )
    }
}

@Composable
private fun TagEditDialog(
    tag: TagEntity,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onRecolor: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(tag.id) { mutableStateOf(tag.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("管理标签") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(30) },
                    label = { Text("名称") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("#6E93D6", "#4C9A72", "#D47760", "#B06DAD", "#C58A32").forEach {
                        AssistChip(
                            onClick = { onRecolor(it) },
                            label = { Text(if (tag.colorHex == it) "✓" else "●") },
                        )
                    }
                }
                TextButton(onClick = onDelete) {
                    Text("删除标签", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            Button(onClick = { onRename(name) }, enabled = name.isNotBlank()) {
                Text("保存")
            }
        },
    )
}

@Composable
private fun PinVerifyDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, (Boolean, Long) -> Unit) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!checking) onDismiss() },
        title = { Text("确认关闭应用锁") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("请输入当前应用锁 PIN。关闭后，私人媒体页面将不再要求身份验证。")
                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        pin = it.filter(Char::isDigit).take(8)
                        error = null
                    },
                    label = { Text("当前 PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = pin.length in 4..8 && !checking,
                onClick = {
                    checking = true
                    onConfirm(pin) { verified, retryMs ->
                        checking = false
                        if (!verified) {
                            error = if (retryMs > 0) {
                                "PIN 不正确，请在 ${((retryMs + 999) / 1_000)} 秒后重试"
                            } else {
                                "PIN 不正确"
                            }
                        }
                    }
                },
            ) { Text(if (checking) "正在验证…" else "关闭应用锁") }
        },
        dismissButton = { TextButton(enabled = !checking, onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun PinSetupDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = pin.matches(Regex("\\d{4,8}")) && pin == confirm
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置应用锁 PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "指纹或面容不可用时，用这组 4～8 位数字解锁。PIN 只以加盐摘要保存在本机。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                    label = { Text("输入 PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it.filter(Char::isDigit).take(8) },
                    label = { Text("再次输入") },
                    supportingText = {
                        if (confirm.isNotEmpty() && pin != confirm) Text("两次输入不一致")
                    },
                    isError = confirm.isNotEmpty() && pin != confirm,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            Button(onClick = { onConfirm(pin) }, enabled = valid) { Text("启用应用锁") }
        },
    )
}

@Composable
private fun SettingSwitch(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun CacheRow(title: String, bytes: Long, onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                formatCacheSize(bytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTonalButton(onClick = onClear, enabled = bytes > 0L) { Text("清理") }
    }
}

private fun formatCacheSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun SectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun ThemePreview(
    theme: LanPlayThemeDefinition,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .clip(shape)
            .background(theme.background)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) theme.primary else theme.outline.copy(alpha = 0.55f),
                shape,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = "主题：${theme.name}，${theme.description}${if (selected) "，已选择" else ""}"
            }
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.7f)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.linearGradient(
                        listOf(theme.primary.copy(alpha = 0.9f), theme.secondary.copy(alpha = 0.72f))
                    )
                )
        ) {
            Surface(
                color = Color.White.copy(alpha = 0.75f),
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            ) {
                Box(Modifier.width(58.dp).height(8.dp))
            }
            if (selected) {
                Surface(
                    color = theme.primary,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.align(Alignment.TopEnd).padding(7.dp).size(24.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        Text(
            theme.name,
            color = theme.onBackground,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 9.dp),
        )
        Text(
            theme.description,
            color = theme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun <T> ChoiceRow(
    choices: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        choices.forEach { (value, label) ->
            AssistChip(
                onClick = { onSelect(value) },
                label = { Text(label) },
                leadingIcon = if (selected == value) {
                    { Icon(Icons.Rounded.Check, null, Modifier.size(17.dp)) }
                } else null,
            )
        }
    }
}
