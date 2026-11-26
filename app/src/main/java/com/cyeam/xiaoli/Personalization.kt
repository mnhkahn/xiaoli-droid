package com.cyeam.xiaoli

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun Personalization(vm:ChatViewModel,onDismiss:()->Unit){
 val scope=rememberCoroutineScope()
 var instructions by remember{mutableStateOf("")}
 var memory by remember{mutableStateOf(true)}
 var memories by remember{mutableStateOf<Map<String,String>>(emptyMap())}
 var loaded by remember{mutableStateOf(false)}
 var busy by remember{mutableStateOf(false)}
 var key by remember{mutableStateOf("")}
 var value by remember{mutableStateOf("")}
 var editor by remember{mutableStateOf(false)}
 suspend fun reload(){val json=vm.api.json("/mobile/v1/memories").optJSONObject("memories")?:JSONObject();memories=json.keys().asSequence().associateWith{json.getString(it)}}
 fun operation(block:suspend()->Unit){scope.launch{busy=true;try{block()}catch(e:Exception){vm.error(e.message)}finally{busy=false}}}
 LaunchedEffect(Unit){try{val profile=vm.api.json("/mobile/v1/profile");instructions=profile.optString("instructions");memory=profile.optBoolean("memory_enabled",true);reload();loaded=true}catch(e:Exception){vm.error(e.message)}}
 AlertDialog(onDismissRequest=onDismiss,title={Text("个性化")},text={
  LazyColumn(Modifier.heightIn(max=440.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
   item{if(!loaded)LinearProgressIndicator(Modifier.fillMaxWidth())}
   item{OutlinedTextField(instructions,{instructions=it},label={Text("自定义说明")},modifier=Modifier.fillMaxWidth(),minLines=3,maxLines=6)}
   item{Row {Text("使用记忆",Modifier.weight(1f));Switch(memory,{memory=it},enabled=loaded&&!busy)}}
   item{TextButton(enabled=loaded&&!busy,onClick={key="";value="";editor=true}){Text("添加记忆")}}
   items(memories.toList(),key={it.first}){entry->Column{TextButton(enabled=!busy,onClick={key=entry.first;value=entry.second;editor=true}){Text(entry.first)};Text(entry.second)}}
  }
 },confirmButton={TextButton(enabled=loaded&&!busy&&instructions.length<=4000,onClick={operation{vm.api.json("/mobile/v1/profile","PATCH",JSONObject().put("instructions",instructions).put("memory_enabled",memory));onDismiss()}}){Text("保存")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
 if(editor)AlertDialog(onDismissRequest={editor=false},title={Text("记忆")},text={Column{OutlinedTextField(key,{key=it},label={Text("名称")});OutlinedTextField(value,{value=it},label={Text("内容")},minLines=2,maxLines=5)}},confirmButton={TextButton(enabled=!busy&&key.isNotBlank()&&value.isNotBlank(),onClick={operation{vm.api.json("/mobile/v1/memories","PUT",JSONObject().put("key",key).put("value",value));reload();editor=false}}){Text("保存")}},dismissButton={Row{if(memories.containsKey(key))TextButton(enabled=!busy,onClick={operation{vm.api.json("/mobile/v1/memories","DELETE",JSONObject().put("key",key));reload();editor=false}}){Text("删除")};TextButton(onClick={editor=false}){Text("取消")}}})
}
