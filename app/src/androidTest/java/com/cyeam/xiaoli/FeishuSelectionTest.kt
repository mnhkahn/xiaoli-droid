package com.cyeam.xiaoli

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FeishuSelectionTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun findWebView(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWebView(view.getChildAt(it)) }
        else -> null
    }

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
            compose.onNodeWithText("使用飞书登录").assertIsDisplayed()
            compose.onNodeWithText("返回小李").assertDoesNotExist()
            compose.waitUntil(15000) {
                context.getSharedPreferences("feishu", Context.MODE_PRIVATE).getBoolean("legacyRemoved", false)
            }
            assertTrue(context.getSharedPreferences("connection", Context.MODE_PRIVATE).all.isEmpty())
            assertTrue(context.getSharedPreferences("preferences", Context.MODE_PRIVATE).all.isEmpty())
            assertFalse(draft.exists())
            assertFalse(attachment.exists())
            activity.recreate()
            compose.onNodeWithText("使用飞书登录").assertIsDisplayed()
        }
    }

    @Test fun unpairedDeviceSelectsOneChannelAndRestoresItAfterRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            compose.onNodeWithText("添加已有智能体").assertDoesNotExist()
            compose.onNodeWithText("使用飞书登录").performClick()
            compose.onNodeWithText("返回").performClick()
            compose.onNodeWithText("使用飞书登录").performClick()
            compose.onNodeWithText("飞书登录").assertIsDisplayed()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val launches = ArrayBlockingQueue<Intent>(1)
            val monitor = object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.action != Intent.ACTION_VIEW) return null
                    launches.offer(intent)
                    return Instrumentation.ActivityResult(0, null)
                }
            }
            val passport = "https://accounts.feishu.cn/accounts/page/login"
            val destination = "https://aily.feishu.cn/ai/agents/bot"
            val handoff = "lark://client/verify?qr_code=test"
            val ready = CountDownLatch(1)
            instrumentation.addMonitor(monitor)
            try {
                activity.onActivity {
                    val login = checkNotNull(findWebView(it.window.decorView))
                    login.stopLoading()
                    val production = login.webViewClient
                    login.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                            val html = if (request.url.toString() == passport) {
                                """<a href="$handoff" style="position:fixed;inset:0">Login</a>"""
                            } else "<p>Aily</p>"
                            return WebResourceResponse("text/html", "UTF-8", html.byteInputStream())
                        }
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                            production.shouldOverrideUrlLoading(view, request)
                        override fun onPageFinished(view: WebView, url: String) {
                            production.onPageFinished(view, url)
                            if (url == passport) view.postVisualStateCallback(1, object : WebView.VisualStateCallback() {
                                override fun onComplete(requestId: Long) { ready.countDown() }
                            })
                        }
                    }
                    login.loadUrl(passport)
                }
                assertTrue("Passport page rendered", ready.await(10, TimeUnit.SECONDS))
                val bounds = android.graphics.Rect()
                activity.onActivity { checkNotNull(findWebView(it.window.decorView)).getGlobalVisibleRect(bounds) }
                android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                    "input tap ${bounds.centerX()} ${bounds.centerY()}"
                )).use { it.readBytes() }
                val launch = checkNotNull(launches.poll(10, TimeUnit.SECONDS))
                assertEquals("com.ss.android.lark", launch.`package`)
                assertEquals(handoff, launch.dataString)
                compose.onNodeWithText("添加已有智能体").assertDoesNotExist()
            } finally {
                instrumentation.removeMonitor(monitor)
            }
            // Simulate Passport returning to the protected Aily destination in this WebView.
            activity.onActivity { screen ->
                val login = checkNotNull(findWebView(screen.window.decorView))
                login.loadUrl(destination)
            }
            compose.waitUntil(10000) { compose.onAllNodesWithText("添加已有智能体").fetchSemanticsNodes().isNotEmpty() }
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
            compose.onNodeWithText("切换").performClick()
            compose.onNodeWithText("清除本机飞书登录状态").performClick()
            compose.onNodeWithText("清除").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("使用飞书登录").fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test fun savedChannelAloneDoesNotSkipLogin() {
        FeishuChannelStore(context).save(listOf(FeishuChannel("Existing agent", "test-channel")))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("使用飞书登录").assertIsDisplayed()
            compose.onNodeWithText("Existing agent").assertDoesNotExist()
            compose.onNodeWithText("选择智能体").assertDoesNotExist()
        }
    }
}
