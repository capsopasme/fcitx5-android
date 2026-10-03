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
 * [onAudio] receives float samples in [-1, 1] and the chunk's RMS level, on the capture thread.
 */
class AudioCapture(
    private val onAudio: (pcm: FloatArray, rms: Float) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    @Volatile
    private var running = false
    private var thread: Thread? = null

    val isRunning get() = running

    @SuppressLint("MissingPermission")
    fun start(context: Context): Boolean {
        if (running) return true
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
        running = true
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val chunk = ShortArray(rate / 10) // 100ms
            try {
                record.startRecording()
                while (running) {
                    val n = record.read(chunk, 0, chunk.size)
                    if (n < 0) throw IllegalStateException("AudioRecord.read=$n")
                    if (n == 0) continue
                    val pcm = FloatArray(n)
                    var sum = 0.0
                    for (i in 0 until n) {
                        val v = chunk[i] / 32768f
                        pcm[i] = v
                        sum += v * v
                    }
                    if (running) onAudio(pcm, sqrt(sum / n).toFloat())
                }
            } catch (e: Throwable) {
                Log.w("AudioCapture", "capture failed", e)
                if (running) onFailure(e)
            } finally {
                running = false
                try {
                    record.stop()
                } catch (_: IllegalStateException) {
                }
                record.release()
            }
        }, "voice-capture").apply { start() }
        return true
    }

    fun stop() {
        running = false
        thread = null
    }

    companion object {
        fun hasPermission(context: Context) =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
}
