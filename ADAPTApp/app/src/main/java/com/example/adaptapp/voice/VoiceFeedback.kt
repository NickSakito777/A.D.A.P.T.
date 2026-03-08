package com.example.adaptapp.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

// TTS 封装 — 语音反馈给 SCI 患者
// TTS wrapper — audio feedback for SCI users
class VoiceFeedback(context: Context) {

    companion object {
        private const val TAG = "VoiceFeedback"
    }

    private var tts: TextToSpeech? = null
    private var ready = false
    private val utteranceId = AtomicInteger(0)

    // 当前是否正在播报
    // Whether TTS is currently speaking
    val isSpeaking: Boolean get() = tts?.isSpeaking == true

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                ready = result != TextToSpeech.LANG_MISSING_DATA
                        && result != TextToSpeech.LANG_NOT_SUPPORTED
                if (!ready) Log.e(TAG, "English TTS not available")
            } else {
                Log.e(TAG, "TTS init failed, status=$status")
                ready = false
            }
        }
    }

    // 播报文字，完成后执行回调
    // Speak text, execute callback when done
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!ready || tts == null) {
            Log.w(TAG, "TTS not ready, skipping: $text")
            onDone?.invoke()
            return
        }

        val id = "utterance_${utteranceId.incrementAndGet()}"

        if (onDone != null) {
            tts!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id) onDone()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id) {
                        Log.e(TAG, "TTS error for: $text")
                        onDone()
                    }
                }
            })
        }

        tts!!.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    // 立即停止播报（急停时使用）
    // Stop immediately (used for emergency stop)
    fun stop() {
        tts?.stop()
    }

    // TTS 是否可用
    // Whether TTS engine is available
    fun isAvailable(): Boolean = ready

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
