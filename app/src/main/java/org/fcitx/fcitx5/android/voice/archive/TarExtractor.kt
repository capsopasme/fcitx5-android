/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.voice.archive

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Minimal tar (ustar / GNU / pax) extractor, enough for sherpa-onnx model archives.
 * Only regular files and directories are extracted; links and devices are ignored.
 */
object TarExtractor {

    fun interface Filter {
        /** @param path path inside archive, '/' separated, without leading "./" */
        fun accept(path: String): Boolean
    }

    fun interface Progress {
        /** @param bytes number of uncompressed bytes consumed so far */
        fun onProgress(bytes: Long)
    }

    /**
     * @return list of extracted relative paths (files only)
     */
    fun extractTarBz2(
        input: InputStream,
        destDir: File,
        filter: Filter = Filter { true },
        progress: Progress? = null,
        isCancelled: () -> Boolean = { false }
    ): List<String> = BZip2InputStream(BufferedInputStream(input, 1 shl 16)).use {
        extractTar(it, destDir, filter, progress, isCancelled)
    }

    fun extractTar(
        input: InputStream,
        destDir: File,
        filter: Filter = Filter { true },
        progress: Progress? = null,
        isCancelled: () -> Boolean = { false }
    ): List<String> {
        destDir.mkdirs()
        val canonicalDest = destDir.canonicalFile
        val header = ByteArray(BLOCK)
        val buffer = ByteArray(1 shl 16)
        val extracted = mutableListOf<String>()
        var consumed = 0L
        var longName: String? = null
        var paxPath: String? = null
        var zeroBlocks = 0

        while (true) {
            if (isCancelled()) throw InterruptedException("cancelled")
            if (!readFully(input, header, BLOCK)) break
            consumed += BLOCK
            if (header.all { it.toInt() == 0 }) {
                zeroBlocks++
                if (zeroBlocks >= 2) break
                continue
            }
            zeroBlocks = 0
            if (!verifyChecksum(header)) throw IOException("Bad tar header checksum")

            val type = header[156].toInt().toChar()
            val size = parseSize(header)
            val padded = (size + BLOCK - 1) / BLOCK * BLOCK

            when (type) {
                'L' -> { // GNU long name
                    longName = readString(input, size).trimEnd('\u0000')
                    skip(input, padded - size)
                    consumed += padded
                    continue
                }
                'x' -> { // pax extended header
                    val pax = readString(input, size)
                    skip(input, padded - size)
                    consumed += padded
                    paxPath = parsePaxPath(pax)
                    continue
                }
                'g', 'K' -> { // global pax header / GNU long link name
                    skip(input, padded)
                    consumed += padded
                    continue
                }
            }

            val name = (paxPath ?: longName ?: headerName(header)).removePrefix("./")
            longName = null
            paxPath = null

            val isFile = type == '0' || type == '\u0000' || type == '7'
            val isDir = type == '5' || (isFile && name.endsWith("/"))
            val target = File(canonicalDest, name).canonicalFile
            // compare with the trailing separator: "dest-other/x" must not pass as inside "dest"
            if (target != canonicalDest && !target.path.startsWith(canonicalDest.path + File.separator)) {
                throw IOException("Illegal path in archive: $name")
            }
            when {
                isDir -> {
                    // directories are created on demand when extracting files,
                    // so that filtered-out directories don't leave empty folders behind
                    skip(input, padded)
                    consumed += padded
                }
                isFile && name.isNotEmpty() && filter.accept(name) -> {
                    target.parentFile?.mkdirs()
                    var left = size
                    // buffer is already 64 KiB, write it straight through
                    FileOutputStream(target).use { out ->
                        while (left > 0) {
                            if (isCancelled()) throw InterruptedException("cancelled")
                            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                            if (n < 0) throw IOException("Unexpected end of tar data")
                            out.write(buffer, 0, n)
                            left -= n
                            consumed += n
                            progress?.onProgress(consumed)
                        }
                        // make the data durable before the directory is renamed into place,
                        // so a power loss can't leave an "installed" model with truncated files
                        out.fd.sync()
                    }
                    skip(input, padded - size)
                    consumed += padded - size
                    extracted.add(name)
                }
                else -> {
                    skip(input, padded)
                    consumed += padded
                }
            }
            progress?.onProgress(consumed)
        }
        return extracted
    }

    private const val BLOCK = 512

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Boolean {
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) {
                if (off == 0) return false
                throw IOException("Unexpected end of tar data")
            }
            off += n
        }
        return true
    }

    private fun skip(input: InputStream, n: Long) {
        var left = n
        val tmp = ByteArray(8192)
        while (left > 0) {
            val r = input.read(tmp, 0, minOf(tmp.size.toLong(), left).toInt())
            if (r < 0) throw IOException("Unexpected end of tar data")
            left -= r
        }
    }

    private fun readString(input: InputStream, size: Long): String {
        if (size > 1 shl 20) throw IOException("Tar meta entry too large")
        val buf = ByteArray(size.toInt())
        if (size > 0 && !readFully(input, buf, buf.size)) throw IOException("Unexpected end of tar data")
        return String(buf, Charsets.UTF_8)
    }

    private fun cString(h: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && h[end].toInt() != 0) end++
        return String(h, off, end - off, Charsets.UTF_8)
    }

    private fun headerName(h: ByteArray): String {
        val name = cString(h, 0, 100)
        val magic = cString(h, 257, 6)
        if (magic.startsWith("ustar")) {
            val prefix = cString(h, 345, 155)
            if (prefix.isNotEmpty()) return "$prefix/$name"
        }
        return name
    }

    private fun parseSize(h: ByteArray): Long {
        if (h[124].toInt() and 0x80 != 0) {
            // base-256 encoding
            var v = 0L
            for (i in 125 until 136) v = (v shl 8) or (h[i].toLong() and 0xff)
            return v
        }
        val s = cString(h, 124, 12).trim()
        return if (s.isEmpty()) 0L else s.toLong(8)
    }

    private fun verifyChecksum(h: ByteArray): Boolean {
        val stored = cString(h, 148, 8).trim().ifEmpty { return false }.toLongOrNull(8) ?: return false
        var sum = 0L
        for (i in 0 until BLOCK) {
            sum += if (i in 148 until 156) ' '.code else h[i].toInt() and 0xff
        }
        return sum == stored
    }

    private fun parsePaxPath(pax: String): String? {
        // records: "<len> key=value\n"
        var path: String? = null
        pax.lineSequence().forEach { line ->
            val sp = line.indexOf(' ')
            if (sp < 0) return@forEach
            val kv = line.substring(sp + 1)
            if (kv.startsWith("path=")) path = kv.removePrefix("path=")
        }
        return path
    }
}
