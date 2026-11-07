package com.notlexi.nlfileeditor.search

import com.notlexi.nlfileeditor.parser.NumberParser
import java.math.BigDecimal

enum class QueryMode { NAME, TEXT, NUMBER }

data class SearchQuery(
    val text: String,
    val mode: QueryMode,
    /** NUMBER mode: also report plain numbers that are not attached to a name (whole-token matches only). */
    val includeUnlabeled: Boolean = true,
    val caseSensitive: Boolean = false
)

enum class HitKind { FILE_NAME, TEXT, NAMED_VALUE, UNLABELED_NUMBER }

/**
 * One real match. [path] is the true location inside the searched tree; [matched] is the exact text
 * that was found; [label] is the value's name for [HitKind.NAMED_VALUE] (e.g. "player.gold").
 * [offset] is the character position in the file; [node] is kept so the result can be opened,
 * inspected, and re-checked for staleness.
 */
data class SearchHit(
    val node: FileNode,
    val kind: HitKind,
    val line: Int,
    val column: Int,
    val offset: Int,
    val matched: String,
    val label: String?,
    val snippet: String
) {
    val path: String get() = node.path
    val key: String get() = "${node.path}#$offset#$kind"
}

data class SearchProgress(val folders: Int, val filesSeen: Int, val filesRead: Int, val hits: Int, val current: String)

class SearchSummary(
    val query: SearchQuery,
    val hits: List<SearchHit>,
    val filesSeen: Int,
    val filesRead: Int,
    val skippedBinary: Int,
    val skippedLarge: Int,
    val unreadable: Int,
    val unreadableFolders: Int,
    val truncated: Boolean,
    val cancelled: Boolean,
    val error: String?,
    val millis: Long
)

/**
 * Real search over a file tree.
 *  - NAME: file names, case-insensitive; `*` and `?` wildcards work.
 *  - TEXT: literal text inside text files.
 *  - NUMBER: values that EQUAL the typed number. Numbers are located with the same format-aware
 *    parser the editor uses (JSON paths, XML attributes, key=value ...), so each hit says which named
 *    value it is. Optionally plain whole-token numbers that have no name are added and tagged
 *    "unlabeled". Digits inside longer numbers (100 in 1000 or 100.5), words, hashes, dates and
 *    times never match, and every hit is verified against the file text before it is reported.
 * Binary, garbled and oversized files are skipped (and counted), never guessed at.
 */
object SearchEngine {
    const val MAX_HITS = 1_000
    const val MAX_HITS_PER_FILE = 60

    /** Lenient number input: "1,000", "1 000", " 42 ", "+5". Returns null if it is not a number. */
    fun parseNumber(input: String): BigDecimal? {
        val cleaned = input.trim().replace(",", "").replace(" ", "").replace("_", "").removePrefix("+")
        if (cleaned.isEmpty()) return null
        return cleaned.toBigDecimalOrNull()
    }

    private val TOKEN = Regex("""(?<![A-Za-z0-9_.\-])-?\d+(?:\.\d+)?(?![A-Za-z0-9_]|[-:/]\d)""")

    fun search(
        source: FileSource,
        query: SearchQuery,
        cancelled: () -> Boolean = { false },
        onProgress: (SearchProgress) -> Unit = {},
        onHit: (SearchHit) -> Unit = {}
    ): SearchSummary {
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        val text = query.text.trim()
        fun fail(msg: String) = SearchSummary(query, emptyList(), 0, 0, 0, 0, 0, 0, false, false, msg, elapsed())
        if (text.isEmpty()) return fail("Type something to search for.")

        val target: BigDecimal? = if (query.mode == QueryMode.NUMBER) parseNumber(text) else null
        if (query.mode == QueryMode.NUMBER && target == null) return fail("\"$text\" is not a number.")
        val nameRegex = if (query.mode == QueryMode.NAME) globToRegex(text, query.caseSensitive) else null

        val hits = ArrayList<SearchHit>()
        var read = 0
        var binary = 0
        var large = 0
        var unreadable = 0
        var lastReport = 0L
        var capped = false

        fun report(current: String, folders: Int, seen: Int) {
            val now = System.nanoTime()
            if (now - lastReport < 80_000_000L) return
            lastReport = now
            onProgress(SearchProgress(folders, seen, read, hits.size, current))
        }
        fun add(h: SearchHit): Boolean {
            if (hits.size >= MAX_HITS) { capped = true; return false }
            hits.add(h)
            onHit(h)
            return true
        }

        var seen = 0
        val walk = FileWalker.walk(source, cancelled) { node ->
            seen++
            report(node.path, 0, seen)
            when (query.mode) {
                QueryMode.NAME -> {
                    if (nameRegex!!.containsMatchIn(node.name)) {
                        if (!add(SearchHit(node, HitKind.FILE_NAME, 0, 0, 0, node.name, null, node.path))) return@walk false
                    }
                }
                QueryMode.TEXT, QueryMode.NUMBER -> {
                    when (val r = FileWalker.readText(source, node)) {
                        is FileWalker.TextRead.Ok -> {
                            read++
                            val cont = if (query.mode == QueryMode.TEXT) searchText(node, r.text, text, query.caseSensitive, ::add)
                            else searchNumber(node, r.text, text, target!!, query.includeUnlabeled, ::add)
                            if (!cont) return@walk false
                        }
                        FileWalker.TextRead.Binary -> binary++
                        FileWalker.TextRead.TooLarge -> large++
                        FileWalker.TextRead.Unreadable -> unreadable++
                    }
                }
            }
            true
        }
        val wasCancelled = cancelled()
        onProgress(SearchProgress(walk.folders, seen, read, hits.size, ""))
        return SearchSummary(
            query, hits, seen, read, binary, large, unreadable, walk.unreadableFolders,
            truncated = capped || walk.truncated, cancelled = wasCancelled, error = null, millis = elapsed()
        )
    }

