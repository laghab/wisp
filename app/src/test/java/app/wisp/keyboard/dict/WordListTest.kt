package app.wisp.keyboard.dict

import org.junit.Assert.assertEquals
import org.junit.Test

class WordListTest {
    private fun read(text: String) = WordList.read(text.byteInputStream())

    @Test fun readsWordsWithFrequencies() = assertEquals(listOf("kya" to 215, "hai" to 200), read("kya\t215\nhai\t200\n"))

    @Test fun aMissingOrBadFrequencyIsTheMiddleOfTheRange() = assertEquals(listOf("a1" to 100, "b2" to 100), read("a1\nb2\tnope\n"))

    @Test fun frequenciesAreClampedToTheRange() = assertEquals(listOf("big" to 255, "neg" to 0), read("big\t900\nneg\t-5\n"))

    @Test fun commentsBlankLinesAndWordsWithSpacesAreSkipped() = assertEquals(listOf("ok" to 50), read("# a comment\n\ntwo words\t10\nok\t50\n"))

    @Test fun veryLongWordsAreSkipped() = assertEquals(emptyList<Pair<String, Int>>(), read("x".repeat(60) + "\t10\n"))
}
