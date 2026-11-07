package com.notlexi.nlfileeditor.util

import com.notlexi.nlfileeditor.model.NumberEntry
import java.math.BigDecimal
import java.math.RoundingMode

/** Validation and +/- stepping for number text. Uses BigDecimal so big values never lose digits. */
object NumberUtils {
    private val VALID = Regex("""-?\d+(\.\d+)?""")

    /** Returns an error message, or null if [text] is a valid number. */
    fun validate(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return "Enter a number"
        if (!VALID.matches(t)) return "Not a valid number"
        return null
    }

    /** Adds [delta] steps (1 for whole numbers, 0.1 for decimals) to [current]. */
    fun step(current: String, entry: NumberEntry, delta: Int): String {
        val base = current.trim().toBigDecimalOrNull() ?: entry.original.toBigDecimal()
        val scale = maxOf(entry.decimals, base.scale().coerceAtLeast(0))
        val unit = if (scale == 0) BigDecimal.ONE else BigDecimal("0.1")
        val result = base.add(unit.multiply(BigDecimal(delta)))
        return result.setScale(scale, RoundingMode.HALF_UP).toPlainString()
    }
}
