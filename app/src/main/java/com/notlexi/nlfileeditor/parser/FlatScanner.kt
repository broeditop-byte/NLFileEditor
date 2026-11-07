package com.notlexi.nlfileeditor.parser

/**
 * Reads "flat dumps": one long run of keys and values separated only by spaces, e.g.
 *
 *   CurrentCharacter 5 CurrentMissionSet 9 LastShowFreeUpgradeDate 10/29/2026 14:12:56
 *   FreeUpgradeCount 3 TranscendByLeadborad True OpenFreeChestCount13 CompleteTaskCount29
 *
 * The text is split into whitespace tokens (a trailing "," or ";" is not part of a token) and
 * every token is classified:
 *
 *   KEY     a name (letters, digits, _ . -) that does not end in digits
 *   NUMBER  a standalone integer / decimal                       -> editable value
 *   BOOL    the words True / False (any case)                    -> editable on the True/False tab
 *   GLUED   a name with the number stuck to its end (Count13)    -> split into key + value
 *   DATE    a date, a time or a version (10/29/2026, 14:12:56)   -> skipped completely;
 *           "10/29/2026 14:12:56" is ONE value made of two DATE tokens
 *   OTHER   anything else
 *
 * Keys and values alternate, so a run of consecutive KEY tokens is "key, string value, key,
 * string value, ..., key": the LAST token of the run is the name of the number / boolean that
 * follows, the earlier tokens are text values of earlier keys and are never part of the name.
 *
 * A GLUED-looking token that is directly followed by a value (Slot1 100) is a plain name that
 * happens to contain digits, so it is treated as KEY, not split.
 *
 * [scan] / [scanBooleans] return null when the text does not look like such a dump, so normal
 * line-based files keep going through [GenericScanner] exactly as before.
 */
internal object FlatScanner {
    private const val SAMPLE = 200_000
    private const val MAX_RESULTS = 60_000

    private val NUMBER = Regex("""-?\d+(?:\.\d+)?""")
    private val WORD = Regex("""[A-Za-z_][\w.\-]*""")
    /** letters/underscores then trailing digits, e.g. OpenFreeChestCount13 -> (OpenFreeChestCount)(13) */
    private val GLUED = Regex("""([A-Za-z_][\w.\-]*?[A-Za-z_])(-?\d+(?:\.\d+)?)""")
    private val DATE = Regex("""\d{1,4}[/.\-]\d{1,2}[/.\-]\d{1,4}(?:T\d{1,2}:\d{2}(?::\d{2})?(?:\.\d+)?Z?)?""")
    private val TIME = Regex("""\d{1,2}:\d{2}(?::\d{2})?(?:\.\d+)?Z?""")
    private val VERSION = Regex("""[vV]?\d+(?:\.\d+){2,}""")
    private val AMPM = Regex("""(?i)[ap]\.?m\.?""")
    private val HEX = Regex("""[0-9a-fA-F]{8,}""")

    private enum class Kind { KEY, NUMBER, GLUED, BOOL, DATE, OTHER }

    private class Result(val numbers: List<RawNumber>, val booleans: List<RawNumber>)

    /** Walks whitespace separated tokens. After [advance] returns true, [start]/[end] is the token. */
    private class Tokens(private val text: String, private val limit: Int, var pos: Int = 0) {
        var start = 0
        var end = 0
        var newLine = false

        fun advance(): Boolean {
            newLine = false
            while (pos < limit) {
                val c = text[pos]
                if (c.isWhitespace() || c == '\uFEFF') {
                    if (c == '\n') newLine = true
                    pos++
                } else break
            }
            if (pos >= limit) return false
            start = pos
            while (pos < limit && !text[pos].isWhitespace()) pos++
            end = pos
            // "Gold 100," -> the comma / semicolon is a separator, not part of the token
            while (end - start > 1 && (text[end - 1] == ',' || text[end - 1] == ';')) end--
            return true
        }
    }

    /** Numbers of a flat dump, or null if the text is not a flat dump. */
    fun scan(text: String): List<RawNumber>? = analyse(text)?.numbers

    /** True/False values of a flat dump, or null if the text is not a flat dump. */
    fun scanBooleans(text: String): List<RawNumber>? = analyse(text)?.booleans

