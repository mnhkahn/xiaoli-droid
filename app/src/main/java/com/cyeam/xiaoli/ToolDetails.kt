package com.cyeam.xiaoli

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

@Composable fun ToolDetails(tools:List<ToolRun>){
 var expanded by remember{mutableStateOf(false)}
 val uri=LocalUriHandler.current
 TextButton(onClick={expanded=!expanded},contentPadding=PaddingValues(0.dp)){
  Icon(if(expanded)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,null)
  Text(if(tools.any{it.status=="running"})"正在使用工具" else "执行过程")
 }
 if(expanded) Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
  tools.forEach{tool->
   Text(tool.name+" · "+when(tool.status){"running"->"执行中";"error"->tool.error.ifBlank{"执行失败"};"interrupted"->"已中断";else->"${tool.elapsed} ms"},style=MaterialTheme.typography.bodySmall)
   tool.sources.forEach{source->TextButton(onClick={if(source.url.startsWith("https://")||source.url.startsWith("http://"))runCatching{uri.openUri(source.url)}}){Text(source.title.ifBlank{source.url},maxLines=2)}}
  }
 }
}
