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
}
