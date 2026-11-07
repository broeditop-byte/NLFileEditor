package com.notlexi.nlfileeditor.parser

/**
 * Fallback scanner for key=value, key: value, "key": 5 (broken JSON), INI files and plain
 * "name 100" lines. A number is only reported when it has a name in front of it.
 * Date/time shaped tokens (10/29/2026, 14:12:56, 2026-10-29) are never reported.
 */
internal object GenericScanner {
    /**
     * group 1 = label (one to six words), group 2 = the number.
     * The number must not be part of a longer word, a version like 1.2.3, or a date/time such as
     * 10/29/2026, 14:12:56 or 2026-10-29.
     */
    private val LABEL_NUMBER = Regex(
        """(?<![\w.\-])([A-Za-z_][\w.\-]*(?:[ \t]+[A-Za-z_][\w.\-]*){0,5})["']?(?:[ \t]*[:=][ \t]*|[ \t]+)["']?(-?\d+(?:\.\d+)?)(?!\w|\.\d|[/:\-]\d)"""
    )
    private val INI_SECTION = Regex("""^\s*\[([^\]\r\n]+)]\s*$""")

    fun scan(text: String): List<RawNumber> {
        val out = ArrayList<RawNumber>()
        var section = "General"
        var pos = 0
        while (pos <= text.length) {
            var nl = text.indexOf('\n', pos)
            if (nl < 0) nl = text.length
            val line = text.substring(pos, nl)
            val trimmed = line.trimStart()

            val ini = INI_SECTION.find(line)
            if (ini != null) {
                section = ini.groupValues[1].trim()
            } else if (trimmed.isNotEmpty() &&
                !trimmed.startsWith("#") && !trimmed.startsWith("//") && !trimmed.startsWith(";")
            ) {
                scanLine(line, pos, section, out)
            }
            pos = nl + 1
        }
        return out
    }

    /**
     * A label may swallow earlier words of the same line ("HasRated False HadBuyCoin 3").
     * Everything up to and including the last True/False word belongs to another entry, so drop it.
     */
    private fun cleanLabel(label: String): String {
        val words = label.split(' ', '\t').filter { it.isNotEmpty() }
        val lastBool = words.indexOfLast { it.equals("true", ignoreCase = true) || it.equals("false", ignoreCase = true) }
        return if (lastBool < 0) label else words.drop(lastBool + 1).joinToString(" ")
    }

    private fun scanLine(line: String, lineOffset: Int, section: String, out: MutableList<RawNumber>) {
        // Quote parity is tracked incrementally so very long lines stay fast (no re-counting).
        var quotes = 0
        var counted = 0
        for (m in LABEL_NUMBER.findAll(line)) {
            val num = m.groups[2]!!
            val numStart = num.range.first
            val numEnd = num.range.last + 1
            while (counted < numStart) {
                if (line[counted] == '"') quotes++
                counted++
            }
            val label = cleanLabel(m.groupValues[1].trim())
            if (label.isEmpty() || label.length > 60) continue

            val before = if (numStart > 0) line[numStart - 1] else ' '
            if (before == '"' || before == '\'') {
                // Quoted value like "200": the closing quote must follow right after the number.
                if (numEnd >= line.length || line[numEnd] != before) continue
            } else if (quotes % 2 == 1) {
                // Number sits in the middle of a longer quoted sentence/hash -> not a value.
                continue
            }
            out.add(RawNumber(label, section, lineOffset + numStart, lineOffset + numEnd))
        }
    }
}
