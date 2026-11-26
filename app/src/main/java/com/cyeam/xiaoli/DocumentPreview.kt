package com.cyeam.xiaoli

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

suspend fun shareDownload(context:Context,api:Api,url:String,name:String,mime:String) {
    val file=withContext(Dispatchers.IO) {
        val directory=File(context.cacheDir,"exports").apply{mkdirs()}
        File(directory,File(name).name).also{it.writeBytes(api.download(url))}
    }
    val uri=FileProvider.getUriForFile(context,context.packageName+".files",file)
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM,uri).apply{clipData=ClipData.newRawUri(name,uri)}.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),null))
}

@Composable
fun DocumentPreview(file:ChatFile,api:Api,onError:(String?)->Unit,onDismiss:()->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var text by remember(file.id){mutableStateOf<String?>(null)}
    var error by remember(file.id){mutableStateOf<String?>(null)}
    var working by remember{mutableStateOf(false)}
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(file.mime.ifBlank{"application/octet-stream"})){uri->
        if(uri!=null)scope.launch {working=true;try {val bytes=api.download(file.url);withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)!!.use{it.write(bytes)}}}catch(e:Exception){onError(e.message)}finally{working=false}}
    }
    LaunchedEffect(file.id){try{text=api.download(file.url+"?preview=text",300_000).toString(Charsets.UTF_8)}catch(e:Exception){error=e.message}}
    AlertDialog(onDismissRequest=onDismiss,title={Text(file.name)},text={
        Column {
            if(working)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(text==null && error==null)CircularProgressIndicator()
            error?.let{Text(it)}
            text?.let{value->val blocks=remember(value){value.chunked(2000)};LazyColumn(Modifier.heightIn(max=400.dp)){items(blocks){block->SelectionContainer{Text(block,modifier=Modifier.padding(bottom=8.dp))}}}}
        }
    },confirmButton={TextButton(onClick=onDismiss){Text("关闭")}},dismissButton={Row{
        TextButton(enabled=!working,onClick={save.launch(file.name)}){Text("保存")}
        TextButton(enabled=!working,onClick={scope.launch {working=true;try{shareDownload(context,api,file.url,file.name,file.mime)}catch(e:Exception){onError(e.message)}finally{working=false}}}){Text("分享")}
    }})
}