    private fun analyse(text: String): Result? {
        if (!looksFlat(text)) return null
        val numbers = ArrayList<RawNumber>()
        val booleans = ArrayList<RawNumber>()
        var lastKeyS = -1
        var lastKeyE = -1
        var prev = Kind.OTHER

        val t = Tokens(text, text.length)
        while (t.advance()) {
            val s = t.start
            val e = t.end
            val kind = kindOf(text, s, e, prev, t.pos, text.length)
            when (kind) {
                Kind.KEY -> { lastKeyS = s; lastKeyE = e }
                Kind.NUMBER -> {
                    if (lastKeyS >= 0 && numbers.size < MAX_RESULTS) {
                        numbers.add(RawNumber(text.substring(lastKeyS, lastKeyE), "General", s, e))
                    }
                    lastKeyS = -1
                }
                Kind.BOOL -> {
                    if (lastKeyS >= 0 && booleans.size < MAX_RESULTS) {
                        booleans.add(RawNumber(text.substring(lastKeyS, lastKeyE), "General", s, e))
                    }
                    lastKeyS = -1
                }
                Kind.GLUED -> {
                    val g = GLUED.matchEntire(text.substring(s, e))
                    if (g != null && numbers.size < MAX_RESULTS) {
                        val labelLen = g.groupValues[1].length
                        numbers.add(RawNumber(g.groupValues[1], "General", s + labelLen, e))
                    }
                    lastKeyS = -1
                }
                // A date / time (or its second half) ends the key run and is never editable.
                Kind.DATE, Kind.OTHER -> lastKeyS = -1
            }
            prev = kind
        }
        return Result(numbers, booleans)
    }

    /** Decides from the first part of the text whether it is an alternating key/value dump. */
    private fun looksFlat(text: String): Boolean {
        val limit = minOf(text.length, SAMPLE)
        var tokens = 0
        var runs = 0        // groups of consecutive key tokens
        var pairs = 0       // runs that are followed by a value
        var valuePairs = 0  // ... of which the value is a number or True/False
        var others = 0
        var glued = 0
        var inRun = false
        var inLine = 0
        var maxInLine = 0
        var prev = Kind.OTHER

        val t = Tokens(text, limit)
        while (t.advance()) {
            if (t.newLine) inLine = 0
            inLine++
            if (inLine > maxInLine) maxInLine = inLine
            tokens++
            val kind = kindOf(text, t.start, t.end, prev, t.pos, limit)
            when (kind) {
                Kind.KEY -> { if (!inRun) runs++; inRun = true }
                Kind.GLUED -> { runs++; pairs++; valuePairs++; glued++; inRun = false }
                Kind.NUMBER, Kind.BOOL -> { if (inRun) { pairs++; valuePairs++ }; inRun = false }
                Kind.DATE -> { if (inRun && prev != Kind.DATE) pairs++; inRun = false }
                Kind.OTHER -> { others++; inRun = false }
            }
            prev = kind
        }
        // Line-per-pair files ("Gold 100") stay with GenericScanner unless a line holds many
        // tokens or the glued "Count13" style is used (which GenericScanner cannot split).
        return (maxInLine >= 6 || glued >= 2) &&
            tokens >= 8 &&
            valuePairs >= 3 &&
            runs > 0 &&
            pairs * 100 / runs >= 50 &&
            others * 100 / tokens <= 25
    }

    /** Classifies the token [s, e). [after] is the position right behind it (for look-ahead). */
    private fun kindOf(text: String, s: Int, e: Int, prev: Kind, after: Int, limit: Int): Kind {
        val k = classify(text, s, e, prev)
        if (k != Kind.GLUED) return k
        // "Slot1 100": the name itself contains digits -> not a glued value.
        val look = Tokens(text, limit, after)
        if (look.advance()) {
            val next = classify(text, look.start, look.end, k)
            if (next == Kind.NUMBER || next == Kind.BOOL || next == Kind.DATE) return Kind.KEY
        }
        return k
    }

    private fun classify(text: String, s: Int, e: Int, prev: Kind): Kind {
        val tok = text.substring(s, e)
        val c = tok[0]
        if (c in '0'..'9' || c == '-') {
            return when {
                DATE.matches(tok) || TIME.matches(tok) || VERSION.matches(tok) -> Kind.DATE
                NUMBER.matches(tok) -> Kind.NUMBER
                else -> Kind.OTHER
            }
        }
        if (c in 'A'..'Z' || c in 'a'..'z' || c == '_') {
            if (tok.equals("true", ignoreCase = true) || tok.equals("false", ignoreCase = true)) return Kind.BOOL
            if (prev == Kind.DATE && AMPM.matches(tok)) return Kind.DATE
            val g = GLUED.matchEntire(tok)
            if (g != null && g.groupValues[1].length >= 3 &&
                g.groupValues[2].length <= 12 && !HEX.matches(tok)
            ) return Kind.GLUED
            return if (WORD.matches(tok)) Kind.KEY else Kind.OTHER
        }
        return Kind.OTHER
    }
}
