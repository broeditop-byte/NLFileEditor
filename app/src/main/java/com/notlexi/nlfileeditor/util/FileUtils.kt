package com.notlexi.nlfileeditor.util

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.IOException

class FileTooLargeException : IOException("File is too large")

/** Android file helpers (names, reading, writing). */
object FileUtils {
    const val MAX_BYTES = 30 * 1024 * 1024 // 30 MB

    fun displayName(cr: ContentResolver, uri: Uri): String {
        try {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx)?.let { if (it.isNotBlank()) return it }
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    @Throws(IOException::class)
    fun readBytes(cr: ContentResolver, uri: Uri): ByteArray {
        val input = cr.openInputStream(uri) ?: throw IOException("Cannot open file")
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) throw FileTooLargeException()
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    @Throws(IOException::class)
    fun writeBytes(cr: ContentResolver, uri: Uri, bytes: ByteArray) {
        val output = cr.openOutputStream(uri, "wt") ?: throw IOException("Cannot write file")
        output.use { it.write(bytes); it.flush() }
    }

    /** "save.json" -> "save_edited.json", "notes" -> "notes_edited". */
    fun editedName(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) + "_edited" + name.substring(dot) else name + "_edited"
    }
}
