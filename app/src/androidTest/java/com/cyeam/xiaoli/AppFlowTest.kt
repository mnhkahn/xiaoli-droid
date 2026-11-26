package com.cyeam.xiaoli

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*

class AppFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var server: MockWebServer
    private var scenario: ActivityScenario<MainActivity>? = null
    private var originalIntent: android.content.Intent? = null
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    @Volatile private var selectedModel = ""
    @Volatile private var sent = false
    @Volatile private var replied = false
    @Volatile private var longHistory = false
    @Volatile private var questionCard = false
    private lateinit var context: Context

    private fun snapshot(): JSONObject {
        val messages = JSONArray()
        if (longHistory)
            for (i in 0 until 60) messages.put(
                JSONObject()
                    .put("id", "history-$i")
                    .put("role", if (i % 2 == 0) "user" else "assistant")
                    .put("text", "第 $i 条历史消息\n\n**小李**支持流畅地浏览长对话。\n\n- 保留上下文\n- 继续提问")
                    .put("status", "complete")
            )
        if (sent) {
            messages.put(
                JSONObject()
                    .put("id", "u1")
                    .put("role", "user")
                    .put("text", "你好")
                    .put("status", "complete")
            )
            messages.put(
                JSONObject()
                    .put("id", "m1")
                    .put("role", "assistant")
                    .put(
                        "text",
                        if (replied) "授权后继续完成"
                        else
                            "# 小李\n\n**图文回复**\n\n| 项目 | 状态 |\n| --- | --- |\n| 图片 | 完成 |\n\n![结果](${server.url("/image.png")})\n\n```kotlin\nval ready = true\n```",
                    )
                    .put("thinking", "正在核对任务")
                    .put("status", "complete")
            )
        }
        val pending = JSONArray()
        if (sent && !replied)
            pending.put(
                JSONObject()
                    .put("id", if (questionCard) "question-1" else "permission-1")
                    .put("kind", if (questionCard) "question" else "permission")
                    .put("question", "是否允许执行？")
                    .put("command", "echo ready")
                    .put("options", JSONArray(listOf("允许", "拒绝")))
            )
        return JSONObject()
            .put("id", "test-session")
            .put("title", "测试对话")
            .put("model",selectedModel)
            .put("messages", messages)
            .put("pending", pending)
            .put("busy", false)
            .put("before", 0)
            .put("revision", if (replied) 3 else if (sent) 2 else 1)
    }

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("connection", Context.MODE_PRIVATE).edit().clear().commit()
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(r: RecordedRequest): MockResponse {
                    requests += r
                    val path = r.path.orEmpty().substringBefore('?')
                    if (path == "/xiaozhi/pair")
                        return json(
                            JSONObject().put("websocket", JSONObject().put("token", "test-token"))
                        )
                    if(path=="/mobile/v1/models")return json(JSONObject().put("models",JSONArray().put(JSONObject().put("id","openrouter:vision:free").put("name","Vision Free").put("provider","openrouter")).put(JSONObject().put("id","siliconflow:text-free").put("name","Text Free").put("provider","siliconflow"))))
                    if(path=="/mobile/v1/sessions/test-session"&&r.method=="PATCH") {selectedModel=JSONObject(r.body.readUtf8()).getString("model");return json(snapshot())}
                    if (path == "/mobile/v1/capabilities")
                        return json(JSONObject().put("protocol", 1))
                    if (path == "/mobile/v1/sessions" && r.method == "GET")
                        return json(
                            JSONObject()
                                .put(
                                    "sessions",
                                    JSONArray()
                                        .put(
                                            JSONObject()
                                                .put("id", "test-session")
                                                .put("title", "测试对话")
                                        ),
                                )
                        )
                    if (path == "/mobile/v1/sessions") return json(snapshot()).setResponseCode(201)
                    if (path == "/mobile/v1/sessions/test-session") return json(snapshot())
                    if (path.endsWith("/messages")) {
                        sent = true
                        return json(JSONObject().put("accepted", true)).setResponseCode(202)
                    }
                    if (path.endsWith("/reply")) {
                        replied = true
                        return json(JSONObject().put("accepted", true)).setResponseCode(202)
                    }
                    if (path.endsWith("/events")) {
                        val snap = snapshot()
                        val event =
                            JSONObject()
                                .put("id", snap.getLong("revision"))
                                .put("type", "snapshot")
                                .put("data", snap)
                        return MockResponse()
                            .setHeader("Content-Type", "text/event-stream")
                            .setBody("id: ${snap.getLong("revision")}\ndata: $event\n\n")
                    }
                    if (path == "/image.png") {
                        val bitmap =
                            android.graphics.Bitmap.createBitmap(
                                16,
                                16,
                                android.graphics.Bitmap.Config.ARGB_8888,
                            )
                        val bytes = java.io.ByteArrayOutputStream()
                        bitmap.eraseColor(android.graphics.Color.RED)
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes)
                        bitmap.recycle()
                        return MockResponse()
                            .setHeader("Content-Type", "image/png")
                            .setBody(Buffer().write(bytes.toByteArray()))
                    }
                    return MockResponse().setResponseCode(404)
                }
            }
        server.start()
    }

    private fun json(body: JSONObject) =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())

    private fun launch(paired: Boolean = true) {
        if (paired) {
            Credentials(context).apply {
                save(this@AppFlowTest.server.url("/").toString().trimEnd('/'), "test-token")
                session("test-session")
            }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { originalIntent = android.content.Intent(it.intent) }
    }

    @After
    fun cleanup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val monitor =
                androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val stages =
                listOf(
                    androidx.test.runner.lifecycle.Stage.RESUMED,
                    androidx.test.runner.lifecycle.Stage.PAUSED,
                    androidx.test.runner.lifecycle.Stage.STARTED,
                    androidx.test.runner.lifecycle.Stage.STOPPED,
                )
            stages
                .flatMap { monitor.getActivitiesInStage(it) }
                .distinct()
                .filterIsInstance<MainActivity>()
                .forEach { activity ->
                    originalIntent?.let { activity.intent = it }
                }
        }
        scenario?.close()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val monitor=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            listOf(androidx.test.runner.lifecycle.Stage.RESUMED,androidx.test.runner.lifecycle.Stage.PAUSED,androidx.test.runner.lifecycle.Stage.STARTED,androidx.test.runner.lifecycle.Stage.STOPPED).flatMap{monitor.getActivitiesInStage(it)}.distinct().filterIsInstance<MainActivity>().forEach{it.finish()}
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        server.shutdown()
    }

    @Test
    fun selectsFreeModelFromCatalog() {
        launch()
        compose.waitUntil(15000){compose.onAllNodesWithContentDescription("会话列表").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("会话列表").performClick()
        compose.onNodeWithText("设置",useUnmergedTree=true).performClick()
        compose.onNodeWithText("模型",useUnmergedTree=true).performClick()
        compose.waitUntil(15000){compose.onAllNodesWithText("Vision Free").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("model-search").performTextInput("Vision")
        compose.onNodeWithText("Text Free").assertDoesNotExist()
        compose.onNodeWithText("Vision Free").performClick()
        compose.onNodeWithText("应用").performClick()
        compose.waitUntil(15000){selectedModel=="openrouter:vision:free"}
        compose.onNodeWithText("模型",useUnmergedTree=true).performClick()
        compose.onNodeWithContentDescription("刷新模型").performClick()
        compose.onNodeWithText("服务端默认",substring=true).performClick()
        compose.onNodeWithText("应用").performClick()
        compose.waitUntil(15000){selectedModel.isEmpty()}
        Assert.assertTrue(requests.count{it.path=="/mobile/v1/models"}>=2)
    }

    @Test
    fun submitsSharedImage() {
        Credentials(context).apply {
            save(this@AppFlowTest.server.url("/").toString().trimEnd('/'), "test-token")
            session("test-session")
        }
        val file =
            java.io.File(context.cacheDir, "photos/shared.png").apply { parentFile!!.mkdirs() }
        val bitmap =
            android.graphics.Bitmap.createBitmap(100, 100, android.graphics.Bitmap.Config.ARGB_8888)
        file.outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        val uri =
            androidx.core.content.FileProvider.getUriForFile(
                context,
                context.packageName + ".files",
                file,
            )
        val intent =
            android.content
                .Intent(context, MainActivity::class.java)
                .setAction(android.content.Intent.ACTION_SEND)
                .setType("image/png")
                .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                .putExtra(android.content.Intent.EXTRA_TEXT, "看看这张图")
        scenario = ActivityScenario.launch(intent)
        scenario!!.onActivity { originalIntent = android.content.Intent(it.intent) }
        compose.waitUntil(15000) {
            compose.onAllNodesWithContentDescription("待发送图片").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("发送").performClick()
        compose.waitUntil(15000) { requests.any { it.path?.endsWith("/messages") == true } }
        val body = requests.first { it.path?.endsWith("/messages") == true }.body.readUtf8()
        Assert.assertTrue(body.contains("name=\"images\""))
        Assert.assertTrue(body.contains("看看这张图"))
    }

    @Test
    fun sendsTextRendersImageAndResumesPermission() {
        launch()
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("说点什么…").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer").performTextInput("你好")
        compose.onNodeWithContentDescription("发送").performClick()
        lateinit var model: ChatViewModel
        scenario!!.onActivity {
            model = androidx.lifecycle.ViewModelProvider(it)[ChatViewModel::class.java]
        }
        compose.waitUntil(15000) { model.state.value.chat.pending.isNotEmpty() }
        compose.onNodeWithTag("messages").performScrollToNode(hasText("思考过程"))
        compose.onNodeWithText("思考过程").performScrollTo().performClick()
        compose.onNodeWithText("正在核对任务").assertExists()
        compose.onNodeWithContentDescription("结果").performScrollTo().performClick()
        capture("xiaoli-preview")
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("messages").performScrollToNode(hasText("允许"))
        compose.onNodeWithText("允许").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(10000) { replied }

        val submitted = requests.first { it.path?.endsWith("/messages") == true }
        Assert.assertEquals("Bearer test-token", submitted.getHeader("Authorization"))
        Assert.assertTrue(submitted.body.readUtf8().contains("你好"))
        val reply = requests.first { it.path?.endsWith("/reply") == true }
        val body = JSONObject(reply.body.readUtf8())
        Assert.assertEquals("permission-1", body.getString("interaction_id"))
        Assert.assertEquals("允许", body.getString("text"))
    }

    @Test
    fun pairingAndHistoryAreOperable() {
        launch(false)
        compose.onNodeWithText("返回小李").performClick()
        compose.onNodeWithText("连接小李").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("server").fetchSemanticsNodes().isNotEmpty()
        }
        compose
            .onNodeWithTag("server")
            .performTextReplacement(server.url("/").toString().trimEnd('/'))
        compose.onNodeWithTag("pair-code").performTextInput("pair-code")
        capture("xiaoli-connect")
        compose.onNodeWithTag("connect-confirm").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("说点什么…").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("会话列表").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("测试对话").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("测试对话").performClick()
        Assert.assertTrue(requests.any { it.path == "/xiaozhi/pair" })
    }

    private fun shell(command: String): String {
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation()
                    .uiAutomation
                    .executeShellCommand(command)
            )
            .bufferedReader()
            .use { it.readText() }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        shell("screencap -p /sdcard/Download/$name.png")
    }

    @Test
    fun answersFreeTextQuestion() {
        sent = true
        questionCard = true
        launch()
        lateinit var model: ChatViewModel
        scenario!!.onActivity {
            model = androidx.lifecycle.ViewModelProvider(it)[ChatViewModel::class.java]
        }
        compose.waitUntil(15000) { model.state.value.chat.pending.isNotEmpty() }
        compose.onNodeWithTag("messages").performScrollToNode(hasText("补充回答"))
        compose.onNodeWithText("补充回答").performTextInput("先做第一项")
        compose.onNodeWithContentDescription("提交回答").performClick()
        compose.waitUntil(10000) { replied }
        val json =
            JSONObject(requests.first { it.path?.endsWith("/reply") == true }.body.readUtf8())
        Assert.assertEquals("question-1", json.getString("interaction_id"))
        Assert.assertEquals("先做第一项", json.getString("text"))
    }

    @Test
    fun registersDefaultAssistantAndOpensSystemSession() {
        // The role change can destroy/recreate the task. Let the system own its
        // lifecycle instead of attaching ActivityScenario to the original task.
        originalIntent=android.content.Intent(context,MainActivity::class.java)
        context.startActivity(android.content.Intent(originalIntent).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        compose.waitUntil(10000){compose.onAllNodesWithText("飞书登录").fetchSemanticsNodes().isNotEmpty()}
        shell("cmd role remove-role-holder android.app.role.ASSISTANT com.cyeam.xiaoli")
        val result = shell("cmd role add-role-holder android.app.role.ASSISTANT com.cyeam.xiaoli")
        Assert.assertFalse(result, result.contains("Error"))
        compose.waitUntil(10000) {
            context
                .getSystemService(android.app.role.RoleManager::class.java)
                .isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)
        }
        compose.waitUntil(10000) {
            android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    "voice_interaction_service",
                )
                .orEmpty()
                .contains("com.cyeam.xiaoli")
        }
        val component =
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                "voice_interaction_service",
            )
        Assert.assertTrue(component.orEmpty(), component.orEmpty().contains("com.cyeam.xiaoli"))
        shell("input keyevent KEYCODE_HOME")
        context.startService(
            android.content
                .Intent(context, AssistantService::class.java)
                .setAction(android.content.Intent.ACTION_ASSIST)
        )
        compose.waitUntil(10000) {
            var shown = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                shown =
                    androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                        .filterIsInstance<MainActivity>()
                        .any { it.intent.getBooleanExtra("assistant", false) }
            }
            shown
        }
        compose.onNodeWithText("有什么想做的？").assertExists()
        capture("xiaoli-home")
    }

    @Test
    fun scrollsLongHistoryWithoutLosingComposer() {
        longHistory = true
        launch()
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("说点什么…").fetchSemanticsNodes().isNotEmpty()
        }
        // Warm rendering/ART before measuring steady scrolling separately from startup.
        repeat(3) {
            compose.onNodeWithTag("messages").performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
        repeat(3) {
            compose.onNodeWithTag("messages").performTouchInput { swipeDown() }
            compose.waitForIdle()
        }
        val frames = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        val cpuFrames=java.util.concurrent.ConcurrentLinkedQueue<Long>()
        val listener =
            android.view.Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                frames.add(metrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION))
                cpuFrames.add(listOf(android.view.FrameMetrics.INPUT_HANDLING_DURATION,android.view.FrameMetrics.ANIMATION_DURATION,android.view.FrameMetrics.LAYOUT_MEASURE_DURATION,android.view.FrameMetrics.DRAW_DURATION).sumOf{metrics.getMetric(it).coerceAtLeast(0)})
            }
        scenario!!.onActivity {
            it.window.addOnFrameMetricsAvailableListener(
                listener,
                android.os.Handler(android.os.Looper.getMainLooper()),
            )
        }
        // Real-time system gestures keep frame timings independent of Compose's virtual test clock.
        val width=context.resources.displayMetrics.widthPixels
        val height=context.resources.displayMetrics.heightPixels
        repeat(6){shell("input swipe ${width/2} ${height*7/10} ${width/2} ${height/4} 350");compose.waitForIdle()}
        repeat(6){shell("input swipe ${width/2} ${height/4} ${width/2} ${height*7/10} 350");compose.waitForIdle()}
        compose.onNodeWithContentDescription("录音").assertExists()
        scenario!!.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
        val values = frames.toList().sorted()
        Assert.assertTrue("No rendered frames", values.isNotEmpty())
        val json =
            JSONObject()
                .put("frames", values.size)
                .put("p50_ms", values[values.size / 2] / 1e6)
                .put(
                    "p95_ms",
                    values[(values.size * 95 / 100).coerceAtMost(values.lastIndex)] / 1e6,
                )
                .put("frozen_frames_700ms", values.count { it > 700_000_000 })
        val cpu=cpuFrames.toList().sorted()
        if(cpu.isNotEmpty())json.put("ui_work_p95_ms",cpu[(cpu.size*95/100).coerceAtMost(cpu.lastIndex)]/1e6)
        android.util.Log.i("XiaoliPerformance", json.toString())
        val file = java.io.File(context.getExternalFilesDir(null), "performance.json")
        file.writeText(json.toString(2))
        shell("cp ${file.path} /sdcard/Download/xiaoli-performance.json")
    }
}
