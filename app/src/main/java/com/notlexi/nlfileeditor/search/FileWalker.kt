package com.notlexi.nlfileeditor.search

import com.notlexi.nlfileeditor.util.TextCodec
import java.io.ByteArrayOutputStream

/** Shared directory walk + safe text reading for the search and change engines. */
object FileWalker {
    const val MAX_DEPTH = 14
    const val MAX_FILES = 150_000
    const val MAX_TEXT_BYTES = 8 * 1024 * 1024

    /** Folders that only hold caches / engine data and make a scan slow without ever holding a save. */
    val SKIP_DIRS = setOf(
        "cache", "code_cache", "app_webview", "webview", "app_textures", "unitycache", "shader_cache",
        "no_backup", ".git", "node_modules"
    )

    class Walk(var folders: Int = 0, var files: Int = 0, var truncated: Boolean = false, var unreadableFolders: Int = 0)

    /**
     * Breadth-first walk. [visit] is called for every file and returns false to stop everything.
     * Stops quietly when [cancelled] turns true.
     */
    fun walk(
        source: FileSource,
        cancelled: () -> Boolean,
        visit: (FileNode) -> Boolean
    ): Walk {
        val stats = Walk()
        val queue = ArrayDeque<Pair<FileNode?, Int>>()
        queue.addLast(null to 0)
        while (queue.isNotEmpty()) {
            if (cancelled()) return stats
            val (dir, depth) = queue.removeFirst()
            val kids = source.children(dir)
            if (kids == null) {
                stats.unreadableFolders++
                continue
            }
            stats.folders++
            for (k in kids.sortedBy { it.name.lowercase() }) {
                if (cancelled()) return stats
                if (k.isDir) {
                    if (depth < MAX_DEPTH && k.name.lowercase() !in SKIP_DIRS) queue.addLast(k to depth + 1)
                } else {
                    stats.files++
                    if (stats.files > MAX_FILES) {
                        stats.truncated = true
                        return stats
                    }
                    if (!visit(k)) return stats
                }
            }
        }
        return stats
    }

    sealed interface TextRead {
        class Ok(val text: String, val charsetName: String) : TextRead
        data object Binary : TextRead
        data object TooLarge : TextRead
        data object Unreadable : TextRead
    }

    /** Reads a whole file as text, refusing binary / garbled / oversized content (never guesses). */
    fun readText(source: FileSource, node: FileNode, maxBytes: Int = MAX_TEXT_BYTES): TextRead {
        if (node.size > maxBytes) return TextRead.TooLarge
        val bytes = readBytes(source, node, maxBytes) ?: return TextRead.Unreadable
        if (bytes === TOO_BIG) return TextRead.TooLarge
        return decodeText(bytes)
    }

    /** Text-or-binary decision for bytes that are already in memory. */
    fun decodeText(bytes: ByteArray): TextRead {
        if (bytes.size > MAX_TEXT_BYTES) return TextRead.TooLarge
        if (bytes.isEmpty()) return TextRead.Binary
        if (TextCodec.looksBinary(bytes)) return TextRead.Binary
        val decoded = TextCodec.decode(bytes)
        if (TextCodec.looksGarbled(decoded)) return TextRead.Binary
        return TextRead.Ok(decoded.text, decoded.charset.name())
    }

    private val TOO_BIG = ByteArray(0)

    /** Reads up to [maxBytes]; returns [TOO_BIG] if the stream is longer, null if it cannot be opened. */
    fun readBytes(source: FileSource, node: FileNode, maxBytes: Int): ByteArray? {
        val input = try { source.open(node) } catch (e: Exception) { null } ?: return null
        return try {
            input.use { s ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) return TOO_BIG
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Maps a character offset to a 1-based line number and the text of that line. */
    class LineIndex(private val text: String) {
        private val starts: IntArray = run {
            var n = 1
            for (c in text) if (c == '\n') n++
            val a = IntArray(n)
            var i = 1
            for ((idx, c) in text.withIndex()) if (c == '\n') a[i++] = idx + 1
            a
        }

        fun lineOf(offset: Int): Int {
            var lo = 0
            var hi = starts.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (starts[mid] <= offset) lo = mid else hi = mid - 1
            }
            return lo + 1
        }

        fun lineText(line: Int): String {
            val s = starts[line - 1]
            var e = if (line < starts.size) starts[line] - 1 else text.length
            if (e > s && text[e - 1] == '\r') e--
            return text.substring(s, maxOf(s, e))
        }

        val lineCount: Int get() = starts.size
    }

    /** A short single-line preview around [offset] (never more than ~[width] characters). */
    fun snippet(lineText: String, column: Int, matchLen: Int, width: Int = 140): String {
        if (lineText.length <= width) return lineText.trim()
        val from = (column - width / 3).coerceIn(0, lineText.length - 1)
        val to = minOf(lineText.length, from + width)
        return (if (from > 0) "…" else "") + lineText.substring(from, to).trim() + (if (to < lineText.length) "…" else "")
    }
}
