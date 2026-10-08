/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.voice.AudioCapture
import org.fcitx.fcitx5.android.voice.VoiceClient
import org.fcitx.fcitx5.android.voice.VoiceModelManager
import org.fcitx.fcitx5.android.voice.VoicePermissionActivity
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import timber.log.Timber

/**
 * Built-in offline voice input panel, opened from the toolbar microphone button.
 * Recognized sentences are committed to the editor as soon as VAD detects their end.
 */
class VoiceInputWindow : InputWindow.ExtendedInputWindow<VoiceInputWindow>() {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val theme by manager.theme()

    private val prefs = AppPrefs.getInstance().voice

    private val mainHandler = Handler(Looper.getMainLooper())

    private enum class State { Idle, Loading, Listening, Finishing }

    private var state = State.Idle
    private var attached = false
    private var visible = false

    /** text committed during this panel session, for display only */
    private val committed = StringBuilder()
    private var partial = ""

    private val ui by lazy {
        VoiceInputUi(context, theme).apply {
            micButton.setOnClickListener { toggle() }
            backspaceButton.setOnClickListener {
                service.sendBackspaceFromPanel()
                if (committed.isNotEmpty()) {
                    // drop a whole code point, never half of a surrogate pair
                    val end = committed.length
                    val start = if (end >= 2 && Character.isLowSurrogate(committed[end - 1]) &&
                        Character.isHighSurrogate(committed[end - 2])
                    ) end - 2 else end - 1
                    committed.setLength(start)
                    showTranscript(committed, partial)
                }
            }
            enterButton.setOnClickListener {
                // same as the keyboard's return key: "search" / "send" / "go" where the editor
                // asks for an action, a plain Enter otherwise
                service.sendReturnFromPanel()
            }
            onWindowVisibilityChanged = { v ->
                visible = v
                if (!v) {
                    // the keyboard was hidden while listening: stop the microphone
                    if (state == State.Listening || state == State.Loading) stopListening()
                    // this window stays attached until the next input starts, don't keep
                    // the recognizer process (and its model) pinned all that time
                    scheduleIdleUnbind()
                } else if (attached) {
                    mainHandler.removeCallbacks(idleUnbind)
                    if (isReady()) preloadModel()
                }
            }
        }
    }

    private val clientListener: VoiceClient.Listener = object : VoiceClient.Listener {
        override fun onLoading() {
            if (state == State.Idle) return
            state = State.Loading
            ui.setMicState(VoiceInputUi.MicState.Loading)
            ui.statusText.setText(R.string.voice_status_loading)
        }

        override fun onReady(backend: String, loadMillis: Long) {
            if (state != State.Loading && state != State.Listening) return
            state = State.Listening
            // levels measured while loading weren't shown, let the next chunk update the halo
            shownLevel = -1
            ui.setMicState(VoiceInputUi.MicState.Listening)
            ui.statusText.text = context.getString(R.string.voice_status_listening, backend)
        }

        override fun onPartial(text: String) {
            partial = text
            ui.showTranscript(committed, partial)
        }

        override fun onFinal(text: String) {
            partial = ""
            if (service.editorGeneration != sessionEditor) {
                // Focus moved to another field / app while the last sentence was still being
                // decoded (the panel stops listening when it's detached or hidden, but the result
                // arrives later). Committing now would put it into the wrong editor - possibly a
                // password field, where this panel is never offered.
                Timber.i("Dropping voice result: the editor changed")
                droppedResult = true
                if (attached) ui.statusText.setText(R.string.voice_status_dropped_editor_changed)
                ui.showTranscript(committed, partial)
                return
            }
            val out = postProcess(text)
            if (out.isNotEmpty()) {
                service.commitText(out)
                committed.append(out)
            }
            ui.showTranscript(committed, partial)
        }

        override fun onError(message: String) {
            capture.stop()
            toIdle()
            ui.statusText.text = context.getString(R.string.voice_status_error, message)
            offerSettings()
            if (!attached || !visible) scheduleIdleUnbind()
        }

        override fun onDone(autoStopped: Boolean) {
            capture.stop()
            toIdle()
            // keep the "not inserted" notice visible instead of replacing it
            if (attached && !droppedResult) {
                ui.statusText.setText(
                    if (autoStopped) R.string.voice_status_auto_stopped else R.string.voice_status_idle
                )
            }
            if (!attached || !visible) scheduleIdleUnbind()
        }

        override fun onServiceCrashed() {
            // the client has already dropped its binding, nothing to unbind later
            capture.stop()
            toIdle()
            ui.statusText.setText(R.string.voice_status_crashed)
            offerSettings()
        }
    }

