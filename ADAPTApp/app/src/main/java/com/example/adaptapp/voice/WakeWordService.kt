package com.example.adaptapp.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.core.content.ContextCompat
import java.nio.FloatBuffer

// openWakeWord 唤醒词检测服务（ONNX Runtime）
// Wake word detection using openWakeWord ONNX models
//
// 需要在 assets/ 目录放置以下模型文件（PC 上用 Python 训练生成）：
// Required model files in assets/ (trained on PC with openWakeWord Python):
//   melspectrogram.onnx  — shared mel spectrogram extractor
//   embedding_model.onnx — shared audio embedding model
//   hey_arm.onnx         — "Hey Arm" wake word detector
//   stop.onnx            — "Stop" wake word detector
class WakeWordService(private val context: Context) {

    companion object {
        private const val TAG = "WakeWordService"

        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE = 1280 // 80ms at 16kHz

        // 模型文件名
        const val MELSPEC_MODEL = "melspectrogram.onnx"
        const val EMBEDDING_MODEL = "embedding_model.onnx"
        val WAKE_WORD_MODELS = mapOf(
            "hey arm" to "hey_arm.onnx",
            "stop" to "stop.onnx"
        )

        // Feature dimensions — standard openWakeWord values
        private const val N_MEL_BANDS = 32
        private const val MEL_WINDOW = 76       // 76 mel frames for one embedding
        private const val EMBED_DIM = 96
        private const val EMBED_WINDOW = 16     // 16 embeddings for detection

        private const val THRESHOLD = 0.5f
        private const val COOLDOWN_MS = 2000L   // 检测后冷却 2s 防重复
    }

    // 检测到唤醒词的回调（在后台线程调用）
    var onWakeWord: ((keyword: String) -> Unit)? = null

    @Volatile private var isRunning = false
    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null

    // ONNX
    private var env: OrtEnvironment? = null
    private var melSession: OrtSession? = null
    private var embedSession: OrtSession? = null
    private val wwSessions = mutableMapOf<String, OrtSession>()

    // 特征缓冲区
    private val melBuffer = ArrayDeque<FloatArray>()
    private val embedBuffer = ArrayDeque<FloatArray>()
    private var lastDetectionTime = 0L

    private var _initialized = false
    val isInitialized: Boolean get() = _initialized

    // 初始化 ONNX 模型，失败返回 false（模型文件缺失等）
    fun initialize(): Boolean {
        if (_initialized) return true
        try {
            Log.i(TAG, "Initializing ONNX Runtime...")
            env = OrtEnvironment.getEnvironment()
            Log.i(TAG, "OrtEnvironment OK")

            // 列出 assets 中可用的文件
            try {
                val assetFiles = context.assets.list("") ?: emptyArray()
                Log.i(TAG, "Assets: ${assetFiles.joinToString()}")
            } catch (e: Exception) {
                Log.w(TAG, "Cannot list assets: ${e.message}")
            }

            melSession = loadSession(MELSPEC_MODEL)
            if (melSession == null) {
                Log.e(TAG, "$MELSPEC_MODEL not found in assets")
                return false
            }
            embedSession = loadSession(EMBEDDING_MODEL)
            if (embedSession == null) {
                Log.e(TAG, "$EMBEDDING_MODEL not found in assets")
                return false
            }

            for ((keyword, file) in WAKE_WORD_MODELS) {
                val session = loadSession(file)
                if (session != null) {
                    wwSessions[keyword] = session
                } else {
                    Log.w(TAG, "$file not found, keyword '$keyword' unavailable")
                }
            }

            if (wwSessions.isEmpty()) {
                Log.e(TAG, "No wake word models loaded")
                return false
            }

            _initialized = true
            Log.i(TAG, "Initialized: ${wwSessions.keys}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Init failed: ${e.message}")
            return false
        }
    }

    // 启动麦克风监听
    fun start() {
        if (!_initialized || isRunning) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO not granted")
            return
        }

        isRunning = true
        clearBuffers()

