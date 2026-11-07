package com.notlexi.nlfileeditor.parser

/**
 * Small, position-aware JSON reader. It does not build a tree; it only walks the text and
 * records the exact start/end of every plain number together with its full path
 * (e.g. player.inventory.gold or items[2].count).
 *
 * Throws IllegalArgumentException if the text is not valid JSON, so the caller can fall back
 * to the generic scanner.
 */
internal class JsonScanner(private val s: String) {
    private val out = ArrayList<RawNumber>()
    private var i = 0

    /** Every true/false literal found while walking (used by the True/False tab). */
    val booleans = ArrayList<RawNumber>()

    fun scan(): List<RawNumber> {
        skipWs()
        value("", 0)
        skipWs()
        if (i != s.length) fail()
        return out
    }

    private fun fail(): Nothing = throw IllegalArgumentException("Invalid JSON near $i")

    private fun skipWs() {
        while (i < s.length && (s[i].isWhitespace() || s[i] == '﻿')) i++
    }

    private fun value(path: String, depth: Int) {
        if (depth > 200 || i >= s.length) fail()
        when (s[i]) {
            '{' -> obj(path, depth)
            '[' -> arr(path, depth)
            '"' -> readString()
            '-', in '0'..'9' -> number(path)
            else -> literal(path)
        }
    }

    private fun obj(path: String, depth: Int) {
        i++ // {
        skipWs()
        if (peek() == '}') { i++; return }
        while (true) {
            skipWs()
            if (peek() != '"') fail()
            val key = readString()
            skipWs()
            if (peek() != ':') fail()
            i++
            skipWs()
            value(if (path.isEmpty()) key else "$path.$key", depth + 1)
            skipWs()
            when (peek()) {
                ',' -> i++
                '}' -> { i++; return }
                else -> fail()
            }
        }
    }

    private fun arr(path: String, depth: Int) {
        i++ // [
        skipWs()
        if (peek() == ']') { i++; return }
        var index = 0
        while (true) {
            skipWs()
            value("$path[$index]", depth + 1)
            index++
            skipWs()
            when (peek()) {
                ',' -> i++
                ']' -> { i++; return }
                else -> fail()
            }
        }
    }

    /** Returns the raw (still escaped) text between the quotes. */
    private fun readString(): String {
        i++ // opening quote
        val begin = i
        while (i < s.length) {
            val c = s[i]
            if (c == '\\') { i += 2; continue }
            if (c == '"') {
                val str = s.substring(begin, i)
                i++
                return str
            }
            i++
        }
        fail()
    }

    private fun number(path: String) {
        val start = i
        if (s[i] == '-') i++
        if (i >= s.length || s[i] !in '0'..'9') fail()
        while (i < s.length && s[i] in '0'..'9') i++
        if (i < s.length && s[i] == '.') {
            i++
            if (i >= s.length || s[i] !in '0'..'9') fail()
            while (i < s.length && s[i] in '0'..'9') i++
        }
        var exponent = false
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            exponent = true
            i++
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            if (i >= s.length || s[i] !in '0'..'9') fail()
            while (i < s.length && s[i] in '0'..'9') i++
        }
        // Numbers written like 1e5 are left alone; they are rarely meant to be hand-edited.
        if (!exponent) {
            val label = path.ifEmpty { "value" }
            out.add(RawNumber(label, ScanUtil.sectionOf(path), start, i))
        }
    }

    private fun literal(path: String) {
        for (word in arrayOf("true", "false", "null")) {
            if (s.startsWith(word, i)) {
                if (word != "null") {
                    booleans.add(RawNumber(path.ifEmpty { "value" }, ScanUtil.sectionOf(path), i, i + word.length))
                }
                i += word.length
                return
            }
        }
        fail()
    }

    private fun peek(): Char = if (i < s.length) s[i] else fail()
}
