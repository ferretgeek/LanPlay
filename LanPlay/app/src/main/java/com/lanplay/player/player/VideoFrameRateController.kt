package com.lanplay.player.player

import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * 给系统合成器提供视频源帧率提示。长视频使用 FIXED_SOURCE + ALWAYS，
 * 系统会为 23.976/25/29.97fps 素材选择合适的 60/90/120Hz 倍频档；
 * 离开播放器时清为 0，让界面恢复系统的高刷新率选择。
 */
internal class VideoFrameRateController {
    private var view: SurfaceView? = null
    private var requestedFps = 30f
    private var callback: SurfaceHolder.Callback? = null

    fun attach(surfaceView: SurfaceView) {
        detach()
        view = surfaceView
        callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = apply(holder.surface)
            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) = apply(holder.surface)
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        }.also(surfaceView.holder::addCallback)
        apply(surfaceView.holder.surface)
    }

    fun updateSourceFps(fps: Float) {
        if (fps.isFinite() && fps in 1f..240f) {
            requestedFps = fps
            view?.holder?.surface?.let(::apply)
        }
    }

    fun detach() {
        view?.let { surfaceView ->
            clear(surfaceView.holder.surface)
            callback?.let(surfaceView.holder::removeCallback)
        }
        callback = null
        view = null
    }

    private fun apply(surface: Surface) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !surface.isValid) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(
                    requestedFps,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ALWAYS,
                )
            } else {
                surface.setFrameRate(
                    requestedFps,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                )
            }
        }
    }

    private fun clear(surface: Surface) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !surface.isValid) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(
                    0f,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    Surface.CHANGE_FRAME_RATE_ALWAYS,
                )
            } else {
                surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            }
        }
    }
}
