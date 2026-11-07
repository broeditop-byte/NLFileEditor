package com.notlexi.nlfileeditor.search

import com.notlexi.nlfileeditor.parser.NumberParser
import java.math.BigDecimal
import java.util.zip.CRC32

/** A named value found in a text file (a number or a True/False flag) at the time of a snapshot. */
data class ValueState(val key: String, val text: String, val offset: Int)

/** What one file looked like at snapshot time. */
class FileState(
    val node: FileNode,
    val path: String,
    val size: Long,
    val modified: Long,
    /** Parsed named values (null for files that are not readable text, e.g. binary). */
    val values: List<ValueState>?,
    /** CRC32 of the content (files up to [ChangeDetector.HASH_LIMIT]); null when too large to hash. */
    val crc: Long?,
    /** Raw bytes kept for small binary files so changed offsets can be shown. */
    val bytes: ByteArray?
)

class Snapshot(val takenAt: Long, val files: Map<String, FileState>, val filesSeen: Int, val unsupported: Int)

enum class ChangeKind { ADDED, REMOVED, MODIFIED }

data class ValueChange(val key: String, val before: String?, val after: String?, val numericDelta: BigDecimal?)

/** A changed region of a small binary file, read as little-endian 32-bit integers (both views are shown, no guessing). */
data class BinaryChange(val offset: Int, val before: Int, val after: Int)

class FileChange(
    val node: FileNode?,
    val path: String,
    val kind: ChangeKind,
    val sizeBefore: Long?,
    val sizeAfter: Long?,
    val valueChanges: List<ValueChange>,
    val binaryChanges: List<BinaryChange>,
    /** True when content changed but no value-level detail is available (unsupported / encrypted / large). */
    val opaque: Boolean
)

class ChangeReport(val changes: List<FileChange>, val before: Snapshot, val after: Snapshot) {
    val valueChangeCount: Int get() = changes.sumOf { it.valueChanges.size }
}

/**
 * Snapshots the readable files of a folder and compares two snapshots.
 *
 * What it can and cannot do (shown to the user too):
 *  - Text formats the editor understands (JSON, XML, key=value, INI, flat dumps): exact named values are
 *    compared, so "player.coins 120 -> 110" is reported with the file and character position.
 *  - Small binary files: changed 4-byte positions are listed with their integer values. What they mean is not guessed.
 *  - Encrypted / compressed / server-side / in-memory data cannot be interpreted; such files are only reported
 *    as "changed" (size / checksum), or not at all if the game never writes them.
 */
object ChangeDetector {
    const val HASH_LIMIT = 32 * 1024 * 1024
    const val BINARY_KEEP_LIMIT = 1 * 1024 * 1024
    const val MAX_BINARY_CHANGES = 200

    fun capture(
        source: FileSource,
        cancelled: () -> Boolean = { false },
        onProgress: (seen: Int, current: String) -> Unit = { _, _ -> }
    ): Snapshot {
        val files = LinkedHashMap<String, FileState>()
        var unsupported = 0
        var seen = 0
        FileWalker.walk(source, cancelled) { node ->
            seen++
            if (seen % 8 == 0) onProgress(seen, node.path)
            if (isNeverASave(node.name)) return@walk true
            val state = stateOf(source, node)
            if (state == null) unsupported++ else files[node.path] = state
            true
        }
        onProgress(seen, "")
        return Snapshot(System.currentTimeMillis(), files, seen, unsupported)
    }

    private fun isNeverASave(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("png", "jpg", "jpeg", "webp", "gif", "mp3", "ogg", "wav", "mp4", "ttf", "otf", "so", "dex", "apk", "obb")
    }

    private fun stateOf(source: FileSource, node: FileNode): FileState? {
        val limit = if (node.size <= FileWalker.MAX_TEXT_BYTES) FileWalker.MAX_TEXT_BYTES else HASH_LIMIT
        val bytes = FileWalker.readBytes(source, node, limit) ?: return null
        if (bytes.size == 0 && node.size > 0) return FileState(node, node.path, node.size, node.lastModified, null, null, null)
        val crc = CRC32().also { it.update(bytes) }.value
        val read = FileWalker.decodeText(bytes)
        if (read is FileWalker.TextRead.Ok) {
            val values = extractValues(read.text)
            return FileState(node, node.path, node.size, node.lastModified, values, crc, null)
        }
        val keep = if (node.size in 1..BINARY_KEEP_LIMIT.toLong()) bytes else null
        return FileState(node, node.path, node.size, node.lastModified, null, crc, keep)
    }

