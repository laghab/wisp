package app.wisp.keyboard.voice

import app.wisp.keyboard.voice.model.DeviceFacts
import app.wisp.keyboard.voice.model.Fit
import app.wisp.keyboard.voice.model.ModelAdvisor
import app.wisp.keyboard.voice.model.ModelCatalog
import app.wisp.keyboard.voice.model.ModelInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAdvisorTest {
    private fun phone(gb: Double, free: Double = gb * 0.5) = DeviceFacts((gb * 1024).toLong(), (free * 1024).toLong(), 4)
    private fun spec(id: String) = ModelCatalog.byId(id)!!

    @Test fun aSmallPhoneGetsTheSmallModel() {
        val r = ModelAdvisor.recommend(phone(2.0))
        assertEquals("moonshine-tiny-en", r.model.id)
        assertTrue(r.reason.contains("small, fast"))
    }

    @Test fun aMidRangePhoneGetsAnAccurateModelThatLeavesRoom() {
        val r = ModelAdvisor.recommend(phone(6.0))
        assertTrue("got ${r.model.id}", r.model.id in setOf("parakeet-110m-en", "moonshine-base-en"))
        assertTrue(ModelAdvisor.assess(r.model, phone(6.0)).fit == Fit.GOOD)
    }

    @Test fun aHighEndPhoneGetsTheMostAccurateModelAndItsCostIsStated() {
        val r = ModelAdvisor.recommend(phone(12.0))
        assertEquals("parakeet-v3", r.model.id)
        assertTrue(r.reason.contains("bigger download"))
    }

    @Test fun theBigModelIsTooHeavyForASmallPhone() {
        val a = ModelAdvisor.assess(spec("parakeet-v3"), phone(3.0))
        assertEquals(Fit.TOO_HEAVY, a.fit); assertTrue(a.reason.contains("slow or get closed"))
    }

    @Test fun theBigModelIsHeavyButUsableOnAnEightGigabytePhone() = assertEquals(Fit.HEAVY, ModelAdvisor.assess(spec("parakeet-v3"), phone(8.0, free = 4.0)).fit)

    @Test fun theTinyModelFitsEverywhere() { for (gb in listOf(2.0, 4.0, 8.0, 16.0)) assertEquals(Fit.GOOD, ModelAdvisor.assess(spec("moonshine-tiny-en"), phone(gb)).fit) }

    @Test fun everyModelHasProsAndCons() {
        for (m in ModelCatalog.all) { val n = ModelInfo.notesFor(m.id); assertTrue("${m.id} pros", n.pros.isNotEmpty()); assertTrue("${m.id} cons", n.cons.isNotEmpty()) }
    }

    @Test fun nothingBreaksOnAPhoneWithAlmostNoMemory() {
        val r = ModelAdvisor.recommend(DeviceFacts(1024, 300, 2))
        assertEquals("moonshine-tiny-en", r.model.id)
    }

    @Test fun hindiSpeakersAreTold() {
        assertTrue(ModelInfo.notesFor("whisper-small").cons.any { it.contains("Devanagari") })
    }
}
