package com.notlexi.nlfileeditor.util

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Lossless bytes <-> text conversion, so unchanged parts of a file stay byte-for-byte identical.
 * UTF-8 is tried first; if the bytes are not valid UTF-8 we use ISO-8859-1, which maps every
 * byte to exactly one character and back.
 */
object TextCodec {
    class Decoded(val text: String, val charset: Charset)

    fun decode(bytes: ByteArray): Decoded {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            Decoded(decoder.decode(ByteBuffer.wrap(bytes)).toString(), Charsets.UTF_8)
        } catch (e: CharacterCodingException) {
            Decoded(String(bytes, Charsets.ISO_8859_1), Charsets.ISO_8859_1)
        }
    }

    /** True if the bytes look like a binary file (NUL bytes or lots of control characters). */
    fun looksBinary(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val n = minOf(bytes.size, 8192)
        var control = 0
        for (k in 0 until n) {
            val b = bytes[k].toInt() and 0xFF
            if (b == 0) return true
            if (b < 9 || b in 14..26 || b in 28..31) control++
        }
        return control * 100 / n > 5
    }

    /**
     * True if the DECODED text is mostly garbage: control characters, replacement glyphs (U+FFFD),
     * C1 control codes, private-use symbols, or (for files that are not valid UTF-8) a large share
     * of non-ASCII characters, which is what binary data looks like when read as Latin-1.
     */
    fun looksGarbled(decoded: Decoded): Boolean {
        val text = decoded.text
        val n = minOf(text.length, 16_384)
        if (n == 0) return false
        var bad = 0
        var nonAscii = 0
        for (k in 0 until n) {
            val c = text[k]
            if (c == '\t' || c == '\n' || c == '\r') continue
            if (c < ' ' || c == '\u007F' ||
                c in '\u0080'..'\u009F' ||
                c == '�' ||
                c in ''..''
            ) bad++
            if (c.code > 127) nonAscii++
        }
        if (bad * 100 / n >= 8) return true
        return decoded.charset == Charsets.ISO_8859_1 && nonAscii * 100 / n >= 30
    }
}