    private val client: VoiceClient by lazy { VoiceClient(context, clientListener) }

    /** last level shown by the halo, see [VoiceInputUi.levelStep] */
    @Volatile
    private var shownLevel = -1

    private val capture: AudioCapture by lazy {
        AudioCapture(
            onAudio = { pcm, rms, session ->
                // one main thread hop per chunk (10/s) for both: audio has to be queued in order
                // with MSG_START / MSG_STOP anyway, see VoiceClient.sendAudio
                val level = VoiceInputUi.levelStep(rms)
                val levelChanged = level != shownLevel
                shownLevel = level
                mainHandler.post {
                    client.sendAudio(pcm, session)
                    if (levelChanged && state == State.Listening) ui.setLevelStep(level)
                }
            },
            onFailure = { e ->
                mainHandler.post {
                    // microphone busy (e.g. during a call) or permission revoked
                    client.cancel()
                    if (state != State.Idle) toIdle()
                    ui.statusText.text = context.getString(
                        R.string.voice_status_error, e.localizedMessage ?: e.javaClass.simpleName
                    )
                }
            }
        )
    }

    private var finishingSince = 0L

    /** [FcitxInputMethodService.editorGeneration] when listening started */
    private var sessionEditor = -1

    /** a result of the current session was dropped because the editor changed */
    private var droppedResult = false

    /**
     * Unbind shortly after the panel is closed or hidden. While bound (BIND_IMPORTANT) the
     * `:voice` process runs at the keyboard's priority and can't be reclaimed; once unbound it
     * becomes a cached process that the system freezes, keeping the model loaded for free
     * until memory is needed. The short delay just coalesces quick hide/show flickers.
     */
    private val idleUnbind: Runnable = object : Runnable {
        override fun run() {
            if (attached && visible) return
            if (state == State.Listening || state == State.Loading) return
            if (state == State.Finishing &&
                SystemClock.elapsedRealtime() - finishingSince < FINISH_TIMEOUT_MS
            ) {
                // a long final decode (Qwen3 on CPU, or a model still loading) is still running,
                // unbinding now would throw its result away
                mainHandler.postDelayed(this, IDLE_UNBIND_DELAY_MS)
                return
            }
            // also covers a recognizer that never answered MSG_STOP
            if (state == State.Finishing) toIdle()
            client.unbind()
        }
    }

    private fun scheduleIdleUnbind() {
        mainHandler.removeCallbacks(idleUnbind)
        mainHandler.postDelayed(idleUnbind, IDLE_UNBIND_DELAY_MS)
    }

    private fun isReady() =
        AudioCapture.hasPermission(context) && VoiceModelManager.isInstalled(context, prefs.model.getValue())

    /**
     * Bind and have the recognizer load the model now, while the user is still looking at the
     * panel, so that the first sentence doesn't wait for it (seconds for a cold QNN context or
     * Qwen3-ASR). Cheap when the model is already loaded in the cached process.
     */
    private fun preloadModel() {
        if (state != State.Idle) return
        client.preload(
            model = prefs.model.getValue(),
            language = prefs.language.getValue(),
            itn = prefs.itn.getValue(),
            silenceMs = prefs.silenceMillis.getValue(),
        )
    }

    private fun toIdle() {
        state = State.Idle
        partial = ""
        ui.setMicState(VoiceInputUi.MicState.Idle)
        ui.showTranscript(committed, partial)
    }

    private fun offerSettings() {
        ui.showAction(context.getString(R.string.voice_open_settings)) {
            AppUtil.launchMainToVoiceSettings(context)
        }
    }

    private fun toggle() {
        when (state) {
            State.Idle -> startListening()
            State.Loading, State.Listening -> stopListening()
            State.Finishing -> {}
        }
    }

