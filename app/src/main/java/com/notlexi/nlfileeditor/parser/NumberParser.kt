package com.notlexi.nlfileeditor.parser

import com.notlexi.nlfileeditor.model.BooleanEntry
import com.notlexi.nlfileeditor.model.FileFormat
import com.notlexi.nlfileeditor.model.NumberEntry
import com.notlexi.nlfileeditor.model.ParseResult

/** Entry point of the parsing engine: picks the best scanner for the text. */
object NumberParser {
    const val MAX_ENTRIES = 50_000

    fun parse(text: String): ParseResult {
        val head = text.trimStart('﻿', ' ', '\t', '\r', '\n')

        // 1) Valid JSON -> full key paths
        if (head.startsWith("{") || head.startsWith("[")) {
            val raw = try {
                JsonScanner(text).scan()
            } catch (e: IllegalArgumentException) {
                emptyList()
            }
            if (raw.isNotEmpty()) return ParseResult(finish(text, raw), FileFormat.JSON)
        }

        // 2) XML attributes / element text
        if (head.startsWith("<")) {
            val raw = XmlScanner.scan(text)
            if (raw.isNotEmpty()) return ParseResult(finish(text, raw), FileFormat.XML)
        }

        // 3) Source code (minified JS, SDK files): NOT a save file. Only conservative constants
        //    (plain assignments / single timing arguments) are listed, section "Code constants".
        //    Never falls back to the generic scanner, which would read garbage out of code.
        if (CodeScanner.looksLikeCode(text)) {
            return ParseResult(finish(text, CodeScanner.scan(text)), FileFormat.CODE)
        }

        // 4) Flat "Name 5 OtherName 9 Count13 Flag True Date 10/29/2026 14:12:56" dumps
        val flat = FlatScanner.scan(text)
        if (!flat.isNullOrEmpty()) return ParseResult(finish(text, flat), FileFormat.TEXT)

        // 5) key=value, key: value, INI, "name 100" lines
        return ParseResult(finish(text, GenericScanner.scan(text)), FileFormat.TEXT)
    }

    /** True/False values, scanned on demand when the user opens the True/False tab. */
    fun parseBooleans(text: String): List<BooleanEntry> = BooleanScanner.scan(text)

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
