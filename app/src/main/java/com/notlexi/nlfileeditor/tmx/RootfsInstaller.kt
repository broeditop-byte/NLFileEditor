package com.notlexi.nlfileeditor.tmx

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.utils.CountingInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

enum class InstallPhase { IDLE, FINDING, DOWNLOADING, VERIFYING, EXTRACTING, CONFIGURING, DONE }

class InstallProgress(val phase: InstallPhase, val fraction: Float, val detail: String)

class InstallException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Installs the NL TMX Full environment: an Alpine Linux root filesystem under the app's private
 * storage. Everything here is real work: the HTTPS download (resumable) reports real byte counts,
 * the SHA-256 published by Alpine is checked, and extraction reports the share of the archive consumed.
 * The result is kept in filesDir, so it survives app restarts and updates.
 */
class RootfsInstaller(private val context: Context) {
    val base = File(context.filesDir, "nltmx_full")
    val rootfs = File(base, "rootfs")
    private val marker = File(base, "installed.properties")

    fun isInstalled(): Boolean = marker.isFile && File(rootfs, "bin").exists() && File(rootfs, "etc").exists()

    fun installedRelease(): String? = try {
        if (!marker.isFile) null else java.util.Properties().also { p -> marker.inputStream().use { p.load(it) } }.getProperty("release")
    } catch (e: Exception) {
        null
    }

    /** Runs the whole installation. [cancelled] is polled; progress goes to [onProgress]. Throws [InstallException]. */
    fun install(cancelled: () -> Boolean, onProgress: (InstallProgress) -> Unit) {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { AlpineIndex.archFor(it) != null }
            ?: throw InstallException("This phone's CPU (${Build.SUPPORTED_ABIS.joinToString()}) is not supported by NL TMX Full.")
        val arch = AlpineIndex.archFor(abi)!!
        base.mkdirs()

        onProgress(InstallProgress(InstallPhase.FINDING, 0f, "Looking up the current Alpine Linux release…"))
        val yaml = httpText(AlpineIndex.indexUrl(arch))
        val info = AlpineIndex.parse(yaml) ?: throw InstallException("Could not read Alpine's release list.")
        if (cancelled()) throw InstallException("Cancelled.")

        val archive = File(base, info.file)
        download(AlpineIndex.fileUrl(arch, info.file), archive, info.size, cancelled, onProgress)

        onProgress(InstallProgress(InstallPhase.VERIFYING, 0f, "Checking the download…"))
        val actual = sha256(archive, cancelled)
        if (!actual.equals(info.sha256, ignoreCase = true)) {
            archive.delete()
            throw InstallException("The download is damaged (checksum mismatch). Tap Download to try again.")
        }

        val staging = File(base, "rootfs.tmp")
        safeDelete(staging)
        staging.mkdirs()
        try {
            extract(archive, staging, cancelled, onProgress)
            onProgress(InstallProgress(InstallPhase.CONFIGURING, 0.98f, "Configuring…"))
            configure(staging)
            safeDelete(rootfs)
            if (!staging.renameTo(rootfs)) throw InstallException("Could not finish the installation (rename failed).")
        } catch (e: Throwable) {
            safeDelete(staging)
            throw e
        }
        java.util.Properties().apply {
            setProperty("release", info.release.ifEmpty { info.file })
            setProperty("file", info.file)
            setProperty("arch", arch)
            setProperty("installedAt", System.currentTimeMillis().toString())
            FileOutputStream(marker).use { store(it, "NL TMX Full") }
        }
        archive.delete()
        onProgress(InstallProgress(InstallPhase.DONE, 1f, "Installed Alpine Linux ${info.release}"))
    }

    fun uninstall() {
        marker.delete()
        safeDelete(rootfs)
        safeDelete(File(base, "rootfs.tmp"))
        base.listFiles()?.filter { it.name.endsWith(".part") || it.name.endsWith(".tar.gz") }?.forEach { it.delete() }
    }

    // ---------------------------------------------------------------- download

