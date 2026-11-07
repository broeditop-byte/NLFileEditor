package com.notlexi.nlfileeditor.search

import java.io.File
import java.io.InputStream

/** One file or folder in a searchable tree. [path] is relative to the tree root ("saves/slot1.json"). */
interface FileNode {
    val path: String
    val name: String
    val isDir: Boolean
    val size: Long
    val lastModified: Long
}

/**
 * Where the engines read from. The app uses a Storage Access Framework tree ([SafFileSource]);
 * [JavaFileSource] reads a normal directory (app-private storage, and the unit tests).
 * Keeping the engines independent of Android makes them testable on a plain JVM.
 */
interface FileSource {
    /** Children of [dir], or of the root when [dir] is null. Null = the folder could not be read. */
    fun children(dir: FileNode?): List<FileNode>?

    /** A fresh stream (nothing cached), or null when the file cannot be opened. */
    fun open(node: FileNode): InputStream?

    /** Current metadata of [node], or null when it no longer exists (used to spot stale results). */
    fun stat(node: FileNode): FileNode?
}

class JavaFileNode(val file: File, override val path: String) : FileNode {
    // Metadata is captured when the node is created, so a node stays a faithful record of what was found.
    override val name: String = file.name
    override val isDir: Boolean = file.isDirectory
    override val size: Long = if (file.isFile) file.length() else 0L
    override val lastModified: Long = file.lastModified()
}

class JavaFileSource(private val root: File) : FileSource {
    override fun children(dir: FileNode?): List<FileNode>? {
        val base = if (dir == null) root else (dir as JavaFileNode).file
        val list = base.listFiles() ?: return null
        val prefix = (dir?.path ?: "")
        return list.map { JavaFileNode(it, if (prefix.isEmpty()) it.name else prefix + "/" + it.name) }
    }

    override fun open(node: FileNode): InputStream? =
        try { (node as JavaFileNode).file.inputStream() } catch (e: java.io.IOException) { null }

    override fun stat(node: FileNode): FileNode? {
        val f = (node as JavaFileNode).file
        return if (f.exists()) JavaFileNode(f, node.path) else null
    }
}
