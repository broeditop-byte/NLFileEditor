package com.notlexi.nlfileeditor.parser

import com.notlexi.nlfileeditor.model.CODE_SECTION

/**
 * Conservative scanner for source code, mainly minified JavaScript / SDK files.
 *
 * It walks the code with a small lexer (skipping strings, comments and regex literals) and only
 * reports a number when it is a standalone value assigned to a readable name:
 *
 *   var maxRetries = 3;      {adTimeout:5000}      "refreshRate": 30      this.retryCount=4
 *
 * It also accepts a number that is the only argument of a clearly named timing call:
 *
 *   sleep(500)      this.delay(250)      wait(3000)
 *
 * Numbers inside expressions (a*5), loop counters, hex/exponent/version-like tokens, very long
 * numbers, and values assigned to short mangled names (a=1) are ignored. Showing too little is
 * always preferred over showing garbage.
 */
internal object CodeScanner {
    private const val SAMPLE = 100_000
    /** Hard cap: a source file must never flood the list, however many constants it has. */
    private const val MAX_RESULTS = 60

    private val KEYWORDS = Regex("""\b(?:function|return|var|let|const|typeof)\b|=>""")
    private val FUNCTION_OPEN = Regex("""\bfunction\s*[\w$]*\s*\(""")
    private val TIMING_CALLS = setOf("sleep", "wait", "delay", "settimeout", "setinterval", "timeout")
    private val SINGLE_ARG = Regex("""\(\s*(\d{1,9}(?:\.\d+)?)\s*\)""")
    private val PURE = Regex("""\d+(?:\.\d+)?""")
    private val IDENT_LIKE = Regex("""[A-Za-z_$][\w$\-]*""")
    private val REGEX_KEYWORDS = setOf("return", "typeof", "case", "in", "of", "void", "delete", "throw", "else", "do")
    private val NOT_NAMES = setOf(
        "this", "true", "false", "null", "undefined", "return", "var", "let", "const", "function", "void", "typeof",
        // layout / animation / bookkeeping names that are never game values
        "length", "index", "offset", "version", "status", "width", "height", "top", "left", "right", "bottom",
        "opacity", "zindex", "margin", "padding", "size", "type", "code", "color", "scale", "angle", "radius"
    )
    /** Typical JavaScript idioms that show up in SDK / minified code but never in save data. */
    private val JS_MARKERS = Regex("""\.prototype\b|\bthis\.|===|!==|\.length\b|\bdocument\.|\bwindow\.|\bnew [A-Z]\w*\(|\.call\(|\.apply\(|\bnull\b|\bundefined\b""")

    /**
     * True if the text looks like source code, in particular minified / obfuscated JavaScript:
     * dense in braces, parentheses and semicolons, full of `function(` / `=function(` / `=>`,
     * and with very little normal spacing.
     */
    fun looksLikeCode(text: String): Boolean {
        val n = minOf(text.length, SAMPLE)
        if (n < 200) return false
        var punct = 0
        var spaces = 0
        for (k in 0 until n) {
            val c = text[k]
            when {
                c == '{' || c == '}' || c == '(' || c == ')' || c == ';' -> punct++
                c == ' ' || c == '\t' || c == '\n' || c == '\r' -> spaces++
            }
        }
        val punctPercent = punct * 100 / n
        if (punctPercent < 3) return false

        val sample = text.substring(0, n)
        val keywords = KEYWORDS.findAll(sample).take(5).count()
        val functions = FUNCTION_OPEN.findAll(sample).take(3).count()
        val spacePercent = spaces * 100 / n

        // Regular source code.
        if (punctPercent >= 4 && keywords >= 5) return true
        // JavaScript idioms (prototype, this., ===, window., ...) on top of dense punctuation.
        if (punctPercent >= 2 && JS_MARKERS.findAll(sample).take(12).count() >= 12) return true
        // One enormous line full of brackets and semicolons is minified code, not a save file.
        val lines = sample.count { it == '\n' } + 1
        if (punctPercent >= 3 && n / lines >= 800 && keywords >= 2) return true
        // Minified: few spaces, several function( openings and a decent amount of punctuation.
        return spacePercent < 12 && functions >= 3 && keywords >= 3
    }

