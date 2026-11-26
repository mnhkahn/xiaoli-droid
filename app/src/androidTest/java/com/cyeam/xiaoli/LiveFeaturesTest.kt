package com.cyeam.xiaoli

import android.content.Intent
import android.graphics.pdf.PdfDocument
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*

/** Deployed-service checks, opt-in only. Never contains pairing credentials. */
class LiveFeaturesTest {
 @Test fun deployedDocumentsMemoriesTasksNotificationsAndTemporaryConversation()=runBlocking {
  Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveFeatures")=="yes")
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val credentials=Credentials(context)
  Assert.assertTrue("Pairing required",credentials.token().isNotBlank())
  val api=Api(credentials.server,credentials.device,credentials.token())
  val selected="nvidia/nemotron-3.5-super-vl:free"
  val report=JSONObject().put("model",selected)
  val sessions=mutableListOf<String>();var taskId="";var temporaryId=""
  val memoryKey="验收-${UUID.randomUUID()}"
  val profile=api.json("/mobile/v1/profile")
  var scenario:ActivityScenario<MainActivity>?=null
  suspend fun create():String {val snapshot=api.json("/mobile/v1/sessions","POST");val id=snapshot.getString("id");sessions+=id;api.json("/mobile/v1/sessions/$id","PATCH",JSONObject().put("model",selected));return id}
  suspend fun awaitAnswer(id:String):JSONObject=withTimeout(240000){
   while(true){val snapshot=api.json("/mobile/v1/sessions/$id");val answer=snapshot.objects("messages").lastOrNull{it.optString("role")=="assistant"};if(answer!=null&&answer.optString("status")!="streaming"){Assert.assertEquals(answer.optString("text"),"complete",answer.getString("status"));return@withTimeout snapshot};delay(1000)}
   @Suppress("UNREACHABLE_CODE") JSONObject()
  }
  try{
   api.json("/mobile/v1/profile","PATCH",JSONObject().put("instructions",profile.optString("instructions")+"\n验收期间保持简洁。").put("memory_enabled",true))
   api.json("/mobile/v1/memories","PUT",JSONObject().put("key",memoryKey).put("value","中文短句"))
   Assert.assertEquals("中文短句",api.json("/mobile/v1/memories").getJSONObject("memories").getString(memoryKey));report.put("memoryCRUD",true)
   api.json("/mobile/v1/memories","DELETE",JSONObject().put("key",memoryKey))
   val id=create()
   val pdf=File(context.cacheDir,"exports/validation.pdf").apply{parentFile!!.mkdirs()}
   val document=PdfDocument()
   try{val page=document.startPage(PdfDocument.PageInfo.Builder(600,800,1).create());page.canvas.drawText("Xiaoli PDF validation 314159",40f,80f,android.graphics.Paint().apply{textSize=20f});document.finishPage(page);pdf.outputStream().use{document.writeTo(it)}}finally{document.close()}
   val started=System.currentTimeMillis()
   api.multipart("/mobile/v1/sessions/$id/messages",mapOf("text" to "只根据附件，告诉我 PDF 里的六位验证码。不要调用工具，不需要读取其他文件。","request_id" to UUID.randomUUID().toString()),listOf("files" to pdf))
   val documentResult=awaitAnswer(id)
   val file=documentResult.objects("messages").first{it.getString("role")=="user"}.objects("files").single()
   Assert.assertTrue(api.download(file.getString("url")+"?preview=text").toString(Charsets.UTF_8).contains("314159"))
   Assert.assertTrue(documentResult.objects("messages").last().getString("text").contains("314159"))
   report.put("pdf",JSONObject().put("parsed",true).put("modelRead",true).put("elapsedMs",System.currentTimeMillis()-started));pdf.delete()
   val taskSession=create()
   scenario=ActivityScenario.launch(MainActivity::class.java)
   InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS").close()
   scenario.onActivity{activity->ViewModelProvider(activity)[ChatViewModel::class.java].notificationPreference(true);androidx.core.content.ContextCompat.startForegroundService(activity,Intent(activity,TaskNotifications::class.java))}
   delay(3000)
   val task=api.json("/mobile/v1/tasks","POST",JSONObject().put("session_id",taskSession).put("title","定时验收").put("prompt","请只回复：定时验收通过。不要使用任何工具。").put("next",Instant.now().plusSeconds(20).toString()).put("timezone","Asia/Shanghai").put("repeat","none"))
   taskId=task.getString("id")
   scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
   val taskResult=awaitAnswer(taskSession)
   Assert.assertTrue(taskResult.objects("messages").last().getString("text").contains("定时验收通过"))
   val notifications=context.getSystemService(android.app.NotificationManager::class.java)
   withTimeout(20000){while(notifications.activeNotifications.none{it.id==taskSession.hashCode()})delay(250)}
   report.put("scheduledTask",true).put("backgroundNotification",true)
   scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
   val temporary=api.json("/mobile/v1/sessions","POST",JSONObject().put("temporary",true));temporaryId=temporary.getString("id")
   api.json("/mobile/v1/sessions/$temporaryId","PATCH",JSONObject().put("model",selected))
   api.multipart("/mobile/v1/sessions/$temporaryId/messages",mapOf("text" to "请只回复：临时验收通过。","request_id" to UUID.randomUUID().toString()),emptyList())
   val temporaryResult=awaitAnswer(temporaryId)
   Assert.assertTrue(temporaryResult.objects("messages").last().getString("text").contains("临时验收通过"))
   Assert.assertTrue(api.json("/mobile/v1/sessions").objects("sessions").none{it.getString("id")==temporaryId})
   api.json("/mobile/v1/sessions/$temporaryId/discard","POST");temporaryId=""
   report.put("temporaryConversation",true)
   val audio=File(context.cacheDir,"voice-validation.ogg")
   if(audio.isFile){val transcription=api.multipart("/mobile/v1/transcribe",emptyMap(),listOf("audio" to audio)).getString("text");Assert.assertTrue("ASR did not recognize test speech",transcription.contains("口算")||transcription.contains("检查"));report.put("asr",true)}
   File(context.getExternalFilesDir(null),"live-features.json").writeText(report.toString(2))
  }finally{
   withContext(NonCancellable){
    runCatching{api.json("/mobile/v1/profile","PATCH",profile)}
    runCatching{api.json("/mobile/v1/memories","DELETE",JSONObject().put("key",memoryKey))}
    if(taskId.isNotBlank())runCatching{api.json("/mobile/v1/tasks/$taskId","DELETE")}
    if(temporaryId.isNotBlank())runCatching{api.json("/mobile/v1/sessions/$temporaryId/discard","POST")}
    for(id in sessions)runCatching{api.json("/mobile/v1/sessions/$id","PATCH",JSONObject().put("deleted",true))}
   }
   scenario?.close();context.stopService(Intent(context,TaskNotifications::class.java));context.getSharedPreferences("preferences",0).edit().putBoolean("notifications",false).apply();context.getSystemService(android.app.NotificationManager::class.java).cancelAll()
  }
 }
}