    private fun httpText(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode != 200) throw InstallException("Server answered ${c.responseCode} for the release list.")
            return c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            throw InstallException("No internet connection or the mirror is unreachable.", e)
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "NLFileEditor/6.0 (NL TMX installer)")
        return c
    }

    private fun download(url: String, target: File, expected: Long, cancelled: () -> Boolean, onProgress: (InstallProgress) -> Unit) {
        val part = File(target.path + ".part")
        if (target.isFile && expected > 0 && target.length() == expected) return // already downloaded earlier
        var have = if (part.isFile) part.length() else 0L
        val c = open(url)
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = try { c.responseCode } catch (e: IOException) { throw InstallException("No internet connection or the mirror is unreachable.", e) }
            if (code == 416) { part.delete(); have = 0; throw InstallException("Download restarted. Tap Download again.") }
            if (code != 200 && code != 206) throw InstallException("Server answered $code for the download.")
            if (code == 200) have = 0 // server ignored the range: start over
            val total = if (expected > 0) expected else have + c.contentLengthLong
            FileOutputStream(part, have > 0).use { out ->
                c.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var done = have
                    var lastReport = 0L
                    while (true) {
                        if (cancelled()) throw InstallException("Cancelled. The partial download is kept and will resume.")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.nanoTime()
                        if (now - lastReport > 100_000_000L) {
                            lastReport = now
                            val f = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                            onProgress(InstallProgress(InstallPhase.DOWNLOADING, f * 0.55f, "Downloading ${mb(done)} of ${if (total > 0) mb(total) else "?"}"))
                        }
                    }
                }
            }
        } catch (e: IOException) {
            if (e is InstallException) throw e
            throw InstallException("The download was interrupted. Tap Download to resume.", e)
        } finally {
            c.disconnect()
        }
        if (expected > 0 && part.length() != expected) throw InstallException("The download is incomplete. Tap Download to resume.")
        target.delete()
        if (!part.renameTo(target)) throw InstallException("Could not save the download.")
    }

    private fun mb(bytes: Long) = String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)

    private fun sha256(f: File, cancelled: () -> Boolean): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { s ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                if (cancelled()) throw InstallException("Cancelled.")
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------- extraction

    private fun extract(archive: File, root: File, cancelled: () -> Boolean, onProgress: (InstallProgress) -> Unit) {
        val total = archive.length().coerceAtLeast(1)
        val counting = CountingInputStream(BufferedInputStream(FileInputStream(archive), 128 * 1024))
        var files = 0
        var lastReport = 0L
        TarArchiveInputStream(GzipCompressorInputStream(counting)).use { tar ->
            while (true) {
                if (cancelled()) throw InstallException("Cancelled.")
                val e: TarArchiveEntry = tar.nextTarEntry ?: break
                val target = AlpineIndex.safeTarget(root, e.name) ?: continue
                when {
                    e.isDirectory -> {
                        target.mkdirs()
                        chmod(target, e.mode or 0b111_000_000) // owner must stay writable while extracting
                    }
                    e.isSymbolicLink -> {
                        target.parentFile?.mkdirs()
                        removeIfExists(target)
                        Os.symlink(e.linkName, target.path)
                    }
                    e.isLink -> {
                        val src = AlpineIndex.safeTarget(root, e.linkName)
                        if (src != null && src.isFile) {
                            target.parentFile?.mkdirs()
                            removeIfExists(target)
                            src.copyTo(target, overwrite = true)
                            chmod(target, e.mode or 0b110_000_000)
                        }
                    }
                    e.isFile -> {
                        target.parentFile?.mkdirs()
                        removeIfExists(target)
                        FileOutputStream(target).use { out -> tar.copyTo(out, 64 * 1024) }
                        chmod(target, e.mode or 0b110_000_000)
                    }
                    else -> {} // device nodes / fifos: /dev is provided by the host at run time
                }
                files++
                val now = System.nanoTime()
                if (now - lastReport > 100_000_000L) {
                    lastReport = now
                    val f = (counting.bytesRead.toFloat() / total).coerceIn(0f, 1f)
                    onProgress(InstallProgress(InstallPhase.EXTRACTING, 0.6f + f * 0.38f, "Extracting… $files files"))
                }
            }
        }
    }

    private fun chmod(f: File, mode: Int) {
        try { Os.chmod(f.path, mode and 0xFFF) } catch (_: Exception) { }
    }

    private fun removeIfExists(f: File) {
        try {
            Os.lstat(f.path)
            f.delete()
        } catch (_: Exception) {
        }
    }

    private fun configure(root: File) {
        for (d in listOf("proc", "sys", "dev", "tmp", "root", "run", "var/tmp")) File(root, d).mkdirs()
        chmod(File(root, "tmp"), 0b111_111_111 or 0x200) // 1777
        chmod(File(root, "root"), 0b111_000_000)
        File(root, "etc/resolv.conf").apply { parentFile?.mkdirs(); delete(); writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n") }
        File(root, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(root, "etc/motd").writeText(
            "\nNL TMX Full - Alpine Linux, run through proot (no root needed).\n" +
                "Install packages with:  apk add <package>      (e.g. apk add python3 git nano)\n" +
                "This is Alpine's apk repository, not Termux's pkg repository.\n\n"
        )
        File(root, "root/.profile").writeText(
            "export PS1='nl-tmx:\\w # '\n" +
                "export HISTFILE=/root/.ash_history\n" +
                "alias ll='ls -la'\n"
        )
    }

    companion object {
        /** Deletes a tree without ever following symlinks (Alpine's tree contains absolute links like /proc/mounts). */
        fun safeDelete(f: File) {
            val isLink = try {
                OsConstants.S_ISLNK(Os.lstat(f.path).st_mode)
            } catch (e: Exception) {
                return // does not exist
            }
            if (!isLink && f.isDirectory) {
                try { Os.chmod(f.path, 0b111_000_000) } catch (_: Exception) { }
                f.listFiles()?.forEach { safeDelete(it) }
            }
            f.delete()
        }
    }
}
