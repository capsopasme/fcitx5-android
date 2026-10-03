/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.voice.archive

import java.io.IOException
import java.io.InputStream

/**
 * A small, dependency free bzip2 decompressor.
 *
 * Speech models from sherpa-onnx are distributed as `.tar.bz2`, and the Android framework
 * has no built-in bzip2 support. This implementation follows the reference bzip2 1.0.8
 * decoder (Huffman -> MTF/RLE2 -> inverse BWT -> RLE1). Concatenated streams are supported.
 * Block / stream CRCs are verified.
 */
class BZip2InputStream(input: InputStream) : InputStream() {

    private val bits = BitReader(input)

    private var blockSize100k = 0

    /** inverse BWT linked list; low 8 bits = byte, high 24 bits = next index */
    private var tt = IntArray(0)

    // decoded block state
    private var tPos = 0
    private var remaining = 0          // number of BWT symbols left in current block
    private var lastByte = -1
    private var runCount = 0           // how many identical bytes we've seen in a row
    private var pendingRepeat = 0      // bytes left to emit from an RLE1 run
    private var pendingByte = 0

    private var blockCrc = 0
    private var computedBlockCrc = 0
    private var computedStreamCrc = 0
    private var storedStreamCrc = 0

    private var eof = false
    private var blockActive = false

    init {
        if (!readStreamHeader(first = true)) throw IOException("Not a bzip2 stream")
    }

