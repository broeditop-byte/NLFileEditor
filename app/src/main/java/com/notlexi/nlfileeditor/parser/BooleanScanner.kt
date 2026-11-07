package com.notlexi.nlfileeditor.parser

import com.notlexi.nlfileeditor.model.BOOL_ID_BASE
import com.notlexi.nlfileeditor.model.BooleanEntry

/**
 * On-demand scan for True/False values. It is separate from [NumberParser] because it only runs
 * when the user opens the True/False tab.
 *
 * Supported shapes (the same families as the number scanners):
 *  - JSON            {"hasRated": false}  -> path as the name
 *  - XML             <boolean name="HasRated" value="false"/>, <flag>true</flag>
 *  - flat dumps      HasRated False HadBuyCoin False TutorialCompleted True
 *  - key: value      hasRated: true
 *  - key=value       HasRated=False        (also INI, "key": true in broken JSON)
 *  - "key value"     HasRated False        (one pair on a line)
 *
 * In source code only `name: true` / `name = true` with a readable name is accepted.
 */
object BooleanScanner {
    const val MAX_ENTRIES = 20_000
    private const val CODE_MAX_ENTRIES = 40

    private val BOOL_WORD = Regex("""(?i)true|false""")
    private val KEY_TOKEN = Regex("""[A-Za-z_][\w.\-]*""")
    private val DELIM = Regex(
        """(?<![\w.\-])["']?([A-Za-z_][\w.\-]*)["']?[ \t]*[:=][ \t]*["']?(true|false)(?![\w])""",
        RegexOption.IGNORE_CASE
    )
    private val STOP_WORDS = setOf(
        "is", "are", "was", "were", "be", "been", "to", "if", "the", "and", "or", "not", "it",
        "set", "returns", "return", "then", "else", "when", "that", "this", "as", "of", "in",
        "on", "at", "for", "with", "by", "a", "an", "its", "equals", "default"
    )

