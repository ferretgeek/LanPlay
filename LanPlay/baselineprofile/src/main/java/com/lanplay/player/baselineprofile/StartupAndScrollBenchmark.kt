package com.lanplay.player.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupAndScrollBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldStartupWithBaselineProfile() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(
            baselineProfileMode = BaselineProfileMode.Require,
            warmupIterations = 1,
        ),
        startupMode = StartupMode.COLD,
        iterations = 5,
        setupBlock = {
            device.executeShellCommand(
                "am start -W -a android.intent.action.MAIN -c android.intent.category.HOME"
            )
        },
    ) {
        device.executeShellCommand(
            "am start -W -n $PACKAGE/.LauncherAlias --ez benchmark_gallery true"
        )
    }

    /**
     * 只计入用户连续划动画廊的帧，启动与 SMB 后台刷新留在预热段。
     * 使用屏幕比例坐标，手机、平板以及不同分辨率都能执行同一条路径。
     */
    @Test
    fun galleryScrollWithBaselineProfile() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(
            baselineProfileMode = BaselineProfileMode.Require,
            warmupIterations = 1,
        ),
        startupMode = StartupMode.WARM,
        iterations = 5,
        setupBlock = {
            device.executeShellCommand(
                "am start -W -a android.intent.action.MAIN -c android.intent.category.HOME"
            )
            device.executeShellCommand(
                "am start -W -n $PACKAGE/.LauncherAlias --ez benchmark_gallery true"
            )
            // 让画廊进入稳定滚动阶段；不能 waitForIdle，因为测试页会持续动画。
            Thread.sleep(1_000)
        },
    ) {
        // 只采集稳定滚动帧，不把 Activity 重建和首屏布局混入滚动 P99。
        Thread.sleep(4_500)
    }

    private companion object {
        const val PACKAGE = "com.lanplay.player"
    }
}
