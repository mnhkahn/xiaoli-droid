package com.cyeam.xiaoli

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuOpen
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Cream:Color @Composable get() = MaterialTheme.colorScheme.background
private val Ink:Color @Composable get() = MaterialTheme.colorScheme.onBackground
private val Vermilion = Color(0xFFDD452F)

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()
    private var feishuMode by mutableStateOf(false)
    private fun openFeishu(enabled: Boolean) {
        feishuMode = enabled
        getSharedPreferences("connection", MODE_PRIVATE).edit().putBoolean("feishu.mode", enabled).apply()
    }

    private lateinit var appLock:AppLock
    private var recorder: MediaRecorder? = null
    private var audio: File? = null
    private var photo: Uri? = null
    private var speech: TextToSpeech? = null
    private var speechReady = false
    private var voiceRequested=false
    private var voiceGateJob:Job?=null
    private var focus:android.media.AudioFocusRequest?=null
    private var scanServer = "https://xiaoli-server.fly.dev"
    private var locationCancellation:android.os.CancellationSignal?=null
    private val locationPermission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){permissions->if(permissions.values.any{it})shareLocation() else vm.error("位置权限未开启")}
    private fun paste(){
        val clip=getSystemService(android.content.ClipboardManager::class.java).primaryClip
        if(clip==null){vm.error("剪贴板为空");return}
        val items=(0 until clip.itemCount).map{clip.getItemAt(it)}
        vm.receiveShare(items.mapNotNull{it.text?.toString()}.joinToString("\n"),items.mapNotNull{it.uri}.filter{it.scheme=="content"})
    }
    private fun shareLocation(){
        val coarse=ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
        val fine=ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
        if(!coarse&&!fine){locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION));return}
        val manager=getSystemService(android.location.LocationManager::class.java)
        val provider=if(fine&&manager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER))android.location.LocationManager.GPS_PROVIDER else if(manager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER))android.location.LocationManager.NETWORK_PROVIDER else null
        if(provider==null){vm.error("请先开启系统定位");return}
        locationCancellation?.cancel()
        val cancellation=android.os.CancellationSignal();locationCancellation=cancellation
        lifecycleScope.launch{delay(15000);if(locationCancellation===cancellation){cancellation.cancel();locationCancellation=null;vm.error("暂时无法获取位置，请稍后重试")}}
        fun received(location:android.location.Location?){
            if(locationCancellation!==cancellation)return
            locationCancellation=null
            if(location==null){vm.error("暂时无法获取位置");return}
            val coordinates=String.format(Locale.US,"%.5f, %.5f",location.latitude,location.longitude)
            vm.draft((vm.state.value.draft+"\n我现在的位置："+coordinates+if(fine)"" else "（大致位置）").trim())
        }
        if(android.os.Build.VERSION.SDK_INT>=30)manager.getCurrentLocation(provider,cancellation,mainExecutor){received(it)}
        else {
            val listener=object:android.location.LocationListener{
                override fun onLocationChanged(location:android.location.Location){manager.removeUpdates(this);received(location)}
                @Deprecated("Legacy location callback") override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?){}
                override fun onProviderEnabled(provider:String){}
                override fun onProviderDisabled(provider:String){}
            }
            cancellation.setOnCancelListener{manager.removeUpdates(listener)}
            @Suppress("DEPRECATION") manager.requestSingleUpdate(provider,listener,mainLooper)
        }
    }
    private val pickImages =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) {
            vm.addImages(it)
        }
    private val pickDocuments=registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){vm.addDocuments(it)}
    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (it) openCamera() else vm.error("需要相机权限才能拍照")
        }
    private val takePhoto =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            if (ok) photo?.let { vm.addImages(listOf(it)) }
            photo = null
        }
    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if(it){if(voiceRequested)startVoice() else startRecording()}else{voiceRequested=false;vm.error("需要麦克风权限才能录音")}
        }
    private val scan =
        registerForActivityResult(ScanContract()) { result ->
            result.contents?.let { code -> vm.pair(scanServer, code) }
        }
    private val notificationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){if(it)enableNotifications() else vm.error("通知权限未开启")}
    private fun enableNotifications(){vm.notificationPreference(true);ContextCompat.startForegroundService(this,Intent(this,TaskNotifications::class.java))}
    private fun notifications(){if(vm.state.value.notifications){vm.notificationPreference(false);stopService(Intent(this,TaskNotifications::class.java))}else if(android.os.Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else enableNotifications()}
    private val assistantRole =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    private val unknownSources =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (packageManager.canRequestPackageInstalls()) vm.install()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appLock=AppLock(this){vm.error(it)}
        photo = savedInstanceState?.getString("photo")?.let(Uri::parse)
        speech =
            TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    speechReady =
                        (speech?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                            ?: TextToSpeech.LANG_NOT_SUPPORTED) >= 0
                }
            }
        speech?.setOnUtteranceProgressListener(object:android.speech.tts.UtteranceProgressListener(){
            override fun onStart(id:String?){}
            override fun onDone(id:String?){runOnUiThread {if(id?.startsWith("voice-")==true&&vm.state.value.voiceMode&&vm.state.value.voiceStage=="speaking"&&lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)){vm.voice(true,"listening");startRecording()}}}
            @Deprecated("Legacy TTS callback") override fun onError(id:String?){runOnUiThread{if(vm.state.value.voiceMode){vm.voice(true,"paused");vm.error("语音播报失败，请重试")}}}
        })
        lifecycleScope.launch {
            vm.state.collectLatest {state->
                if(!state.voiceMode||state.voiceStage!="thinking")return@collectLatest
                val index=state.chat.messages.indexOfFirst{it.id==state.voiceRequestId}
                val reply=state.chat.messages.getOrNull(index+1)?.takeIf{index>=0&&it.role=="assistant"}?:return@collectLatest
                if(state.chat.pending.isNotEmpty()){vm.voice(true,"paused");return@collectLatest}
                if(reply.status=="complete"&&reply.text.isNotBlank()){
                    vm.voice(true,"speaking")
                    speakChunks(reply.text,"voice-${reply.id}")
                }else if(reply.status in listOf("error","interrupted","cancelled")) vm.voice(true,"paused")
            }
        }
        lifecycleScope.launch(Dispatchers.IO) { runCatching {
        getSystemService(ShortcutManager::class.java).dynamicShortcuts =
            listOf(
                ShortcutInfo.Builder(this@MainActivity, "assistant")
                    .setShortLabel("唤起小李")
                    .setIcon(Icon.createWithResource(this@MainActivity, R.drawable.ic_assist))
                    .setIntent(
                        Intent(this@MainActivity, MainActivity::class.java).setAction(Intent.ACTION_ASSIST)
                    )
                    .build()
            )
        } }
        feishuMode = savedInstanceState?.takeIf { it.containsKey("feishu.mode") }?.getBoolean("feishu.mode")
            ?: getSharedPreferences("connection", MODE_PRIVATE).let { prefs ->
            prefs.getBoolean("feishu.mode", !prefs.contains("credential"))
        }
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            MaterialTheme(
                colorScheme =
                    if(state.appearance=="dark" || state.appearance=="system" && isSystemInDarkTheme()) darkColorScheme(primary=Color(0xFFFF9B87),background=Color(0xFF1D1B19),surface=Color(0xFF1D1B19))
                    else lightColorScheme(primary=Vermilion,background=Color(0xFFFFFCF8),surface=Color(0xFFFFFCF8),onSurface=Color(0xFF262320),onBackground=Color(0xFF262320))
            ) {
                if(appLock.locked)Surface(Modifier.fillMaxSize()){Box(contentAlignment=Alignment.Center){Button(onClick={appLock.unlock{resumeAssistant()}}){Text("解锁")}}}
                else if (feishuMode) FeishuScreen(onExit = { openFeishu(false) })
                else XiaoliScreen(
                    state,
                    vm,
                    onFeishu = { openFeishu(true) },
                    onPhotos = {
                        pickImages.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    onCamera = ::camera,
                    onDocuments={pickDocuments.launch(arrayOf("text/*","application/pdf","application/json","application/vnd.openxmlformats-officedocument.wordprocessingml.document","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))},
                    onMic = ::microphone,
                    onVoice=::toggleVoice,
                    onLocation=::shareLocation,
                    onPaste=::paste,
                    onSpeak = ::speak,
                    onAssistant = ::requestAssistant,
                    onNotifications=::notifications,
                    onLock={appLock.toggle(vm::lockPreference)},
                    onScan = { server ->
                        scanServer = server
                        scan.launch(
                            ScanOptions()
                                .setPrompt("扫描管理台配对码")
                                .setBeepEnabled(false)
                                .setOrientationLocked(false)
                        )
                    },
                )
                LaunchedEffect(state.downloadProgress, state.update) {
                    if (vm.installation != null && state.downloadProgress == null) {
                        if (packageManager.canRequestPackageInstalls()) vm.install()
                        else
                            unknownSources.launch(
                                Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:$packageName"),
                                )
                            )
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("photo", photo?.toString())
        outState.putBoolean("feishu.mode", feishuMode)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.getBooleanExtra("assistant", false) || intent.hasExtra("session_id") ||
            intent.action in setOf(Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            feishuMode = false
        }
        intent.getStringExtra("session_id")?.takeIf{it.matches(Regex("[A-Za-z0-9_-]+"))}?.let{vm.openSession(it)}
        if (intent.action == Intent.ACTION_ASSIST || intent.action == Intent.ACTION_VOICE_COMMAND)
            intent.putExtra("assistant", true)
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            stopVoice()
            @Suppress("DEPRECATION")
            val uris =
                if (intent.action == Intent.ACTION_SEND_MULTIPLE)
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                else listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            vm.receiveShare(intent.getStringExtra(Intent.EXTRA_TEXT),uris)
        }
    }

    override fun onStart() {
        super.onStart()
        vm.resume()
    }

    override fun onResume() {
        TaskNotifications.visibleSession=vm.state.value.chat.sessionId
        vm.notificationPreference(getSharedPreferences("preferences",0).getBoolean("notifications",false))
        if(vm.state.value.notifications&&vm.state.value.paired&&(android.os.Build.VERSION.SDK_INT<33||ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED))enableNotifications()
        super.onResume()
        if(::appLock.isInitialized)appLock.unlock{resumeAssistant()}
        resumeAssistant()
    }

    private fun resumeAssistant(){
        if (::appLock.isInitialized&&!appLock.locked&&intent.getBooleanExtra("assistant", false) && vm.state.value.paired) {
            intent.removeExtra("assistant")
            microphone()
        }
    }

    override fun onStop() {
        super.onStop()
        if(::appLock.isInitialized)appLock.background()
        TaskNotifications.visibleSession=null
        locationCancellation?.cancel();locationCancellation=null
        vm.pause()
        stopVoice()
        stopRecording(false)
        speech?.stop()
    }

    override fun onDestroy() {
        if(::appLock.isInitialized)appLock.close()
        speech?.shutdown()
        super.onDestroy()
    }

    private fun camera() {
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
            openCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun openCamera() {
        val file =
            File.createTempFile("camera-", ".jpg", File(cacheDir, "photos").apply { mkdirs() })
        photo = FileProvider.getUriForFile(this, "$packageName.files", file)
        runCatching { photo?.let { takePhoto.launch(it) } }.onFailure { vm.error("无法打开相机") }
    }

    private fun toggleVoice(){if(vm.state.value.voiceMode)stopVoice() else {
        voiceRequested=true
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)microphonePermission.launch(Manifest.permission.RECORD_AUDIO)else startVoice()
    }}
    private fun startVoice(){
        voiceRequested=false
        val state=vm.state.value
        if(!state.paired||state.chat.busy||state.chat.pending.isNotEmpty()||state.recording||state.transcribing||state.draft.isNotBlank()){vm.error("请先完成当前对话并清空输入框");return}
        if(!speechReady){vm.error("请先在系统设置中安装中文语音引擎");return}
        val manager=getSystemService(android.media.AudioManager::class.java)
        val request=android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build()).setOnAudioFocusChangeListener{change->if(change<0)runOnUiThread{stopVoice()}}.build()
        if(manager.requestAudioFocus(request)!=android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED){vm.error("麦克风或音频正在被占用");return}
        focus=request;vm.voice(true);startRecording()
    }
    private fun stopVoice(){voiceGateJob?.cancel();voiceGateJob=null;vm.voice(false);speech?.stop();stopRecording(false);focus?.let{getSystemService(android.media.AudioManager::class.java).abandonAudioFocusRequest(it)};focus=null}
    private fun microphone() {
        if(vm.state.value.voiceMode && recorder==null){
            if(vm.state.value.voiceStage=="thinking"){vm.stop();vm.voice(true,"paused");return}
            if(vm.state.value.transcribing)return
            speech?.stop();vm.voice(true,"listening");startRecording();return
        }
        if (vm.state.value.recording && recorder == null) return
        if (recorder != null) {
            stopRecording(true)
            return
        }
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
        )
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        else startRecording()
    }

    @Suppress("DEPRECATION")
    private fun startRecording() {
        if (!vm.state.value.paired) {
            vm.error("请先连接服务器")
            return
        }
        if (vm.state.value.recording || vm.state.value.transcribing) return
        val file = File.createTempFile("speech-", ".ogg", cacheDir)
        val next =
            if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        vm.recording(true)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    next.setAudioSource(MediaRecorder.AudioSource.MIC)
                    next.setOutputFormat(MediaRecorder.OutputFormat.OGG)
                    next.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                    next.setAudioSamplingRate(16000)
                    next.setAudioEncodingBitRate(24000)
                    next.setMaxDuration(60_000)
                    next.setOutputFile(file.path)
                    next.setOnInfoListener { _, what, _ ->
                        if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED)
                            runOnUiThread { stopRecording(true) }
                    }
                    next.prepare()
                    next.start()
                }
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    recorder = next
                    audio = file
                    if(vm.state.value.voiceMode){
                        voiceGateJob?.cancel()
                        voiceGateJob=lifecycleScope.launch {
                            val started=android.os.SystemClock.elapsedRealtime();val gate=VoiceGate()
                            while(recorder===next){delay(100);val elapsed=android.os.SystemClock.elapsedRealtime()-started
                                if(gate.sample(elapsed,runCatching{next.maxAmplitude}.getOrDefault(0))){stopRecording(true);break}
                                if(elapsed>20000&&!gate.heardSpeech){stopVoice();vm.error("未检测到声音，请重新开始");break}
                            }
                        }
                    }
                } else {
                    runCatching { next.stop() }
                    next.release()
                    file.delete()
                    vm.recording(false)
                }
            } catch (e: Exception) {
                next.release()
                file.delete()
                vm.recording(false)
                vm.error("无法开始录音，请检查麦克风权限")
            }
        }
    }

    private fun stopRecording(submit: Boolean) {
        voiceGateJob?.cancel();voiceGateJob=null
        val active = recorder ?: return
        recorder = null
        vm.recording(false)
        val file = audio
        audio = null
        val success = runCatching { active.stop() }.isSuccess
        active.release()
        if (submit && success && file != null) vm.transcribe(file,autoSend=vm.state.value.voiceMode) else file?.delete()
    }

    private fun speakChunks(text:String,lastId:String){
        speech?.setSpeechRate(vm.state.value.speechRate)
        val parts=ttsChunks(text.replace(Regex("[`#*]"),""))
        parts.forEachIndexed{index,part->speech?.speak(part,if(index==0)TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,null,if(index==parts.lastIndex)lastId else "part-$index")}
    }
    private fun speak(text: String) {
        if(speech?.isSpeaking==true){speech?.stop();return}
        if (speechReady)
            speakChunks(text,"reply")
        else vm.error("请在系统设置中安装中文语音引擎")
    }

    private fun requestAssistant() {
        val roles = getSystemService(RoleManager::class.java)
        if (
            roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
                !roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        )
            assistantRole.launch(roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
        else
            runCatching { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                .onFailure { vm.error("系统未提供默认助手设置入口") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XiaoliScreen(
    state: AppState,
    vm: ChatViewModel,
    onFeishu: () -> Unit = {},
    onPhotos: () -> Unit,
    onCamera: () -> Unit,
    onDocuments:()->Unit,
    onMic: () -> Unit,
    onVoice:()->Unit,
    onLocation:()->Unit,
    onPaste:()->Unit,
    onSpeak: (String) -> Unit,
    onAssistant: () -> Unit,
    onNotifications:()->Unit,
    onLock:()->Unit,
    onScan: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val list = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    var settings by remember { mutableStateOf(false) }
    var tasks by remember{mutableStateOf(false)}
    var preview by remember { mutableStateOf<String?>(null) }
    var documentPreview by remember{mutableStateOf<ChatFile?>(null)}
    var attachments by remember { mutableStateOf(false) }
    var showConnect by remember { mutableStateOf(false) }
    val context=LocalContext.current
    var editMessage by remember{mutableStateOf<ChatMessage?>(null)}
    var regenerate by remember{mutableStateOf<ChatMessage?>(null)}
    var editText by remember{mutableStateOf("")}
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            vm.error(null)
        }
    }
    // Do not pull the reader away from older messages during a streamed reply.
    val nearBottom by remember {
        derivedStateOf {
            list.layoutInfo.visibleItemsInfo.lastOrNull()?.index.let {
                it == null || it >= list.layoutInfo.totalItemsCount - 3
            }
        }
    }
    LaunchedEffect(
        state.chat.messages.size,
        state.chat.messages.lastOrNull()?.text?.length,
        state.chat.pending.size,
    ) {
        if (nearBottom && list.layoutInfo.totalItemsCount > 0)
            list.scrollToItem(list.layoutInfo.totalItemsCount - 1)
    }
    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Cream) {
                Row(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("小李", fontWeight = FontWeight.Bold, fontSize = 24.sp)
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            vm.newSession()
                            scope.launch { drawer.close() }
                        }
                    ) {
                        Icon(Icons.Rounded.Add, "新对话")
                    }
                }
                TextButton(onClick={vm.newSession(temporary=true);scope.launch{drawer.close()}}){Text("临时对话")}
                OutlinedTextField(value=state.sessionQuery,onValueChange={vm.filterHistory(query=it)},singleLine=true,placeholder={Text("搜索")},modifier=Modifier.padding(horizontal=16.dp).fillMaxWidth().testTag("history-search"))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=12.dp)) {
                    listOf("" to "对话","archived" to "归档","deleted" to "回收站").forEach{(view,label)->FilterChip(selected=state.sessionView==view,onClick={vm.filterHistory(view=view)},label={Text(label)},modifier=Modifier.padding(end=8.dp))}
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(state.sessions, key = { it.id }) { session ->
                        NavigationDrawerItem(
                            label = { Text((if(session.pinned) "★ " else "")+session.title, maxLines = 1) },
                            badge = { SessionMenu(session,vm) },
                            selected = state.chat.sessionId == session.id,
                            onClick = {
                                vm.openSession(session.id)
                                scope.launch { drawer.close() }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                        )
                    }
                }
                TextButton(enabled=state.paired,onClick={tasks=true;scope.launch{drawer.close()}}){Icon(Icons.Rounded.TaskAlt,null);Spacer(Modifier.width(8.dp));Text("任务")}
                TextButton(
                    onClick = {
                        settings = true
                        scope.launch { drawer.close() }
                    },
                    modifier = Modifier.padding(16.dp),
                ) {
                    Icon(Icons.Rounded.Settings, null)
                    Spacer(Modifier.width(8.dp))
                    Text("设置")
                }
            }
        },
    ) {
        Scaffold(
            containerColor = Cream,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Cream),
                    title = { Text(if(state.chat.temporary) "临时对话" else "小李", fontWeight = FontWeight.SemiBold, fontSize = 20.sp) },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                vm.history()
                                scope.launch { drawer.open() }
                            }
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.MenuOpen, "会话列表")
                        }
                    },
                    actions = {
                        if(state.chat.sessionId.isNotBlank()) IconButton(onClick={scope.launch {runCatching{vm.export()}.onSuccess{shareText(context,it)}.onFailure{vm.error(it.message)}}}){Icon(Icons.Rounded.Share,"导出会话")}
                        IconButton(
                            enabled=!state.loading&&!state.sending&&!state.recording&&!state.transcribing&&!state.voiceMode,
                            modifier=Modifier.testTag("new-session"),
                            onClick = { if (state.paired) vm.newSession() else showConnect = true }
                        ) {
                            Icon(Icons.Rounded.Edit, "新对话")
                        }
                    },
                )
            },
            bottomBar = {
                Column(
                    Modifier.navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    if(state.chat.archived||state.chat.deleted)TextButton(onClick={vm.restoreCurrent()}){Text("恢复对话")}
                    if (state.preparing || state.transcribing)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp))
                    if (state.attachments.isNotEmpty() || state.remoteImages.isNotEmpty())
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            state.remoteImages.forEach { image ->
                                Box {
                                    AsyncImage(model=vm.api.imageRequest(image.url).let{request->coil.request.ImageRequest.Builder(context).data(request.url.toString()).headers(request.headers).build()},contentDescription="待发送图片",modifier=Modifier.size(64.dp).clickable{preview=image.url})
                                    IconButton(onClick={vm.removeRemoteImage(image.id)},modifier=Modifier.size(24.dp).align(Alignment.TopEnd).background(Cream,CircleShape)){Icon(Icons.Rounded.Close,"移除图片",Modifier.size(16.dp))}
                                }
                            }
                            state.attachments.forEach { path ->
                                Box {
                                    AsyncImage(
                                        model = File(path),
                                        contentDescription = "待发送图片",
                                        modifier =
                                            Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)),
                                    )
                                    IconButton(
                                        onClick = { vm.removeImage(path) },
                                        modifier =
                                            Modifier.size(24.dp)
                                                .align(Alignment.TopEnd)
                                                .background(Cream, CircleShape),
                                    ) {
                                        Icon(
                                            Icons.Rounded.Close,
                                            "移除图片",
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                    if(state.documents.isNotEmpty() || state.remoteFiles.isNotEmpty())Row(Modifier.horizontalScroll(rememberScrollState())){
                        state.documents.forEach{path->InputChip(selected=false,onClick={vm.removeDocument(path)},label={Text(File(path).name,maxLines=1)},trailingIcon={Icon(Icons.Rounded.Close,"移除文档")})}
                        state.remoteFiles.forEach{file->InputChip(selected=false,onClick={documentPreview=file},label={Text(file.name,maxLines=1)},trailingIcon={IconButton(onClick={vm.removeRemoteFile(file.id)}){Icon(Icons.Rounded.Close,"移除文档")}})}
                    }
                    if(state.searchEnabled)InputChip(selected=true,onClick={vm.search(false)},label={Text("联网搜索")},trailingIcon={Icon(Icons.Rounded.Close,"关闭联网搜索")})
                    if(state.voiceMode)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                        Text(when(state.voiceStage){"listening"->"正在听…";"transcribing"->"正在识别…";"thinking"->"正在思考…";"speaking"->"正在说…";else->"点击麦克风继续"},Modifier.weight(1f))
                        TextButton(onClick=onVoice){Text("结束语音")}
                    }
                    if (state.recording && !state.voiceMode)
                        Row(
                            Modifier.fillMaxWidth().padding(8.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Text("正在录音 · 点击麦克风结束", color = Vermilion)
                        }
                    Surface(
                        shape = RoundedCornerShape(26.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 4.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            IconButton(
                                onClick = {
                                    if (state.paired) attachments = true else showConnect = true
                                },
                                enabled = !state.sending && !state.preparing,
                            ) {
                                Icon(Icons.Rounded.Add, "添加图片")
                            }
                            TextField(
                                value = state.draft,
                                enabled=!state.chat.archived&&!state.chat.deleted,
                                onValueChange = vm::draft,
                                placeholder = { Text(if (state.paired) "说点什么…" else "连接后开始对话") },
                                modifier = Modifier.weight(1f).testTag("composer"),
                                maxLines = 5,
                                colors =
                                    TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                    ),
                                textStyle = LocalTextStyle.current.copy(fontSize = 16.sp),
                            )
                            IconButton(
                                onClick = onMic,
                                enabled = state.paired && !state.transcribing && !state.sending,
                            ) {
                                Icon(
                                    if (state.recording) Icons.Rounded.StopCircle
                                    else Icons.Rounded.Mic,
                                    if (state.recording) "结束录音" else "录音",
                                    tint = if (state.recording) Vermilion else Ink,
                                )
                            }
                            FilledIconButton(
                                onClick = {
                                    if (!state.paired) showConnect = true
                                    else if (state.chat.busy) vm.stop() else vm.send()
                                },
                                enabled =
                                    !state.sending && !state.chat.archived && !state.chat.deleted &&
                                        !state.preparing &&
                                        (state.chat.busy ||
                                            state.draft.isNotBlank() ||
                                            state.attachments.isNotEmpty() || state.remoteImages.isNotEmpty() || state.documents.isNotEmpty() || state.remoteFiles.isNotEmpty() ||
                                            !state.paired),
                                modifier = Modifier.padding(3.dp).size(40.dp),
                            ) {
                                if (state.sending)
                                    CircularProgressIndicator(
                                        Modifier.size(18.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp,
                                    )
                                else
                                    Icon(
                                        if (state.chat.busy) Icons.Rounded.Stop
                                        else Icons.Rounded.ArrowUpward,
                                        if (state.chat.busy) "停止生成" else "发送",
                                    )
                            }
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (state.chat.messages.isEmpty() && !state.loading)
                    Column(
                        Modifier.align(Alignment.Center).padding(bottom = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Image(painterResource(R.drawable.avatar), "小李", Modifier.size(112.dp))
                        Spacer(Modifier.height(20.dp))
                        Text("有什么想做的？", fontSize = 25.sp, fontWeight = FontWeight.Medium)
                        if (!state.paired) {
                            Spacer(Modifier.height(20.dp))
                            Button(onClick = onFeishu) { Text("连接飞书智能体") }
                            FilledTonalButton(onClick = { showConnect = true }) { Text("连接小李") }
                        }
                    }
                if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
                if (state.paired)
                    LazyColumn(
                        state = list,
                        modifier = Modifier.testTag("messages").fillMaxSize(),
                        contentPadding =
                            PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        if (state.chat.before > 0)
                            item("older") { TextButton(onClick = { vm.older() }) { Text("更早的消息") } }
                        items(state.chat.messages, key = { it.id }, contentType = { it.role }) {
                            message ->
                            MessageRow(message, vm.api, { preview = it }, onSpeak, onEdit={editMessage=message;editText=message.text},onRegenerate={regenerate=message},enabled=!state.chat.busy && !state.sending && !state.chat.temporary,onFile={documentPreview=it})
                        }
                        if (state.chat.pending.isNotEmpty())
                            item("cancel-pending") {
                                TextButton(onClick = { vm.stop() }) { Text("取消任务") }
                            }
                        if (state.chat.progress.isNotBlank() && state.chat.busy)
                            item("progress") {
                                Text(state.chat.progress, color = Color.Gray, fontSize = 13.sp)
                            }
                        items(state.chat.pending, key = { it.id }) { interaction ->
                            InteractionCard(
                                interaction,
                                state.sending,
                                { vm.reply(interaction, it) },
                            )
                        }
                        if (!state.connected && state.paired && state.chat.sessionId.isNotBlank())
                            item("connection") {
                                Text("正在连接…", color = Color.Gray, fontSize = 12.sp)
                            }
                    }
            }
        }
    }
    editMessage?.let{message->AlertDialog(onDismissRequest={editMessage=null},title={Text("编辑问题")},text={OutlinedTextField(value=editText,onValueChange={editText=it},modifier=Modifier.heightIn(max=300.dp))},confirmButton={TextButton(onClick={vm.fork(message,editText);editMessage=null}){Text("建立分支")}},dismissButton={TextButton(onClick={editMessage=null}){Text("取消")}})}
    regenerate?.let{message->AlertDialog(onDismissRequest={regenerate=null},title={Text("重新回答")},text={Text("将保留原对话，并把原问题放入新分支。检查后发送；工具操作可能再次执行。")},confirmButton={TextButton(onClick={vm.fork(message);regenerate=null}){Text("继续")}},dismissButton={TextButton(onClick={regenerate=null}){Text("取消")}})}
    if (showConnect) {
        ConnectionDialog(state, vm, onDismiss = { showConnect = false }, onScan = onScan)
    }
    if(tasks)TasksDialog(state,vm,onNotifications){tasks=false}
    if (settings)
        SettingsDialog(
            state,
            vm,
            onDismiss = { settings = false },
            onFeishu = { settings = false; onFeishu() },
            onConnect = {
                settings = false
                showConnect = true
            },
            onAssistant = onAssistant,
            onLock=onLock,
        )
    if (attachments)
        ModalBottomSheet(onDismissRequest = { attachments = false }, containerColor = Cream) {
            Column(Modifier.padding(16.dp)) {
                TextButton(
                    onClick = {
                        attachments = false
                        onPhotos()
                    }
                ) {
                    Icon(Icons.Rounded.PhotoLibrary, null)
                    Spacer(Modifier.width(12.dp))
                    Text("相册")
                }
                TextButton(
                    onClick = {
                        attachments = false
                        onCamera()
                    }
                ) {
                    Icon(Icons.Rounded.PhotoCamera, null)
                    Spacer(Modifier.width(12.dp))
                    Text("拍照")
                }
                TextButton(enabled=!state.chat.temporary,onClick={attachments=false;vm.search(!state.searchEnabled)}){Icon(Icons.Rounded.TravelExplore,null);Spacer(Modifier.width(12.dp));Text("联网搜索")}
                TextButton(onClick={attachments=false;onPaste()}){Icon(Icons.Rounded.ContentPaste,null);Spacer(Modifier.width(12.dp));Text("粘贴")}
                TextButton(onClick={attachments=false;onLocation()}){Icon(Icons.Rounded.LocationOn,null);Spacer(Modifier.width(12.dp));Text("位置")}
                TextButton(onClick={attachments=false;onVoice()}) {Icon(Icons.Rounded.Headphones,null);Spacer(Modifier.width(12.dp));Text("语音对话")}
                TextButton(onClick={attachments=false;onDocuments()}) {Icon(Icons.Rounded.AttachFile,null);Spacer(Modifier.width(12.dp));Text("文件")}
                Spacer(Modifier.height(20.dp))
            }
        }
    documentPreview?.let{DocumentPreview(it,vm.api,vm::error){documentPreview=null}}
    preview?.let { ImagePreview(it, vm.api) { preview = null } }
    state.update?.let { u ->
        AlertDialog(
            onDismissRequest = { if (state.downloadProgress == null) vm.dismissUpdate() },
            title = { Text("小李 ${u.version}") },
            text = {
                Column {
                    if (u.notes.isNotBlank()) Text(u.notes.take(1500))
                    state.downloadProgress?.let {
                        LinearProgressIndicator(
                            progress = { it / 100f },
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.downloadUpdate() },
                    enabled = state.downloadProgress == null,
                ) {
                    Text(
                        if (state.downloadProgress == null) "更新"
                        else "下载中 ${state.downloadProgress}%"
                    )
                }
            },
            dismissButton = {
                if (state.downloadProgress == null)
                    TextButton(onClick = { vm.dismissUpdate() }) { Text("稍后") }
            },
        )
    }
}

@Composable
private fun MessageRow(
    message: ChatMessage,
    api: Api,
    onImage: (String) -> Unit,
    onSpeak: (String) -> Unit,
    onEdit:()->Unit,
    onRegenerate:()->Unit,
    enabled:Boolean,
    onFile:(ChatFile)->Unit,
) {
    val user = message.role == "user"
    val messageContext=LocalContext.current
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (user) Alignment.End else Alignment.Start,
    ) {
        if (message.thinking.isNotEmpty()) {
            var expanded by remember(message.id) { mutableStateOf(false) }
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    null,
                    Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("思考过程", fontSize = 13.sp, color = Color.Gray)
            }
            AnimatedVisibility(expanded) {
                SelectionContainer {
                    Text(
                        message.thinking,
                        color = Color.Gray,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }
        }
        if(message.tools.isNotEmpty())ToolDetails(message.tools)
        val body: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                message.images
                    .filter { user || !message.text.contains(it.url) }
                    .forEach { ChatPicture(it.url, api, onImage) }
                message.files.forEach{file->OutlinedButton(onClick={onFile(file)}){Icon(Icons.Rounded.InsertDriveFile,null);Spacer(Modifier.width(8.dp));Text(file.name,maxLines=2)}}
                if (message.text.isNotBlank()) {
                    if (user || message.status == "streaming")
                        SelectionContainer {
                            Text(message.text, fontSize = 16.sp, lineHeight = 26.sp)
                        }
                    else MarkdownBody(message.text, api, onImage)
                } else if (message.status == "streaming")
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
        if (user)
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Box(Modifier.padding(14.dp)) { body() }
            }
        else body()
        if (message.status in listOf("error", "interrupted", "cancelled"))
            Text(
                when (message.status) {
                    "interrupted" -> "回复已中断"
                    "cancelled" -> "已停止"
                    else -> "回复失败"
                },
                color = Vermilion,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        if(message.status!="streaming") {
            var menu by remember{mutableStateOf(false)}
            Box {
                IconButton(onClick={menu=true}){Icon(Icons.Rounded.MoreHoriz,"消息操作",tint=Color.Gray)}
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                    DropdownMenuItem(text={Text(if(user) "编辑" else "重新回答")},enabled=enabled,onClick={menu=false;if(user)onEdit() else onRegenerate()})
                    DropdownMenuItem(text={Text("分享")},onClick={menu=false;shareText(context=messageContext,text=message.text)})
                }
            }
        }
        if (!user && message.status == "complete" && message.text.isNotEmpty()) {
            val context = LocalContext.current
            Row {
                IconButton(
                    onClick = {
                        val clipboard =
                            context.getSystemService(android.content.ClipboardManager::class.java)
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("小李", message.text)
                        )
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(Icons.Rounded.ContentCopy, "复制回复", Modifier.size(16.dp), tint = Color.Gray)
                }
                IconButton(onClick = { onSpeak(message.text) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.VolumeUp, "朗读回复", Modifier.size(18.dp), tint = Color.Gray)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InteractionCard(card: Interaction, sending: Boolean, onReply: (String) -> Unit) {
    var text by remember(card.id) { mutableStateOf("") }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(card.question, fontWeight = FontWeight.Medium)
            if (card.command.isNotBlank())
                SelectionContainer {
                    Text(
                        card.command,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 13.sp,
                    )
                }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                card.options.forEach { opt ->
                    OutlinedButton(
                        onClick = { onReply(opt) },
                        enabled = !sending && card.answer.isBlank(),
                    ) {
                        Text(if (card.answer == opt) "✓ $opt" else opt)
                    }
                }
            }
            if (card.kind == "question" && card.answer.isBlank())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text("补充回答") },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { onReply(text) },
                        enabled = text.isNotBlank() && !sending,
                    ) {
                        Icon(Icons.Rounded.ArrowUpward, "提交回答")
                    }
                }
        }
    }
}

