/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.voice

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads a 16 kHz mono PCM16 wav file (the test files shipped with sherpa-onnx models) */
object WavReader {
    fun readMono16k(file: File): FloatArray? {
        val bytes = file.readBytes()
        if (bytes.size < 44) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE"
        ) return null
        var pos = 12
        var channels = 0
        var rate = 0
        var bits = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val len = bb.getInt(pos + 4)
            val body = pos + 8
            if (len < 0 || body + len > bytes.size) return null
            when (id) {
                "fmt " -> {
                    if (bb.getShort(body).toInt() != 1) return null // PCM only
                    channels = bb.getShort(body + 2).toInt()
                    rate = bb.getInt(body + 4)
                    bits = bb.getShort(body + 14).toInt()
                }
                "data" -> {
                    if (channels != 1 || rate != VoiceProtocol.SAMPLE_RATE || bits != 16) return null
                    val n = len / 2
                    return FloatArray(n) { bb.getShort(body + it * 2) / 32768f }
                }
            }
            pos = body + len + (len and 1)
        }
        return null
    }
}