    private fun readStreamHeader(first: Boolean): Boolean {
        val b = bits.readByteAlignedOrEof() ?: return false
        if (b != 'B'.code) {
            if (first) throw IOException("Not a bzip2 stream")
            // trailing garbage after a complete stream: ignore
            return false
        }
        if (bits.readBits(8) != 'Z'.code || bits.readBits(8) != 'h'.code) {
            throw IOException("Bad bzip2 header")
        }
        val level = bits.readBits(8) - '0'.code
        if (level !in 1..9) throw IOException("Bad bzip2 block size")
        blockSize100k = level
        if (tt.size < level * 100000) tt = IntArray(level * 100000)
        computedStreamCrc = 0
        return true
    }

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (eof) return -1
        var n = 0
        while (n < len) {
            if (pendingRepeat > 0) {
                b[off + n] = pendingByte.toByte()
                updateCrc(pendingByte)
                pendingRepeat--
                n++
                continue
            }
            if (!blockActive) {
                if (!startNextBlock()) {
                    eof = true
                    return if (n == 0) -1 else n
                }
                continue
            }
            if (remaining == 0) {
                finishBlock()
                continue
            }
            // next BWT output byte
            val entry = tt[tPos]
            val ch = entry and 0xff
            tPos = entry ushr 8
            remaining--
            if (runCount == 4) {
                // this byte is a repeat count for the previous run
                pendingRepeat = ch
                pendingByte = lastByte
                runCount = 0
                lastByte = -1
                continue
            }
            if (ch == lastByte) {
                runCount++
            } else {
                runCount = 1
                lastByte = ch
            }
            b[off + n] = ch.toByte()
            updateCrc(ch)
            n++
        }
        return n
    }

    private fun updateCrc(byte: Int) {
        computedBlockCrc = (computedBlockCrc shl 8) xor CRC_TABLE[((computedBlockCrc ushr 24) xor byte) and 0xff]
    }

    private fun finishBlock() {
        computedBlockCrc = computedBlockCrc.inv()
        if (computedBlockCrc != blockCrc) throw IOException("bzip2 block CRC mismatch")
        computedStreamCrc = ((computedStreamCrc shl 1) or (computedStreamCrc ushr 31)) xor computedBlockCrc
        blockActive = false
    }

    /** @return false at the very end of input */
    private fun startNextBlock(): Boolean {
        while (true) {
            val magicHi = bits.readBits(24)
            val magicLo = bits.readBits(24)
            if (magicHi == 0x314159 && magicLo == 0x265359) {
                blockCrc = bits.readInt()
                decodeBlock()
                return true
            }
            if (magicHi == 0x177245 && magicLo == 0x385090) {
                storedStreamCrc = bits.readInt()
                if (storedStreamCrc != computedStreamCrc) throw IOException("bzip2 stream CRC mismatch")
                bits.alignToByte()
                // concatenated stream?
                if (!readStreamHeader(first = false)) return false
                continue
            }
            throw IOException("Bad bzip2 block magic")
        }
    }

    private fun decodeBlock() {
        if (bits.readBits(1) != 0) throw IOException("Randomised bzip2 blocks are not supported")
        val origPtr = bits.readBits(24)

        // symbol map
        val seqToUnseq = IntArray(256)
        var nInUse = 0
        val inUse16 = bits.readBits(16)
        for (i in 0 until 16) {
            if (inUse16 and (0x8000 ushr i) != 0) {
                val w = bits.readBits(16)
                for (j in 0 until 16) {
                    if (w and (0x8000 ushr j) != 0) seqToUnseq[nInUse++] = i * 16 + j
                }
            }
        }
        if (nInUse == 0) throw IOException("bzip2: no symbols in use")
        val alphaSize = nInUse + 2

        val nGroups = bits.readBits(3)
        if (nGroups !in 2..6) throw IOException("bzip2: bad number of Huffman groups")
        val nSelectors = bits.readBits(15)
        if (nSelectors < 1) throw IOException("bzip2: bad number of selectors")

        // selectors (MTF coded)
        val mtfGroups = IntArray(nGroups) { it }
        val selectors = ByteArray(minOf(nSelectors, MAX_SELECTORS))
        for (i in 0 until nSelectors) {
            var j = 0
            while (bits.readBits(1) == 1) {
                j++
                if (j >= nGroups) throw IOException("bzip2: bad selector")
            }
            val v = mtfGroups[j]
            for (k in j downTo 1) mtfGroups[k] = mtfGroups[k - 1]
            mtfGroups[0] = v
            if (i < MAX_SELECTORS) selectors[i] = v.toByte()
        }
        val usedSelectors = selectors.size

        // coding tables
        val limit = Array(nGroups) { IntArray(MAX_CODE_LEN + 2) }
        val base = Array(nGroups) { IntArray(MAX_CODE_LEN + 2) }
        val perm = Array(nGroups) { IntArray(alphaSize) }
        val minLens = IntArray(nGroups)
        val lens = IntArray(alphaSize)
        for (t in 0 until nGroups) {
            var curr = bits.readBits(5)
            for (i in 0 until alphaSize) {
                while (true) {
                    if (curr < 1 || curr > MAX_CODE_LEN) throw IOException("bzip2: bad code length")
                    if (bits.readBits(1) == 0) break
                    if (bits.readBits(1) == 0) curr++ else curr--
                }
                lens[i] = curr
            }
            var minLen = 32
            var maxLen = 0
            for (i in 0 until alphaSize) {
                if (lens[i] > maxLen) maxLen = lens[i]
                if (lens[i] < minLen) minLen = lens[i]
            }
            createDecodeTables(limit[t], base[t], perm[t], lens, minLen, maxLen, alphaSize)
            minLens[t] = minLen
        }

        // MTF / RLE2 decoding into tt (store bytes temporarily in low 8 bits)
        val unzftab = IntArray(256)
        val mtf = IntArray(256) { it }
        val eob = nInUse + 1
        val blockMax = blockSize100k * 100000
        var nblock = 0
        var groupNo = -1
        var groupPos = 0
        var gLimit = limit[0]
        var gBase = base[0]
        var gPerm = perm[0]
        var gMinLen = 0

        fun nextSym(): Int {
            if (groupPos == 0) {
                groupNo++
                if (groupNo >= usedSelectors) throw IOException("bzip2: selector overflow")
                groupPos = GROUP_SIZE
                val g = selectors[groupNo].toInt()
                gLimit = limit[g]; gBase = base[g]; gPerm = perm[g]; gMinLen = minLens[g]
            }
            groupPos--
            var zn = gMinLen
            var zvec = bits.readBits(zn)
            while (true) {
                if (zn > MAX_CODE_LEN) throw IOException("bzip2: bad Huffman code")
                if (zvec <= gLimit[zn]) break
                zn++
                zvec = (zvec shl 1) or bits.readBits(1)
            }
            val idx = zvec - gBase[zn]
            if (idx < 0 || idx >= alphaSize) throw IOException("bzip2: bad Huffman code")
            return gPerm[idx]
        }

        var sym = nextSym()
        while (sym != eob) {
            if (sym == RUNA || sym == RUNB) {
                var es = 0
                var n = 1
                do {
                    es += if (sym == RUNA) n else 2 * n
                    n = n shl 1
                    if (n >= 2 * 1024 * 1024) throw IOException("bzip2: run too long")
                    sym = nextSym()
                } while (sym == RUNA || sym == RUNB)
                val uc = seqToUnseq[mtf[0]]
                unzftab[uc] += es
                if (nblock + es > blockMax) throw IOException("bzip2: block overflow")
                repeat(es) { tt[nblock++] = uc }
                continue
            }
            // MTF index = sym - 1
            if (nblock >= blockMax) throw IOException("bzip2: block overflow")
            val nn = sym - 1
            val v = mtf[nn]
            System.arraycopy(mtf, 0, mtf, 1, nn)
            mtf[0] = v
            val uc = seqToUnseq[v]
            unzftab[uc]++
            tt[nblock++] = uc
            sym = nextSym()
        }
        if (origPtr < 0 || origPtr >= nblock) throw IOException("bzip2: bad origPtr")

        // inverse BWT
        val cftab = IntArray(257)
        for (i in 0 until 256) cftab[i + 1] = cftab[i] + unzftab[i]
        for (i in 0 until nblock) {
            val uc = tt[i] and 0xff
            tt[cftab[uc]] = tt[cftab[uc]] or (i shl 8)
            cftab[uc]++
        }
        tPos = tt[origPtr] ushr 8
        remaining = nblock
        lastByte = -1
        runCount = 0
        pendingRepeat = 0
        computedBlockCrc = -1
        blockActive = true
    }

    private fun createDecodeTables(
        limit: IntArray, base: IntArray, perm: IntArray, length: IntArray,
        minLen: Int, maxLen: Int, alphaSize: Int
    ) {
        // port of BZ2_hbCreateDecodeTables()
        var pp = 0
        for (i in minLen..maxLen) for (j in 0 until alphaSize) if (length[j] == i) perm[pp++] = j
        base.fill(0)
        for (i in 0 until alphaSize) base[length[i] + 1]++
        for (i in 1 until base.size) base[i] += base[i - 1]
        limit.fill(-1)
        var vec = 0
        for (i in minLen..maxLen) {
            vec += base[i + 1] - base[i]
            limit[i] = vec - 1
            vec = vec shl 1
        }
        for (i in minLen + 1..maxLen) {
            base[i] = ((limit[i - 1] + 1) shl 1) - base[i]
        }
    }

    override fun close() = bits.close()

    private class BitReader(private val input: InputStream) {
        private val buf = ByteArray(64 * 1024)
        private var bufLen = 0
        private var bufPos = 0
        private var bitBuf = 0L
        private var bitCount = 0

        private fun fillByte(): Int {
            if (bufPos == bufLen) {
                bufLen = input.read(buf, 0, buf.size)
                bufPos = 0
                if (bufLen <= 0) {
                    bufLen = 0
                    return -1
                }
            }
            return buf[bufPos++].toInt() and 0xff
        }

        fun readBits(n: Int): Int {
            if (n == 0) return 0
            while (bitCount < n) {
                val b = fillByte()
                if (b < 0) throw IOException("Unexpected end of bzip2 data")
                bitBuf = (bitBuf shl 8) or b.toLong()
                bitCount += 8
            }
            bitCount -= n
            return ((bitBuf ushr bitCount) and ((1L shl n) - 1)).toInt()
        }

        fun readInt(): Int = (readBits(16) shl 16) or readBits(16)

        fun alignToByte() {
            bitCount -= bitCount % 8
        }

        /** Read one byte at a byte boundary, null on EOF */
        fun readByteAlignedOrEof(): Int? {
            alignToByte()
            if (bitCount >= 8) {
                bitCount -= 8
                return ((bitBuf ushr bitCount) and 0xff).toInt()
            }
            val b = fillByte()
            return if (b < 0) null else b
        }

        fun close() = input.close()
    }

    companion object {
        private const val RUNA = 0
        private const val RUNB = 1
        private const val GROUP_SIZE = 50
        private const val MAX_CODE_LEN = 20
        private const val MAX_SELECTORS = 18002

        private val CRC_TABLE = IntArray(256).also { t ->
            for (i in 0 until 256) {
                var c = i shl 24
                repeat(8) {
                    c = if (c and 0x80000000.toInt() != 0) (c shl 1) xor 0x04c11db7 else c shl 1
                }
                t[i] = c
            }
        }
    }
}
