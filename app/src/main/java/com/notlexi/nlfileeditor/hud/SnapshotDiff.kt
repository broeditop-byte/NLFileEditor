package com.notlexi.nlfileeditor.hud

import com.notlexi.nlfileeditor.model.FileFormat
import com.notlexi.nlfileeditor.parser.BooleanScanner
import com.notlexi.nlfileeditor.parser.NumberParser
import java.math.BigDecimal

/** What kind of value a [ChangedValue] is. Decides which editor the HUD shows. */
enum class ValueKind { NUMBER, BOOLEAN }

/**
 * One value whose text differs between Snapshot A and Snapshot B.
 *
 * [startIndex] (inclusive) and [endIndex] (exclusive) are CHARACTER offsets of the value inside
 * the decoded text of Snapshot B (the "after" snapshot), so they can be used to replace exactly
 * that range. [key] is the label the existing parsers found (e.g. "player.coins"); when no label
 * could be determined it starts with "Unknown value". The HUD never claims a key is a specific
 * game resource: it only shows what the file calls it.
 */
data class ChangedValue(
    val key: String,
    val oldValue: String,
    val newValue: String,
    val startIndex: Int,
    val endIndex: Int,
    val kind: ValueKind = ValueKind.NUMBER
)

data class DiffResult(
    val changes: List<ChangedValue>,
    /** Changes dropped because they look like timestamps / dates. */
    val ignoredTimeLike: Int,
    /** Groups skipped because the same label appeared a different number of times (unsafe to pair). */
    val skippedAmbiguous: Int,
    /** Number of changes found before the display cap. */
    val totalFound: Int,
    val format: FileFormat?,
    /** True when the file had no recognisable structure and a positional token diff was used. */
    val usedFallback: Boolean
) {
    val truncated: Boolean get() = totalFound > changes.size
}

/**
 * Compares two snapshots of the same file and reports ONLY values that really changed.
 * It reuses the app's own parsers (JSON / XML / flat dumps / key=value) so labels and ranges match
 * what the editor shows. Unstructured text falls back to a careful positional token comparison.
 */
object SnapshotDiff {
    const val MAX_CHANGES = 500
    private const val UNKNOWN = "Unknown value"

    private class Item(val label: String, val start: Int, val end: Int, val text: String)

    private class Acc {
        val out = ArrayList<ChangedValue>()
        var ignored = 0
        var skipped = 0
    }

    fun compare(before: String, after: String): DiffResult {
        if (before == after) return DiffResult(emptyList(), 0, 0, 0, null, false)

        val parsedA = NumberParser.parse(before)
        val parsedB = NumberParser.parse(after)
        val numA = parsedA.entries.map { Item(it.label, it.start, it.end, it.original) }
        val numB = parsedB.entries.map { Item(it.label, it.start, it.end, it.original) }
        val acc = Acc()

        val structured = numA.isNotEmpty() && numB.isNotEmpty()
        if (structured) pairByLabel(numA, numB, ValueKind.NUMBER, acc)

        val boolA = BooleanScanner.scan(before).map { Item(it.label, it.start, it.end, it.original) }
        val boolB = BooleanScanner.scan(after).map { Item(it.label, it.start, it.end, it.original) }
        if (boolA.isNotEmpty() && boolB.isNotEmpty()) pairByLabel(boolA, boolB, ValueKind.BOOLEAN, acc)

        if (!structured) tokenFallback(before, after, acc)

        val sorted = acc.out.sortedBy { it.startIndex }
        return DiffResult(
            changes = sorted.take(MAX_CHANGES),
            ignoredTimeLike = acc.ignored,
            skippedAmbiguous = acc.skipped,
            totalFound = sorted.size,
            format = parsedB.format,
            usedFallback = !structured
        )
    }

    // ---------------------------------------------------------------- labelled pairing

    private fun pairByLabel(a: List<Item>, b: List<Item>, kind: ValueKind, acc: Acc) {
        val groupsA = a.groupBy { it.label }
        val groupsB = b.groupBy { it.label }
        for ((label, listB) in groupsB) {
            val listA = groupsA[label] ?: continue // new key: nothing to compare against
            if (listA.size != listB.size) {
                // Same name, different number of occurrences: pairing would be a guess.
                acc.skipped++
                continue
            }
            for (i in listB.indices) {
                val old = listA[i].text
                val new = listB[i].text
                if (kind == ValueKind.BOOLEAN) {
                    if (old.equals(new, ignoreCase = true)) continue
                } else {
                    if (old == new || numericallyEqual(old, new)) continue
                }
                if (isTimeLike(label, old, new)) {
                    acc.ignored++
                    continue
                }
                acc.out.add(
                    ChangedValue(
                        key = if (label.isBlank()) "$UNKNOWN @${listB[i].start}" else label,
                        oldValue = old,
                        newValue = new,
                        startIndex = listB[i].start,
                        endIndex = listB[i].end,
                        kind = kind
                    )
                )
            }
        }
    }

