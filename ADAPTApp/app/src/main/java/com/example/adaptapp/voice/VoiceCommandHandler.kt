package com.example.adaptapp.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.adaptapp.connection.ConnectionState
import com.example.adaptapp.controller.ArmController
import com.example.adaptapp.model.ArmPosition
import com.example.adaptapp.repository.PositionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// 语音状态 — UI 观察用
// Voice status — observed by UI
enum class VoiceStatus {
    PAUSED,          // Setup Mode / 初始化中
    IDLE,            // 待命，openWakeWord 监听中
    LISTENING,       // SpeechRecognizer 录音中
    CONFIRMING,      // 等待用户确认 yes/no
    DISAMBIGUATING,  // 等待用户选择候选
    EXECUTING        // 机械臂运动中
}

// 暴露给 UI 的语音状态
// Voice state exposed to UI
data class VoiceState(
    val status: VoiceStatus,
    val displayText: String = "",
    val isVoiceAvailable: Boolean = true
)

// 语音命令状态机 — 协调唤醒词、语音识别、TTS、机械臂控制
// Voice command state machine — coordinates wake word, SR, TTS, arm control
class VoiceCommandHandler(
    private val context: Context,
    private val armController: ArmController,
    private val positionRepository: PositionRepository,
    private val feedback: VoiceFeedback
) {

    companion object {
        private const val TAG = "VoiceCommandHandler"
        private const val LISTEN_TIMEOUT_MS = 8000L
        private const val ARRIVAL_DELAY_MS = 4000L
        private const val MAX_SR_FAILURES = 3
    }

    // UI 观察的状态流
    private val _voiceState = MutableStateFlow(VoiceState(VoiceStatus.PAUSED))
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    // 唤醒词引擎控制回调 — 由 MainActivity 设置
    // Wake word engine control — set by integration layer
    var onWakeWordStart: (() -> Unit)? = null
    var onWakeWordStop: (() -> Unit)? = null

    private val matcher = CommandMatcher()
    private val handler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null

    // 待执行的动作（确认态使用）
    private var pendingAction: PendingAction? = null
    // 歧义候选列表
    private var pendingCandidates: List<String>? = null
    // SR 连续失败计数
    private var srFailureCount = 0

    private val timeoutRunnable = Runnable { handleTimeout() }
    private val arrivalRunnable = Runnable { handleArrival() }

    // 待执行动作类型
    private sealed class PendingAction {
        data class MoveToPosition(val name: String, val position: ArmPosition) : PendingAction()
        data object Fold : PendingAction()
    }

    // === 外部调用入口 ===

    // WakeWordService 检测到唤醒词时调用（可能在非主线程）
    // Called by WakeWordService when wake word detected (may be off main thread)
    fun onWakeWord(keyword: String) {
        handler.post {
            when {
                keyword.equals("stop", ignoreCase = true) -> handleEmergencyStop()
                _voiceState.value.status == VoiceStatus.IDLE -> transitionToListening()
            }
        }
    }

    // 进入 Recall Mode — 启动语音系统
    fun resume() {
        handler.post {
            srFailureCount = 0
            setState(VoiceStatus.IDLE, "Say 'Hey Arm'")
            onWakeWordStart?.invoke()
        }
    }

    // 进入 Setup Mode — 暂停语音系统
    fun pause() {
        handler.post {
            cancelAllTimers()
            stopSpeechRecognizer()
            feedback.stop()
            onWakeWordStop?.invoke()
            pendingAction = null
            pendingCandidates = null
            setState(VoiceStatus.PAUSED)
        }
    }

    fun destroy() {
        handler.post {
            pause()
            speechRecognizer?.destroy()
            speechRecognizer = null
        }
    }

    // === 状态转换 ===

    // IDLE → LISTENING：唤醒后启动语音识别
    private fun transitionToListening() {
        onWakeWordStop?.invoke() // 释放麦克风
        setState(VoiceStatus.LISTENING, "Listening...")
        feedback.speak("Listening") {
            handler.post { startSpeechRecognizer() }
        }
    }

    // LISTENING/CONFIRMING/DISAMBIGUATING → PROCESSING（瞬时）
    private fun processText(text: String) {
        Log.i(TAG, "=== Processing speech: \"$text\" ===")

        // 急停优先级最高
        val lower = text.lowercase()
        if (lower.contains("stop") || lower.contains("emergency")) {
            handleEmergencyStop()
            return
        }

        if (!checkConnection()) return

        val positions = positionRepository.getAll()
        val names = positions.map { it.name }
        Log.i(TAG, "Saved positions: $names")
        val result = matcher.match(text, names)
        Log.i(TAG, "Match result: $result")

        when (result) {
            is CommandMatcher.MatchResult.EmergencyStop -> handleEmergencyStop()
            is CommandMatcher.MatchResult.Cancel -> handleCancel()
            is CommandMatcher.MatchResult.Landscape -> executeImmediate("Landscape mode") {
                armController.setPhoneMode(true)
            }
            is CommandMatcher.MatchResult.Portrait -> executeImmediate("Portrait mode") {
                armController.setPhoneMode(false)
            }
            is CommandMatcher.MatchResult.FoldArm -> {
                requestConfirmation(PendingAction.Fold, "Fold the arm, confirm?")
            }
            is CommandMatcher.MatchResult.PositionMatch -> {
                val pos = positions.find { it.name == result.name }
                if (pos != null) {
                    requestConfirmation(
                        PendingAction.MoveToPosition(result.name, pos),
                        "Move to ${result.name}, confirm?"
                    )
                } else {
                    handleNoMatch()
                }
            }
            is CommandMatcher.MatchResult.Ambiguous -> {
                pendingCandidates = result.candidates
                val prompt = "Did you mean ${result.candidates.joinToString(" or ")}?"
                setState(VoiceStatus.DISAMBIGUATING, result.candidates.joinToString(" or ") + "?")
                feedback.speak(prompt) {
                    handler.post { startSpeechRecognizer() }
                }
            }
            is CommandMatcher.MatchResult.NoMatch -> handleNoMatch()
        }
    }

    // 请求用户确认
    private fun requestConfirmation(action: PendingAction, prompt: String) {
        pendingAction = action
        setState(VoiceStatus.CONFIRMING, "Confirm: ${actionName(action)}?")
        feedback.speak(prompt) {
            handler.post { startSpeechRecognizer() }
        }
    }

    // 确认态收到文字
    private fun handleConfirmationResult(text: String) {
        val lower = text.lowercase()
        if (lower.contains("stop") || lower.contains("emergency")) {
            handleEmergencyStop()
            return
        }
        when {
            matcher.isConfirmation(text) -> pendingAction?.let { executeAction(it) }
            matcher.isDenial(text) -> handleCancel()
            else -> {
                // 用户改主意说了新命令
                pendingAction = null
                processText(text)
            }
        }
    }

    // 歧义态收到文字
    private fun handleDisambiguationResult(text: String) {
        val lower = text.lowercase()
        if (lower.contains("stop") || lower.contains("emergency")) {
            handleEmergencyStop()
            return
        }
        if (matcher.isDenial(text)) {
            handleCancel()
            return
        }

        // 在候选中匹配
        val candidates = pendingCandidates ?: run { handleCancel(); return }
        val positions = positionRepository.getAll()
        val candidateResult = matcher.match(text, candidates)

        when (candidateResult) {
            is CommandMatcher.MatchResult.PositionMatch -> {
                // 明确匹配候选 — 跳过二次确认，直接执行
                val pos = positions.find { it.name == candidateResult.name }
                if (pos != null) {
                    pendingCandidates = null
                    executeAction(PendingAction.MoveToPosition(candidateResult.name, pos))
                } else {
                    handleNoMatch()
                }
            }
            else -> {
                // 不相关回答 — 当作新命令
                pendingCandidates = null
                processText(text)
            }
        }
    }

    // === 执行 ===

    // 执行需要等待的动作（位置召回 / 折叠）
    private fun executeAction(action: PendingAction) {
        if (!checkConnection()) return

        pendingAction = null
        pendingCandidates = null
        srFailureCount = 0

        val name = actionName(action)
        setState(VoiceStatus.EXECUTING, "Moving: $name")

        // 恢复唤醒词监听（EXECUTING 期间可以听 "Stop"）
        onWakeWordStart?.invoke()

        when (action) {
            is PendingAction.MoveToPosition -> {
                armController.moveTo(action.position)
                feedback.speak("Moving to ${action.name}")
            }
            is PendingAction.Fold -> {
                armController.foldAndRelease()
                feedback.speak("Folding")
            }
        }

        handler.postDelayed(arrivalRunnable, ARRIVAL_DELAY_MS)
    }

    // 执行即时命令（横屏 / 竖屏 — 无需等待）
    private fun executeImmediate(ttsMessage: String, command: () -> Unit) {
        command()
        srFailureCount = 0
        // openWakeWord 和 TTS 可共存，直接恢复待命
        returnToIdle()
        feedback.speak(ttsMessage)
    }

    // === 事件处理 ===

    private fun handleEmergencyStop() {
        cancelAllTimers()
        stopSpeechRecognizer()
        feedback.stop()
        armController.emergencyStop()
        armController.setTorque(true)
        pendingAction = null
        pendingCandidates = null
        srFailureCount = 0
        returnToIdle()
        feedback.speak("Emergency stop activated")
    }

    private fun handleCancel() {
        stopSpeechRecognizer()
        cancelAllTimers()
        pendingAction = null
        pendingCandidates = null
        srFailureCount = 0
        returnToIdle()
        feedback.speak("Cancelled")
    }

    private fun handleNoMatch() {
        stopSpeechRecognizer()
        returnToIdle()
        feedback.speak("No matching position found")
    }

    private fun handleTimeout() {
        stopSpeechRecognizer()
        returnToIdle()
        feedback.speak("Timed out")
    }

    private fun handleArrival() {
        // openWakeWord 已在 executeAction 中启动，无需重复
        setState(VoiceStatus.IDLE, "Say 'Hey Arm'")
        feedback.speak("Arrived")
    }

    // 回到待命态：启动唤醒词 + 更新 UI
    private fun returnToIdle() {
        setState(VoiceStatus.IDLE, "Say 'Hey Arm'")
        onWakeWordStart?.invoke()
    }

    // 检查连接状态
    private fun checkConnection(): Boolean {
        if (armController.connection.connectionState.value != ConnectionState.CONNECTED) {
            stopSpeechRecognizer()
            pendingAction = null
            pendingCandidates = null
            returnToIdle()
            feedback.speak("Connection lost, cannot execute")
            return false
        }
        return true
    }

    // === SpeechRecognizer 管理 ===

    private fun startSpeechRecognizer() {
        val status = _voiceState.value.status
        if (status != VoiceStatus.LISTENING
            && status != VoiceStatus.CONFIRMING
            && status != VoiceStatus.DISAMBIGUATING) return

        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onResults(results: Bundle?) {
                cancelListenTimeout()
                val allResults = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                Log.i(TAG, "SR results: $allResults")
                val text = allResults?.firstOrNull()?.trim() ?: ""
                if (text.isEmpty()) {
                    Log.w(TAG, "SR returned empty text")
                    onSrFailure()
                } else {
                    srFailureCount = 0
                    dispatchSpeechResult(text)
                }
            }

            override fun onError(error: Int) {
                cancelListenTimeout()
                val errorName = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
                    SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_PERMISSIONS"
                    SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
                    SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_BUSY"
                    SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
                    11 -> "ERROR_TOO_MANY_REQUESTS"
                    12 -> "ERROR_SERVER_DISCONNECTED"
                    13 -> "ERROR_LANGUAGE_NOT_SUPPORTED"
                    14 -> "ERROR_LANGUAGE_UNAVAILABLE"
                    else -> "ERROR_$error"
                }
                Log.w(TAG, "SpeechRecognizer error: $errorName ($error)")
                onSrFailure()
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

        speechRecognizer?.startListening(intent)
        startListenTimeout()
    }

    // 根据当前状态分发识别结果
    private fun dispatchSpeechResult(text: String) {
        Log.d(TAG, "Speech result: \"$text\" in state ${_voiceState.value.status}")
        when (_voiceState.value.status) {
            VoiceStatus.LISTENING -> processText(text)
            VoiceStatus.CONFIRMING -> handleConfirmationResult(text)
            VoiceStatus.DISAMBIGUATING -> handleDisambiguationResult(text)
            else -> {}
        }
    }

    // SR 失败处理
    private fun onSrFailure() {
        srFailureCount++
        if (srFailureCount >= MAX_SR_FAILURES) {
            returnToIdle()
            feedback.speak("Voice recognition not working, please try again later")
            srFailureCount = 0
        } else {
            returnToIdle()
        }
    }

    private fun stopSpeechRecognizer() {
        try { speechRecognizer?.cancel() } catch (_: Exception) {}
    }

    // === 定时器 ===

    private fun startListenTimeout() {
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, LISTEN_TIMEOUT_MS)
    }

    private fun cancelListenTimeout() {
        handler.removeCallbacks(timeoutRunnable)
    }

    private fun cancelAllTimers() {
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(arrivalRunnable)
    }

    // === 工具 ===

    private fun setState(status: VoiceStatus, displayText: String = "") {
        _voiceState.value = VoiceState(status, displayText, feedback.isAvailable())
    }

    private fun actionName(action: PendingAction): String = when (action) {
        is PendingAction.MoveToPosition -> action.name
        is PendingAction.Fold -> "fold"
    }
}
