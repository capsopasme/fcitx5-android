/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.voice

import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.Vad
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.voice.VoiceProtocol as P
import java.io.File

/**
 * Runs VAD + offline ASR in the isolated `:voice` process.
 *
 * Audio is captured by the IME process and streamed here in small float chunks.
 * We use VAD to cut sentences: every finished sentence is decoded once and sent back as
 * [P.EVT_FINAL]; while the user is still speaking, the current sentence is periodically
 * re-decoded and sent as [P.EVT_PARTIAL] (simulated streaming).
 */
class VoiceRecognitionService : Service() {

    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler

    private val incoming = Messenger(Handler(Looper.getMainLooper()) { msg ->
        // copy the message, the original is recycled after handleMessage returns
        worker.sendMessage(Message.obtain(msg))
        true
    })

    override fun onCreate() {
        super.onCreate()
        workerThread = HandlerThread("voice-worker").apply { start() }
        worker = Handler(workerThread.looper) { msg ->
            try {
                handleWork(msg)
            } catch (e: Throwable) {
                Log.e(TAG, "worker error", e)
                reply(msg.replyTo ?: session?.replyTo, P.EVT_ERROR, msg.arg1) {
                    putString(P.KEY_MESSAGE, e.localizedMessage ?: e.javaClass.simpleName)
                }
                resetSession()
            }
            true
        }
    }

    override fun onBind(intent: Intent?): IBinder = incoming.binder

    override fun onUnbind(intent: Intent?): Boolean {
        worker.post { resetSession() }
        return false
    }

    override fun onDestroy() {
        worker.post {
            resetSession()
            vad?.release()
            vad = null
            // keep the engine cached in this process: re-opening the voice panel is instant
        }
        workerThread.quitSafely()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            worker.post { if (session == null) releaseEngine() }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // worker thread state

    private class Session(
        val id: Int,
        val replyTo: Messenger?,
        val partial: Boolean,
    ) {
        val buffer = FloatRingBuffer()

        /** samples in [buffer] already fed to VAD */
        var fed = 0
        var speechStarted = false
        var speechStart = 0
        var lastPartialAt = 0L
        var lastPartialText = ""
    }

    private var session: Session? = null
    private var vad: Vad? = null
    private var vadKey: Pair<SpeechModel, Int>? = null

    private fun handleWork(msg: Message) {
        when (msg.what) {
            P.MSG_START -> start(msg)
            P.MSG_AUDIO -> audio(msg)
            P.MSG_STOP -> stop(msg.arg1, cancel = false)
            P.MSG_CANCEL -> stop(msg.arg1, cancel = true)
            P.MSG_SELF_TEST -> selfTest(msg)
        }
    }

    private fun parseKey(data: Bundle): VoiceEngine.Key = VoiceEngine.Key(
        SpeechModel.valueOf(data.getString(P.KEY_MODEL)!!),
        SpeechLanguage.valueOf(data.getString(P.KEY_LANGUAGE) ?: SpeechLanguage.Auto.name),
        data.getBoolean(P.KEY_ITN, true),
    )

    private fun ensureEngine(key: VoiceEngine.Key, replyTo: Messenger?, id: Int): VoiceEngine {
        cachedEngine?.let { if (it.key == key) return it }
        reply(replyTo, P.EVT_LOADING, id)
        releaseEngine()
        return VoiceEngine.create(this, key).also { cachedEngine = it }
    }

    private fun start(msg: Message) {
        val data = msg.data
        val key = parseKey(data)
        val silenceMs = data.getInt(P.KEY_SILENCE_MS, 600)
        resetSession()
        val engine = ensureEngine(key, msg.replyTo, msg.arg1)
        if (vad == null || vadKey != key.model to silenceMs) {
            vad?.release()
            vad = VoiceEngine.createVad(this, key.model, silenceMs)
            vadKey = key.model to silenceMs
        }
        vad!!.reset()
        session = Session(
            msg.arg1, msg.replyTo,
            partial = data.getBoolean(P.KEY_PARTIAL, true) && key.model.fastEnoughForPartial
        )
        reply(msg.replyTo, P.EVT_READY, msg.arg1) {
            putString(P.KEY_BACKEND, engine.backendName)
            putLong(P.KEY_LOAD_MS, engine.loadMillis)
        }
    }

    private fun audio(msg: Message) {
        val s = session ?: return
        if (s.id != msg.arg1) return
        val pcm = msg.data.getFloatArray(P.KEY_PCM) ?: return
        val engine = cachedEngine ?: return
        val vad = vad ?: return
        s.buffer.append(pcm)

        while (s.fed + VoiceEngine.VAD_WINDOW <= s.buffer.size) {
            vad.acceptWaveform(s.buffer.copyOfRange(s.fed, s.fed + VoiceEngine.VAD_WINDOW))
            s.fed += VoiceEngine.VAD_WINDOW
            if (!s.speechStarted && vad.isSpeechDetected()) {
                s.speechStarted = true
                // include ~0.3s before the detected onset
                s.speechStart = (s.fed - PRE_ROLL).coerceAtLeast(0)
                s.lastPartialAt = 0L
            }
        }

        drainSegments(s, engine, vad)

        if (s.speechStarted && s.partial) {
            val now = SystemClock.elapsedRealtime()
            // skip partial decoding if we're falling behind
            if (now - s.lastPartialAt >= PARTIAL_INTERVAL_MS && !worker.hasMessages(P.MSG_AUDIO)) {
                s.lastPartialAt = now
                val text = engine.recognize(s.buffer.copyOfRange(s.speechStart, s.fed))
                if (text.isNotEmpty() && text != s.lastPartialText) {
                    s.lastPartialText = text
                    reply(s.replyTo, P.EVT_PARTIAL, s.id) { putString(P.KEY_TEXT, text) }
                }
            }
        } else if (!s.speechStarted && s.fed > KEEP_WHEN_SILENT * 2) {
            // nobody is talking: drop old audio, keep a short tail for pre-roll
            val drop = s.fed - KEEP_WHEN_SILENT
            s.buffer.dropFront(drop)
            s.fed -= drop
        }
    }

    private fun drainSegments(s: Session, engine: VoiceEngine, vad: Vad) {
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            val t0 = SystemClock.elapsedRealtime()
            val text = engine.recognize(segment.samples)
            val cost = SystemClock.elapsedRealtime() - t0
            Log.d(TAG, "segment ${segment.samples.size} samples -> '$text' in ${cost}ms")
            if (text.isNotEmpty()) {
                reply(s.replyTo, P.EVT_FINAL, s.id) {
                    putString(P.KEY_TEXT, text)
                    putLong(P.KEY_DECODE_MS, cost)
                }
            }
            // speech ended: everything fed so far belongs to that segment
            s.speechStarted = false
            s.lastPartialText = ""
            s.buffer.dropFront(s.fed)
            s.fed = 0
            s.speechStart = 0
        }
    }

