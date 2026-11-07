package com.notlexi.nlfileeditor.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SearchAndChangeTest {

    private fun tree(): File {
        val root = createTempDir("nltest")
        File(root, "saves").mkdirs()
        File(root, "cache").mkdirs()
        File(root, "saves/player.json").writeText("""{"player":{"gold":1000,"coins":100,"level":4,"name":"x","inv":{"gems":100}}}""")
        File(root, "saves/notes.txt").writeText("gold 100\nsome text 1000 and 100.5 and 2026-11-04 and ver 1.100.2\nhash abc100def\nlone 100 here\n")
        File(root, "cfg.ini").writeText("[a]\ncoins=100\nmusic=true\n")
        File(root, "bin.dat").writeBytes(ByteArray(300) { (it % 7).toByte() })
        File(root, "cache/skip.json").writeText("""{"coins":100}""")
        return root
    }

    private fun contentAt(root: File, hit: SearchHit) = File(root, hit.path).readText()

    @Test fun numberSearchOnlyReturnsRealMatches() {
        val root = tree()
        val r = SearchEngine.search(JavaFileSource(root), SearchQuery("100", QueryMode.NUMBER))
        assertNull(r.error)
        assertTrue(r.hits.isNotEmpty())
        for (h in r.hits) {
            assertEquals("100", h.matched)
            assertEquals("100", contentAt(root, h).substring(h.offset, h.offset + 3))
            assertFalse("skipped folder must not be searched", h.path.startsWith("cache"))
        }
        val named = r.hits.filter { it.kind == HitKind.NAMED_VALUE }
        assertTrue(named.any { it.path == "saves/player.json" && it.label!!.endsWith("coins") })
        assertTrue(named.any { it.path == "saves/player.json" && it.label!!.endsWith("gems") })
        assertTrue(named.any { it.path == "cfg.ini" && it.label == "coins" })
        // 1000, 100.5, the date, the version, "abc100def" must not produce hits
        val notes = r.hits.filter { it.path == "saves/notes.txt" }
        assertTrue(notes.all { it.line == 1 || it.line == 4 })
        assertEquals(r.hits.size, r.hits.map { it.key }.toSet().size) // no duplicates
        assertEquals(1, r.skippedBinary)
    }

    @Test fun numberSearchWithoutUnlabeledOnlyNamed() {
        val root = tree()
        val r = SearchEngine.search(JavaFileSource(root), SearchQuery("100", QueryMode.NUMBER, includeUnlabeled = false))
        assertTrue(r.hits.all { it.kind == HitKind.NAMED_VALUE })
    }

    @Test fun decimalEqualityAndBadInput() {
        val root = tree()
        val bad = SearchEngine.search(JavaFileSource(root), SearchQuery("abc", QueryMode.NUMBER))
        assertNotNull(bad.error)
        val r = SearchEngine.search(JavaFileSource(root), SearchQuery("1,000", QueryMode.NUMBER))
        assertTrue(r.hits.any { it.path == "saves/player.json" && it.label!!.endsWith("gold") })
    }

    @Test fun nameAndTextSearch() {
        val root = tree()
        val names = SearchEngine.search(JavaFileSource(root), SearchQuery("*.json", QueryMode.NAME))
        assertEquals(listOf("saves/player.json"), names.hits.map { it.path })
        val text = SearchEngine.search(JavaFileSource(root), SearchQuery("GOLD", QueryMode.TEXT))
        assertTrue(text.hits.any { it.path == "saves/notes.txt" && it.line == 1 })
        assertTrue(text.hits.any { it.path == "saves/player.json" })
    }

    @Test fun cancellation() {
        val r = SearchEngine.search(JavaFileSource(tree()), SearchQuery("100", QueryMode.NUMBER), cancelled = { true })
        assertTrue(r.cancelled)
        assertTrue(r.hits.isEmpty())
    }

    @Test fun staleDetection() {
        val root = tree()
        val src = JavaFileSource(root)
        val hit = SearchEngine.search(src, SearchQuery("100", QueryMode.NUMBER)).hits.first { it.path == "cfg.ini" }
        assertFalse(SearchEngine.isStale(src, hit))
        File(root, "cfg.ini").writeText("[a]\ncoins=555\nmusic=true\n")
        File(root, "cfg.ini").setLastModified(System.currentTimeMillis() + 5000)
        assertTrue(SearchEngine.isStale(src, hit))
        val ctx = SearchEngine.context(src, hit)
        assertNotNull(ctx)
    }

    @Test fun changeDetection() {
        val root = tree()
        val src = JavaFileSource(root)
        val a = ChangeDetector.capture(src)
        File(root, "saves/player.json").writeText("""{"player":{"gold":1000,"coins":90,"level":4,"name":"x","inv":{"gems":100}}}""")
        File(root, "cfg.ini").writeText("[a]\ncoins=100\nmusic=false\n")
        File(root, "new.json").writeText("""{"x":1}""")
        val bin = ByteArray(300) { (it % 7).toByte() }
        bin[40] = 99
        File(root, "bin.dat").writeBytes(bin)
        val b = ChangeDetector.capture(src)
        val report = ChangeDetector.diff(a, b)
        val json = report.changes.first { it.path == "saves/player.json" }
        assertEquals(1, json.valueChanges.size)
        assertEquals("100", json.valueChanges[0].before)
        assertEquals("90", json.valueChanges[0].after)
        assertEquals("-10", json.valueChanges[0].numericDelta!!.toPlainString())
        val ini = report.changes.first { it.path == "cfg.ini" }
        assertTrue(ini.valueChanges.any { it.before.equals("true", true) && it.after.equals("false", true) })
        assertTrue(report.changes.any { it.path == "new.json" && it.kind == ChangeKind.ADDED })
        val binChange = report.changes.first { it.path == "bin.dat" }
        assertEquals(1, binChange.binaryChanges.size)
        assertEquals(40, binChange.binaryChanges[0].offset)
        // unchanged file produces no entry
        assertFalse(report.changes.any { it.path == "saves/notes.txt" })
    }
}
