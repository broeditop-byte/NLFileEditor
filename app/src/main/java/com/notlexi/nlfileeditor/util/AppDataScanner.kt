package com.notlexi.nlfileeditor.util

import android.content.ContentResolver
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import com.notlexi.nlfileeditor.model.AppDataCandidate
import com.notlexi.nlfileeditor.parser.QuickScan

/**
 * Looks through a folder the user picked with the system folder picker (Storage Access Framework)
 * and finds the files that really hold editable save data.
 *
 * Two passes:
 *  1. The whole folder is walked and every file gets a filename score (save, progress, profile,
 *     player, prefs, .dat/.sav, no extension under shared_prefs/files ...). This only decides the
 *     ORDER in which files are opened and which ones are skipped when there are too many.
 *  2. The top files are opened and a prefix of their content is run through the same detection the
 *     editor uses ([QuickScan]). A file is only offered when it holds at least [MIN_VALUES] real
 *     numbers / True/False values; the number of values found is shown next to it. That content
 *     check, not the name, decides what is shown and recommended.
 *
 * Only [DocumentsContract] queries are used, so no storage permission is needed. Depth, number of
 * entries and time are limited, so a huge folder can never freeze the app.
 */
object AppDataScanner {

    sealed interface Result {
        /** Best candidates first. */
        class Found(val candidates: List<AppDataCandidate>) : Result

        /** The system refused access, or the folder came back empty. */
        data object Inaccessible : Result

        /** The folder could be read, but no file in it holds editable values. */
        data object NoEditableData : Result
    }

    /** A file needs at least this many detected values to be listed. */
    const val MIN_VALUES = 3

    private const val MAX_DEPTH = 8
    private const val MAX_NODES = 6_000
    private const val WALK_MILLIS = 10_000L
    private const val TOTAL_MILLIS = 25_000L
    private const val PEEK_BYTES = 64 * 1024
    private const val PEEK_LIMIT = 150
    private const val MAX_LISTED = 40

    private val SKIP_DIRS = setOf(
        "cache", "code_cache", "app_webview", "webview", "app_textures", "unitycache", "il2cpp",
        "shader_cache", "no_backup"
    )

