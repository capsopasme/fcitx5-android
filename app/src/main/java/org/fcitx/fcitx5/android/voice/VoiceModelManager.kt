/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.voice

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.voice.archive.TarExtractor
import timber.log.Timber
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * Downloads / imports / deletes speech models. Lives in the main (IME) process.
 *
 * Models are stored in app-private internal storage (`filesDir/voice-models/<dirName>`).
 * Internal storage is required: external storage is mounted noexec and QNN needs real file paths.
 */
object VoiceModelManager {

    sealed interface State {
        data object NotInstalled : State
        data class Installed(val bytes: Long) : State
        data class Working(val phase: Phase, val done: Long, val total: Long) : State
        data class Failed(val message: String) : State
    }

    enum class Phase { Download, Import }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<SpeechModel, Job>()
    private var importJob: Job? = null

    private val _states = MutableStateFlow<Map<SpeechModel, State>>(emptyMap())
    val states: StateFlow<Map<SpeechModel, State>> = _states.asStateFlow()

    private val _importState = MutableStateFlow<State?>(null)
    val importState: StateFlow<State?> = _importState.asStateFlow()

    fun rootDir(ctx: Context) = File(ctx.filesDir, "voice-models")

    fun modelDir(ctx: Context, model: SpeechModel) = File(rootDir(ctx), model.dirName)

    fun isInstalled(ctx: Context, model: SpeechModel) =
        model.requiredFiles.all { File(modelDir(ctx, model), it).isFile }

    private fun dirSize(f: File): Long =
        if (f.isFile) f.length() else f.listFiles()?.sumOf { dirSize(it) } ?: 0L

    private fun diskState(ctx: Context, model: SpeechModel): State =
        if (isInstalled(ctx, model)) State.Installed(dirSize(modelDir(ctx, model)))
        else State.NotInstalled

    private fun setState(model: SpeechModel, state: State) {
        _states.update { it + (model to state) }
    }

    /** Re-scan disk, keeping states of running jobs */
    fun refresh(ctx: Context) {
        SpeechModel.entries.forEach { m ->
            if (jobs[m]?.isActive == true) return@forEach
            val old = _states.value[m]
            val new = diskState(ctx, m)
            // keep failure message visible until the user retries
            if (old is State.Failed && new is State.NotInstalled) return@forEach
            setState(m, new)
        }
    }

    fun isBusy(model: SpeechModel) = jobs[model]?.isActive == true

    fun cancel(model: SpeechModel) {
        jobs.remove(model)?.cancel()
    }

    fun delete(ctx: Context, model: SpeechModel) {
        cancel(model)
        modelDir(ctx, model).deleteRecursively()
        setState(model, State.NotInstalled)
    }

    /**
     * @param urlPrefix optional mirror prefix, e.g. "https://ghfast.top/";
     * the final url is `prefix + https://github.com/...`
     */
    fun download(ctx: Context, model: SpeechModel, urlPrefix: String) {
        if (isBusy(model)) return
        val appCtx = ctx.applicationContext
        val url = urlPrefix.trim().let { p ->
            if (p.isEmpty()) model.defaultUrl
            else if (p.contains("{url}")) p.replace("{url}", model.defaultUrl)
            else p + model.defaultUrl
        }
        jobs[model] = scope.launch {
            setState(model, State.Working(Phase.Download, 0, model.downloadSizeMb * 1024L * 1024L))
            val tmp = File(rootDir(appCtx), ".tmp-${model.dirName}")
            try {
                tmp.deleteRecursively()
                tmp.mkdirs()
                Timber.i("Downloading speech model from $url")
                val conn = openConnection(url)
                val total = conn.contentLengthLong.takeIf { it > 0 }
                    ?: (model.downloadSizeMb * 1024L * 1024L)
                val job = coroutineContext[Job]
                conn.inputStream.use { raw ->
                    val counting = CountingInputStream(raw) { read ->
                        setState(model, State.Working(Phase.Download, read, total))
                    }
                    TarExtractor.extractTarBz2(
                        counting, tmp,
                        filter = { model.shouldExtract(it) },
                        isCancelled = { job?.isActive != true }
                    )
                }
                conn.disconnect()
                installFromTmp(appCtx, tmp, model)
                setState(model, diskState(appCtx, model))
            } catch (e: Throwable) {
                Timber.w(e, "Failed to download $model")
                tmp.deleteRecursively()
                if (isActive) {
                    setState(model, State.Failed(e.localizedMessage ?: e.javaClass.simpleName))
                } else {
                    setState(model, diskState(appCtx, model))
                }
            } finally {
                jobs.remove(model)
            }
        }
    }