    private fun stop(id: Int, cancel: Boolean) {
        val s = session ?: return
        if (s.id != id) return
        val vad = vad
        val engine = cachedEngine
        if (!cancel && vad != null && engine != null) {
            // feed the incomplete last window, then force the trailing speech out
            if (s.fed < s.buffer.size) {
                val rest = s.buffer.copyOfRange(s.fed, s.buffer.size)
                vad.acceptWaveform(rest)
                s.fed = s.buffer.size
            }
            vad.flush()
            drainSegments(s, engine, vad)
        }
        vad?.reset()
        reply(s.replyTo, P.EVT_DONE, s.id)
        session = null
    }

    private fun resetSession() {
        session = null
        vad?.reset()
    }

    private fun selfTest(msg: Message) {
        val key = parseKey(msg.data)
        resetSession()
        val engine = ensureEngine(key, msg.replyTo, msg.arg1)
        val wav = File(VoiceModelManager.modelDir(this, key.model), key.model.testWav)
        val samples = if (wav.isFile) WavReader.readMono16k(wav) else null
        if (samples == null) {
            reply(msg.replyTo, P.EVT_ERROR, msg.arg1) {
                putString(P.KEY_MESSAGE, getString(R.string.voice_error_no_test_wav))
            }
            return
        }
        // first run warms up, second one is measured
        engine.recognize(samples)
        val t0 = SystemClock.elapsedRealtime()
        val text = engine.recognize(samples)
        val cost = SystemClock.elapsedRealtime() - t0
        reply(msg.replyTo, P.EVT_TEST_RESULT, msg.arg1) {
            putString(P.KEY_TEXT, text)
            putString(P.KEY_BACKEND, engine.backendName)
            putLong(P.KEY_LOAD_MS, engine.loadMillis)
            putLong(P.KEY_DECODE_MS, cost)
            putLong(P.KEY_AUDIO_MS, samples.size * 1000L / P.SAMPLE_RATE)
        }
    }

    private inline fun reply(to: Messenger?, what: Int, id: Int, fill: Bundle.() -> Unit = {}) {
        to ?: return
        val m = Message.obtain(null, what, id, 0)
        m.data = Bundle().apply(fill)
        try {
            to.send(m)
        } catch (e: RemoteException) {
            Log.w(TAG, "client gone", e)
            if (session?.replyTo == to) session = null
        }
    }

    companion object {
        private const val TAG = "VoiceRecognition"
        private const val PRE_ROLL = P.SAMPLE_RATE * 3 / 10
        private const val KEEP_WHEN_SILENT = P.SAMPLE_RATE / 2
        private const val PARTIAL_INTERVAL_MS = 350L

        /** survives service re-creation as long as the `:voice` process is alive */
        @Volatile
        private var cachedEngine: VoiceEngine? = null

        private fun releaseEngine() {
            cachedEngine?.release()
            cachedEngine = null
        }
    }
}

/** Growable float buffer with cheap append and drop-from-front */
internal class FloatRingBuffer {
    private var data = FloatArray(P.SAMPLE_RATE * 4)
    private var start = 0
    var size = 0
        private set

    fun append(src: FloatArray) {
        if (start + size + src.size > data.size) {
            if (size + src.size <= data.size / 2) {
                // compact
                System.arraycopy(data, start, data, 0, size)
            } else {
                val newData = FloatArray(maxOf(data.size * 2, size + src.size))
                System.arraycopy(data, start, newData, 0, size)
                data = newData
            }
            start = 0
        }
        System.arraycopy(src, 0, data, start + size, src.size)
        size += src.size
    }

    fun copyOfRange(from: Int, to: Int): FloatArray {
        require(from in 0..to && to <= size)
        return data.copyOfRange(start + from, start + to)
    }

    fun dropFront(n: Int) {
        val k = n.coerceIn(0, size)
        start += k
        size -= k
        if (size == 0) start = 0
    }
}
