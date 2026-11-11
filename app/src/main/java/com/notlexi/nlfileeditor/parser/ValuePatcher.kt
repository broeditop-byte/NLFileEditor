package com.notlexi.nlfileeditor.parser

import com.notlexi.nlfileeditor.model.NumberEntry

/**
 * Rebuilds the file text by swapping ONLY the recorded number ranges.
 * Everything between the ranges is copied untouched.
 */
object ValuePatcher {
    /** [edits] maps entry id -> new number text. Entries without a (different) edit are left as-is. */
    fun apply(text: String, entries: List<NumberEntry>, edits: Map<Int, String>): String {
        val sb = StringBuilder(text.length + 16)
        var cursor = 0
        for (e in entries.sortedBy { it.start }) {
            val newValue = edits[e.id]?.trim() ?: continue
            if (newValue == e.original) continue
            sb.append(text, cursor, e.start)
            sb.append(newValue)
            cursor = e.end
        }
        sb.append(text, cursor, text.length)
        return sb.toString()
    }
}
