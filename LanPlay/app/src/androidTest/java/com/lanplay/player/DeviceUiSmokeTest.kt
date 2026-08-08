package com.lanplay.player

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/** 荣耀实机与目标规格模拟器的基础 UI、语义和导航冒烟，不读取或修改真实 SMB 配置。 */
@RunWith(AndroidJUnit4::class)
class DeviceUiSmokeTest {
    @get:Rule(order = 0)
    val wakeDevice = object : TestWatcher() {
        override fun starting(description: Description) {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.executeShellCommand("cmd power wakeup").close()
            automation.executeShellCommand("wm dismiss-keyguard").close()
        }
    }

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @Test
    fun onboardingAndBottomNavigationAreReachable() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("am start -W -n $PACKAGE/.LauncherAlias").close()

        compose.waitUntil(20_000) {
            textExists("欢迎使用 LanPlay") ||
                textExists("连接你的电脑") ||
                clickableTextExists("媒体")
        }
        if (textExists("欢迎使用 LanPlay")) {
            clickButton("继续")
            waitForText("连接你的电脑", 5_000)
            clickButton("稍后连接，继续")
            waitForText("保持后台播放", 5_000)
            compose.onNodeWithText("去系统设置").assertHasClickAction()
            clickButton("稍后设置，继续")
            waitForText("选一种舒服的外观", 5_000)
            clickButton("继续")
            waitForText("都好了", 5_000)
            clickButton("开始使用")
        }

        compose.waitUntil(15_000) {
            textExists("连接你的电脑") || clickableTextExists("媒体")
        }
        // 未配置服务器时验证连接入口；已有安全测试数据时直接验证主页导航，
        // 不清空或覆盖设备上的真实 SMB 配置。
        if (textExists("连接你的电脑")) {
            compose.onNode(
                (hasText("扫描局域网") or hasText("正在扫描", substring = true)) and
                    hasClickAction()
            ).assertHasClickAction()
            compose.onNodeWithText("电脑地址").assertExists()
        }

        listOf("历史", "清理", "回收", "设置", "媒体").forEach { tab ->
            val tabNode = compose.onNode(hasText(tab) and hasClickAction())
            tabNode.performClick()
            tabNode.assertIsSelected()
        }
    }

    private fun waitForText(text: String, timeoutMillis: Long) {
        compose.waitUntil(timeoutMillis) {
            runCatching {
                compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
    }

    private fun textExists(text: String): Boolean = runCatching {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }.getOrDefault(false)

    private fun clickableTextExists(text: String): Boolean = runCatching {
        compose.onAllNodes(hasText(text) and hasClickAction())
            .fetchSemanticsNodes()
            .isNotEmpty()
    }.getOrDefault(false)

    private fun clickButton(text: String) {
        compose.onNode(hasText(text) and hasClickAction()).performClick()
    }

    private companion object {
        const val PACKAGE = "com.lanplay.player"
    }
}
