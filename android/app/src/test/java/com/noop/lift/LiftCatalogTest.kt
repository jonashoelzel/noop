package com.noop.lift

import com.noop.data.LiftMuscle
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric only for org.json, which the plain JVM test classpath stubs out.
@RunWith(RobolectricTestRunner::class)
class LiftCatalogTest {
    private val bundled: List<LiftCatalog.Entry> by lazy {
        LiftCatalog.parse(File("src/main/assets/${LiftCatalog.ASSET}").readText())
    }

    @Test
    fun theBundledLibraryParsesCompletely() {
        val raw = org.json.JSONObject(File("src/main/assets/${LiftCatalog.ASSET}").readText()).getJSONArray("exercises")
        // Every row survives parsing: a primary muscle the app does not know would silently drop it.
        assertEquals(raw.length(), bundled.size)
        assertTrue(bundled.size > 500)
        assertTrue(bundled.none { it.name.isBlank() })
        assertEquals(bundled.size, bundled.map { it.name.lowercase() }.toSet().size)
        assertTrue(bundled.none { it.primary in it.secondaries })
    }

    @Test
    fun parseSkipsUnknownMusclesAndDefaultsEquipment() {
        val parsed = LiftCatalog.parse(
            """{"exercises":[
                {"n":"A","p":"chest","s":["triceps","chest","nope"],"e":"barbell"},
                {"n":"B","p":"nope","s":[],"e":"cable"},
                {"n":"C","p":"quads","s":[],"e":"spaceship"}
            ]}""",
        )
        assertEquals(listOf("A", "C"), parsed.map { it.name })
        assertEquals(listOf(LiftMuscle.triceps), parsed[0].secondaries)
        assertEquals(LiftCatalog.Equipment.OTHER, parsed[1].equipment)
    }

    @Test
    fun searchMatchesEveryWordAndRanksPrefixesFirst() {
        val entries = listOf(
            LiftCatalog.Entry("Barbell Bench Press", LiftMuscle.chest, emptyList(), LiftCatalog.Equipment.BARBELL),
            LiftCatalog.Entry("Bench Dip", LiftMuscle.triceps, emptyList(), LiftCatalog.Equipment.BODYWEIGHT),
            LiftCatalog.Entry("Dumbbell Bench Press", LiftMuscle.chest, emptyList(), LiftCatalog.Equipment.DUMBBELL),
            LiftCatalog.Entry("Barbell Squat", LiftMuscle.quads, emptyList(), LiftCatalog.Equipment.BARBELL),
        )
        assertEquals(listOf("Barbell Bench Press", "Dumbbell Bench Press"), LiftCatalog.search(entries, "press bench").map { it.name })
        assertEquals("Bench Dip", LiftCatalog.search(entries, "bench").first().name)
        assertEquals(listOf("Barbell Squat"), LiftCatalog.search(entries, "", region = LiftMuscle.Region.legs).map { it.name })
        assertEquals(listOf("Dumbbell Bench Press"), LiftCatalog.search(entries, "", equipment = LiftCatalog.Equipment.DUMBBELL).map { it.name })
        assertEquals(listOf("Bench Dip"), LiftCatalog.search(entries, "", muscle = LiftMuscle.triceps).map { it.name })
    }

    @Test
    fun theLibraryCoversTheBasicLifts() {
        for (q in listOf("bench press", "squat", "deadlift", "lateral raise", "pull-up", "row", "curl")) {
            assertTrue(q, LiftCatalog.search(bundled, q).isNotEmpty())
        }
        val lateral = LiftCatalog.search(bundled, "side lateral raise").first()
        assertEquals(LiftMuscle.sideDelts, lateral.primary)
    }
}
