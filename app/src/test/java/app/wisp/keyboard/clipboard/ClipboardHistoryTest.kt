package app.wisp.keyboard.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClipboardHistoryTest {
    private var now = 1_000_000L
    private fun history() = ClipboardHistory({ now })
    private fun texts(h: ClipboardHistory, f: ClipFilter = ClipFilter.ALL) = h.items(f).map { it.text }

    @Test fun copiedLinkCanBeInsertedLater() {
        val h = history()
        assertTrue(h.add("https://example.com/page"))
        assertEquals(Kind.LINK, h.items().single().kind)
        assertEquals(listOf("https://example.com/page"), texts(h, ClipFilter.LINKS))
    }

    @Test fun sensitiveAndPasswordFieldCopiesAreNeverStored() {
        val h = history()
        assertFalse(h.add("hunter2", sensitive = true))
        assertFalse(h.add("secret", fromPassword = true))
        assertTrue(h.items().isEmpty())
    }

    @Test fun codesAreDetectedAndExpireQuickly() {
        val h = history()
        h.add("482 913")
        h.add("a normal sentence")
        assertEquals(listOf("482 913"), texts(h, ClipFilter.CODES))
        now += 5 * 60_000L
        assertEquals(listOf("a normal sentence"), texts(h))
        assertTrue(h.items(ClipFilter.CODES).isEmpty())
    }

    @Test fun unpinnedItemsClearAfterOneHourButPinnedStay() {
        val h = history()
        h.add("keep me"); h.add("lose me")
        h.pin(h.items().first { it.text == "keep me" }.id, true)
        now += 61 * 60_000L
        assertEquals(listOf("keep me"), texts(h))
        assertEquals(listOf("keep me"), texts(h, ClipFilter.PINNED))
        assertNull(h.expiresInMs(h.items().single()))
    }

    @Test fun copyingTheSameTextAgainMovesItToTheTopAndKeepsItsPin() {
        val h = history()
        h.add("one"); h.add("two")
        h.pin(h.items().first { it.text == "one" }.id, true)
        now += 1000; h.add("one")
        assertEquals(1, texts(h).count { it == "one" })
        assertTrue(h.items().first { it.text == "one" }.pinned)
    }

    @Test fun switchingHistoryOffStopsStoringAndForgetsUnpinned() {
        val h = history()
        h.add("a"); h.add("b"); h.pin(h.items().first { it.text == "a" }.id, true)
        h.enabled = false
        assertEquals(listOf("a"), texts(h))
        assertFalse(h.add("c"))
    }

    @Test fun sizeIsBoundedAndEmptyOrHugeTextIsIgnored() {
        val h = ClipboardHistory({ now }, maxItems = 3)
        for (i in 1..6) { h.add("item $i"); now += 10 }
        assertEquals(3, h.items().size)
        assertFalse(h.add("   ")); assertFalse(h.add("x".repeat(ClipboardHistory.MAX_LENGTH + 1)))
    }

    @Test fun savesAndLoadsIncludingAwkwardText() {
        val f = File.createTempFile("clip", ".tsv").also { it.deleteOnExit() }
        val a = history(); a.add("line one\nline\ttwo\\end"); a.add("https://x.dev"); a.pin(a.items().first().id, true); a.save(f)
        val b = history(); b.load(f)
        assertEquals(texts(a).toSet(), texts(b).toSet())
        assertTrue(texts(b).contains("line one\nline\ttwo\\end"))
        assertEquals(a.items().count { it.pinned }, b.items().count { it.pinned })
    }

    @Test fun classify() {
        assertEquals(Kind.CODE, ClipboardHistory.classify("G-123456"))
        assertEquals(Kind.CODE, ClipboardHistory.classify("4829"))
        assertEquals(Kind.LINK, ClipboardHistory.classify("github.com/wisp-kb/wisp"))
        assertEquals(Kind.TEXT, ClipboardHistory.classify("meeting at 4:30"))
        assertEquals(Kind.TEXT, ClipboardHistory.classify("123456789012"))
    }
}