    private fun startListening() {
        if (state != State.Idle) return
        mainHandler.removeCallbacks(idleUnbind)
        ui.showAction(null, null)
        if (!AudioCapture.hasPermission(context)) {
            ui.statusText.setText(R.string.voice_status_need_permission)
            ui.showAction(context.getString(R.string.voice_grant_permission)) {
                VoicePermissionActivity.launch(context)
            }
            return
        }
        val model = prefs.model.getValue()
        if (!VoiceModelManager.isInstalled(context, model)) {
            ui.statusText.text = context.getString(
                R.string.voice_status_need_model, context.getString(model.stringRes)
            )
            ui.showAction(context.getString(R.string.voice_download_model)) {
                AppUtil.launchMainToVoiceSettings(context)
            }
            return
        }
        // commit whatever is being composed by fcitx before inserting recognized text
        service.postFcitxJob {
            if (!isEmpty()) focusOutIn()
        }
        partial = ""
        sessionEditor = service.editorGeneration
        droppedResult = false
        state = State.Loading
        ui.setMicState(VoiceInputUi.MicState.Loading)
        ui.statusText.setText(R.string.voice_status_starting)
        val session = client.start(
            model = model,
            language = prefs.language.getValue(),
            itn = prefs.itn.getValue(),
            partial = prefs.partialResults.getValue(),
            silenceMs = prefs.silenceMillis.getValue(),
            idleStopMs = prefs.idleStopSeconds.getValue() * 1000,
        )
        // the recognizer can't be reached: its error is already on the way, don't record at all
        if (session == 0) return
        shownLevel = -1
        // audio recorded while the model is loading is queued, nothing gets lost
        if (!capture.start(context, session)) {
            client.cancel()
            toIdle()
        }
    }

    private fun stopListening() {
        val id = client.currentSession
        // send MSG_STOP only after the capture thread has delivered its last chunk,
        // otherwise the end of the last word can be cut off
        val pending = capture.stop { mainHandler.post { client.stop(id) } }
        if (client.isSessionActive) {
            state = State.Finishing
            finishingSince = SystemClock.elapsedRealtime()
            ui.setMicState(VoiceInputUi.MicState.Loading)
            ui.statusText.setText(R.string.voice_status_finishing)
            if (!pending) client.stop(id)
        } else {
            toIdle()
        }
    }

    private fun isAsciiWordChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    private fun postProcess(raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) return text
        if (prefs.dropTrailingPeriod.getValue()) {
            text = text.trimEnd('。', '.', '．')
            if (text.isEmpty()) return text
        }
        if (prefs.autoSpace.getValue() && isAsciiWordChar(text.first())) {
            val before = service.currentInputConnection?.getTextBeforeCursor(1, 0)
            val prev = before?.lastOrNull()
            if (prev != null && (isAsciiWordChar(prev) || prev in ".,!?;:")) {
                text = " $text"
            }
        }
        return text
    }

    override val title: String by lazy {
        context.getString(R.string.voice_input)
    }

    private val settingsButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_settings_24, theme).apply {
            contentDescription = context.getString(R.string.voice_open_settings)
            setOnClickListener { AppUtil.launchMainToVoiceSettings(context) }
        }
    }

    private val barExtension by lazy {
        context.horizontalLayout {
            add(settingsButton, lParams(dp(40), dp(40)))
        }
    }

    override fun onCreateBarExtension(): View = barExtension

    override fun onCreateView(): View = ui.root

    override fun onAttached() {
        attached = true
        visible = true
        mainHandler.removeCallbacks(idleUnbind)
        committed.setLength(0)
        partial = ""
        ui.showTranscript(committed, partial)
        ui.showAction(null, null)
        ui.setMicState(VoiceInputUi.MicState.Idle)
        ui.statusText.setText(R.string.voice_status_idle)
        val ready = isReady()
        if (prefs.autoStart.getValue() || !ready) {
            // starting loads the model anyway
            startListening()
        } else {
            preloadModel()
        }
    }

    override fun onDetached() {
        attached = false
        if (state == State.Loading || state == State.Listening) stopListening()
        // unbinds once idle; also covers a recognizer that never answers
        scheduleIdleUnbind()
    }

    companion object {
        private const val IDLE_UNBIND_DELAY_MS = 10_000L
        private const val FINISH_TIMEOUT_MS = 60_000L
    }

}