    fun scan(text: String): List<RawNumber> {
        val out = ArrayList<RawNumber>()
        val n = text.length
        var i = 0

        var prevSig = '\u0000'      // last significant character (for regex-vs-division)
        var prevTok = ""            // last identifier, if the previous token was one
        // Label candidate: an identifier / quoted key, followed (state 2) by '=' or ':'
        var cand = ""
        var candPrevSig = '\u0000'
        var candPrevTok = ""
        var state = 0               // 0 none, 1 candidate seen, 2 candidate + assignment operator seen
        var pendingMinus = -2

        while (i < n && out.size < MAX_RESULTS) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++

                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    val e = text.indexOf('\n', i)
                    i = if (e < 0) n else e
                }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    val e = text.indexOf("*/", i + 2)
                    i = if (e < 0) n else e + 2
                }
                c == '/' -> {
                    val ni = if (regexAllowed(prevSig, prevTok)) skipRegex(text, i) else i + 1
                    prevSig = if (ni > i + 1) 'a' else '/'
                    prevTok = ""
                    state = 0
                    i = ni
                }

                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < n && text[j] != c && text[j] != '\n') {
                        if (text[j] == '\\') j++
                        j++
                    }
                    val end = minOf(j, n)
                    val closed = end < n && text[end] == c
                    val content = text.substring(i + 1, end)
                    i = if (closed) end + 1 else end
                    var k = i
                    while (k < n && (text[k] == ' ' || text[k] == '\t')) k++
                    if (closed && k < n && text[k] == ':' && IDENT_LIKE.matches(content)) {
                        cand = content; candPrevSig = prevSig; candPrevTok = prevTok; state = 1
                    } else {
                        state = 0
                    }
                    prevSig = '"'; prevTok = ""
                }
                c == '`' -> {
                    var j = i + 1
                    while (j < n && text[j] != '`') {
                        if (text[j] == '\\') j++
                        j++
                    }
                    i = minOf(j + 1, n)
                    state = 0; prevSig = '"'; prevTok = ""
                }

                c.isLetter() || c == '_' || c == '$' -> {
                    val s = i
                    var j = i
                    while (true) {
                        while (j < n && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '$')) j++
                        if (j + 1 < n && text[j] == '.' &&
                            (text[j + 1].isLetter() || text[j + 1] == '_' || text[j + 1] == '$')
                        ) j++ else break
                    }
                    val chain = text.substring(s, j)
                    // sleep(500) / this.delay(250): a number that is the only argument of a timing call
                    if (chain.substringAfterLast('.').lowercase() in TIMING_CALLS) {
                        val arg = SINGLE_ARG.matchAt(text, j)
                        if (arg != null) {
                            val g = arg.groups[1]!!
                            out.add(RawNumber("${chain.substringAfterLast('.')} (argument)", CODE_SECTION, g.range.first, g.range.last + 1))
                        }
                    }
                    cand = chain; candPrevSig = prevSig; candPrevTok = prevTok; state = 1
                    prevSig = 'a'; prevTok = chain
                    i = j
                }

                c == '-' && state == 2 && i + 1 < n && text[i + 1].isDigit() -> {
                    pendingMinus = i
                    i++
                }

                c.isDigit() -> {
                    val s = i
                    var j = i
                    while (j < n && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '$' ||
                            (text[j] == '.' && j + 1 < n && text[j + 1].isDigit()))
                    ) j++
                    val raw = text.substring(s, j)
                    val numStart = if (pendingMinus == s - 1) s - 1 else s
                    if (state == 2 && PURE.matches(raw) &&
                        raw.count { it.isDigit() } <= 9 &&
                        plausibleName(cand) && followedByTerminator(text, j)
                    ) {
                        out.add(RawNumber(cand.removePrefix("this."), CODE_SECTION, numStart, j))
                    }
                    pendingMinus = -2
                    state = 0; prevSig = '0'; prevTok = ""
                    i = j
                }

                c == '=' -> {
                    val nx = if (i + 1 < n) text[i + 1] else ' '
                    val pv = if (i > 0) text[i - 1] else ' '
                    if (nx == '=' || nx == '>') {
                        i += if (nx == '=' && i + 2 < n && text[i + 2] == '=') 3 else 2
                        state = 0
                    } else if (pv in "!<>=+-*/%&|^") {
                        i++
                        state = 0
                    } else {
                        val ctxOk = candPrevTok == "var" || candPrevTok == "let" || candPrevTok == "const" ||
                            candPrevSig == ';' || candPrevSig == '{' || candPrevSig == '}' ||
                            candPrevSig == ',' || candPrevSig == '\u0000'
                        state = if (state == 1 && ctxOk) 2 else 0
                        i++
                    }
                    prevSig = '='; prevTok = ""
                }

                c == ':' -> {
                    state = if (state == 1 && (candPrevSig == '{' || candPrevSig == ',')) 2 else 0
                    prevSig = ':'; prevTok = ""
                    i++
                }

                else -> {
                    state = 0
                    prevSig = c; prevTok = ""
                    i++
                }
            }
        }
        return out
    }

    private fun regexAllowed(prevSig: Char, prevTok: String): Boolean =
        prevSig == '\u0000' || prevSig in "(,=:[!&|?{};+-*%<>~^" ||
            (prevSig == 'a' && prevTok in REGEX_KEYWORDS)

    /** Returns the index after a regex literal starting at [start], or start+1 if it is not one. */
    private fun skipRegex(text: String, start: Int): Int {
        val n = text.length
        var j = start + 1
        var inClass = false
        while (j < n) {
            val ch = text[j]
            if (ch == '\n') return start + 1
            if (ch == '\\') { j += 2; continue }
            if (ch == '[') inClass = true
            else if (ch == ']') inClass = false
            else if (ch == '/' && !inClass) {
                j++
                while (j < n && text[j].isLetter()) j++
                return j
            }
            j++
        }
        return start + 1
    }

    /** A value must stand alone: next comes , ; } ) ] a comment, a line break, or the end. */
    private fun followedByTerminator(text: String, from: Int): Boolean {
        var k = from
        var sawNewline = false
        while (k < text.length && text[k].isWhitespace()) {
            if (text[k] == '\n') sawNewline = true
            k++
        }
        if (k >= text.length || sawNewline) return true
        return text[k] in ",;})]" || text.startsWith("//", k) || text.startsWith("/*", k)
    }

    /** Readable names only: not short minified names, not hex/hash-like, not keywords. */
    internal fun plausibleName(chain: String): Boolean {
        val last = chain.substringAfterLast('.')
        if (last.length < 4 || last.length > 40) return false
        if (last.startsWith("_0x")) return false
        if (last in NOT_NAMES) return false
        val hexLike = last.length >= 6 && last.any { it.isDigit() } &&
            last.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        return !hexLike
    }
}
