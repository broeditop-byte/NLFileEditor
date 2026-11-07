package com.notlexi.nlfileeditor.tmx

import java.io.File

/** One downloadable root filesystem from Alpine's release index. */
data class RootfsInfo(val file: String, val sha256: String, val size: Long, val release: String)

/**
 * Reads Alpine Linux's `latest-releases.yaml`, so the app downloads whatever the current stable
 * mini root filesystem is (with its published SHA-256) instead of hard-coding a version that
 * disappears from the mirror.
 */
object AlpineIndex {
    const val MIRROR = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases"

    /** Alpine's name for this device's CPU, or null when the CPU is not supported. */
    fun archFor(abi: String): String? = when (abi) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a" -> "armv7"
        "x86_64" -> "x86_64"
        else -> null
    }

    fun indexUrl(arch: String) = "$MIRROR/$arch/latest-releases.yaml"
    fun fileUrl(arch: String, file: String) = "$MIRROR/$arch/$file"

    fun parse(yaml: String): RootfsInfo? {
        val entries = ArrayList<MutableMap<String, String>>()
        var cur: MutableMap<String, String>? = null
        val kv = Regex("""^\s*([A-Za-z0-9_]+):\s*"?(.*?)"?\s*$""")
        for (line in yaml.lineSequence()) {
            if (line.trimStart().startsWith("-") && line.trim().length <= 1) {
                cur = HashMap()
                entries.add(cur)
                continue
            }
            val m = kv.find(line) ?: continue
            cur?.put(m.groupValues[1], m.groupValues[2])
        }
        for (e in entries) {
            val file = e["file"] ?: continue
            if (!file.startsWith("alpine-minirootfs-") || !file.endsWith(".tar.gz")) continue
            val sha = e["sha256"] ?: continue
            if (!Regex("[0-9a-fA-F]{64}").matches(sha)) continue
            return RootfsInfo(file, sha.lowercase(), e["size"]?.toLongOrNull() ?: -1L, e["release"] ?: "")
        }
        return null
    }

    /**
     * Where an archive entry may be written: inside [root] only. Absolute paths and ".." segments
     * are rejected, so a damaged or hostile archive can never write outside the root filesystem.
     */
    fun safeTarget(root: File, entryName: String): File? {
        val clean = entryName.removePrefix("./").trimStart('/')
        if (clean.isEmpty() || clean == ".") return null
        if (clean.split('/').any { it == ".." }) return null
        val target = File(root, clean)
        val rootPath = root.absolutePath
        return if (target.absolutePath == rootPath || target.absolutePath.startsWith("$rootPath/")) target else null
    }
}
