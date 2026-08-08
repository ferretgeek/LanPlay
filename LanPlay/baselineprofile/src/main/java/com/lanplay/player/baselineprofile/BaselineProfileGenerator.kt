package com.lanplay.player.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(
        packageName = PACKAGE,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
    }

    @Test
    fun browseBaseline() = rule.collect(
        packageName = PACKAGE,
        includeInStartupProfile = false,
    ) {
        device.executeShellCommand(
            "am start -W -n $PACKAGE/.LauncherAlias --ez benchmark_gallery true"
        )
        device.waitForIdle()
        Thread.sleep(5_000)
    }

    private companion object {
        const val PACKAGE = "com.lanplay.player"
    }
}
