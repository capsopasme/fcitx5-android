/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import kotlin.math.sqrt

/**
 * Captures 16 kHz mono microphone audio on a background thread.
 * [onAudio] receives float samples in [-1, 1], the chunk's RMS level and the token passed to
 * [start], on the capture thread. The token lets the receiver tell which recording a chunk
 * belongs to: the last chunk of a stopped recording can arrive after a new one has started.
 *
 * Every [start] gets its own worker with its own running flag: a quick stop() + start()
 * can never leave two threads feeding the same session, and the old thread's cleanup
 * can never stop the new one.
 */
class AudioCapture(
    private val onAudio: (pcm: FloatArray, rms: Float, token: Int) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private class Worker {
        @Volatile
        var running = true

        /** set by [stop], invoked on the capture thread after the last [onAudio] */
        @Volatile
        var onStopped: (() -> Unit)? = null
    }

    @Volatile
    private var current: Worker? = null

    val isRunning get() = current?.running == true

    @SuppressLint("MissingPermission")
    fun start(context: Context, token: Int): Boolean {
        if (isRunning) return true
        if (!hasPermission(context)) return false
        val rate = VoiceProtocol.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            onFailure(IllegalStateException("AudioRecord.getMinBufferSize=$minBuf"))
            return false
        }
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, rate / 5 * 2)
            )
        } catch (e: Exception) {
            onFailure(e)
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            onFailure(IllegalStateException("AudioRecord not initialized"))
            return false
        }
        val worker = Worker()
        current = worker
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val chunk = ShortArray(rate / 10) // 100ms
            var emptyReads = 0
            try {
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IllegalStateException("AudioRecord failed to start (microphone busy?)")
                }
                while (worker.running) {
                    val n = record.read(chunk, 0, chunk.size)
                    if (n < 0) throw IllegalStateException("AudioRecord.read=$n")
                    if (n == 0) {
                        // a blocking read should never return 0; don't spin the CPU if it does
                        if (++emptyReads > 20) throw IllegalStateException("AudioRecord returns no data")
                        Thread.sleep(10)
                        continue
                    }
                    emptyReads = 0
                    val pcm = FloatArray(n)
                    var sum = 0.0
                    for (i in 0 until n) {
                        val v = chunk[i] / 32768f
                        pcm[i] = v
                        sum += v * v
                    }
                    // also deliver the chunk read right after stop(): it is the tail of the speech
                    onAudio(pcm, sqrt(sum / n).toFloat(), token)
                }
            } catch (e: Throwable) {
                Log.w("AudioCapture", "capture failed", e)
                if (worker.running) onFailure(e)
            } finally {
                worker.running = false
                try {
                    record.stop()
                } catch (_: IllegalStateException) {
                }
                record.release()
                if (current === worker) current = null
            }
            // non-null only if stop() was called; runs even if the last read failed
            worker.onStopped?.invoke()
        }, "voice-capture").start()
        return true
    }

    /**
     * Asynchronous: the capture thread finishes its current read (<= 100ms), delivers it,
     * releases the microphone and then calls [onStopped] on the capture thread.
     *
     * @return false if nothing was being captured, [onStopped] will not be called then
     */
    fun stop(onStopped: (() -> Unit)? = null): Boolean {
        val w = current ?: return false
        current = null
        w.onStopped = onStopped
        w.running = false
        return true
    }

    companion object {
        fun hasPermission(context: Context) =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
}