    /** Named numbers and True/False flags, keyed so repeated names stay distinguishable ("coins", "coins#2"). */
    fun extractValues(text: String): List<ValueState> {
        val out = ArrayList<ValueState>()
        val seenKeys = HashMap<String, Int>()
        fun keyFor(label: String): String {
            val n = (seenKeys[label] ?: 0) + 1
            seenKeys[label] = n
            return if (n == 1) label else "$label#$n"
        }
        val parsed = try { NumberParser.parse(text).entries } catch (e: Exception) { emptyList() }
        for (e in parsed) out.add(ValueState(keyFor(e.label), e.original, e.start))
        val bools = try { NumberParser.parseBooleans(text) } catch (e: Exception) { emptyList() }
        for (b in bools) out.add(ValueState(keyFor("[flag] " + b.label), b.original, b.start))
        return out
    }

    fun diff(before: Snapshot, after: Snapshot): ChangeReport {
        val changes = ArrayList<FileChange>()
        for ((path, now) in after.files) {
            val old = before.files[path]
            if (old == null) {
                changes.add(FileChange(now.node, path, ChangeKind.ADDED, null, now.size, emptyList(), emptyList(), now.values == null))
                continue
            }
            if (old.crc != null && old.crc == now.crc && old.size == now.size) continue // identical content
            val vc = if (old.values != null && now.values != null) diffValues(old.values, now.values) else emptyList()
            val bc = if (old.bytes != null && now.bytes != null) diffBinary(old.bytes, now.bytes) else emptyList()
            val opaque = vc.isEmpty() && bc.isEmpty()
            // Text file whose parsed values are identical (e.g. only whitespace changed): still a real change, flagged opaque.
            changes.add(FileChange(now.node, path, ChangeKind.MODIFIED, old.size, now.size, vc, bc, opaque))
        }
        for ((path, old) in before.files) {
            if (!after.files.containsKey(path)) {
                changes.add(FileChange(null, path, ChangeKind.REMOVED, old.size, null, emptyList(), emptyList(), false))
            }
        }
        changes.sortWith(compareByDescending<FileChange> { it.valueChanges.size + it.binaryChanges.size }.thenBy { it.path })
        return ChangeReport(changes, before, after)
    }

    private fun diffValues(a: List<ValueState>, b: List<ValueState>): List<ValueChange> {
        val oldMap = LinkedHashMap<String, String>()
        for (v in a) oldMap[v.key] = v.text
        val out = ArrayList<ValueChange>()
        val seen = HashSet<String>()
        for (v in b) {
            seen.add(v.key)
            val old = oldMap[v.key]
            if (old == null) out.add(ValueChange(v.key, null, v.text, null))
            else if (old != v.text) out.add(ValueChange(v.key, old, v.text, delta(old, v.text)))
        }
        for ((k, v) in oldMap) if (k !in seen) out.add(ValueChange(k, v, null, null))
        return out
    }

    private fun delta(a: String, b: String): BigDecimal? {
        val x = a.toBigDecimalOrNull() ?: return null
        val y = b.toBigDecimalOrNull() ?: return null
        return y.subtract(x)
    }

    private fun diffBinary(a: ByteArray, b: ByteArray): List<BinaryChange> {
        val out = ArrayList<BinaryChange>()
        val n = minOf(a.size, b.size)
        var i = 0
        while (i < n && out.size < MAX_BINARY_CHANGES) {
            val end = minOf(i + 4, n)
            var differs = false
            for (k in i until end) if (a[k] != b[k]) { differs = true; break }
            if (differs) out.add(BinaryChange(i, int32(a, i), int32(b, i)))
            i += 4
        }
        return out
    }

    private fun int32(x: ByteArray, at: Int): Int {
        var v = 0
        for (k in 0 until 4) {
            val idx = at + k
            val byte = if (idx < x.size) x[idx].toInt() and 0xFF else 0
            v = v or (byte shl (8 * k))
        }
        return v
    }
}