    // ---------------------------------------------------------------- unstructured fallback

    private val NUM = Regex("""-?\d+(?:\.\d+)?""")

    private class Token(val start: Int, val end: Int, val text: String)

    /**
     * For text with no recognisable structure: trim the identical beginning and end of both
     * snapshots, take the numbers left in the middle of each, and pair them in order. Only done
     * when both sides hold the same count, otherwise nothing is guessed.
     */
    private fun tokenFallback(a: String, b: String, acc: Acc) {
        val max = minOf(a.length, b.length)
        var p = 0
        while (p < max && a[p] == b[p]) p++
        while (p > 0 && (a[p - 1].isDigit() || a[p - 1] == '.')) p-- // never cut a number in half
        var s = 0
        while (s < max - p && a[a.length - 1 - s] == b[b.length - 1 - s]) s++
        while (s > 0 && (a[a.length - s].isDigit() || a[a.length - s] == '.')) s--

        val midEndA = a.length - s
        val midEndB = b.length - s
        val tokensA = tokensIn(a, p, midEndA)
        val tokensB = tokensIn(b, p, midEndB)
        if (tokensA.size != tokensB.size) {
            acc.skipped++
            return
        }
        for (i in tokensB.indices) {
            val ta = tokensA[i]
            val tb = tokensB[i]
            if (ta.text == tb.text || numericallyEqual(ta.text, tb.text)) continue
            if (looksLikeDatePart(a, ta) || looksLikeDatePart(b, tb)) {
                acc.ignored++
                continue
            }
            if (looksEpoch(ta.text) && looksEpoch(tb.text)) {
                acc.ignored++
                continue
            }
            acc.out.add(
                ChangedValue(
                    key = unknownKey(b, tb.start),
                    oldValue = ta.text,
                    newValue = tb.text,
                    startIndex = tb.start,
                    endIndex = tb.end,
                    kind = ValueKind.NUMBER
                )
            )
        }
    }

    private fun tokensIn(text: String, from: Int, to: Int): List<Token> {
        val list = ArrayList<Token>()
        for (m in NUM.findAll(text)) {
            val r = m.range
            if (r.first < to && r.last + 1 > from) list.add(Token(r.first, r.last + 1, m.value))
        }
        return list
    }

    private fun unknownKey(text: String, start: Int): String {
        val from = (start - 16).coerceAtLeast(0)
        val ctx = text.substring(from, start).substringAfterLast('\n').trim().takeLast(14)
        return if (ctx.isBlank()) "$UNKNOWN @$start" else "$UNKNOWN near \u201C$ctx\u201D"
    }

    /** Part of a date, clock time, range or dotted version ("10/29/2026", "14:12:56", "1.2.3"). */
    private fun looksLikeDatePart(text: String, t: Token): Boolean {
        val before = if (t.start > 0) text[t.start - 1] else ' '
        val after = if (t.end < text.length) text[t.end] else ' '
        if (before == '/' || before == ':' || after == '/' || after == ':') return true
        if (t.text.startsWith("-") && before.isDigit()) return true
        if (before == '.' && t.start > 1 && text[t.start - 2].isDigit()) return true
        if (after == '.' && t.end + 1 < text.length && text[t.end + 1].isDigit()) return true
        return false
    }

    // ---------------------------------------------------------------- time-like filtering

    private val TIME_TOKENS = setOf(
        "time", "timestamp", "date", "datetime", "epoch", "utc", "unix", "ts", "ticks", "tick",
        "modified", "updated", "created", "expires", "expiry", "expire",
        "playtime", "gametime", "uptime", "runtime", "lastlogin", "lastplayed", "lastsave", "savetime"
    )

    private fun labelTokens(label: String): List<String> =
        label.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() }

    private fun isTimeLike(label: String, old: String, new: String): Boolean {
        if (labelTokens(label).any { it in TIME_TOKENS }) return true
        return looksEpoch(old) && looksEpoch(new)
    }

    /** Unix seconds / milliseconds in 2000..2100, or .NET / Windows tick counts. */
    private fun looksEpoch(s: String): Boolean {
        val t = s.trim().removePrefix("-")
        if (t.isEmpty() || !t.all { it.isDigit() }) return false
        val v = t.toLongOrNull() ?: return false
        return when (t.length) {
            10 -> v in 946_684_800L..4_102_444_800L
            13 -> v in 946_684_800_000L..4_102_444_800_000L
            18 -> t.startsWith("63") || t.startsWith("13")
            else -> false
        }
    }

    private fun numericallyEqual(a: String, b: String): Boolean {
        val x: BigDecimal = a.trim().toBigDecimalOrNull() ?: return false
        val y: BigDecimal = b.trim().toBigDecimalOrNull() ?: return false
        return x.compareTo(y) == 0
    }
}
