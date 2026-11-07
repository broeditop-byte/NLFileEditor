package com.notlexi.nlfileeditor.parser

import com.notlexi.nlfileeditor.model.FileFormat

/**
 * Fast "how many editable values does this text hold?" check used by Browse Apps to decide which
 * files are worth showing. It runs the SAME detection as the editor (NumberParser for numbers,
 * BooleanScanner for True/False), so a count shown in the file list matches what the editor opens.
 */
object QuickScan {
    class Summary(val numbers: Int, val booleans: Int, val isCode: Boolean) {
        val total: Int get() = numbers + booleans
    }

    private val EMPTY = Summary(0, 0, false)

    fun summarize(text: String): Summary {
        if (text.isBlank()) return EMPTY
        return try {
            val result = NumberParser.parse(text)
            // Source code (minified JS, SDK files) is never a save file.
            if (result.format == FileFormat.CODE) return Summary(0, 0, true)
            val bools = BooleanScanner.scan(text).size
            Summary(result.entries.size, bools, false)
        } catch (e: Exception) {
            EMPTY
        }
    }

    /**
     * Cuts text that was read from the first part of a bigger file so that a number is never
     * sliced in half ("Gold 12" of "Gold 1250"): drops everything after the last separator.
     */
    fun trimPartialTail(text: String): String {
        val floor = (text.length - 64).coerceAtLeast(0)
        for (k in text.length - 1 downTo floor) {
            val c = text[k]
            if (c.isWhitespace() || c == ',' || c == ';' || c == '}' || c == ']' || c == '>') {
                return text.substring(0, k + 1)
            }
        }
        return text
    }
}
