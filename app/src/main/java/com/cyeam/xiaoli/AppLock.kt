package com.cyeam.xiaoli

import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*

class AppLock(private val activity:ComponentActivity,private val onError:(String)->Unit){
 private val preferences=activity.getSharedPreferences("preferences",0)
 var locked by mutableStateOf(preferences.getBoolean("app_lock",false));private set
 private var authenticating=false
 private var cancellation:CancellationSignal?=null
 val enabled get()=preferences.getBoolean("app_lock",false)
 init{if(enabled&&!activity.getSystemService(KeyguardManager::class.java).isDeviceSecure){preferences.edit().putBoolean("app_lock",false).apply();locked=false};secureWindow()}
 private fun secureWindow(){if(enabled)activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)}
 fun background(){if(enabled)locked=true}
 fun toggle(onChange:(Boolean)->Unit){
  if(enabled){preferences.edit().putBoolean("app_lock",false).apply();locked=false;secureWindow();onChange(false);return}
  if(!activity.getSystemService(KeyguardManager::class.java).isDeviceSecure){onError("请先设置系统锁屏密码");return}
  authenticate{preferences.edit().putBoolean("app_lock",true).apply();locked=false;secureWindow();onChange(true)}
 }
 fun unlock(after:()->Unit={}){if(locked)authenticate{locked=false;after()}}
 @Suppress("DEPRECATION") private fun authenticate(success:()->Unit){
  if(authenticating)return
  authenticating=true
  val builder=BiometricPrompt.Builder(activity).setTitle("解锁小李")
  if(android.os.Build.VERSION.SDK_INT>=30)builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)else builder.setDeviceCredentialAllowed(true)
  val signal=CancellationSignal();cancellation=signal
  builder.build().authenticate(signal,activity.mainExecutor,object:BiometricPrompt.AuthenticationCallback(){
   override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult){authenticating=false;success()}
   override fun onAuthenticationError(code:Int,message:CharSequence){authenticating=false;if(code!=BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED&&code!=BiometricPrompt.BIOMETRIC_ERROR_CANCELED)onError(message.toString())}
  })
 }
 fun close(){cancellation?.cancel()}
}
