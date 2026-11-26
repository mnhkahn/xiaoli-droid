package com.cyeam.xiaoli

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.*

class VoiceEngineTest {
 @Test fun synthesizesChineseSpeechWithInstalledEngine(){
  Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveVoice")=="yes")
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val initialized=CountDownLatch(1);var status=TextToSpeech.ERROR
  val engine=TextToSpeech(context,{result->status=result;initialized.countDown()})
  val file=File(context.cacheDir,"voice-engine-validation.wav")
  try{
   Assert.assertTrue("Engine initialization timed out",initialized.await(30,TimeUnit.SECONDS))
   Assert.assertEquals("No usable TTS engine",TextToSpeech.SUCCESS,status)
   val language=engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
   println("TTS Chinese language availability: $language")
   Assert.assertTrue("Chinese voice data unavailable",language>=0)
   val completed=CountDownLatch(1);var error=false
   engine.setOnUtteranceProgressListener(object:UtteranceProgressListener(){override fun onStart(id:String?){};override fun onDone(id:String?){completed.countDown()};@Deprecated("Legacy callback")override fun onError(id:String?){error=true;completed.countDown()}})
   Assert.assertEquals(TextToSpeech.SUCCESS,engine.synthesizeToFile("小李，请帮我检查这道口算题。",null,file,"validation"))
   Assert.assertTrue("Speech synthesis timed out",completed.await(60,TimeUnit.SECONDS));Assert.assertFalse("Engine could not synthesize Chinese speech",error);Assert.assertTrue("Audio file empty",file.length()>1000)
   println("TTS synthesized audio bytes: ${file.length()}")
  }finally{engine.shutdown();file.delete()}
 }
}
