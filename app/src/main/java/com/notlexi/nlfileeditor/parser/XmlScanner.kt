package com.notlexi.nlfileeditor.parser

/**
 * Finds numbers in XML attributes and element text.
 *
 *  <player gold="100" level="4"/>        -> player.gold, player.level
 *  <item name="diamond" value="100"/>    -> diamond
 *  <coins>250</coins>                    -> parent.coins
 */
internal object XmlScanner {
    // comments | processing instructions | CDATA | doctype etc. | start/end tags
    private val TOKEN = Regex(
        """<!--.*?-->|<\?.*?\?>|<!\[CDATA\[.*?]]>|<![^>]*>|<(/?)([A-Za-z_][\w:.\-]*)([^>]*?)(/?)>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val ATTR = Regex("""([\w:.\-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val TEXT_NUMBER = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*$""")

    private val NAME_ATTRS = setOf("name", "id", "key", "label")
    private val VALUE_ATTRS = setOf("value", "val", "v", "amount", "count")
    private val IGNORED_ATTRS = setOf("version", "xmlns", "encoding")

    fun scan(text: String): List<RawNumber> {
        val out = ArrayList<RawNumber>()
        val stack = ArrayList<String>()

        for (tok in TOKEN.findAll(text)) {
            val name = tok.groups[2]?.value ?: continue // comment / PI / CDATA / doctype
            if (tok.groups[1]?.value == "/") {
                if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                continue
            }
            val selfClosing = tok.groups[4]?.value == "/"
            val attrsGroup = tok.groups[3]
            val attrsText = attrsGroup?.value.orEmpty()
            val attrsStart = attrsGroup?.range?.first ?: 0
            val section = stack.lastOrNull() ?: name
            val path = (stack + name).joinToString(".")

            val attrs = ATTR.findAll(attrsText).toList()
            // If the element is named by an attribute (name="gold"), use that as the label.
            val nameValue = attrs.firstOrNull { m ->
                m.groupValues[1].lowercase() in NAME_ATTRS &&
                    !ScanUtil.PURE_NUMBER.matches(valueOf(m))
            }?.let { valueOf(it) }?.takeIf { it.isNotBlank() }

            for (m in attrs) {
                val key = m.groupValues[1]
                if (key.lowercase() in IGNORED_ATTRS || key.startsWith("xmlns")) continue
                val valueGroup = m.groups[2] ?: m.groups[3] ?: continue
                val value = valueGroup.value
                if (!ScanUtil.PURE_NUMBER.matches(value)) continue
                val label = when {
                    nameValue != null && key.lowercase() in VALUE_ATTRS -> nameValue
                    nameValue != null -> "$nameValue.$key"
                    else -> "$path.$key"
                }
                val start = attrsStart + valueGroup.range.first
                out.add(RawNumber(label, section, start, start + value.length))
            }

            if (!selfClosing) {
                stack.add(name)
                // Text directly inside the element, e.g. <gold>100</gold>
                val contentStart = tok.range.last + 1
                val next = text.indexOf('<', contentStart)
                if (next > contentStart) {
                    val segment = text.substring(contentStart, next)
                    TEXT_NUMBER.find(segment)?.let { m ->
                        val g = m.groups[1]!!
                        val start = contentStart + g.range.first
                        out.add(RawNumber(nameValue ?: path, section, start, start + g.value.length))
                    }
                }
            }
        }
        return out
    }

    private fun valueOf(m: MatchResult): String = m.groups[2]?.value ?: m.groups[3]?.value.orEmpty()
}
