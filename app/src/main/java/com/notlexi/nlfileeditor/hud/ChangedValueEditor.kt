package com.notlexi.nlfileeditor.hud

import com.notlexi.nlfileeditor.model.BooleanEntry
import com.notlexi.nlfileeditor.parser.BooleanScanner
import com.notlexi.nlfileeditor.parser.NumberParser
import com.notlexi.nlfileeditor.util.NumberUtils

/** Validation and safe text replacement for the value the user picked in the HUD. */
object ChangedValueEditor {
    private const val MAX_INPUT_LENGTH = 30

    class Located(val start: Int, val end: Int)

    /** Returns an error message, or null when [input] is acceptable for [change]. */
    fun validate(change: ChangedValue, input: String): String? {
        val t = input.trim()
        if (change.kind == ValueKind.BOOLEAN) {
            return if (t.equals("true", true) || t.equals("false", true)) null else "Choose True or False"
        }
        NumberUtils.validate(t)?.let { return it }
        if (t.length > MAX_INPUT_LENGTH) return "Number is too long"
        val wasWhole = !change.newValue.contains('.')
        if (wasWhole && t.contains('.')) return "This value is a whole number, decimals are not allowed"
        return null
    }

    /** The exact text to write (booleans keep the file's style: True / true / TRUE). */
    fun normalize(change: ChangedValue, input: String): String {
        val t = input.trim()
        if (change.kind != ValueKind.BOOLEAN) return t
        return BooleanEntry(0, change.key, 0, change.newValue.length, change.newValue)
            .textFor(t.equals("true", ignoreCase = true))
    }

    /**
     * Finds where [change] currently sits in [text]. When the file is byte-identical to Snapshot B
     * the recorded range is exact. Otherwise the value is looked up again by its label and must
     * be unique, so a different value is never overwritten by accident.
     */
    fun locate(text: String, change: ChangedValue, identicalToSnapshot: Boolean): Located? {
        val s = change.startIndex
        val e = change.endIndex
        if (identicalToSnapshot) {
            return if (s >= 0 && s < e && e <= text.length && text.substring(s, e) == change.newValue) {
                Located(s, e)
            } else null
        }
        val matches: List<Located> = if (change.kind == ValueKind.NUMBER) {
            NumberParser.parse(text).entries
                .filter { it.label == change.key && it.original == change.newValue }
                .map { Located(it.start, it.end) }
        } else {
            BooleanScanner.scan(text)
                .filter { it.label == change.key && it.original.equals(change.newValue, ignoreCase = true) }
                .map { Located(it.start, it.end) }
        }
        return matches.singleOrNull()
    }

    fun replaceRange(text: String, at: Located, replacement: String): String =
        text.substring(0, at.start) + replacement + text.substring(at.end)
}
