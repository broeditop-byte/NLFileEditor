package com.notlexi.nlfileeditor.search

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.InputStream

class SafNode(
    val docId: String,
    val uri: Uri,
    override val path: String,
    override val name: String,
    override val isDir: Boolean,
    override val size: Long,
    override val lastModified: Long
) : FileNode

/**
 * [FileSource] over a folder the user granted through the Storage Access Framework. Only
 * [DocumentsContract] queries are used (no storage permission, no raw paths), so it reads exactly
 * what Android allows the app to see: a folder Android refuses to share simply returns null here.
 */
class SafFileSource(private val cr: ContentResolver, private val tree: Uri) : FileSource {
    private val rootId: String? = try { DocumentsContract.getTreeDocumentId(tree) } catch (e: Exception) { null }

    private val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    )

    override fun children(dir: FileNode?): List<FileNode>? {
        val parentId = (dir as? SafNode)?.docId ?: rootId ?: return null
        val prefix = dir?.path ?: ""
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        return try {
            val out = ArrayList<FileNode>()
            val cursor = cr.query(uri, projection, null, null, null) ?: return null
            cursor.use { c ->
                while (c.moveToNext()) {
                    val node = readRow(c, prefix) ?: continue
                    out.add(node)
                }
            }
            out
        } catch (e: Exception) { // SecurityException, IllegalArgumentException, provider crashes ...
            null
        }
    }

    private fun readRow(c: android.database.Cursor, prefix: String): SafNode? {
        val id = c.getString(0) ?: return null
        val name = c.getString(1) ?: return null
        val mime = c.getString(2)
        val size = if (c.isNull(3)) -1L else c.getLong(3)
        val modified = if (c.isNull(4)) 0L else c.getLong(4)
        return SafNode(
            docId = id,
            uri = DocumentsContract.buildDocumentUriUsingTree(tree, id),
            path = if (prefix.isEmpty()) name else "$prefix/$name",
            name = name,
            isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR,
            size = size,
            lastModified = modified
        )
    }

    override fun open(node: FileNode): InputStream? = try {
        cr.openInputStream((node as SafNode).uri)
    } catch (e: Exception) {
        null
    }

    override fun stat(node: FileNode): FileNode? {
        val n = node as SafNode
        return try {
            cr.query(n.uri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) readRow(c, n.path.substringBeforeLast('/', "")) else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
