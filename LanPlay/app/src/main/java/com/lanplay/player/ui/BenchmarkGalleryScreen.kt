package com.lanplay.player.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 隐藏的性能采集路径：结构、图片比例和文字密度与真实海报画廊一致，但不依赖 SMB
 * 数据或账号。只有测试 Intent 能进入，正常导航没有入口。
 */
@Composable
fun BenchmarkGalleryScreen() {
    val state = rememberLazyGridState()
    val items = (1..80).toList()
    LaunchedEffect(Unit) {
        delay(450)
        while (currentCoroutineContext().isActive) {
            state.animateScrollBy(
                value = 3_600f,
                animationSpec = tween(1_800, easing = LinearEasing),
            )
            state.animateScrollBy(
                value = -3_600f,
                animationSpec = tween(1_800, easing = LinearEasing),
            )
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(items, key = { it }) { index ->
                BenchmarkPosterCard(index)
            }
        }
    }
}

@Composable
private fun BenchmarkPosterCard(index: Int) {
    val palettes = listOf(
        Color(0xFF496B9C) to Color(0xFFB9CBE3),
        Color(0xFF34866F) to Color(0xFFA8DCC8),
        Color(0xFF8A647C) to Color(0xFFE4B8CD),
        Color(0xFF936A43) to Color(0xFFE5C39E),
    )
    val (start, end) = palettes[index % palettes.size]
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(start, end))),
            contentAlignment = Alignment.BottomStart,
        ) {
            Text(
                text = (index + 1).toString().padStart(2, '0'),
                modifier = Modifier.padding(14.dp),
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = "局域网影片 ${index + 1}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Text(
            text = "1080p · 已匹配字幕",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
