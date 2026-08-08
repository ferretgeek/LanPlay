package com.lanplay.player.ui.theme

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.lanplay.player.data.prefs.AppearanceSettings
import com.lanplay.player.data.prefs.DarkMode

data class LanPlayThemeDefinition(
    val id: String,
    val name: String,
    val description: String,
    val background: Color,
    val surface: Color,
    val surfaceLow: Color,
    val surfaceHigh: Color,
    val primary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val outline: Color,
    val onBackground: Color,
    val onSurfaceVariant: Color,
    val darkAccent: Color,
)

object LanPlayThemes {
    val Mist = LanPlayThemeDefinition(
        "mist", "晨雾", "冷灰蓝 · 默认",
        Color(0xFFFAFBFC), Color.White, Color(0xFFF7F9FB), Color(0xFFECEFF3),
        Color(0xFF4A6FA5), Color(0xFFDDE7F5), Color(0xFF16304F),
        Color(0xFF7B8FA8), Color(0xFFC6CFD9), Color(0xFF1A1D21),
        Color(0xFF5A626C), Color(0xFF8FB3F0),
    )
    val Mint = LanPlayThemeDefinition(
        "mint", "薄荷", "清新绿",
        Color(0xFFF7FBF9), Color.White, Color(0xFFF3F9F6), Color(0xFFE2EFE8),
        Color(0xFF2E9E7A), Color(0xFFD3EFE4), Color(0xFF073A2A),
        Color(0xFF86BFA8), Color(0xFFBCD5CA), Color(0xFF16241E),
        Color(0xFF4C6459), Color(0xFF5FCFA8),
    )
    val SeaSalt = LanPlayThemeDefinition(
        "sea", "海盐", "通透青蓝",
        Color(0xFFF6FAFB), Color.White, Color(0xFFF2F8FA), Color(0xFFDFECF0),
        Color(0xFF3B8EA5), Color(0xFFD2EBF3), Color(0xFF06343F),
        Color(0xFF7FB8C9), Color(0xFFB7D3DB), Color(0xFF142226),
        Color(0xFF495F66), Color(0xFF6FC4DC),
    )
    val Sakura = LanPlayThemeDefinition(
        "sakura", "樱雪", "柔粉",
        Color(0xFFFDF8F9), Color.White, Color(0xFFFCF5F7), Color(0xFFF5E5EA),
        Color(0xFFD4788F), Color(0xFFFADDE4), Color(0xFF4A1526),
        Color(0xFFE5A9B8), Color(0xFFE4C8D0), Color(0xFF2A1D21),
        Color(0xFF6B535A), Color(0xFFF0A8BC),
    )
    val WarmSand = LanPlayThemeDefinition(
        "sand", "暖砂", "米杏奶油",
        Color(0xFFFDFBF7), Color.White, Color(0xFFFBF7F1), Color(0xFFF1E9DC),
        Color(0xFFC08B5C), Color(0xFFF6E4CE), Color(0xFF4A2F14),
        Color(0xFFD9B78F), Color(0xFFDCCDB6), Color(0xFF2A231A),
        Color(0xFF6B5D4B), Color(0xFFE0B888),
    )
    val Wisteria = LanPlayThemeDefinition(
        "wisteria", "紫藤", "淡雅紫",
        Color(0xFFFAF9FC), Color.White, Color(0xFFF7F5FB), Color(0xFFEAE6F3),
        Color(0xFF7B6BA8), Color(0xFFE6E0F5), Color(0xFF241A44),
        Color(0xFFA899C7), Color(0xFFCFC7E0), Color(0xFF1F1B29),
        Color(0xFF575070), Color(0xFFB0A0DC),
    )
    /** Android 12+ 使用壁纸动态色；这里的色板仅供预览和旧系统降级。 */
    val Dynamic = LanPlayThemeDefinition(
        "dynamic", "系统取色", "跟随壁纸",
        Color(0xFFF7F8FC), Color.White, Color(0xFFF3F5FA), Color(0xFFE6EAF2),
        Color(0xFF596789), Color(0xFFDDE3F3), Color(0xFF18233D),
        Color(0xFF7A86A4), Color(0xFFC5CBDA), Color(0xFF1A1D24),
        Color(0xFF5A6272), Color(0xFFAEC6FF),
    )
    val all = listOf(Mist, Mint, SeaSalt, Sakura, WarmSand, Wisteria)
    val selectable = all + Dynamic
    fun byId(id: String) = selectable.firstOrNull { it.id == id } ?: Mist
}