        thread = Thread({
            initAudioRecord()
            audioLoop()
            releaseAudioRecord()
        }, "WakeWord-Audio").apply { start() }
    }

    // 停止麦克风监听（释放 AudioRecord 给 SpeechRecognizer 用）
    fun stop() {
        isRunning = false
        thread?.join(1000)
        thread = null
    }

    fun destroy() {
        stop()
        wwSessions.values.forEach { runCatching { it.close() } }
        wwSessions.clear()
        runCatching { melSession?.close() }
        runCatching { embedSession?.close() }
        runCatching { env?.close() }
        melSession = null
        embedSession = null
        env = null
        _initialized = false
    }

    // === AudioRecord ===

    @SuppressLint("MissingPermission") // 调用前已检查权限
    private fun initAudioRecord() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, FRAME_SIZE * 4)
        )
        audioRecord?.startRecording()
    }

    private fun releaseAudioRecord() {
        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
        }
        audioRecord = null
    }

    // === 音频处理主循环 ===

    private fun audioLoop() {
        val buffer = ShortArray(FRAME_SIZE)

        while (isRunning) {
            val read = audioRecord?.read(buffer, 0, FRAME_SIZE) ?: break
            if (read != FRAME_SIZE) continue

            // PCM16 → float (keep int16 range, mel model expects it)
            val floats = FloatArray(FRAME_SIZE) { buffer[it].toFloat() }
            processFrame(floats)
        }
    }

    private fun processFrame(audio: FloatArray) {
        val ortEnv = env ?: return

        // 1. Audio → Mel spectrogram features (~5 frames per 1280-sample chunk)
        val melFrames = runMelSpec(ortEnv, audio) ?: return
        for (frame in melFrames) {
            melBuffer.addLast(frame)
        }
        while (melBuffer.size > MEL_WINDOW) melBuffer.removeFirst()

        // 2. Compute embedding every chunk (need 76 mel frames)
        if (melBuffer.size < MEL_WINDOW) return

        val embedding = runEmbedding(ortEnv) ?: return
        embedBuffer.addLast(embedding)
        while (embedBuffer.size > EMBED_WINDOW) embedBuffer.removeFirst()

        // 3. Detect wake words when embed window is full
        if (embedBuffer.size < EMBED_WINDOW) return

        val now = System.currentTimeMillis()
        if (now - lastDetectionTime < COOLDOWN_MS) return

        for ((keyword, session) in wwSessions) {
            val score = runDetection(ortEnv, session) ?: continue
            if (score > THRESHOLD) {
                lastDetectionTime = now
                Log.i(TAG, "Detected '$keyword' score=${"%.3f".format(score)}")
                onWakeWord?.invoke(keyword)
                clearBuffers()
                break
            }
        }
    }

    // === ONNX 推理 ===

    // Audio (1, 1280) → Mel output (time, 1, ?, 32) → squeeze → list of 32-dim frames with transform
    private fun runMelSpec(ortEnv: OrtEnvironment, audio: FloatArray): List<FloatArray>? {
        val session = melSession ?: return null
        return try {
            val shape = longArrayOf(1, FRAME_SIZE.toLong())
            OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(audio), shape).use { tensor ->
                session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                    extractMelFrames(result[0].value)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MelSpec: ${e.message}")
            null
        }
    }

    // Squeeze mel output to list of 32-dim frames, apply openWakeWord transform: x/10+2
    @Suppress("UNCHECKED_CAST")
    private fun extractMelFrames(output: Any?): List<FloatArray>? {
        // Output shape is (time, 1, bands, 32) or nested arrays — squeeze to (time, 32)
        val flat = flattenToFrames(output) ?: return null
        // Apply mel transform
        return flat.map { frame ->
            FloatArray(frame.size) { i -> frame[i] / 10f + 2f }
        }
    }

    // Recursively extract to list of FloatArrays (innermost dimension = mel bands)
    @Suppress("UNCHECKED_CAST")
    private fun flattenToFrames(output: Any?): List<FloatArray>? = when (output) {
        is FloatArray -> listOf(output)
        is Array<*> -> {
            if (output.isEmpty()) null
            else if (output[0] is FloatArray) {
                output.map { it as FloatArray }
            } else {
                // Recurse and collect
                output.flatMap { flattenToFrames(it) ?: emptyList() }.ifEmpty { null }
            }
        }
        else -> null
    }

    // Mel buffer (1, 76, 32, 1) → Embedding (1, 1, 1, 96) → 取 96 维向量
    private fun runEmbedding(ortEnv: OrtEnvironment): FloatArray? {
        val session = embedSession ?: return null
        return try {
            // 展平 mel buffer 为 (1, 76, 32, 1) — 注意尾部维度 1
            val flat = FloatArray(MEL_WINDOW * N_MEL_BANDS)
            melBuffer.forEachIndexed { i, frame ->
                System.arraycopy(frame, 0, flat, i * N_MEL_BANDS, minOf(frame.size, N_MEL_BANDS))
            }
            val shape = longArrayOf(1, MEL_WINDOW.toLong(), N_MEL_BANDS.toLong(), 1)
            OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(flat), shape).use { tensor ->
                session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                    extractFloatArray(result[0].value)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Embedding: ${e.message}")
            null
        }
    }

    // Embedding buffer (1, EMBED_WINDOW, EMBED_DIM) → Score (0.0~1.0)
    private fun runDetection(ortEnv: OrtEnvironment, session: OrtSession): Float? {
        return try {
            val flat = FloatArray(EMBED_WINDOW * EMBED_DIM)
            embedBuffer.forEachIndexed { i, frame ->
                System.arraycopy(frame, 0, flat, i * EMBED_DIM, minOf(frame.size, EMBED_DIM))
            }
            val shape = longArrayOf(1, EMBED_WINDOW.toLong(), EMBED_DIM.toLong())
            OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(flat), shape).use { tensor ->
                session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                    extractScalar(result[0].value)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Detection: ${e.message}")
            null
        }
    }

    // === 工具方法 ===

    private fun loadSession(fileName: String): OrtSession? {
        val ortEnv = env ?: run {
            Log.e(TAG, "loadSession($fileName): env is null")
            return null
        }
        return try {
            val bytes = context.assets.open(fileName).use { it.readBytes() }
            Log.d(TAG, "loadSession($fileName): read ${bytes.size} bytes")
            ortEnv.createSession(bytes).also {
                Log.i(TAG, "loadSession($fileName): OK")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "loadSession($fileName) failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractFloatArray(output: Any?): FloatArray? = when (output) {
        is FloatArray -> output
        is Array<*> -> extractFloatArray(output[0])
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractScalar(output: Any?): Float? = when (output) {
        is Float -> output
        is FloatArray -> output.firstOrNull()
        is Array<*> -> extractScalar(output[0])
        else -> null
    }

    private fun clearBuffers() {
        melBuffer.clear()
        embedBuffer.clear()
    }
}
