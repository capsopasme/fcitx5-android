/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.voice

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.QnnConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import org.fcitx.fcitx5.android.R
import java.io.File
import java.io.IOException

/**
 * Wraps a sherpa-onnx [OfflineRecognizer]. Only used inside the `:voice` process,
 * because native failures in QNN / onnxruntime call exit() and would kill the keyboard otherwise.
 */
class VoiceEngine private constructor(
    /** what was asked for */
    val key: Key,
    /** what is actually loaded: [SpeechModel.SenseVoiceCpu] when the NPU model fell back to it */
    val model: SpeechModel,
    private val recognizer: OfflineRecognizer,
    val loadMillis: Long,
    /** shown in the panel's status line */
    val backendName: String,
) {

    data class Key(
        val model: SpeechModel,
        val language: SpeechLanguage,
        val itn: Boolean,
    )

    /** a CPU engine standing in for an NPU model whose initialization crashed */
    val isFallback: Boolean
        get() = model != key.model

    /** @return recognized text, trimmed */
    fun recognize(samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, VoiceProtocol.SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    fun release() = recognizer.release()

    class EngineException(message: String) : Exception(message)

    companion object {
        private const val TAG = "VoiceEngine"

        @Volatile
        private var adspPathSet = false

        private val bigCoreThreads: Int
            get() = Runtime.getRuntime().availableProcessors().coerceIn(1, 8).let { if (it >= 8) 4 else 2 }

        /**
         * @param forceQnn try the NPU even if its last initialization crashed the process
         */
        fun create(ctx: Context, key: Key, forceQnn: Boolean = false): VoiceEngine {
            if (!key.model.isQnn) return load(ctx, key, key.model, ctx.getString(R.string.voice_backend_cpu))
            val guard = QnnCrashGuard(ctx)
            if (forceQnn) guard.clear()
            if (guard.crashedBefore()) {
                // QNN reports fatal errors with exit(): loading it again would kill the process
                // again, every time the panel opens. Use the CPU model instead if it's there.
                Log.w(TAG, "QNN initialization crashed last time, not trying again")
                val cpu = SpeechModel.SenseVoiceCpu
                if (VoiceModelManager.isInstalled(ctx, cpu)) {
                    return load(ctx, key, cpu, ctx.getString(R.string.voice_backend_cpu_fallback))
                }
                throw EngineException(ctx.getString(R.string.voice_error_qnn_crashed))
            }
            guard.arm()
            try {
                val engine = load(ctx, key, key.model, ctx.getString(R.string.voice_backend_npu))
                // The first inference sets up the graph on the DSP: it is the other place where
                // QNN fails fatally, and doing it now keeps that latency out of the first sentence.
                try {
                    engine.recognize(FloatArray(VoiceProtocol.SAMPLE_RATE / 2))
                } catch (e: Throwable) {
                    engine.release()
                    throw e
                }
                return engine
            } finally {
                // only reached if the process survived; an exception (missing files, wrong SoC)
                // is reported normally and doesn't disable the NPU
                guard.disarm()
            }
        }

        private fun load(ctx: Context, key: Key, model: SpeechModel, backendName: String): VoiceEngine {
            val dir = VoiceModelManager.modelDir(ctx, model)
            val missing = model.requiredFiles.filterNot { File(dir, it).isFile }
            if (missing.isNotEmpty()) {
                throw EngineException(ctx.getString(R.string.voice_error_model_missing, missing.joinToString()))
            }
            val modelConfig = when (model) {
                SpeechModel.SenseVoiceQnnSM8650 -> {
                    checkQnnUsable(ctx)
                    OfflineModelConfig(
                        provider = "qnn",
                        senseVoice = OfflineSenseVoiceModelConfig(
                            language = key.language.senseVoiceCode,
                            useInverseTextNormalization = key.itn,
                            qnnConfig = QnnConfig(
                                backendLib = "libQnnHtp.so",
                                systemLib = "libQnnSystem.so",
                                contextBinary = File(dir, "model.bin").absolutePath,
                            ),
                        ),
                        tokens = File(dir, "tokens.txt").absolutePath,
                        // only used for feature extraction
                        numThreads = 2,
                    )
                }
                SpeechModel.SenseVoiceCpu -> OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = File(dir, "model.int8.onnx").absolutePath,
                        language = key.language.senseVoiceCode,
                        useInverseTextNormalization = key.itn,
                    ),
                    tokens = File(dir, "tokens.txt").absolutePath,
                    numThreads = bigCoreThreads,
                )
                SpeechModel.Qwen3Asr -> OfflineModelConfig(
                    qwen3Asr = OfflineQwen3AsrModelConfig(
                        convFrontend = File(dir, "conv_frontend.onnx").absolutePath,
                        encoder = File(dir, "encoder.int8.onnx").absolutePath,
                        decoder = File(dir, "decoder.int8.onnx").absolutePath,
                        tokenizer = File(dir, "tokenizer").absolutePath,
                    ),
                    tokens = "",
                    numThreads = bigCoreThreads,
                )
            }
            val config = OfflineRecognizerConfig(modelConfig = modelConfig)
            val start = SystemClock.elapsedRealtime()
            val recognizer = try {
                OfflineRecognizer(assetManager = null, config = config)
            } catch (e: IllegalArgumentException) {
                throw EngineException(ctx.getString(R.string.voice_error_init_failed, e.message ?: ""))
            }
            val elapsed = SystemClock.elapsedRealtime() - start
            Log.i(TAG, "Loaded $model in ${elapsed}ms")
            return VoiceEngine(key, model, recognizer, elapsed, backendName)
        }

        private fun checkQnnUsable(ctx: Context) {
            val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else ""
            Log.i(TAG, "SoC model: '$soc', hardware: '${Build.HARDWARE}'")
            if (soc.isNotBlank() && soc != Build.UNKNOWN && !soc.uppercase().startsWith("SM8650")) {
                throw EngineException(ctx.getString(R.string.voice_error_qnn_soc, soc))
            }
            val libDir = ctx.applicationInfo.nativeLibraryDir
            val required = listOf("libQnnHtp.so", "libQnnSystem.so", "libQnnHtpV75Stub.so", "libQnnHtpV75Skel.so")
            val missing = required.filterNot { File(libDir, it).exists() }
            if (missing.isNotEmpty()) {
                throw EngineException(ctx.getString(R.string.voice_error_qnn_libs, missing.joinToString()))
            }
            if (!adspPathSet) {
                // without ADSP_LIBRARY_PATH the DSP can't find libQnnHtpV75Skel.so (error 1008)
                OfflineRecognizer.prependAdspLibraryPath(libDir)
                adspPathSet = true
            }
        }

        /**
         * Silero VAD bundled as a raw resource, copied to internal storage.
         * Written to a temp file, fsync'ed, then renamed: a power loss can never leave a
         * truncated model behind (onnxruntime would abort() on it and the voice process
         * would crash on every start).
         * The copy survives app updates, so it's replaced when the bundled one differs
         * (an update shipping a new VAD model would otherwise keep using the old file).
         */
        fun createVad(ctx: Context, model: SpeechModel, minSilenceMs: Int): Vad {
            val file = File(VoiceModelManager.rootDir(ctx), VoiceModelManager.VAD_FILE_NAME)
            // ~640 KB, read only when the VAD is (re)created
            val bundled = ctx.resources.openRawResource(R.raw.silero_vad).use { it.readBytes() }
            if (!file.isFile || !sameContent(file, bundled)) {
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".tmp")
                java.io.FileOutputStream(tmp).use { out ->
                    out.write(bundled)
                    out.fd.sync()
                }
                if (!tmp.renameTo(file)) {
                    tmp.delete()
                    throw EngineException("Failed to install VAD model")
                }
            }
            val config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = file.absolutePath,
                    threshold = 0.5f,
                    minSilenceDuration = minSilenceMs / 1000f,
                    minSpeechDuration = 0.25f,
                    windowSize = VAD_WINDOW,
                    // soft limit: past it VAD cuts at the next short pause. The service force-cuts
                    // at maxSegmentSeconds, this leaves room to find a natural break first.
                    maxSpeechDuration = (model.maxSegmentSeconds - 4f).coerceAtLeast(5f),
                ),
                sampleRate = VoiceProtocol.SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
            )
            return Vad(assetManager = null, config = config)
        }

        private fun sameContent(file: File, bytes: ByteArray): Boolean =
            file.length() == bytes.size.toLong() && file.readBytes().contentEquals(bytes)

        const val VAD_WINDOW = 512
    }
}

/**
 * Remembers that loading the NPU model killed the `:voice` process. QNN reports fatal errors
 * (no access to the DSP, a context binary that doesn't match the runtime, ...) with exit(), so
 * there is no exception to catch: a marker file is written before loading and deleted after it,
 * and if it is still there next time, the previous attempt never returned.
 *
 * The marker holds the APK's install time, so an app update (possibly with other QNN
 * libraries) tries the NPU again. One tiny write per model load, which happens rarely: the
 * loaded model is kept in the cached process.
 */
internal class QnnCrashGuard(ctx: Context) {
    private val file = File(ctx.noBackupFilesDir, "voice-qnn-loading")

    private val stamp: String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
    } catch (_: Exception) {
        "0"
    }

    fun crashedBefore(): Boolean {
        if (!file.exists()) return false
        val content = try {
            file.readText()
        } catch (_: IOException) {
            ""
        }
        if (content == stamp) return true
        // left behind by another version of the app
        file.delete()
        return false
    }

    fun arm() {
        try {
            file.writeText(stamp)
        } catch (e: IOException) {
            Log.w("QnnCrashGuard", "Cannot write marker", e)
        }
    }

    fun disarm() {
        file.delete()
    }

    fun clear() = disarm()
}
