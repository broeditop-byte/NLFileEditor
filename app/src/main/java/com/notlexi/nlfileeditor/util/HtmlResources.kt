package com.notlexi.nlfileeditor.util

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import android.webkit.WebResourceResponse
import java.io.FileNotFoundException

/**
 * Serves the files next to an opened HTML page to the WebView.
 *
 * Android's file picker hands the app ONE document, not its folder, so a page that says
 * `<img src="logo.png">` or `<link href="css/site.css">` cannot find its siblings by itself (that was
 * the "logos, icons and images are missing" bug). The user grants the page's folder once
 * (Storage Access Framework, ACTION_OPEN_DOCUMENT_TREE); this class then answers the WebView's
 * relative-URL requests from that folder. Nothing outside the granted folder is reachable, `..`
 * segments are rejected, and the WebView itself keeps allowFileAccess = false.
 *
 * Thread-safe: WebView calls shouldInterceptRequest from several worker threads.
 */
class DocTreeResolver(private val cr: ContentResolver, private val tree: Uri) {

    private class Child(val docId: String, val isDir: Boolean, val mime: String?)

    private val rootId: String? = try {
        DocumentsContract.getTreeDocumentId(tree)
    } catch (e: Exception) {
        null
    }

    /** docId -> (display name -> child). Each folder is listed at most once. */
    private val dirCache = HashMap<String, Map<String, Child>>()

    /** Opens [path] ("css/site.css", leading slash optional) or returns null when it does not exist. */
    fun open(path: String): WebResourceResponse? {
        val segments = splitPath(path) ?: return null
        var docId = rootId ?: return null
        var child: Child? = null
        for ((i, seg) in segments.withIndex()) {
            val children = list(docId) ?: return null
            child = children[seg] ?: children.entries.firstOrNull { it.key.equals(seg, ignoreCase = true) }?.value
            if (child == null) return null
            // Every segment but the last must be a folder; the last one must be a file.
            if (i < segments.size - 1 && !child.isDir) return null
            docId = child.docId
        }
        val file = child ?: return null
        if (file.isDir) return null
        val name = segments.last()
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, file.docId)
        val stream = try {
            cr.openInputStream(uri)
        } catch (e: FileNotFoundException) {
            null
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } ?: return null
        val mime = mimeFor(name, file.mime)
        val encoding = if (mime.startsWith("text/") || mime == "application/javascript" || mime == "image/svg+xml") "UTF-8" else null
        return WebResourceResponse(mime, encoding, 200, "OK", mapOf("Cache-Control" to "no-cache"), stream)
    }

    private fun list(parentDocId: String): Map<String, Child>? {
        synchronized(dirCache) { dirCache[parentDocId]?.let { return it } }
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val map = HashMap<String, Child>()
        try {
            cr.query(uri, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    map[name] = Child(id, mime == DocumentsContract.Document.MIME_TYPE_DIR, mime)
                }
            } ?: return null
        } catch (e: Exception) {
            return null
        }
        synchronized(dirCache) { dirCache[parentDocId] = map }
        return map
    }

    companion object {
        /** Virtual origin the page is loaded from; requests to it are answered from the granted folder. */
        const val HOST = "nl-local.invalid"
        const val BASE_URL = "https://$HOST/"

        /** Splits "a/b//c.png" into segments; null if the path tries to leave the folder. */
        fun splitPath(path: String): List<String>? {
            val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
            if (parts.isEmpty() || parts.any { it == ".." || it.contains('\u0000') }) return null
            return parts
        }

        private val EXTRA_MIME = mapOf(
            "js" to "application/javascript", "mjs" to "application/javascript",
            "css" to "text/css", "html" to "text/html", "htm" to "text/html",
            "svg" to "image/svg+xml", "json" to "application/json",
            "woff" to "font/woff", "woff2" to "font/woff2", "ttf" to "font/ttf", "otf" to "font/otf",
            "ico" to "image/x-icon", "webp" to "image/webp", "avif" to "image/avif",
            "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
            "mp4" to "video/mp4", "webm" to "video/webm", "mp3" to "audio/mpeg", "ogg" to "audio/ogg",
            "wasm" to "application/wasm", "txt" to "text/plain", "xml" to "application/xml"
        )

        fun mimeFor(name: String, providerMime: String?): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            EXTRA_MIME[ext]?.let { return it }
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
            return providerMime?.takeIf { it != "application/octet-stream" } ?: "application/octet-stream"
        }

        private val RELATIVE_REF = Regex(
            """(?:src|href|poster|data-src)\s*=\s*["'](?!https?:|data:|//|#|javascript:|mailto:|tel:|about:|blob:)[^"']+["']|url\(\s*["']?(?!https?:|data:|//|#)[^)"']+""",
            RegexOption.IGNORE_CASE
        )

        /** True when the page refers to files by relative path (so the user should grant its folder). */
        fun usesRelativeResources(html: String): Boolean = RELATIVE_REF.containsMatchIn(html)
    }
}
