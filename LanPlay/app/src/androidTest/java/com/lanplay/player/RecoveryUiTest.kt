package com.lanplay.player

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.File

/**
 * 真机数据库恢复门禁。
 *
 * 该测试会确认“保留旧库并重建”，因此默认跳过。只允许在已经由外部测试步骤故意损坏、
 * 且不含用户数据的 debug 安装上通过 `-e allowDestructiveRecovery true` 显式执行。
 */
@RunWith(AndroidJUnit4::class)
class RecoveryUiTest {
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
    fun corruptedDatabaseCanRetryThenArchiveAndRebuild() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(
            "仅允许在一次性 debug 数据上执行破坏性恢复测试",
            InstrumentationRegistry.getArguments().getString("allowDestructiveRecovery") == "true",
        )

        instrumentation.uiAutomation
            .executeShellCommand("am start -W -n $PACKAGE/.LauncherAlias")
            .close()
        waitForText("本机数据需要恢复", 15_000)
        compose.onNodeWithText("重试打开").assertExists().performClick()
        waitForText("重试打开", 15_000)

        compose.onNodeWithText("重建空库（保留旧库）").assertExists().performClick()
        compose.onNodeWithText("重建空数据库？").assertExists()
        compose.onNodeWithText("保留旧库并重建").assertExists().performClick()

        waitForText("连接你的电脑", 20_000)
        val recoveryRoot = File(instrumentation.targetContext.filesDir, "database-recovery")
        val archived = recoveryRoot.walkTopDown()
            .filter(File::isFile)
            .map { it.relativeTo(recoveryRoot).invariantSeparatorsPath }
            .toList()
        assertTrue(
            "旧数据库未进入私有恢复目录",
            archived.any { it.endsWith("database/lanplay.db") },
        )
        assertTrue(
            "原数据库密钥未随旧库归档",
            archived.any { it.endsWith("no-backup/lanplay-db-key.lpc") },
        )
    }

    private fun waitForText(text: String, timeoutMillis: Long) {
        compose.waitUntil(timeoutMillis) {
            runCatching {
                compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
    }

    private companion object {
        const val PACKAGE = "com.lanplay.player"
    }
}
