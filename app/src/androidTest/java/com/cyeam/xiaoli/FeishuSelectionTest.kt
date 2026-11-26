package com.cyeam.xiaoli

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class FeishuSelectionTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun unpairedDeviceSelectsOneChannelAndRestoresItAfterRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        try {
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
        } finally {
            preferences.edit().clear().commit()
        }
    }
}