    /**
     * Import a `.tar.bz2` archive downloaded elsewhere (browser / PC).
     * The model type is detected from the top level directory name.
     */
    fun importArchive(ctx: Context, uri: Uri, onFinished: (Result<SpeechModel>) -> Unit) {
        if (importJob?.isActive == true) return
        val appCtx = ctx.applicationContext
        importJob = scope.launch {
            val tmp = File(rootDir(appCtx), ".tmp-import")
            val result = runCatching {
                tmp.deleteRecursively()
                tmp.mkdirs()
                val total = appCtx.contentResolver.openAssetFileDescriptor(uri, "r")
                    ?.use { it.length }?.takeIf { it > 0 } ?: -1L
                _importState.value = State.Working(Phase.Import, 0, total)
                val job = coroutineContext[Job]
                val input = appCtx.contentResolver.openInputStream(uri)
                    ?: throw IOException("Cannot open $uri")
                input.use { raw ->
                    val counting = CountingInputStream(raw) { read ->
                        _importState.value = State.Working(Phase.Import, read, total)
                    }
                    TarExtractor.extractTarBz2(
                        counting, tmp,
                        filter = { path ->
                            val top = path.substringBefore('/')
                            SpeechModel.fromDirName(top)?.shouldExtract(path) == true
                        },
                        isCancelled = { job?.isActive != true }
                    )
                }
                val model = tmp.listFiles()?.firstNotNullOfOrNull { SpeechModel.fromDirName(it.name) }
                    ?: throw IOException("Unknown model archive")
                cancel(model)
                installFromTmp(appCtx, tmp, model)
                setState(model, diskState(appCtx, model))
                model
            }
            tmp.deleteRecursively()
            _importState.value = null
            result.exceptionOrNull()?.let { Timber.w(it, "Failed to import speech model") }
            onFinished(result)
        }
    }

    private fun installFromTmp(ctx: Context, tmp: File, model: SpeechModel) {
        val extracted = File(tmp, model.dirName)
        val missing = model.requiredFiles.filterNot { File(extracted, it).isFile }
        if (missing.isNotEmpty()) throw IOException("Archive is missing: ${missing.joinToString()}")
        val dest = modelDir(ctx, model)
        dest.deleteRecursively()
        dest.parentFile?.mkdirs()
        if (!extracted.renameTo(dest)) throw IOException("Failed to move model into place")
        tmp.deleteRecursively()
    }

    private fun openConnection(url: String): HttpURLConnection {
        var current = URL(url)
        // follow redirects manually, HttpURLConnection refuses some cross host ones
        repeat(8) {
            val conn = current.openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "fcitx5-android-voice")
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                    ?: throw IOException("HTTP $code without Location")
                conn.disconnect()
                current = URL(current, location)
                return@repeat
            }
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                throw IOException("HTTP $code: $current")
            }
            return conn
        }
        throw IOException("Too many redirects")
    }

    private class CountingInputStream(
        input: InputStream,
        private val onRead: (Long) -> Unit
    ) : FilterInputStream(input) {
        private var count = 0L
        private var lastReport = 0L

        private fun advance(n: Long) {
            if (n <= 0) return
            count += n
            if (count - lastReport >= 256 * 1024) {
                lastReport = count
                onRead(count)
            }
        }

        override fun read(): Int = super.read().also { if (it >= 0) advance(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { advance(it.toLong()) }

        override fun skip(n: Long): Long = super.skip(n).also { advance(it) }
    }
}
