package app.wisp.keyboard.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EmojiDataTest {
    private fun res(name: String) = listOf(File("src/main/res/raw/$name"), File("app/src/main/res/raw/$name")).first { it.isFile }.readText()
    private val data = EmojiData.parse(res("emoji.tsv"), res("kaomoji.txt"), res("symbols.txt"))

    @Test fun shippedListsAreFilled() {
        assertTrue(data.emoji.size > 1500)
        assertTrue(data.kaomoji.size >= 30)
        assertTrue(data.symbols.size >= 80)
        assertEquals(EmojiData.GROUPS.size, data.emoji.map { it.group }.toSet().size)
    }

    @Test fun searchFindsSmileyFaces() {
        val r = data.search("smile").map { it.ch }
        assertTrue(r.contains("😄"))          // grinning face with smiling eyes
        assertTrue(data.search("smile").all { "smil" in it.name })
        assertTrue(data.search("cats").any { "cat" in it.name })
    }

    @Test fun searchNeedsEveryWordAndRanksWordStartsFirst() {
        val r = data.search("red heart")
        assertTrue(r.isNotEmpty() && r.all { "red" in it.name && "heart" in it.name })
        val first = data.search("cat").first()
        assertTrue(first.name.startsWith("cat") || " cat" in first.name)
    }

    @Test fun emptyOrUnknownQueryGivesNothing() {
        assertTrue(data.search("").isEmpty())
        assertTrue(data.search("zzzzqqq").isEmpty())
    }

    @Test fun unrenderableEmojiAreDropped() {
        val d = EmojiData.parse("A\ta thing\t0\nB\tb thing\t0", "", "", renders = { it == "A" })
        assertEquals(listOf("A"), d.emoji.map { it.ch })
    }

    @Test fun recentsKeepNewestFirstWithoutDuplicatesAndAreBounded() {
        var store = ""
        val r = RecentEmoji({ store }, { store = it }, max = 3)
        for (e in listOf("a", "b", "c", "a", "d")) r.add(e)
        assertEquals(listOf("d", "a", "c"), r.list())
        r.clear(); assertTrue(r.list().isEmpty())
    }
}
