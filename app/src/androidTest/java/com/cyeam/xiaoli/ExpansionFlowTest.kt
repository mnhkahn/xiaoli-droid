package com.cyeam.xiaoli

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*

class ExpansionFlowTest {
 @get:Rule val compose=createEmptyComposeRule()
 private val requests=CopyOnWriteArrayList<RecordedRequest>()
 private lateinit var context:Context
 private lateinit var server:MockWebServer
 private var scenario:ActivityScenario<MainActivity>?=null
 private var originalIntent:Intent?=null
 @Volatile private var notificationReady=false
 @Volatile private var activityRequests=0
 private var title="普通对话"
 private var pinned=false
 private var archived=false
 private var deleted=false
 private var branch=false
 private var branchText=""
 private var instructions=""
 private var memoryEnabled=true
 private val memories=JSONObject()
 private val tasks=JSONArray()
 private fun json(value:JSONObject)=MockResponse().setHeader("Content-Type","application/json").setBody(value.toString())
 private fun snapshot(id:String):JSONObject=JSONObject().put("id",id).put("title",title).put("pinned",pinned).put("archived",archived).put("deleted",deleted).put("busy",false).put("revision",1).put("pending",JSONArray()).put("messages",if(id=="branch")JSONArray() else JSONArray().put(JSONObject().put("id","user-original").put("role","user").put("text","原来的问题").put("status","complete")).put(JSONObject().put("id","answer-original").put("role","assistant").put("text","原来的回答").put("status","complete"))).apply{if(id=="branch")put("draft",JSONObject().put("text",branchText).put("images",JSONArray()))}
 @Before fun setup(){
  context=InstrumentationRegistry.getInstrumentation().targetContext
  context.stopService(Intent(context,TaskNotifications::class.java))
  context.getSharedPreferences("preferences",0).edit().clear().commit()
  context.getSharedPreferences("connection",0).edit().clear().commit()
  File(context.filesDir,"drafts").deleteRecursively()
  server=MockWebServer()
  server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse{
   requests+=request
   val path=request.requestUrl!!.encodedPath
   if(path=="/mobile/v1/sessions"&&request.method=="GET"){
    val query=request.requestUrl!!.queryParameter("q").orEmpty()
    val view=request.requestUrl!!.queryParameter("view").orEmpty()
    val items=JSONArray()
    if(title.contains(query)&&deleted==(view=="deleted")&&(deleted||archived==(view=="archived")))items.put(JSONObject().put("id","normal").put("title",title).put("pinned",pinned).put("archived",archived).put("deleted",deleted))
    return json(JSONObject().put("sessions",items))
   }
   if(path=="/mobile/v1/activity"){
    activityRequests++
    val item=JSONObject().put("session_id","task-session").put("message_id",if(notificationReady)"new-result" else "old-result").put("title","后台任务").put("status","complete")
    return MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: ${JSONObject().put("items",JSONArray().put(item))}\n\n")
   }
   if(path=="/mobile/v1/profile"){
    if(request.method=="PATCH"){val body=JSONObject(request.body.readUtf8());instructions=body.optString("instructions",instructions);memoryEnabled=body.optBoolean("memory_enabled",memoryEnabled)}
    return json(JSONObject().put("instructions",instructions).put("memory_enabled",memoryEnabled))
   }
   if(path=="/mobile/v1/memories"){
    if(request.method in listOf("PUT","DELETE")){val body=JSONObject(request.body.readUtf8());if(request.method=="DELETE")memories.remove(body.getString("key"))else memories.put(body.getString("key"),body.getString("value"))}
    return json(JSONObject().put("memories",memories))
   }
   if(path=="/mobile/v1/tasks"){
    if(request.method=="POST"){val body=JSONObject(request.body.readUtf8()).put("id","task-one").put("status","scheduled");tasks.put(body);return json(body).setResponseCode(201)}
    return json(JSONObject().put("tasks",tasks))
   }
   if(path=="/mobile/v1/sessions/normal"&&request.method=="PATCH"){
    val body=JSONObject(request.body.readUtf8());title=body.optString("title",title);pinned=body.optBoolean("pinned",pinned);archived=body.optBoolean("archived",archived);deleted=body.optBoolean("deleted",deleted);return json(snapshot("normal"))
   }
   if(path=="/mobile/v1/sessions/normal/fork") {branch=true;branchText=JSONObject(request.body.readUtf8()).optString("text","原来的问题");return json(snapshot("branch")).setResponseCode(201)}
   if(path.endsWith("/events")){val id=path.split('/')[4];val value=snapshot(id);return MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: ${JSONObject().put("id",1).put("type","snapshot").put("data",value)}\n\n").setBodyDelay(100,java.util.concurrent.TimeUnit.MILLISECONDS)}
   if(path.startsWith("/mobile/v1/sessions/"))return json(snapshot(path.split('/')[4]))
   return MockResponse().setResponseCode(404)
  }}
  server.start()
  Credentials(context).apply{save(this@ExpansionFlowTest.server.url("/").toString().trimEnd('/'),"test-token");session("normal")}
 }
 private fun launch(){scenario=ActivityScenario.launch(MainActivity::class.java);scenario!!.onActivity{originalIntent=Intent(it.intent)};compose.waitUntil(15000){compose.onAllNodesWithText("原来的问题").fetchSemanticsNodes().isNotEmpty()}}
 private fun drawer(){compose.onNodeWithContentDescription("会话列表").performClick()}
 private fun settings(){drawer();compose.onNodeWithText("设置").performClick()}
 @After fun cleanup(){scenario?.onActivity{it.intent=originalIntent};scenario?.close();context.stopService(Intent(context,TaskNotifications::class.java));server.shutdown();context.getSharedPreferences("preferences",0).edit().clear().commit();File(context.filesDir,"drafts").deleteRecursively()}
 @Test fun renamesPinsArchivesAndSearches(){
  launch();drawer()
  compose.onNodeWithContentDescription("管理会话").performClick();compose.onNodeWithText("重命名").performClick()
  compose.onAllNodes(hasSetTextAction()).filterToOne(hasText("普通对话")).performTextReplacement("口算复习")
  compose.onNodeWithText("保存").performClick()
  compose.waitUntil(10000){title=="口算复习"}
  compose.onNodeWithContentDescription("管理会话").performClick();compose.onNodeWithText("置顶").performClick()
  compose.waitUntil(10000){pinned}
  compose.onNode(hasSetTextAction() and hasText("搜索")).performTextInput("口算")
  compose.waitUntil(10000){requests.any{it.requestUrl?.queryParameter("q")=="口算"}}
  compose.onNodeWithContentDescription("管理会话").performClick();compose.onAllNodesWithText("归档").onLast().performClick()
  compose.waitUntil(10000){archived}
  compose.onNodeWithText("归档").performClick()
  compose.waitUntil(10000){requests.any{it.requestUrl?.queryParameter("view")=="archived"}}
  compose.onNodeWithText("★ 口算复习").assertExists()
 }
 @Test fun editingCreatesIndependentDraft(){
  launch();compose.onAllNodesWithContentDescription("消息操作")[0].performClick();compose.onNodeWithText("编辑").performClick()
  compose.onAllNodes(hasSetTextAction()).filterToOne(hasText("原来的问题")).performTextReplacement("修改后的问题")
  compose.onNodeWithText("建立分支").performClick()
  compose.waitUntil(10000){branch&&compose.onAllNodes(hasSetTextAction() and hasText("修改后的问题")).fetchSemanticsNodes().isNotEmpty()}
  Assert.assertTrue(requests.none{it.path?.endsWith("/messages")==true})
  Assert.assertEquals("修改后的问题",branchText)
 }
 @Test fun composerRestoresFromDiskWithNewViewModel(){
  launch();compose.onNodeWithTag("composer").performTextInput("尚未发送的草稿")
  compose.waitUntil(10000){File(context.filesDir,"drafts").listFiles().orEmpty().any{it.readText().contains("尚未发送的草稿")}}
  scenario!!.close();scenario=null
  launch()
  compose.onNode(hasSetTextAction() and hasText("尚未发送的草稿")).assertExists()
 }
 @Test fun memoryAndCustomInstructionsAreEditable(){
  launch();settings();compose.onNodeWithText("个性化").performClick()
  compose.waitUntil(10000){compose.onAllNodesWithText("添加记忆").fetchSemanticsNodes().isNotEmpty()}
  compose.onNode(hasSetTextAction() and hasText("自定义说明")).performTextInput("回答简洁")
  compose.onNodeWithText("添加记忆").performClick()
  compose.onNode(hasSetTextAction() and hasText("名称")).performTextInput("语言")
  compose.onNode(hasSetTextAction() and hasText("内容")).performTextInput("中文")
  compose.onAllNodesWithText("保存").onLast().performClick()
  compose.waitUntil(10000){memories.optString("语言")=="中文"}
  compose.onNodeWithText("保存").performClick()
  compose.waitUntil(10000){instructions=="回答简洁"}
 }
 @Test fun backgroundResultsNotifyAndOpenOwnedConversation(){
  launch()
  val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
  automation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS").close()
  scenario!!.onActivity{activity->
   androidx.lifecycle.ViewModelProvider(activity)[ChatViewModel::class.java].notificationPreference(true)
   androidx.core.content.ContextCompat.startForegroundService(activity,Intent(activity,TaskNotifications::class.java))
  }
  compose.waitUntil(15000){activityRequests>0&&context.getSharedPreferences("preferences",0).all.keys.any{it.startsWith("seen-")}}
  notificationReady=true
  val manager=context.getSystemService(android.app.NotificationManager::class.java)
  compose.waitUntil(20000){manager.activeNotifications.any{it.id=="task-session".hashCode()}}
  manager.activeNotifications.first{it.id=="task-session".hashCode()}.notification.contentIntent.send()
  compose.waitUntil(10000){var id="";scenario!!.onActivity{id=androidx.lifecycle.ViewModelProvider(it)[ChatViewModel::class.java].state.value.chat.sessionId};id=="task-session"}
  context.stopService(Intent(context,TaskNotifications::class.java));manager.cancelAll()
 }
 @Test fun schedulesRealTaskPayload(){
  launch();drawer();compose.onNodeWithText("任务").performClick()
  compose.waitUntil(10000){runCatching{compose.onNodeWithText("新建").assertIsEnabled();true}.getOrDefault(false)}
  compose.onNodeWithText("新建").performClick()
  compose.waitUntil(10000){compose.onAllNodes(hasSetTextAction() and hasText("标题")).fetchSemanticsNodes().isNotEmpty()}
  compose.onNode(hasSetTextAction() and hasText("标题")).performTextInput("练口算")
  compose.onNode(hasSetTextAction() and hasText("到时做什么")).performTextInput("出十道口算")
  compose.onNodeWithText("保存").performClick()
  compose.waitUntil(10000){tasks.length()==1}
  val task=tasks.getJSONObject(0);Assert.assertEquals("normal",task.getString("session_id"));Assert.assertEquals("出十道口算",task.getString("prompt"));Assert.assertTrue(java.time.OffsetDateTime.parse(task.getString("next")).isAfter(java.time.OffsetDateTime.now()))
 }
}
