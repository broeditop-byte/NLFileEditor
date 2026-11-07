package com.notlexi.nlfileeditor.parser

/** A number found by one of the scanners, before ids are assigned. [end] is exclusive. */
data class RawNumber(
    val label: String,
    val section: String,
    val start: Int,
    val end: Int
)

/** Shared helpers for the scanners. */
internal object ScanUtil {
    /** A plain editable number: optional minus, digits, optional decimals. */
    val PURE_NUMBER = Regex("""-?\d+(?:\.\d+)?""")

    /** Section = text before the first '.' or '[' of a path, or "General" for top-level values. */
    fun sectionOf(path: String): String {
        val i = path.indexOfFirst { it == '.' || it == '[' }
        return if (i <= 0) "General" else path.substring(0, i)
    }
}
