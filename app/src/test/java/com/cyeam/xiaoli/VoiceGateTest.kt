package com.cyeam.xiaoli

import org.junit.Assert.*
import org.junit.Test
class VoiceGateTest{
 @Test fun longSpeechIsLosslessAndDoesNotSplitEmoji(){val input="口算。".repeat(1500)+"😀".repeat(2000);val chunks=ttsChunks(input);assertEquals(input,chunks.joinToString(""));assertTrue(chunks.all{it.length<=3500&&!it.last().isHighSurrogate()})}

 @Test fun endsOnlyAfterSpeechAndTrailingSilence(){val gate=VoiceGate();for(t in 100L..600L step 100)assertFalse(gate.sample(t,3000));for(t in 700L..1400L step 100)assertFalse(gate.sample(t,100));assertTrue(gate.sample(1500,100))}
 @Test fun silenceAndShortNoiseCannotSubmit(){val gate=VoiceGate();for(t in 100L..3000L step 100)assertFalse(gate.sample(t,if(t==300L)5000 else 50));assertFalse(gate.heardSpeech)}
}
