package com.notlexi.nlfileeditor.tmx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class AlpineIndexTest {
    private val yaml = """
---
-
  title: "Mini root filesystem"
  desc: "Minimal root filesystem"
  release: "3.22.2"
  date: "2025-10-01"
  file: alpine-minirootfs-3.22.2-aarch64.tar.gz
  size: 3869830
  sha256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
  sha512: aa
-
  title: "Netboot"
  file: alpine-netboot-3.22.2-aarch64.tar.gz
  sha256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
"""

    @Test fun parsesMiniRootfsEntry() {
        val info = AlpineIndex.parse(yaml)
        assertNotNull(info)
        assertEquals("alpine-minirootfs-3.22.2-aarch64.tar.gz", info!!.file)
        assertEquals(3869830L, info.size)
        assertEquals("3.22.2", info.release)
        assertEquals(64, info.sha256.length)
    }

    @Test fun rejectsBadIndex() {
        assertNull(AlpineIndex.parse("-\n  file: alpine-minirootfs-1.tar.gz\n  sha256: nothex\n"))
        assertNull(AlpineIndex.parse(""))
    }

    @Test fun archMapping() {
        assertEquals("aarch64", AlpineIndex.archFor("arm64-v8a"))
        assertEquals("armv7", AlpineIndex.archFor("armeabi-v7a"))
        assertNull(AlpineIndex.archFor("mips"))
    }

    @Test fun archiveEntriesCannotEscapeRoot() {
        val root = File("/data/app/rootfs")
        assertEquals(File(root, "etc/passwd").path, AlpineIndex.safeTarget(root, "./etc/passwd")!!.path)
        assertNull(AlpineIndex.safeTarget(root, "../evil"))
        assertNull(AlpineIndex.safeTarget(root, "a/../../evil"))
        assertNull(AlpineIndex.safeTarget(root, "./"))
        assertEquals(File(root, "etc/x").path, AlpineIndex.safeTarget(root, "/etc/x")!!.path)
    }
}
