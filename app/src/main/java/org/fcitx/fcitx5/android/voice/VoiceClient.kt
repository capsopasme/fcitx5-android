/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import java.util.concurrent.atomic.AtomicInteger
import org.fcitx.fcitx5.android.voice.VoiceProtocol as P

/**
 * IME side of the voice recognizer. Binds to [VoiceRecognitionService] in the `:voice` process.
 * Must be used from the main thread only; all callbacks are delivered on the main thread.
 */
class VoiceClient(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onLoading() {}
        fun onReady(backend: String, loadMillis: Long) {}
        fun onPartial(text: String) {}
        fun onFinal(text: String) {}
        fun onError(message: String) {}

        /** @param autoStopped the recognizer ended the session because nobody spoke for a while */
        fun onDone(autoStopped: Boolean) {}
        fun onTestResult(text: String, backend: String, loadMillis: Long, decodeMillis: Long, audioMillis: Long) {}
        /** the recognizer process died, most likely a native crash during model init */
        fun onServiceCrashed() {}
    }

    private var service: Messenger? = null
    private var bound = false
    private val pending = ArrayDeque<Message>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeSession = 0

    private val incoming = Messenger(Handler(Looper.getMainLooper()) { msg ->
        handleEvent(msg)
        true
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            service = Messenger(binder)
            while (pending.isNotEmpty()) send(pending.removeFirst())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            pending.clear()
            val crashedSession = activeSession != 0
            activeSession = 0
            // The recognizer process died (a native crash or exit() during model loading, or
            // killed by the system). While the binding exists the system would restart it right
            // away, an empty process that only costs memory and a cold start. Let it go, the
            // next start() / preload() binds again.
            unbind()
            if (crashedSession) listener.onServiceCrashed()
        }

        override fun onBindingDied(name: ComponentName?) {
            onServiceDisconnected(name)
        }
    }

    fun bind() {
        if (bound) return
        bound = try {
            context.bindService(
                Intent(context, VoiceRecognitionService::class.java),
                connection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
            )
        } catch (_: SecurityException) {
            false
        }
        if (!bound) {
            // the connection is registered even when binding fails (e.g. before the first unlock)
            try {
                context.unbindService(connection)
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    fun unbind() {
        if (!bound) return
        activeSession = 0
        pending.clear()
        try {
            context.unbindService(connection)
        } catch (_: IllegalArgumentException) {
        }
        bound = false
        service = null
    }

    /**
     * Report a failed bind asynchronously (the caller may still be starting the microphone,
     * which the error handler is expected to stop) instead of queueing messages forever.
     */
    private fun bindOrFail(): Boolean {
        bind()
        if (!bound) mainHandler.post { listener.onError("Cannot connect to the recognizer service") }
        return bound
    }

    val isSessionActive: Boolean
        get() = activeSession != 0

    /** id of the running session, 0 if none */
    val currentSession: Int
        get() = activeSession

    /** @return the new session id, 0 if the recognizer can't be reached */
    fun start(
        model: SpeechModel,
        language: SpeechLanguage,
        itn: Boolean,
        partial: Boolean,
        silenceMs: Int,
        idleStopMs: Int,
    ): Int {
        if (!bindOrFail()) return 0
        activeSession = nextSessionId()
        post(P.MSG_START) {
            putString(P.KEY_MODEL, model.name)
            putString(P.KEY_LANGUAGE, language.name)
            putBoolean(P.KEY_ITN, itn)
            putBoolean(P.KEY_PARTIAL, partial)
            putInt(P.KEY_SILENCE_MS, silenceMs)
            putInt(P.KEY_IDLE_STOP_MS, idleStopMs)
        }
        return activeSession
    }

    /**
     * Main thread only. Every message to the service goes through the same queue in the same
     * order: sending audio straight from the capture thread could overtake MSG_START (still
     * pending while the service connects) or audio queued before it, and the service would drop
     * the first chunks of a sentence or receive them out of order.
     *
     * @param id session the audio was recorded for; audio of an older session is dropped
     */
    fun sendAudio(pcm: FloatArray, id: Int) {
        if (id == 0 || id != activeSession || !bound) return
        val m = Message.obtain(null, P.MSG_AUDIO, id, 0)
        m.data = Bundle().apply { putFloatArray(P.KEY_PCM, pcm) }
        pendingOrSend(m)
    }

    private fun pendingOrSend(m: Message) {
        if (service != null) {
            send(m)
            return
        }
        if (!bound) return // bindService() failed, nobody will ever drain the queue
        if (m.what == P.MSG_AUDIO && pending.size >= MAX_PENDING_AUDIO) {
            // the service doesn't come up: keep the queue bounded (~60s of audio)
            return
        }
        pending.addLast(m)
    }

    /** @param id only stop if this session is still the active one */
    fun stop(id: Int = activeSession) {
        if (activeSession == 0 || id != activeSession) return
        post(P.MSG_STOP)
    }

    fun cancel() {
        if (activeSession == 0) return
        post(P.MSG_CANCEL)
        activeSession = 0
    }

    /**
     * Load the model into the recognizer process ahead of time, so that the first [start]
     * doesn't wait for it. No events are delivered for a preload (it has no session); errors are
     * reported again by the next [start]. Does nothing while a session is running.
     */
    fun preload(model: SpeechModel, language: SpeechLanguage, itn: Boolean, silenceMs: Int) {
        if (activeSession != 0) return
        bind()
        if (!bound) return
        val m = Message.obtain(null, P.MSG_PRELOAD, 0, 0)
        m.data = Bundle().apply {
            putString(P.KEY_MODEL, model.name)
            putString(P.KEY_LANGUAGE, language.name)
            putBoolean(P.KEY_ITN, itn)
            putInt(P.KEY_SILENCE_MS, silenceMs)
        }
        pendingOrSend(m)
    }

    /** An explicit self test also retries the NPU after its initialization crashed once */
    fun selfTest(model: SpeechModel, language: SpeechLanguage, itn: Boolean) {
        if (!bindOrFail()) return
        activeSession = nextSessionId()
        post(P.MSG_SELF_TEST) {
            putString(P.KEY_MODEL, model.name)
            putString(P.KEY_LANGUAGE, language.name)
            putBoolean(P.KEY_ITN, itn)
            putBoolean(P.KEY_FORCE_QNN, true)
        }
    }

    private inline fun post(what: Int, fill: Bundle.() -> Unit = {}) {
        val m = Message.obtain(null, what, activeSession, 0)
        m.data = Bundle().apply(fill)
        pendingOrSend(m)
    }

    private fun send(m: Message) {
        m.replyTo = incoming
        try {
            service?.send(m)
        } catch (_: RemoteException) {
        }
    }

    companion object {
        private const val MAX_PENDING_AUDIO = 600

        /**
         * Shared by every client in this process: each voice panel and the settings self test
         * have their own [VoiceClient], and the service tells sessions apart by id only, so a
         * late MSG_STOP / audio chunk of a closed panel must never match a new panel's session.
         */
        private val sessionIds = AtomicInteger(0)

        private fun nextSessionId(): Int {
            while (true) {
                val id = sessionIds.incrementAndGet()
                if (id != 0) return id // 0 means "no session"
            }
        }
    }

    private fun handleEvent(msg: Message) {
        if (msg.arg1 != activeSession || activeSession == 0) return
        val d = msg.data
        when (msg.what) {
            P.EVT_LOADING -> listener.onLoading()
            P.EVT_READY -> listener.onReady(d.getString(P.KEY_BACKEND) ?: "", d.getLong(P.KEY_LOAD_MS))
            P.EVT_PARTIAL -> listener.onPartial(d.getString(P.KEY_TEXT) ?: "")
            P.EVT_FINAL -> listener.onFinal(d.getString(P.KEY_TEXT) ?: "")
            P.EVT_ERROR -> {
                activeSession = 0
                listener.onError(d.getString(P.KEY_MESSAGE) ?: "")
            }
            P.EVT_DONE -> {
                activeSession = 0
                listener.onDone(d.getBoolean(P.KEY_AUTO_STOPPED, false))
            }
            P.EVT_TEST_RESULT -> {
                activeSession = 0
                listener.onTestResult(
                    d.getString(P.KEY_TEXT) ?: "",
                    d.getString(P.KEY_BACKEND) ?: "",
                    d.getLong(P.KEY_LOAD_MS),
                    d.getLong(P.KEY_DECODE_MS),
                    d.getLong(P.KEY_AUDIO_MS),
                )
            }
        }
    }
}