@Composable
private fun ConnectionDialog(
    state: AppState,
    vm: ChatViewModel,
    onDismiss: () -> Unit,
    onScan: (String) -> Unit,
) {
    var server by remember { mutableStateOf(state.server) }
    var code by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(state.paired, state.loading) {
        if (submitted && state.paired && !state.loading && state.error == null) onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("连接小李") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    modifier = Modifier.testTag("server"),
                    value = server,
                    onValueChange = { server = it },
                    label = { Text("服务器") },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.testTag("pair-code"),
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("配对码") },
                    maxLines = 3,
                )
                Row {
                    TextButton(
                        onClick = {
                            runCatching {
                                    val base = validateServer(server, BuildConfig.DEBUG)
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse("$base/admin"))
                                    )
                                }
                                .onFailure { vm.error(it.message) }
                        }
                    ) {
                        Text("打开管理台")
                    }
                    TextButton(onClick = { onScan(server) }) { Text("扫码") }
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("connect-confirm"),
                onClick = {
                    submitted = true
                    vm.pair(server, code)
                },
                enabled = !state.loading && code.isNotBlank(),
            ) {
                if (state.loading) CircularProgressIndicator(Modifier.size(16.dp)) else Text("连接")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SettingsDialog(
    state: AppState,
    vm: ChatViewModel,
    onDismiss: () -> Unit,
    onConnect: () -> Unit,
    onFeishu: () -> Unit,
    onAssistant: () -> Unit,
    onLock:()->Unit,
) {
    var personal by remember{mutableStateOf(false)}
    var modelDialog by remember{mutableStateOf(false)}
    var selectedModel by remember(state.chat.model){mutableStateOf(state.chat.model)}
    var modelSearch by remember{mutableStateOf("")}
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick={selectedModel=state.chat.model;modelSearch="";modelDialog=true;vm.loadModels()},enabled=state.paired){Text("模型")}
                Row { listOf("system" to "系统","light" to "浅色","dark" to "深色").forEach{(value,label)-> TextButton(onClick={vm.appearance(value)}) { Text((if(state.appearance==value) "✓ " else "")+label) } } }
                TextButton(onClick={personal=true},enabled=state.paired){Text("个性化") }
                Text("朗读速度 · ${"%.1f".format(state.speechRate)}",style=MaterialTheme.typography.bodySmall)
                Slider(value=state.speechRate,onValueChange=vm::speechRate,valueRange=0.5f..2f)
                Row(verticalAlignment=Alignment.CenterVertically){Text("应用锁",Modifier.weight(1f));Switch(state.appLock,{onLock()})}
                TextButton(onClick = onAssistant) { Text("设为默认助手") }
                TextButton(onClick = onFeishu) { Text("飞书智能体") }
                TextButton(onClick = onConnect) { Text(if (state.paired) "重新配对" else "连接服务器") }
                TextButton(onClick = { vm.checkUpdate() }) {
                    Text("检查更新 · ${BuildConfig.VERSION_NAME}")
                }
                if (state.paired)
                    TextButton(
                        onClick = {
                            vm.disconnect()
                            onDismiss()
                        }
                    ) {
                        Text("断开连接")
                    }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
    if(personal)Personalization(vm){personal=false}
    if(modelDialog)AlertDialog(onDismissRequest={modelDialog=false},title={Text("免费模型")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        TextButton(onClick={vm.defaultModel(selectedModel)}){Text("用于新对话")}
        Row(verticalAlignment=Alignment.CenterVertically){
            OutlinedTextField(value=modelSearch,onValueChange={modelSearch=it},placeholder={Text("搜索模型")},singleLine=true,modifier=Modifier.weight(1f).testTag("model-search"))
            IconButton(onClick={vm.loadModels()},enabled=!state.modelsLoading){Icon(Icons.Rounded.Refresh,"刷新模型")}
        }
        if(state.modelsLoading)LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
        state.modelsError?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        LazyColumn(modifier=Modifier.heightIn(max=360.dp)){
            item {TextButton(onClick={selectedModel=""}){Text(if(selectedModel.isEmpty())"✓ 服务端默认" else "服务端默认")}}
            val filtered=state.models.filter{modelSearch.isBlank()||it.name.contains(modelSearch,true)||it.provider.contains(modelSearch,true)}
            items(filtered,key={it.id}){model->
                Row(modifier=Modifier.fillMaxWidth().clickable{selectedModel=model.id}.padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
                    RadioButton(selected=selectedModel==model.id,onClick={selectedModel=model.id})
                    Text(model.name,modifier=Modifier.weight(1f))
                    Text(when(model.provider){"openrouter"->"OpenRouter";"siliconflow"->"硅基流动";else->model.provider},style=MaterialTheme.typography.labelSmall)
                }
            }
            if(!state.modelsLoading&&state.modelsError==null&&filtered.isEmpty())item{Text("暂无可用模型")}
        }
    }},confirmButton={TextButton(onClick={vm.selectModel(selectedModel);modelDialog=false},enabled=!state.chat.busy&&state.chat.pending.isEmpty()){Text("应用")}},dismissButton={TextButton(onClick={modelDialog=false}){Text("取消")}})

}