    /** Pictures, audio, video, archives, fonts, code and databases are never save files we can edit. */
    private val BAD_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "svg", "mp3", "ogg", "wav", "m4a", "mp4",
        "mkv", "webm", "apk", "dex", "so", "jar", "zip", "gz", "rar", "7z", "obb", "ttf", "otf",
        "woff", "bundle", "assetbundle", "unity3d", "pdf", "css", "js", "html", "htm", "log", "tmp",
        "lock", "db", "sqlite", "sqlite3", "db-journal", "db-wal", "db-shm"
    )

    private val NAME_HINTS = listOf(
        "save" to 40, "progress" to 40, "gamedata" to 40, "game_data" to 40, "player" to 35,
        "profile" to 30, "userdata" to 30, "user_data" to 30, "prefs" to 25, "preference" to 25,
        "inventory" to 20, "user" to 15, "slot" to 15, "stats" to 10, "state" to 10, "data" to 10,
        "game" to 10, "settings" to 5, "config" to 5
    )

    private val EXTENSION_POINTS = mapOf(
        "sav" to 30, "save" to 30, "json" to 20, "dat" to 20, "xml" to 15, "plist" to 15,
        "bytes" to 10, "ini" to 10, "cfg" to 10, "txt" to 8, "bak" to 5, "" to 10
    )

    /** Ad / analytics / crash SDKs keep many numbers too (timestamps, counters), but they are not saves. */
    private val SDK_WORDS = listOf(
        "google", "firebase", "facebook", "crashlytics", "appsflyer", "adjust", "applovin", "admob",
        "mobileads", "onesignal", "gms", "analytics", "ironsource", "vungle", "chartboost", "flurry",
        "webview", "cookie", "okhttp", "tapjoy", "mintegral", "pangle", "bytedance", "unityads"
    )

    private class Node(val docId: String, val name: String, val isDir: Boolean, val size: Long)
    private class Dir(val docId: String, val path: String, val depth: Int)
    private class Found(val docId: String, val name: String, val folder: String, val size: Long, val score: Int)

    /**
     * [onProgress] is called with (files checked, files to check) while the contents are examined,
     * so the UI can show that something is happening. It is called from the scanning thread.
     */
    fun scan(
        cr: ContentResolver,
        tree: Uri,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Result {
        val started = SystemClock.elapsedRealtime()
        val rootId = try {
            DocumentsContract.getTreeDocumentId(tree)
        } catch (e: Exception) {
            return Result.Inaccessible
        }

        val rootChildren = try {
            list(cr, tree, rootId)
        } catch (e: Exception) { // SecurityException, IllegalArgumentException, ...
            null
        }
        if (rootChildren.isNullOrEmpty()) return Result.Inaccessible

        // ---------------------------------------------------------------- pass 1: walk + name score
        val files = ArrayList<Found>()
        val queue = ArrayDeque<Dir>()
        var visited = 0

        fun handle(children: List<Node>, dir: Dir) {
            for (child in children) {
                visited++
                if (child.isDir) {
                    if (dir.depth < MAX_DEPTH && child.name.lowercase() !in SKIP_DIRS) {
                        queue.addLast(Dir(child.docId, dir.path + child.name + "/", dir.depth + 1))
                    }
                } else if (isUsable(child)) {
                    files.add(Found(child.docId, child.name, dir.path, child.size, score(child.name, dir.path, child.size)))
                }
            }
        }

        handle(rootChildren, Dir(rootId, "", 0))
        while (queue.isNotEmpty() && visited < MAX_NODES && SystemClock.elapsedRealtime() - started < WALK_MILLIS) {
            val dir = queue.removeFirst()
            val children = (try { list(cr, tree, dir.docId) } catch (e: Exception) { null }) ?: continue
            handle(children, dir)
        }
        if (files.isEmpty()) return Result.NoEditableData

        // ---------------------------------------------------------------- pass 2: look inside
        // Best-named files are opened first; the cap only matters for folders with a lot of files.
        val order = files.sortedWith(compareByDescending<Found> { it.score }.thenBy { it.size }).take(PEEK_LIMIT)

        val found = ArrayList<AppDataCandidate>()
        for ((index, f) in order.withIndex()) {
            if (SystemClock.elapsedRealtime() - started > TOTAL_MILLIS) break
            onProgress(index, order.size)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, f.docId)
            val peek = peek(cr, docUri) ?: continue
            if (peek.count < MIN_VALUES) continue
            val rank = rank(f, peek.count)
            found.add(
                AppDataCandidate(
                    uriString = docUri.toString(),
                    name = f.name,
                    folder = f.folder,
                    size = f.size,
                    valueCount = peek.count,
                    truncated = peek.truncated,
                    score = rank,
                    recommended = false
                )
            )
        }
        onProgress(order.size, order.size)
        if (found.isEmpty()) return Result.NoEditableData

        val ranked = found
            .sortedWith(compareByDescending<AppDataCandidate> { it.score }.thenByDescending { it.valueCount })
            .take(MAX_LISTED)
        val best = ranked[0]
        val second = ranked.getOrNull(1)
        val clearWinner = second == null || best.score - second.score >= 10
        val result = if (clearWinner) listOf(best.copy(recommended = true)) + ranked.drop(1) else ranked
        return Result.Found(result)
    }

    // ---------------------------------------------------------------------------------- helpers

    private class Peek(val count: Int, val truncated: Boolean)

    /** Prefix of a file plus whether the file goes on after it. */
    private class Prefix(val bytes: ByteArray, val truncated: Boolean)

    private fun readPrefix(cr: ContentResolver, uri: Uri): Prefix? {
        val stream = cr.openInputStream(uri) ?: return null
        stream.use { input ->
            val buf = ByteArray(PEEK_BYTES)
            var total = 0
            while (total < buf.size) {
                val n = input.read(buf, total, buf.size - total)
                if (n < 0) break
                total += n
            }
            val more = total == buf.size && input.read() >= 0
            return Prefix(buf.copyOf(total), more)
        }
    }

    /**
     * Reads the start of a file and returns how many editable values the editor would find in it,
     * or null when the file is unreadable, binary or garbled.
     */
    private fun peek(cr: ContentResolver, uri: Uri): Peek? {
        return try {
            val prefix = readPrefix(cr, uri) ?: return null
            var bytes = prefix.bytes
            if (bytes.isEmpty() || TextCodec.looksBinary(bytes)) return null
            if (prefix.truncated) bytes = dropIncompleteUtf8Tail(bytes)

            val decoded = TextCodec.decode(bytes)
            if (TextCodec.looksGarbled(decoded)) return null

            val text = if (prefix.truncated) QuickScan.trimPartialTail(decoded.text) else decoded.text
            val summary = QuickScan.summarize(text)
            if (summary.isCode) return null
            Peek(summary.total, prefix.truncated)
        } catch (e: Exception) {
            null
        }
    }

    /** A cut-off multi-byte UTF-8 character at the end would make the whole prefix look invalid. */
    private fun dropIncompleteUtf8Tail(bytes: ByteArray): ByteArray {
        var end = bytes.size
        var back = 0
        while (end > 0 && back < 4 && (bytes[end - 1].toInt() and 0xC0) == 0x80) { end--; back++ }
        if (end > 0 && (bytes[end - 1].toInt() and 0xC0) == 0xC0) end--
        return if (end == bytes.size) bytes else bytes.copyOf(end)
    }

    private fun list(cr: ContentResolver, tree: Uri, parentDocId: String): List<Node>? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val cursor = cr.query(childrenUri, projection, null, null, null) ?: return null
        val out = ArrayList<Node>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2)
                val size = if (c.isNull(3)) -1L else c.getLong(3)
                out.add(Node(id, name, mime == DocumentsContract.Document.MIME_TYPE_DIR, size))
            }
        }
        return out
    }

    private fun isUsable(node: Node): Boolean {
        if (node.size == 0L || node.size > FileUtils.MAX_BYTES) return false
        val lower = node.name.lowercase()
        val ext = if (lower.contains('.')) lower.substringAfterLast('.') else ""
        return ext !in BAD_EXTENSIONS
    }

    /** Filename-based hint: higher = more likely to be a save/progress file. Only used for ordering. */
    internal fun score(name: String, folder: String, size: Long): Int {
        val lower = name.lowercase()
        val hasExt = lower.contains('.') && !lower.startsWith(".")
        val ext = if (hasExt) lower.substringAfterLast('.') else ""
        val baseName = if (hasExt) lower.substringBeforeLast('.') else lower

        var nameScore = 0
        for ((word, points) in NAME_HINTS) if (baseName.contains(word)) nameScore += points
        var s = minOf(nameScore, 70)
        s += EXTENSION_POINTS[ext] ?: 0

        val segments = folder.lowercase().split('/').filter { it.isNotEmpty() }
        val inPrefs = "shared_prefs" in segments
        if (inPrefs) s += 20
        if ("files" in segments) s += 10
        if (segments.any { it.contains("save") }) s += 15
        if (ext.isEmpty() && (inPrefs || "files" in segments)) s += 15

        if (size in 1L..2_000_000L) s += 10
        if (size > 5_000_000) s -= 10
        return s
    }

    /**
     * Final ranking once the content is known: the name hint plus the number of values really
     * found (capped, so a huge config can't beat a clearly named save file), minus a penalty for
     * ad / analytics SDK files.
     */
    private fun rank(f: Found, valueCount: Int): Int {
        var r = f.score + minOf(valueCount, 60) * 2
        val lower = (f.folder + f.name).lowercase()
        if (SDK_WORDS.any { lower.contains(it) }) r -= 60
        return r
    }
}

/** "532 B", "14.2 KB", "1.3 MB". */
fun formatFileSize(bytes: Long): String = when {
    bytes < 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
