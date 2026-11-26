package com.cyeam.xiaoli

import android.content.Context
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*

fun shareText(context:Context,text:String) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text),null))
}

@Composable
fun SessionMenu(session:SessionItem,vm:ChatViewModel) {
    var expanded by remember{mutableStateOf(false)}
    var rename by remember{mutableStateOf(false)}
    var delete by remember{mutableStateOf(false)}
    var title by remember(session.title){mutableStateOf(session.title)}
    IconButton(onClick={expanded=true}){Icon(Icons.Rounded.MoreVert,"管理会话")}
    DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
        if(session.deleted) DropdownMenuItem(text={Text("恢复")},onClick={expanded=false;vm.manageSession(session,"deleted",false)})
        else {
            DropdownMenuItem(text={Text("重命名")},onClick={expanded=false;rename=true})
            DropdownMenuItem(text={Text(if(session.pinned) "取消置顶" else "置顶")},onClick={expanded=false;vm.manageSession(session,"pinned",!session.pinned)})
            DropdownMenuItem(text={Text(if(session.archived) "取消归档" else "归档")},onClick={expanded=false;vm.manageSession(session,"archived",!session.archived)})
            DropdownMenuItem(text={Text("移到回收站")},onClick={expanded=false;delete=true})
        }
    }
    if(rename) AlertDialog(onDismissRequest={rename=false},title={Text("重命名")},text={OutlinedTextField(title,{title=it},singleLine=true)},confirmButton={TextButton(onClick={vm.manageSession(session,"title",title);rename=false},enabled=title.isNotBlank()&&title.length<=100){Text("保存")}},dismissButton={TextButton(onClick={rename=false}){Text("取消")}})
    if(delete) AlertDialog(onDismissRequest={delete=false},title={Text("移到回收站？")},confirmButton={TextButton(onClick={vm.manageSession(session,"deleted",true);delete=false}){Text("移入")}},dismissButton={TextButton(onClick={delete=false}){Text("取消")}})
}
