/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.voice

import android.content.Context
import android.net.Uri
import android.os.StatFs
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
        model.requiredFiles.all { File(modelDir(ctx, model), it).let { f -> f.isFile && f.length() > 0 } }

    /**
     * Refuse to start when the model can't fit: filling internal storage up to the last byte
     * hurts the whole system (and flash wear, through f2fs garbage collection), and every byte
     * written before the inevitable failure is wasted.
     */
    private fun checkFreeSpace(dir: File, archiveBytes: Long) {
        if (archiveBytes <= 0) return
        dir.mkdirs()
        // extracted size is roughly the archive size (bz2 barely compresses int8 weights)
        val needed = archiveBytes + archiveBytes / 4 + FREE_SPACE_MARGIN
        val available = StatFs(dir.path).availableBytes
        if (available < needed) {
            throw IOException(
                "Not enough storage: need ${needed / MB} MB, ${available / MB} MB available"
            )
        }
    }

    private const val MB = 1024L * 1024L
    private const val FREE_SPACE_MARGIN = 256 * MB

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
        cleanupStaleTemp(ctx)
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

    /**
     * The job stays in [jobs] until it has really finished (it may be blocked in a network read
     * for a while), so that a new download of the same model can wait for it instead of both
     * writing to - and deleting - the same temp directory.
     */
    fun cancel(model: SpeechModel) {
        jobs[model]?.cancel()
    }

    /**
     * A download interrupted by process death leaves up to ~1 GB in a temp directory that
     * nothing would ever clean up unless the same model is downloaded again.
     */
    private fun cleanupStaleTemp(ctx: Context) {
        rootDir(ctx).listFiles()?.forEach { f ->
            val stale = when {
                f.name == ".tmp-import" -> importJob?.isActive != true
                f.name.startsWith(".tmp-") -> {
                    val m = SpeechModel.fromDirName(f.name.removePrefix(".tmp-"))
                    m == null || jobs[m]?.isCompleted != false
                }
                // half-copied VAD model; the voice process may be writing it right now
                f.name.endsWith(".tmp") -> System.currentTimeMillis() - f.lastModified() > 60_000L
                else -> false
            }
            if (stale) {
                Timber.i("Removing stale ${f.name}")
                f.deleteRecursively()
            }
        }
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
        val previous = jobs[model]
        jobs[model] = scope.launch {
            // a cancelled download of the same model may still be winding down
            previous?.join()
            setState(model, State.Working(Phase.Download, 0, model.downloadSizeMb * 1024L * 1024L))
            val tmp = File(rootDir(appCtx), ".tmp-${model.dirName}")
            var conn: HttpURLConnection? = null
            try {
                tmp.deleteRecursively()
                checkFreeSpace(rootDir(appCtx), model.downloadSizeMb * MB)
                tmp.mkdirs()
                Timber.i("Downloading speech model from $url")
                val c = openConnection(url)
                conn = c
                val total = c.contentLengthLong.takeIf { it > 0 }
                    ?: (model.downloadSizeMb * MB)
                checkFreeSpace(rootDir(appCtx), total)
                val job = coroutineContext[Job]
                c.inputStream.use { raw ->
                    val counting = CountingInputStream(raw) { read ->
                        setState(model, State.Working(Phase.Download, read, total))
                    }
                    TarExtractor.extractTarBz2(
                        counting, tmp,
                        filter = { model.shouldExtract(it) },
                        isCancelled = { job?.isActive != true }
                    )
                }
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
                conn?.disconnect()
                coroutineContext[Job]?.let { jobs.remove(model, it) }
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
                checkFreeSpace(rootDir(appCtx), total)
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
