package com.cyeam.xiaoli

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*

/** Explicit opt-in integration test: talks to the deployed service and the selected provider. */
class LiveArithmeticTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun gradesUploadedWorksheetUsingSelectedModel(){
        val args=InstrumentationRegistry.getArguments()
        val code=args.getString("livePairCode").orEmpty()
        Assume.assumeTrue("Live integration requires an explicitly supplied pairing code",code.isNotBlank())
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val server=args.getString("liveServer")?:"https://xiaoli-server.fly.dev"
        val selected="nvidia/nemotron-3.5-super-vl:free"
        val image=File(context.cacheDir,"photos/live.jpg")
        Assert.assertTrue("Live worksheet missing",image.isFile)
        val originalSha=MessageDigest.getInstance("SHA-256").digest(image.readBytes()).joinToString(""){"%02x".format(it)}
        if(code!="reuse")Credentials(context).clear()
        val uri=FileProvider.getUriForFile(context,context.packageName+".files",image)
        val prompt="这是一张口算作业照片。只批改最上面已作答的前10题，下面未作答的题不计错。请直接阅读图片，仅用当前模型判断，不要调用工具、OCR或其他模型。用Markdown表格列出题号、原题、识别到的学生答案、对错或待核对。必须忠实记录手写答案，看不清则待核对，不准用正确计算值反推识别值。不要展示正确答案。最后汇总。"
        val intent=Intent(context,MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM,uri).putExtra(Intent.EXTRA_TEXT,prompt)
        val launchIntent=Intent(context,MainActivity::class.java)
        val scenario=ActivityScenario.launch<MainActivity>(launchIntent)
        lateinit var model:ChatViewModel
        scenario.onActivity{model=ViewModelProvider(it)[ChatViewModel::class.java]}
        try {
            if(code!="reuse") {
            if (compose.onAllNodesWithText("返回小李").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("返回小李").performClick()
            compose.onNodeWithText("连接小李").performClick()
            compose.onNodeWithTag("server").performTextReplacement(server)
            compose.onNodeWithTag("pair-code").performTextInput(code)
            compose.onNodeWithTag("connect-confirm").performClick()
            }
            compose.waitUntil(60000){model.state.value.paired&&model.state.value.chat.sessionId.isNotBlank()&&!model.state.value.loading}
            val previousSession=model.state.value.chat.sessionId
            compose.onNodeWithTag("new-session").performClick()
            compose.waitUntil(30000){model.state.value.chat.sessionId.isNotBlank()&&model.state.value.chat.sessionId!=previousSession&&!model.state.value.loading}
            compose.onNodeWithContentDescription("会话列表").performClick()
            compose.onNodeWithText("设置",useUnmergedTree=true).performClick()
            compose.onNodeWithText("模型",useUnmergedTree=true).performClick()
            compose.waitUntil(30000){!model.state.value.modelsLoading&&model.state.value.models.isNotEmpty()}
            compose.onNodeWithTag("model-search").performTextReplacement(selected)
            scenario.onActivity{it.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(it.window.decorView.windowToken,0)}
            compose.waitForIdle()
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
                File(context.getExternalFilesDir(null),"live-model-picker.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                bitmap.recycle()
            }
            compose.onNode(hasText(selected) and !hasSetTextAction()).performClick()
            compose.onNodeWithText("应用").performClick()
            compose.waitUntil(30000){model.state.value.chat.model==selected||model.state.value.error!=null}
            Assert.assertNull("Model selection failed",model.state.value.error)
            compose.onNodeWithText("完成").performClick()
            scenario.onActivity{it.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))}
            compose.waitUntil(30000){model.state.value.attachments.isNotEmpty()&&!model.state.value.preparing}
            compose.onNodeWithContentDescription("发送").performClick()
            compose.waitUntil(240000){model.state.value.chat.messages.any{it.role=="assistant"&&it.status!="streaming"}}
            val state=model.state.value
            val answer=state.chat.messages.last{it.role=="assistant"}
            val result=JSONObject().put("server",server).put("sessionId",state.chat.sessionId).put("selectedModel",state.chat.model).put("originalImageSha256",originalSha).put("status",answer.status).put("answer",answer.text).put("thinkingChars",answer.thinking.length).put("submittedImages",state.chat.messages.filter{it.role=="user"}.sumOf{it.images.size})
            val output=File(context.getExternalFilesDir(null),"live-arithmetic.json");output.writeText(result.toString(2))
            val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("cp ${output.path} /sdcard/Download/xiaoli-live-arithmetic.json")).close()
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("screencap -p /sdcard/Download/xiaoli-live-arithmetic.png")).close()
            Assert.assertEquals("Wrong model",selected,state.chat.model)
            Assert.assertEquals("Live provider did not complete: ${answer.text}","complete",answer.status)
            Assert.assertTrue("No worksheet image submitted",result.getInt("submittedImages")==1)
            Assert.assertTrue("Missing requested worksheet table: ${answer.text}", Regex("\\|\\s*10\\s*\\|").containsMatchIn(answer.text))
            Assert.assertFalse("Spurious external evidence requirement",answer.text.contains("需要真实工具证据"))
        } finally {scenario.onActivity{it.intent=launchIntent};scenario.close()}
    }
}
