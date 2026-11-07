package com.notlexi.nlfileeditor.hud

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.notlexi.nlfileeditor.util.FileUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.flow.update

class SnapshotTooLargeException(val limitBytes: Int) :
    IOException("File is larger than $limitBytes bytes")

/** A problem reaching the target file, with a message that is safe to show to the user. */
class TargetAccessException(message: String, cause: Throwable? = null) : IOException(message, cause)

class TargetInfo(
    val uri: Uri,
    val name: String,
    val size: Long,
    val lastModified: Long?,
    val canRead: Boolean,
    val canWrite: Boolean,
    val persisted: Boolean
)

/**
 * All file access of the HUD goes through here. It only touches files the user picked with the
 * Storage Access Framework (persisted URI permission). It never looks at other apps' private
 * storage or memory.
 */
class FileAccessManager(context: Context) {
    private val app = context.applicationContext
    private val cr get() = app.contentResolver
    private val prefs: SharedPreferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun savedTarget(): Uri? =
        prefs.getString(KEY_URI, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    /**
     * Remembers [uri] as the target and keeps access to it across restarts when the provider
     * allows it. [grantFlags] are the flags returned by the picker. Returns true when the
     * permission could be persisted.
     */
    fun saveTarget(uri: Uri, grantFlags: Int): Boolean {
        val old = savedTarget()
        if (old != null && old != uri) releasePersisted(old)

        val wantWrite = grantFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0
        var persisted = false
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                if (wantWrite) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
            cr.takePersistableUriPermission(uri, flags)
            persisted = true
        } catch (e: SecurityException) {
            try {
                cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                persisted = true
            } catch (_: SecurityException) {
                // Provider does not support persisting: works until the app is restarted.
            }
        }
        prefs.edit().putString(KEY_URI, uri.toString()).apply()
        HudRuntime.target.value = uri
        // Even when the URI is identical, tell the engine to drop its snapshots and start fresh.
        HudRuntime.targetEpoch.update { it + 1 }
        return persisted
    }

    /**
     * Forgets the selected file: releases its persisted permission, removes it from storage and
     * tells the engine (through [HudRuntime]) to drop Snapshot A / B and all diff results.
     */
    fun clearTarget() {
        savedTarget()?.let { releasePersisted(it) }
        prefs.edit().remove(KEY_URI).apply()
        HudRuntime.target.value = null
        HudRuntime.targetEpoch.update { it + 1 }
    }

    /** Loads the remembered target into [HudRuntime.target] if it is still reachable. */
    fun restoreTarget() {
        val uri = savedTarget()
        if (uri == null) {
            HudRuntime.target.value = null
            return
        }
        val info = runCatching { describe(uri) }.getOrNull()
        if (info == null || !info.canRead) {
            prefs.edit().remove(KEY_URI).apply()
            HudRuntime.target.value = null
        } else {
            HudRuntime.target.value = uri
        }
    }

    fun describe(uri: Uri): TargetInfo? {
        val doc = DocumentFile.fromSingleUri(app, uri) ?: return null
        if (!doc.exists()) return null
        val persisted = cr.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        return TargetInfo(
            uri = uri,
            name = doc.name ?: FileUtils.displayName(cr, uri),
            size = doc.length(),
            lastModified = doc.lastModified().takeIf { it > 0L },
            canRead = doc.canRead(),
            canWrite = doc.canWrite(),
            persisted = persisted
        )
    }

    fun lastModified(uri: Uri): Long? =
        runCatching { DocumentFile.fromSingleUri(app, uri)?.lastModified()?.takeIf { it > 0L } }.getOrNull()

    fun canWrite(uri: Uri): Boolean =
        runCatching { DocumentFile.fromSingleUri(app, uri)?.canWrite() == true }.getOrDefault(false)

    /**
     * Always opens a NEW stream on the provider, so every call returns what is on disk right now
     * (nothing is cached in this class). Provider failures are mapped to messages that are safe to
     * show, so a revoked permission or a dying provider never crashes the HUD.
     */
    @Throws(IOException::class)
    fun readBytes(uri: Uri, maxBytes: Int): ByteArray {
        try {
            val input = cr.openInputStream(uri)
                ?: throw TargetAccessException("The file could not be opened. It may have been moved or deleted.")
            input.use { stream ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw SnapshotTooLargeException(maxBytes)
                    out.write(buf, 0, n)
                }
                return out.toByteArray()
            }
        } catch (e: SecurityException) {
            throw TargetAccessException(
                "Access to this file was denied or revoked. Tap X, then select it again. " +
                    "Files inside other apps' private folders cannot be opened.", e
            )
        } catch (e: FileNotFoundException) {
            throw TargetAccessException("The file no longer exists or is not accessible.", e)
        } catch (e: IllegalArgumentException) {
            throw TargetAccessException("The file provider rejected this file. Select it again.", e)
        } catch (e: UnsupportedOperationException) {
            throw TargetAccessException("The file provider does not allow reading this file.", e)
        } catch (e: IllegalStateException) {
            throw TargetAccessException("The file provider stopped responding. Try again.", e)
        }
    }

    @Throws(IOException::class)
    fun writeBytes(uri: Uri, bytes: ByteArray) {
        if (!canWrite(uri)) {
            throw TargetAccessException("This file is read-only for the app. Select it again and allow write access.")
        }
        try {
            val output = cr.openOutputStream(uri, "wt")
                ?: throw TargetAccessException("The file could not be opened for writing.")
            output.use {
                it.write(bytes)
                it.flush()
            }
        } catch (e: SecurityException) {
            throw TargetAccessException("Write access to this file was denied.", e)
        } catch (e: IllegalArgumentException) {
            throw TargetAccessException("The file provider rejected the write.", e)
        } catch (e: UnsupportedOperationException) {
            throw TargetAccessException("The file provider does not allow writing this file.", e)
        }
    }

    /** Copies [bytes] into app-private storage so a patch can always be undone by hand. */
    @Throws(IOException::class)
    fun backup(displayName: String, bytes: ByteArray): File {
        val dir = File(app.filesDir, BACKUP_DIR)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Cannot create backup folder")
        val safe = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60).ifEmpty { "file" }
        val file = File(dir, "${safe}_${System.currentTimeMillis()}.bak")
        file.writeBytes(bytes)
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_BACKUPS)?.forEach { it.delete() }
        return file
    }

    private fun releasePersisted(uri: Uri) {
        try {
            val held = cr.persistedUriPermissions.firstOrNull { it.uri == uri } ?: return
            var flags = 0
            if (held.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (held.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            if (flags != 0) cr.releasePersistableUriPermission(uri, flags)
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val PREFS = "nl_hud"
        const val KEY_URI = "target_uri"
        const val BACKUP_DIR = "hud_backups"
        const val MAX_BACKUPS = 10
    }
}
