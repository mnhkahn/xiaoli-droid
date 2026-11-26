package com.cyeam.xiaoli

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FeishuSelectionTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun resetFeishuStore() {
        context.getSharedPreferences("feishu", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun upgradeDiscardsTheLegacyConnectionAndDrafts() {
        context.getSharedPreferences("connection", Context.MODE_PRIVATE).edit()
            .putString("credential", "old-device-pairing")
            .putString("session", "old-conversation")
            .putBoolean("feishu.mode", false).commit()
        context.getSharedPreferences("preferences", Context.MODE_PRIVATE).edit()
            .putBoolean("app_lock", true).commit()
        val draft = File(context.filesDir, "drafts/old.json")
        val attachment = File(context.filesDir, "attachments/old/photo.jpg")
        for (file in listOf(draft, attachment)) {
            file.parentFile!!.mkdirs()
            file.writeText("previous conversation")
        }
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            compose.onNodeWithText("飞书登录").assertExists()
            compose.onNodeWithText("返回小李").assertDoesNotExist()
            compose.waitUntil(15000) {
                context.getSharedPreferences("feishu", Context.MODE_PRIVATE).getBoolean("legacyRemoved", false)
            }
            assertTrue(context.getSharedPreferences("connection", Context.MODE_PRIVATE).all.isEmpty())
            assertTrue(context.getSharedPreferences("preferences", Context.MODE_PRIVATE).all.isEmpty())
            assertFalse(draft.exists())
            assertFalse(attachment.exists())
            activity.recreate()
            compose.onNodeWithText("飞书登录").assertExists()
        }
    }

    @Test fun unpairedDeviceSelectsOneChannelAndRestoresItAfterRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            compose.onNodeWithText("飞书登录").assertExists()
            compose.onNodeWithText("选择智能体").performClick()
            compose.onNodeWithText("添加已有智能体").performClick()
            val fields = compose.onAllNodes(hasSetTextAction())
            fields[0].performTextInput("Test Agent")
            fields[1].performTextInput("test-channel")
            compose.onNodeWithText("打开会话").performClick()
            compose.onNodeWithText("Test Agent").assertExists()
            activity.recreate()
            compose.onNodeWithText("Test Agent").assertExists()
            compose.onNodeWithText("切换").performClick()
            compose.onNodeWithText("Test Agent").performClick()
            compose.onNodeWithText("重新加载").assertExists()
        }
    }
}
