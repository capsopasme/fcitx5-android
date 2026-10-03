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

    private lateinit var worker: Handler

    private val incoming = Messenger(Handler(Looper.getMainLooper()) { msg ->
        // copy the message, the original is recycled after handleMessage returns
        worker.sendMessage(Message.obtain(msg))
        true
    })

    override fun onCreate() {
        super.onCreate()
        worker = Handler(workerLooper) { msg ->
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
            vadKey = null
            // Keep small engines: once unbound this process is cached and frozen by the system, so
            // the loaded model costs no CPU and no power, and lmkd reclaims it when memory is
            // actually needed. Large ones (Qwen3-ASR, ~1 GB resident) would push a lot of other
            // apps out of the cache instead; they are released and preloaded again next time the
            // panel opens.
            if (cachedEngine?.key?.model?.keepLoadedWhenIdle == false) releaseEngine()
        }
        // the worker thread is process-wide, see [workerLooper]
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
        /** force-cut speech longer than this, see [VoiceEngine.createVad] */
        val maxSegmentSamples: Int,
    ) {
        val buffer = FloatRingBuffer()

        /** samples in [buffer] already fed to VAD */
        var fed = 0
        var speechStarted = false
        var speechStart = 0
        var lastPartialAt = 0L
        var lastPartialText = ""

        /** [fed] when the last partial was decoded */
        var lastPartialFed = 0

        /** cost of the last partial decode, used to throttle partials */
        var lastPartialCost = 0L
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
            P.MSG_PRELOAD -> preload(msg)
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

    private fun ensureVad(model: SpeechModel, silenceMs: Int): Vad {
        val k = model to silenceMs
        vad?.let { if (vadKey == k) return it }
        vad?.release()
        vad = null
        return VoiceEngine.createVad(this, model, silenceMs).also {
            vad = it
            vadKey = k
        }
    }

    /** warm up model + VAD while the panel is open but nobody is talking yet */
    private fun preload(msg: Message) {
        // a running session already has its engine; never swap it out from under it
        if (session != null) return
        val key = parseKey(msg.data)
        val t0 = SystemClock.elapsedRealtime()
        ensureEngine(key, null, 0)
        ensureVad(key.model, msg.data.getInt(P.KEY_SILENCE_MS, 600))
        Log.d(TAG, "preload $key done in ${SystemClock.elapsedRealtime() - t0}ms")
    }

    private fun start(msg: Message) {
        val data = msg.data
        val key = parseKey(data)
        val silenceMs = data.getInt(P.KEY_SILENCE_MS, 600)
        preemptSession(msg.arg1, msg.replyTo)
        resetSession()
        val engine = ensureEngine(key, msg.replyTo, msg.arg1)
        ensureVad(key.model, silenceMs).reset()
        session = Session(
            msg.arg1, msg.replyTo,
            partial = data.getBoolean(P.KEY_PARTIAL, true) && key.model.fastEnoughForPartial,
            maxSegmentSamples = (key.model.maxSegmentSeconds * P.SAMPLE_RATE).toInt(),
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
                s.lastPartialFed = s.speechStart
            }
            if (s.speechStarted && s.fed - s.speechStart >= s.maxSegmentSamples) {
                // VAD's maxSpeechDuration is only a soft limit (it raises the threshold and waits
                // for a short pause). QNN models have a fixed input length and silently truncate
                // anything longer, so cut here no matter what.
                vad.flush()
            }
            // drain per window, so that the bookkeeping below matches what VAD has emitted
            if (!vad.empty()) drainSegments(s, engine, vad)
        }

        if (s.speechStarted && s.partial) {
            val now = SystemClock.elapsedRealtime()
            // Every partial re-decodes the whole sentence so far (on QNN always a full 20s graph),
            // so keep them rare: wait at least 3x the last decode time, require some new audio,
            // and skip entirely while we're falling behind.
            val interval = maxOf(PARTIAL_INTERVAL_MS, s.lastPartialCost * 3)
            if (now - s.lastPartialAt >= interval &&
                s.fed - s.lastPartialFed >= PARTIAL_MIN_NEW_AUDIO &&
                !worker.hasMessages(P.MSG_AUDIO)
            ) {
                s.lastPartialFed = s.fed
                val text = engine.recognize(s.buffer.copyOfRange(s.speechStart, s.fed))
                s.lastPartialAt = SystemClock.elapsedRealtime()
                s.lastPartialCost = s.lastPartialAt - now
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
            // never log the recognized text itself: logcat is readable by other tools
            Log.d(TAG, "segment ${segment.samples.size} samples -> ${text.length} chars in ${cost}ms")
            if (text.isNotEmpty()) {
                reply(s.replyTo, P.EVT_FINAL, s.id) {
                    putString(P.KEY_TEXT, text)
                    putLong(P.KEY_DECODE_MS, cost)
                }
            }
            // speech ended: everything fed so far belongs to that segment
            s.speechStarted = false
            s.lastPartialText = ""
            s.lastPartialCost = 0L
            s.buffer.dropFront(s.fed)
            s.fed = 0
            s.speechStart = 0
            s.lastPartialFed = 0
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

    /**
     * Only one session at a time: if another client (e.g. the self test in settings while the
     * keyboard is listening) takes over, tell the old one, so it stops its microphone instead
     * of streaming audio that is silently ignored.
     */
    private fun preemptSession(newId: Int, newReplyTo: Messenger?) {
        val old = session ?: return
        if (old.id == newId && old.replyTo == newReplyTo) return
        reply(old.replyTo, P.EVT_ERROR, old.id) {
            putString(P.KEY_MESSAGE, getString(R.string.voice_error_interrupted))
        }
    }

    private fun selfTest(msg: Message) {
        val key = parseKey(msg.data)
        preemptSession(msg.arg1, msg.replyTo)
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
        private const val PARTIAL_INTERVAL_MS = 400L
        private const val PARTIAL_MIN_NEW_AUDIO = P.SAMPLE_RATE * 3 / 10

        /**
         * One worker thread for the whole process, never quit. [cachedEngine] outlives service
         * instances, and with a thread per instance a quick unbind + rebind could release the
         * engine on the old thread while the new one is decoding with it (a native
         * use-after-free). On a single thread every engine access is serialized.
         */
        private val workerLooper: Looper by lazy {
            HandlerThread("voice-worker").apply { start() }.looper
        }

        /**
         * Survives service re-creation as long as the `:voice` process is alive.
         * Only touched on the [workerLooper] thread.
         */
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
