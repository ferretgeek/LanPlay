package com.lanplay.player.ui

import android.app.Activity
import android.content.Intent
import android.content.Context
import android.content.ContextWrapper
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lanplay.player.data.prefs.DarkMode
import com.lanplay.player.ui.theme.LanPlayThemes

private data class OnboardingPage(
    val icon: ImageVector,
    val title: String,
    val body: String,
)

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    onThemeSelected: (String) -> Unit = {},
    onDarkModeSelected: (DarkMode) -> Unit = {},
) {
    val pages = listOf(
        OnboardingPage(
            Icons.Rounded.PlayCircle,
            "欢迎使用 LanPlay",
            "专为局域网大文件设计的私人播放器。视频直接从你的电脑读取，不上传、不需要云端账号。",
        ),
        OnboardingPage(
            Icons.Rounded.FolderShared,
            "连接你的电脑",
            "进入媒体库后可一键扫描局域网，选择电脑、填写凭据并挑选共享；扫描失败时也能手动填写地址。",
        ),
        OnboardingPage(
            Icons.Rounded.BatteryChargingFull,
            "保持后台播放",
            "为了息屏和切到其他应用时不中断，建议在系统电量设置中允许 LanPlay 后台运行。也可以稍后再设置。",
        ),
        OnboardingPage(
            Icons.Rounded.Palette,
            "选一种舒服的外观",
            "主题会立即生效，之后可在设置中随时更换。默认的晨雾清爽耐看，深色模式适合暗环境看片。",
        ),
        OnboardingPage(
            Icons.Rounded.CheckCircle,
            "都好了",
            "开始浏览你的媒体库。想显示海报和演员信息时，在电脑上运行随附的 LanPlay 刮削工具即可。",
        ),
    )
    var page by rememberSaveable { mutableIntStateOf(0) }
    var selectedTheme by rememberSaveable { mutableStateOf("mist") }
    var selectedDarkMode by rememberSaveable { mutableStateOf(DarkMode.FOLLOW_SYSTEM) }
    val context = LocalContext.current
    val item = pages[page]
    BackHandler {
        if (page > 0) page-- else context.findActivity()?.finish()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 720.dp)
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (page < pages.lastIndex) {
                FilledTonalButton(onClick = onComplete) { Text("跳过") }
            }
        }
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = RoundedCornerShape(30.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(112.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        item.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(54.dp),
                    )
                }
            }
            Text(
                item.title,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 34.dp),
            )
            Text(
                item.body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
            if (page == 2) {
                FilledTonalButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            )
                        }
                    },
                    modifier = Modifier.padding(top = 22.dp),
                ) {
                    Text("去系统设置")
                }
            }
            if (page == 3) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LanPlayThemes.selectable.forEach { theme ->
                        FilledTonalButton(
                            onClick = {
                                selectedTheme = theme.id
                                onThemeSelected(theme.id)
                            },
                            modifier = Modifier.width(94.dp),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Surface(
                                    color = theme.primaryContainer,
                                    shape = CircleShape,
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    if (selectedTheme == theme.id) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Box(
                                                Modifier
                                                    .size(10.dp)
                                                    .background(theme.primary, CircleShape)
                                            )
                                        }
                                    }
                                }
                                Text(theme.name, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                }
                Row(
                    Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        DarkMode.FOLLOW_SYSTEM to "跟随系统",
                        DarkMode.LIGHT to "浅色",
                        DarkMode.DARK to "深色",
                    ).forEach { (mode, label) ->
                        FilledTonalButton(
                            onClick = {
                                selectedDarkMode = mode
                                onDarkModeSelected(mode)
                            },
                        ) {
                            Text(if (selectedDarkMode == mode) "✓ $label" else label)
                        }
                    }
                }
            }
        }
        Row(
            Modifier.padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            pages.indices.forEach { index ->
                Box(
                    Modifier
                        .size(if (index == page) 22.dp else 8.dp, 8.dp)
                        .background(
                            if (index == page) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            CircleShape,
                        )
                )
            }
        }
        Button(
            onClick = {
                if (page == pages.lastIndex) onComplete() else page++
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when (page) {
                    pages.lastIndex -> "开始使用"
                    1 -> "稍后连接，继续"
                    2 -> "稍后设置，继续"
                    else -> "继续"
                }
            )
        }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
