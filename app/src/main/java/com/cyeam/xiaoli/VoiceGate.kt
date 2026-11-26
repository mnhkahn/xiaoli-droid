package com.cyeam.xiaoli

/** Amplitude gate with a minimum voiced interval and a trailing silence window. */
class VoiceGate(private val threshold:Int=1400) {
    private var voicedMillis=0L
    private var lastVoice=0L
    private var lastSample=0L
    fun sample(elapsed:Long,amplitude:Int):Boolean {
        val interval=if(lastSample==0L)0 else (elapsed-lastSample).coerceIn(0,200)
        lastSample=elapsed
        if(amplitude>=threshold){voicedMillis+=interval;lastVoice=elapsed}
        return voicedMillis>=250 && elapsed-lastVoice>=900 && elapsed>=1200
    }
    val heardSpeech get()=voicedMillis>=250
}

fun ttsChunks(text:String,limit:Int=3500):List<String>{
 require(limit>=2)
 val chunks=mutableListOf<String>();var start=0
 while(start<text.length){var end=(start+limit).coerceAtMost(text.length)
  if(end<text.length){val boundary=text.lastIndexOfAny(charArrayOf('。','！','？','\n'),end-1);if(boundary>start+limit/2)end=boundary+1;if(text[end-1].isHighSurrogate())end--}
  chunks+=text.substring(start,end);start=end
 }
 return chunks
}