@Composable
fun LanPlayTheme(
    settings: AppearanceSettings,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val selected = LanPlayThemes.byId(settings.themeId)
    val dark = when (settings.darkMode) {
        DarkMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        DarkMode.LIGHT -> false
        DarkMode.DARK -> true
    }
    val dynamic = settings.themeId == LanPlayThemes.Dynamic.id &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val scheme = if (dynamic && dark) {
        dynamicDarkColorScheme(context)
    } else if (dynamic) {
        dynamicLightColorScheme(context)
    } else if (dark) {
        darkColorScheme(
            primary = selected.darkAccent,
            onPrimary = Color(0xFF0A1633),
            primaryContainer = selected.darkAccent.copy(alpha = 0.24f),
            onPrimaryContainer = Color(0xFFE8E8EA),
            secondary = selected.darkAccent.copy(alpha = 0.78f),
            background = Color.Black,
            surface = Color(0xFF0C0C0E),
            surfaceContainerLowest = Color(0xFF050506),
            surfaceContainerLow = Color(0xFF101012),
            surfaceContainer = Color(0xFF141416),
            surfaceContainerHigh = Color(0xFF1C1C1F),
            surfaceContainerHighest = Color(0xFF242428),
            onBackground = Color(0xFFE8E8EA),
            onSurface = Color(0xFFE8E8EA),
            onSurfaceVariant = Color(0xFFA0A0A8),
            outline = Color(0xFF3A3A40),
            outlineVariant = Color(0xFF26262A),
            error = Color(0xFFFFB4AB),
        )
    } else {
        lightColorScheme(
            primary = selected.primary,
            onPrimary = Color.White,
            primaryContainer = selected.primaryContainer,
            onPrimaryContainer = selected.onPrimaryContainer,
            secondary = selected.secondary,
            background = selected.background,
            surface = selected.surface,
            surfaceContainerLowest = selected.surface,
            surfaceContainerLow = selected.surfaceLow,
            surfaceContainer = selected.primaryContainer.copy(alpha = 0.55f),
            surfaceContainerHigh = selected.surfaceHigh,
            surfaceContainerHighest = selected.surfaceHigh,
            onBackground = selected.onBackground,
            onSurface = selected.onBackground,
            onSurfaceVariant = selected.onSurfaceVariant,
            outline = selected.outline,
            outlineVariant = selected.outline.copy(alpha = 0.55f),
            error = Color(0xFFBA1A1A),
        )
    }
    // 主题与明暗切换使用同一组 220ms 颜色过渡；只动画颜色值，不交叉组合整棵页面，
    // 因此播放器、滚动位置和输入状态不会因为换主题被重建。
    val primary by animateColorAsState(scheme.primary, tween(220), label = "themePrimary")
    val onPrimary by animateColorAsState(scheme.onPrimary, tween(220), label = "themeOnPrimary")
    val primaryContainer by animateColorAsState(
        scheme.primaryContainer,
        tween(220),
        label = "themePrimaryContainer",
    )
    val secondary by animateColorAsState(scheme.secondary, tween(220), label = "themeSecondary")
    val background by animateColorAsState(scheme.background, tween(220), label = "themeBackground")
    val surface by animateColorAsState(scheme.surface, tween(220), label = "themeSurface")
    val surfaceLow by animateColorAsState(
        scheme.surfaceContainerLow,
        tween(220),
        label = "themeSurfaceLow",
    )
    val surfaceHigh by animateColorAsState(
        scheme.surfaceContainerHigh,
        tween(220),
        label = "themeSurfaceHigh",
    )
    val onBackground by animateColorAsState(
        scheme.onBackground,
        tween(220),
        label = "themeOnBackground",
    )
    val onSurface by animateColorAsState(scheme.onSurface, tween(220), label = "themeOnSurface")
    val onSurfaceVariant by animateColorAsState(
        scheme.onSurfaceVariant,
        tween(220),
        label = "themeOnSurfaceVariant",
    )
    val outline by animateColorAsState(scheme.outline, tween(220), label = "themeOutline")
    val outlineVariant by animateColorAsState(
        scheme.outlineVariant,
        tween(220),
        label = "themeOutlineVariant",
    )
    val error by animateColorAsState(scheme.error, tween(220), label = "themeError")
    val animatedScheme = scheme.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        secondary = secondary,
        background = background,
        surface = surface,
        surfaceContainerLow = surfaceLow,
        surfaceContainerHigh = surfaceHigh,
        onBackground = onBackground,
        onSurface = onSurface,
        onSurfaceVariant = onSurfaceVariant,
        outline = outline,
        outlineVariant = outlineVariant,
        error = error,
    )
    MaterialTheme(
        colorScheme = animatedScheme,
        typography = LanPlayTypography,
        shapes = LanPlayShapes,
        content = content,
    )
}
