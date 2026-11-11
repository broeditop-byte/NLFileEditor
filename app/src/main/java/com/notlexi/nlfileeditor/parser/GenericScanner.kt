package com.notlexi.nlfileeditor.parser

/**
 * Fallback scanner for key=value, key: value, "key": 5 (broken JSON), INI files and plain
 * "name 100" lines. A number is only reported when it has a name in front of it.
 */
internal object GenericScanner {
    /**
     * group 1 = label (one or more words), group 2 = the number.
     * The number must not be part of a longer word, and must not be part of a version like 1.2.3.
     */
    private val LABEL_NUMBER = Regex(
        """(?<![\w.\-])([A-Za-z_][\w.\-]*(?:[ \t]+[A-Za-z_][\w.\-]*)*)["']?(?:[ \t]*[:=][ \t]*|[ \t]+)["']?(-?\d+(?:\.\d+)?)(?!\w|\.\d)"""
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

    private fun scanLine(line: String, lineOffset: Int, section: String, out: MutableList<RawNumber>) {
        for (m in LABEL_NUMBER.findAll(line)) {
            val num = m.groups[2]!!
            val numStart = num.range.first
            val numEnd = num.range.last + 1
            val label = m.groupValues[1].trim()
            if (label.length > 60) continue

            val before = if (numStart > 0) line[numStart - 1] else ' '
            if (before == '"' || before == '\'') {
                // Quoted value like "200": the closing quote must follow right after the number.
                if (numEnd >= line.length || line[numEnd] != before) continue
            } else if (line.substring(0, numStart).count { it == '"' } % 2 == 1) {
                // Number sits in the middle of a longer quoted sentence/hash -> not a value.
                continue
            }
            out.add(RawNumber(label, section, lineOffset + numStart, lineOffset + numEnd))
        }
    }
}
