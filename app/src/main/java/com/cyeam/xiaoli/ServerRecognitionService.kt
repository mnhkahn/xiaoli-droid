package com.cyeam.xiaoli

import android.content.Intent
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlinx.coroutines.*

/** Recognition engine required by Android's default VoiceInteractionService. */
class ServerRecognitionService : RecognitionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val stopping = AtomicBoolean(false)

    override fun onStartListening(intent: Intent?, callback: Callback) {
        if (job?.isActive == true) {
            callback.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            return
        }
        val credentials = Credentials(this)
        val token = credentials.token()
        if (token.isBlank()) {
            callback.error(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        stopping.set(false)
        job =
            scope.launch {
                val file = File.createTempFile("recognize-", ".ogg", cacheDir)
                @Suppress("DEPRECATION")
                val recorder =
                    if (android.os.Build.VERSION.SDK_INT >= 31)
                        MediaRecorder(this@ServerRecognitionService)
                    else MediaRecorder()
                try {
                    var began = false
                    withContext(Dispatchers.IO) {
                        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                        recorder.setOutputFormat(MediaRecorder.OutputFormat.OGG)
                        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                        recorder.setAudioSamplingRate(16000)
                        recorder.setAudioEncodingBitRate(24000)
                        recorder.setOutputFile(file.path)
                        recorder.prepare()
                        recorder.start()
                        callback.readyForSpeech(Bundle())
                        val start = System.currentTimeMillis()
                        var lastSpeech = start
                        while (
                            isActive &&
                                !stopping.get() &&
                                System.currentTimeMillis() - start < 20000
                        ) {
                            delay(100)
                            val amplitude = recorder.maxAmplitude
                            callback.rmsChanged(
                                (20 * log10((amplitude.coerceAtLeast(1) / 32768.0))).toFloat()
                            )
                            if (amplitude > 700) {
                                lastSpeech = System.currentTimeMillis()
                                if (!began) {
                                    began = true
                                    callback.beginningOfSpeech()
                                }
                            }
                            if (
                                began && System.currentTimeMillis() - lastSpeech > 1400 ||
                                    !began && System.currentTimeMillis() - start > 6000
                            )
                                break
                        }
                        recorder.stop()
                    }
                    callback.endOfSpeech()
                    if (!began) {
                        callback.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                        return@launch
                    }
                    val result =
                        Api(credentials.server, credentials.device, token)
                            .multipart("/mobile/v1/transcribe", emptyMap(), listOf("audio" to file))
                            .getString("text")
                    callback.results(
                        Bundle().apply {
                            putStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION,
                                arrayListOf(result),
                            )
                        }
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SecurityException) {
                    callback.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
                } catch (e: Exception) {
                    callback.error(SpeechRecognizer.ERROR_NETWORK)
                } finally {
                    runCatching { recorder.reset() }
                    recorder.release()
                    file.delete()
                }
            }
    }

    override fun onStopListening(callback: Callback) {
        stopping.set(true)
    }

    override fun onCancel(callback: Callback) {
        job?.cancel()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
