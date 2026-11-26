package com.cyeam.xiaoli

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*

/** Injected recorded audio, real recorder lifecycle, deployed ASR/model and Android TTS. */
class LiveVoiceTest {
 @get:Rule val compose=createEmptyComposeRule()
 @Test fun completesVoiceTurnAndAutomaticallyListensAgain(){
  Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveVoiceConversation")=="yes")
  val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
  val credentials=Credentials(context);Assert.assertTrue(credentials.token().isNotBlank())
  val sample=File(context.cacheDir,"voice-validation.ogg");Assert.assertTrue(sample.isFile)
  instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO").close()
  val api=Api(credentials.server,credentials.device,credentials.token())
  val id=runBlocking{val session=api.json("/mobile/v1/sessions","POST").getString("id");api.json("/mobile/v1/sessions/$session","PATCH",JSONObject().put("model","nvidia/nemotron-3.5-super-vl:free"));session}
  credentials.session(id)
  val launch=Intent(context,MainActivity::class.java)
  val scenario=ActivityScenario.launch<MainActivity>(launch)
  lateinit var vm:ChatViewModel
  scenario.onActivity{vm=ViewModelProvider(it)[ChatViewModel::class.java]}
  var originalRecording:File?=null
  try{
   compose.waitUntil(30000){vm.state.value.chat.sessionId==id&&!vm.state.value.loading}
   compose.onNodeWithContentDescription("添加图片").performClick();compose.onNodeWithText("语音对话").performClick()
   compose.waitUntil(15000){vm.state.value.recording||vm.state.value.error!=null}
   Assert.assertTrue("Continuous voice did not start: ${vm.state.value.error}",vm.state.value.voiceMode&&vm.state.value.recording)
   val injected=File(context.cacheDir,"voice-injected-${UUID.randomUUID()}.ogg");sample.copyTo(injected)
   scenario.onActivity{activity->
    val field=MainActivity::class.java.getDeclaredField("audio");field.isAccessible=true;originalRecording=field.get(activity) as? File;field.set(activity,injected)
   }
   // Allow the real recorder to reach a valid stop state; the uploaded audio is
   // the deterministic sample, not the emulator's silent microphone stream.
   Thread.sleep(500)
   compose.onNodeWithContentDescription("结束录音").performClick()
   compose.waitUntil(240000){(vm.state.value.voiceRequestId.isNotBlank()&&vm.state.value.chat.messages.any{it.role=="assistant"&&it.status=="complete"}&&vm.state.value.recording&&vm.state.value.voiceStage=="listening")||vm.state.value.voiceStage=="paused"}
   Assert.assertEquals("Voice turn paused: ${vm.state.value.error}","listening",vm.state.value.voiceStage)
   Assert.assertTrue("Voice did not resume recording",vm.state.value.recording)
   val user=vm.state.value.chat.messages.first{it.role=="user"};Assert.assertTrue("ASR did not preserve sample speech",user.text.contains("口算"))
   File(context.getExternalFilesDir(null),"live-voice.json").writeText(JSONObject().put("model",vm.state.value.chat.model).put("asrText",user.text).put("reply",vm.state.value.chat.messages.last().text).put("resumedListening",true).put("audioInput","injected-recording").toString(2))
   compose.onNodeWithText("结束语音").performClick()
   compose.waitUntil(10000){!vm.state.value.voiceMode&&!vm.state.value.recording}
  }finally{
   originalRecording?.delete();scenario.onActivity{it.intent=launch};scenario.close()
   runBlocking{runCatching{api.json("/mobile/v1/sessions/$id","PATCH",JSONObject().put("deleted",true))}}
  }
 }
}
