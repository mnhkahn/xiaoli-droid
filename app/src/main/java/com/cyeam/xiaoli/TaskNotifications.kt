package com.cyeam.xiaoli

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject

/** Opt-in foreground connection, with a visible system stop control. */
class TaskNotifications:Service(){
 companion object { @Volatile var visibleSession:String?=null }
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
 private var connection:Job?=null
 override fun onBind(intent:Intent?):IBinder?=null
 override fun onCreate(){
  super.onCreate()
  val manager=getSystemService(NotificationManager::class.java)
  manager.createNotificationChannel(NotificationChannel("connection","后台连接",NotificationManager.IMPORTANCE_LOW))
  manager.createNotificationChannel(NotificationChannel("results","任务和回复",NotificationManager.IMPORTANCE_DEFAULT))
 }
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
  val prefs=getSharedPreferences("preferences",0)
  if(intent?.action=="stop"){prefs.edit().putBoolean("notifications",false).apply();stopSelf();return START_NOT_STICKY}
  val credentials=Credentials(this)
  val token=credentials.token()
  if(!prefs.getBoolean("notifications",false)||token.isEmpty()){stopSelf();return START_NOT_STICKY}
  val stop=PendingIntent.getService(this,1,Intent(this,TaskNotifications::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  val launch=PendingIntent.getActivity(this,1,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  startForeground(1,NotificationCompat.Builder(this,"connection").setSmallIcon(R.drawable.ic_assist).setContentTitle("小李正在接收任务结果").setContentIntent(launch).setOngoing(true).setSilent(true).addAction(0,"停止",stop).build())
  if(connection?.isActive==true)return START_STICKY
  val api=Api(credentials.server,credentials.device,token)
  val owner=java.security.MessageDigest.getInstance("SHA-256").digest((credentials.server+credentials.device).toByteArray()).joinToString(""){"%02x".format(it)}
  connection=scope.launch {
   var failures=0
   while(isActive)try{
    api.activity{snapshot->
     val stored=prefs.getString("seen-$owner",null)
     val seen=stored?.let{runCatching{JSONObject(it)}.getOrNull()}?:JSONObject()
     for(item in snapshot.objects("items")) {
      val session=item.getString("session_id");val status=item.getString("status")
      val identity=item.getString("message_id")+":"+status
      if(stored!=null&&seen.optString(session)!=identity&&status in listOf("complete","waiting","error","interrupted")&&visibleSession!=session){
       val opening=Intent(this@TaskNotifications,MainActivity::class.java).putExtra("session_id",session).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
       val pending=PendingIntent.getActivity(this@TaskNotifications,session.hashCode(),opening,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
       val title=when(status){"waiting"->"需要你确认";"complete"->"回复已完成";else->"任务需要检查"}
       if(android.os.Build.VERSION.SDK_INT<33||ContextCompat.checkSelfPermission(this@TaskNotifications,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
        getSystemService(NotificationManager::class.java).notify(session.hashCode(),NotificationCompat.Builder(this@TaskNotifications,"results").setSmallIcon(R.drawable.ic_assist).setContentTitle(title).setContentText(item.optString("title","小李")).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).setContentIntent(pending).build())
      }
      seen.put(session,identity)
     }
     prefs.edit().putString("seen-$owner",seen.toString()).apply()
    }
    failures=0;delay(1000)
   }catch(e:CancellationException){throw e}catch(e:Exception){
    if(e is ApiError&&e.status==401){prefs.edit().putBoolean("notifications",false).apply();stopSelf();break}
    failures++;delay((1000L shl failures.coerceAtMost(5)).coerceAtMost(30000))
   }
  }
  return START_STICKY
 }
 override fun onDestroy(){connection?.cancel();scope.cancel();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()}
}
