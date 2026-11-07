package com.notlexi.nlfileeditor.parser

import com.notlexi.nlfileeditor.model.BooleanEntry
import com.notlexi.nlfileeditor.model.NumberEntry

/**
 * Rebuilds the file text by swapping ONLY the recorded ranges (numbers and True/False words).
 * Everything between the ranges is copied untouched.
 */
object ValuePatcher {
    private class Span(val id: Int, val start: Int, val end: Int, val original: String)

    /**
     * [edits] maps entry id -> new text (a number, or True/False for booleans).
     * Entries without a (different) edit are left as-is.
     */
    fun apply(
        text: String,
        entries: List<NumberEntry>,
        edits: Map<Int, String>,
        booleans: List<BooleanEntry> = emptyList()
    ): String {
        val spans = ArrayList<Span>(entries.size + booleans.size)
        for (e in entries) if (edits.containsKey(e.id)) spans.add(Span(e.id, e.start, e.end, e.original))
        for (b in booleans) if (edits.containsKey(b.id)) spans.add(Span(b.id, b.start, b.end, b.original))
        spans.sortBy { it.start }

        val sb = StringBuilder(text.length + 16)
        var cursor = 0
        for (s in spans) {
            val newValue = edits[s.id]?.trim() ?: continue
            if (newValue == s.original) continue
            if (s.start < cursor || s.end > text.length) continue // never overlap / overrun
            sb.append(text, cursor, s.start)
            sb.append(newValue)
            cursor = s.end
        }
        sb.append(text, cursor, text.length)
        return sb.toString()
    }
}