    // XML (same token shapes as XmlScanner)
    private val XML_TOKEN = Regex(
        """<!--.*?-->|<\?.*?\?>|<!\[CDATA\[.*?]]>|<![^>]*>|<(/?)([A-Za-z_][\w:.\-]*)([^>]*?)(/?)>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val XML_ATTR = Regex("""([\w:.\-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val NAME_ATTRS = setOf("name", "id", "key", "label")
    private val VALUE_ATTRS = setOf("value", "val", "v")

    /** Scans [text] and returns every True/False value with its exact position. */
    fun scan(text: String): List<BooleanEntry> {
        val sorted = collect(text).sortedBy { it.start }
        val result = ArrayList<BooleanEntry>()
        var lastEnd = -1
        for (r in sorted) {
            if (r.start < lastEnd || r.end <= r.start || r.end > text.length) continue
            val original = text.substring(r.start, r.end)
            if (!BOOL_WORD.matches(original)) continue
            result.add(BooleanEntry(BOOL_ID_BASE + result.size, r.label, r.start, r.end, original))
            lastEnd = r.end
            if (result.size >= MAX_ENTRIES) break
        }
        return result
    }

    private fun collect(text: String): List<RawNumber> {
        val head = text.trimStart('\uFEFF', ' ', '\t', '\r', '\n')

        // 1) Valid JSON -> full key paths
        if (head.startsWith("{") || head.startsWith("[")) {
            try {
                val json = JsonScanner(text)
                json.scan()
                return json.booleans
            } catch (e: IllegalArgumentException) {
                // not valid JSON: fall through to the text scanners
            }
        }

        // 2) XML
        if (head.startsWith("<")) {
            val xml = scanXml(text)
            if (xml.isNotEmpty()) return xml
        }

        // 3) Source code: only `name: true` / `name = true` with a readable name
        if (CodeScanner.looksLikeCode(text)) return scanDelimited(text, codeMode = true)

        // 4) Flat "Key True Key2 5" dumps
        FlatScanner.scanBooleans(text)?.let { if (it.isNotEmpty()) return it }

        // 5) key: value, key=value, "key value"
        return scanDelimited(text, codeMode = false) + scanSpaced(text)
    }

    // ---------------------------------------------------------------- key: true / key=true

    private fun scanDelimited(text: String, codeMode: Boolean): List<RawNumber> {
        val out = ArrayList<RawNumber>()
        for (m in DELIM.findAll(text)) {
            val keyGroup = m.groups[1] ?: continue
            val valueGroup = m.groups[2] ?: continue
            var label = keyGroup.value
            if (codeMode) {
                if (!CodeScanner.plausibleName(label)) continue
                if (!codeContextOk(text, keyGroup.range.first)) continue
                label = label.removePrefix("this.")
            }
            out.add(RawNumber(label, "General", valueGroup.range.first, valueGroup.range.last + 1))
            // Source code must never flood the True/False list.
            if (out.size >= if (codeMode) CODE_MAX_ENTRIES else MAX_ENTRIES) break
        }
        return out
    }

    /** In code, the name must start a property / statement (after { , ; } or var/let/const). */
    private fun codeContextOk(text: String, nameStart: Int): Boolean {
        var k = nameStart - 1
        while (k >= 0 && (text[k] == '"' || text[k] == '\'' || text[k].isWhitespace())) k--
        if (k < 0) return true
        val c = text[k]
        if (c == '{' || c == ',' || c == ';' || c == '}') return true
        var b = k
        while (b >= 0 && text[b].isLetter()) b--
        val word = text.substring(b + 1, k + 1)
        return word == "var" || word == "let" || word == "const"
    }

    // ---------------------------------------------------------------- "HasRated False"

    /** A key word directly followed by True/False on the same line. */
    private fun scanSpaced(text: String): List<RawNumber> {
        val out = ArrayList<RawNumber>()
        val n = text.length
        var i = 0
        var prevS = -1
        var prevE = -1
        var newLine = false
        while (i < n) {
            val c = text[i]
            if (c.isWhitespace() || c == '\uFEFF') {
                if (c == '\n' || c == '\r') newLine = true
                i++
                continue
            }
            val s = i
            while (i < n && !text[i].isWhitespace()) i++
            var e = i
            while (e > s && text[e - 1] in ",;)}]\"'") e--

            if (e > s && prevS >= 0 && !newLine && BOOL_WORD.matches(text.substring(s, e))) {
                val key = text.substring(prevS, prevE)
                if (key.length >= 3 && KEY_TOKEN.matches(key) && !BOOL_WORD.matches(key) &&
                    key.lowercase() !in STOP_WORDS
                ) {
                    out.add(RawNumber(key, "General", s, e))
                }
            }
            prevS = s
            prevE = i
            newLine = false
            if (out.size >= MAX_ENTRIES) break
        }
        return out
    }

    // ---------------------------------------------------------------- XML

    private fun scanXml(text: String): List<RawNumber> {
        val out = ArrayList<RawNumber>()
        val stack = ArrayList<String>()
        for (tok in XML_TOKEN.findAll(text)) {
            val name = tok.groups[2]?.value ?: continue
            if (tok.groups[1]?.value == "/") {
                if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                continue
            }
            val selfClosing = tok.groups[4]?.value == "/"
            val attrsGroup = tok.groups[3]
            val attrsText = attrsGroup?.value.orEmpty()
            val attrsStart = attrsGroup?.range?.first ?: 0
            val path = (stack + name).joinToString(".")

            val attrs = XML_ATTR.findAll(attrsText).toList()
            val nameValue = attrs.firstOrNull { m ->
                val v = attrValue(m)
                m.groupValues[1].lowercase() in NAME_ATTRS && v.isNotBlank() &&
                    !BOOL_WORD.matches(v) && !ScanUtil.PURE_NUMBER.matches(v)
            }?.let { attrValue(it) }

            for (m in attrs) {
                val key = m.groupValues[1]
                val valueGroup = m.groups[2] ?: m.groups[3] ?: continue
                if (!BOOL_WORD.matches(valueGroup.value)) continue
                val label = when {
                    nameValue != null && key.lowercase() in VALUE_ATTRS -> nameValue
                    nameValue != null -> "$nameValue.$key"
                    else -> "$path.$key"
                }
                val start = attrsStart + valueGroup.range.first
                out.add(RawNumber(label, "General", start, start + valueGroup.value.length))
            }

            if (!selfClosing) {
                stack.add(name)
                val contentStart = tok.range.last + 1
                val next = text.indexOf('<', contentStart)
                if (next > contentStart) {
                    val segment = text.substring(contentStart, next)
                    val trimmed = segment.trim()
                    if (BOOL_WORD.matches(trimmed)) {
                        val start = contentStart + segment.indexOf(trimmed)
                        out.add(RawNumber(nameValue ?: path, "General", start, start + trimmed.length))
                    }
                }
            }
            if (out.size >= MAX_ENTRIES) break
        }
        return out
    }

    private fun attrValue(m: MatchResult): String = m.groups[2]?.value ?: m.groups[3]?.value.orEmpty()
}