    private fun searchText(
        node: FileNode, content: String, needle: String, caseSensitive: Boolean, add: (SearchHit) -> Boolean
    ): Boolean {
        val index = FileWalker.LineIndex(content)
        var from = 0
        var count = 0
        while (count < MAX_HITS_PER_FILE) {
            val at = content.indexOf(needle, from, ignoreCase = !caseSensitive)
            if (at < 0) break
            val line = index.lineOf(at)
            val lineText = index.lineText(line)
            val col = at - lineStart(content, at)
            val hit = SearchHit(
                node, HitKind.TEXT, line, col, at,
                content.substring(at, at + needle.length), null,
                FileWalker.snippet(lineText, col, needle.length)
            )
            if (!add(hit)) return false
            count++
            from = at + maxOf(1, needle.length)
        }
        return true
    }

    private fun searchNumber(
        node: FileNode, content: String, rawQuery: String, target: BigDecimal,
        includeUnlabeled: Boolean, add: (SearchHit) -> Boolean
    ): Boolean {
        // Cheap pre-check: the digits of the number must appear in the file at all.
        val digits = rawQuery.trim().removePrefix("+").replace(",", "").replace(" ", "").replace("_", "")
        val probe = target.stripTrailingZeros().toPlainString()
        if (!content.contains(digits) && !content.contains(probe)) return true

        val index = FileWalker.LineIndex(content)
        val claimed = HashSet<Int>()
        var count = 0
        fun emit(kind: HitKind, start: Int, end: Int, label: String?): Boolean {
            val line = index.lineOf(start)
            val col = start - lineStart(content, start)
            val h = SearchHit(node, kind, line, col, start, content.substring(start, end), label,
                FileWalker.snippet(index.lineText(line), col, end - start))
            count++
            return add(h)
        }

        val parsed = try { NumberParser.parse(content).entries } catch (e: Exception) { emptyList() }
        for (e in parsed) {
            if (count >= MAX_HITS_PER_FILE) return true
            val v = e.original.toBigDecimalOrNull() ?: continue
            if (v.compareTo(target) != 0) continue
            // Verify against the real text: the recorded range must still hold exactly this number.
            if (e.end > content.length || content.substring(e.start, e.end) != e.original) continue
            claimed.add(e.start)
            if (!emit(HitKind.NAMED_VALUE, e.start, e.end, e.label)) return false
        }
        if (includeUnlabeled) {
            for (m in TOKEN.findAll(content)) {
                if (count >= MAX_HITS_PER_FILE) break
                if (m.range.first in claimed) continue
                val v = m.value.toBigDecimalOrNull() ?: continue
                if (v.compareTo(target) != 0) continue
                if (!emit(HitKind.UNLABELED_NUMBER, m.range.first, m.range.last + 1, null)) return false
            }
        }
        return true
    }

    private fun lineStart(content: String, offset: Int): Int {
        val i = content.lastIndexOf('\n', offset - 1)
        return if (i < 0) 0 else i + 1
    }

    fun globToRegex(glob: String, caseSensitive: Boolean): Regex {
        val sb = StringBuilder()
        val hasWildcard = glob.any { it == '*' || it == '?' }
        if (!hasWildcard) sb.append(Regex.escape(glob))
        else for (c in glob) when (c) {
            '*' -> sb.append(".*")
            '?' -> sb.append('.')
            else -> sb.append(Regex.escape(c.toString()))
        }
        val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return Regex(if (hasWildcard) "^$sb$" else sb.toString(), options)
    }

    /** Lines around a hit for the "inspect" sheet: (line number, text). Null if the file can't be read as text now. */
    fun context(source: FileSource, hit: SearchHit, radius: Int = 6): List<Pair<Int, String>>? {
        val r = FileWalker.readText(source, hit.node) as? FileWalker.TextRead.Ok ?: return null
        val idx = FileWalker.LineIndex(r.text)
        val line = hit.line.coerceIn(1, idx.lineCount)
        return (maxOf(1, line - radius)..minOf(idx.lineCount, line + radius)).map { it to idx.lineText(it).take(400) }
    }

    /** True when the file changed since the hit was found, or the hit text is no longer at its place. */
    fun isStale(source: FileSource, hit: SearchHit): Boolean {
        val now = source.stat(hit.node) ?: return true
        if (now.size == hit.node.size && now.lastModified == hit.node.lastModified) return false
        if (hit.kind == HitKind.FILE_NAME) return false
        val r = FileWalker.readText(source, now) as? FileWalker.TextRead.Ok ?: return true
        return !(hit.offset + hit.matched.length <= r.text.length &&
            r.text.regionMatches(hit.offset, hit.matched, 0, hit.matched.length, ignoreCase = true))
    }
}
