package com.lanplay.player.ui

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.lanplay.player.data.crypto.CacheCipher
import java.io.File

/**
 * 按实际展示尺寸附近解码本地海报，避免把 800×1200 原图完整放进几十 dp 的卡片。
 * inSampleSize 只取 2 的幂，既保留清晰度，也让离开画廊进入 4K 播放时能及时回收大块堆内存。
 */
fun decodeArtwork(
    path: String?,
    targetWidthPx: Int,
    targetHeightPx: Int,
): ImageBitmap? {
    if (path.isNullOrBlank()) return null
    val file = File(path)
    if (!CacheCipher.isEncrypted(file)) return null
    val bytes = CacheCipher.read(file) ?: return null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (
            bounds.outWidth / (sample * 2) >= targetWidthPx &&
            bounds.outHeight / (sample * 2) >= targetHeightPx
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    } finally {
        bytes.fill(0)
    }
}
