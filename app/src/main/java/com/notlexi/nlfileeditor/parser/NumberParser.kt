package com.notlexi.nlfileeditor.parser

import com.notlexi.nlfileeditor.model.FileFormat
import com.notlexi.nlfileeditor.model.NumberEntry
import com.notlexi.nlfileeditor.model.ParseResult

/** Entry point of the parsing engine: picks the best scanner for the text. */
object NumberParser {
    const val MAX_ENTRIES = 50_000

    fun parse(text: String): ParseResult {
        val head = text.trimStart('﻿', ' ', '\t', '\r', '\n')

        if (head.startsWith("{") || head.startsWith("[")) {
            val raw = try {
                JsonScanner(text).scan()
            } catch (e: IllegalArgumentException) {
                emptyList()
            }
            if (raw.isNotEmpty()) return ParseResult(finish(text, raw), FileFormat.JSON)
        }

        if (head.startsWith("<")) {
            val raw = XmlScanner.scan(text)
            if (raw.isNotEmpty()) return ParseResult(finish(text, raw), FileFormat.XML)
        }

        return ParseResult(finish(text, GenericScanner.scan(text)), FileFormat.TEXT)
    }

    /** Sorts, drops overlaps, caps the count and assigns ids. */
    private fun finish(text: String, raw: List<RawNumber>): List<NumberEntry> {
        val sorted = raw.sortedBy { it.start }
        val result = ArrayList<NumberEntry>(minOf(sorted.size, MAX_ENTRIES))
        var lastEnd = -1
        for (r in sorted) {
            if (r.start < lastEnd || r.end <= r.start || r.end > text.length) continue
            result.add(
                NumberEntry(
                    id = result.size,
                    label = r.label,
                    section = r.section,
                    start = r.start,
                    end = r.end,
                    original = text.substring(r.start, r.end)
                )
            )
            lastEnd = r.end
            if (result.size >= MAX_ENTRIES) break
        }
        return result
    }
}
