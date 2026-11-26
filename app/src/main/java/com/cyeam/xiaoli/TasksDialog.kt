package com.cyeam.xiaoli

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
fun TasksDialog(state:AppState,vm:ChatViewModel,onNotifications:()->Unit,onDismiss:()->Unit){
 val scope=rememberCoroutineScope();val context=LocalContext.current
 var tasks by remember{mutableStateOf<List<JSONObject>>(emptyList())}
 var busy by remember{mutableStateOf(false)};var loaded by remember{mutableStateOf(false)}
 var editor by remember{mutableStateOf(false)};var editing by remember{mutableStateOf<JSONObject?>(null)}
 var title by remember{mutableStateOf("")};var prompt by remember{mutableStateOf("")};var repeat by remember{mutableStateOf("none")}
 var next by remember{mutableStateOf(LocalDateTime.now().plusHours(1).withSecond(0).withNano(0))}
 val zone=ZoneId.systemDefault();val format=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
 suspend fun reload(){tasks=vm.api.json("/mobile/v1/tasks").objects("tasks");loaded=true}
 fun operation(block:suspend()->Unit){scope.launch{busy=true;try{block();reload()}catch(e:Exception){vm.error(e.message)}finally{busy=false}}}
 LaunchedEffect(Unit){operation{}}
 AlertDialog(onDismissRequest=onDismiss,title={Text("任务")},text={Column{
  Row {Text("后台通知",Modifier.weight(1f));Switch(state.notifications,{onNotifications()})}
  if(state.notifications)Text("保持系统通知中的连接即可接收结果。",style=MaterialTheme.typography.bodySmall)
  if(!loaded||busy)LinearProgressIndicator(Modifier.fillMaxWidth())
  LazyColumn(Modifier.heightIn(max=360.dp)) {
   items(tasks,key={it.getString("id")}){task->Column(Modifier.padding(vertical=8.dp)){
    TextButton(onClick={vm.openSession(task.getString("session_id"));onDismiss()}){Text(task.getString("title"))}
    Text(runCatching{Instant.parse(task.getString("next")).atZone(zone).format(format)}.getOrDefault("")+" · "+when(task.optString("status")){"waiting"->"等待确认";"streaming"->"执行中";"complete"->"已完成";"error","interrupted"->"需要检查";"paused"->"已暂停";else->"已安排"},style=MaterialTheme.typography.bodySmall)
    Row{
     TextButton(enabled=!busy,onClick={editing=task;title=task.getString("title");prompt=task.getString("prompt");repeat=task.getString("repeat");next=Instant.parse(task.getString("next")).atZone(zone).toLocalDateTime();editor=true}){Text("编辑")}
     TextButton(enabled=!busy,onClick={operation{vm.api.json("/mobile/v1/tasks/${task.getString("id")}","PATCH",JSONObject().put("paused",!task.optBoolean("paused")))}}){Text(if(task.optBoolean("paused"))"启用" else "暂停")}
     TextButton(enabled=!busy,onClick={operation{vm.api.json("/mobile/v1/tasks/${task.getString("id")}","DELETE")}}){Text("删除")}
    }
   }}
  }
 }},confirmButton={TextButton(onClick=onDismiss){Text("关闭")}},dismissButton={TextButton(enabled=!busy&&state.chat.sessionId.isNotBlank()&&!state.chat.temporary,onClick={editing=null;title="";prompt="";repeat="none";next=LocalDateTime.now().plusHours(1).withSecond(0).withNano(0);editor=true}){Text("新建")}})
 if(editor)AlertDialog(onDismissRequest={editor=false},title={Text(if(editing==null)"新任务" else "编辑任务")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
  OutlinedTextField(title,{title=it},label={Text("标题")},singleLine=true)
  OutlinedTextField(prompt,{prompt=it},label={Text("到时做什么")},minLines=2,maxLines=4)
  TextButton(onClick={DatePickerDialog(context,{_,year,month,day->TimePickerDialog(context,{_,hour,minute->next=LocalDateTime.of(year,month+1,day,hour,minute)},next.hour,next.minute,true).show()},next.year,next.monthValue-1,next.dayOfMonth).show()}){Icon(Icons.Rounded.Schedule,null);Text(next.format(format))}
  Row{listOf("none" to "一次","daily" to "每天","weekly" to "每周").forEach{(value,label)->FilterChip(selected=repeat==value,onClick={repeat=value},label={Text(label)},modifier=Modifier.padding(end=8.dp))}}
 }},confirmButton={TextButton(enabled=!busy&&title.isNotBlank()&&prompt.isNotBlank(),onClick={operation{
  val patch=JSONObject().put("title",title).put("prompt",prompt).put("next",next.atZone(zone).toOffsetDateTime().toString()).put("timezone",zone.id).put("repeat",repeat).put("paused",false).put("session_id",editing?.getString("session_id")?:state.chat.sessionId)
  vm.api.json(if(editing==null)"/mobile/v1/tasks" else "/mobile/v1/tasks/${editing!!.getString("id")}",if(editing==null)"POST" else "PATCH",patch);editor=false
 }}){Text("保存")}},dismissButton={TextButton(onClick={editor=false}){Text("取消")}})
}
