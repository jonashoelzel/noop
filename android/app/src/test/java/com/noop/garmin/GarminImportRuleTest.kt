package com.noop.garmin

import com.noop.garmin.GarminImportRule.Decision
import com.noop.garmin.GarminImportRule.Existing
import org.junit.Assert.assertEquals
import org.junit.Test

/** The skip-rule table both platforms pin (the Swift GarminImportRuleTests carries the same rows). */
class GarminImportRuleTest {

    private fun decide(vararg existing: Existing) = GarminImportRule.decide(1000, 2000, existing.toList())

    @Test
    fun specTable() {
        assertEquals(Decision.IMPORT, decide())
        assertEquals(Decision.ALREADY_IMPORTED, decide(Existing(1001, 1990, "garmin-connect")))
        assertEquals(Decision.IMPORT, decide(Existing(1003, 2000, "garmin-connect")))
        assertEquals(Decision.SENT_FROM_NOOP, decide(Existing(1100, 1900, "manual")))
        assertEquals(Decision.IMPORT, decide(Existing(1600, 2600, "manual")))
        assertEquals(Decision.SENT_FROM_NOOP, decide(Existing(1400, 2500, "manual")))
        assertEquals(Decision.IMPORT, decide(Existing(1000, 2000, "health-connect")))
        assertEquals(Decision.IMPORT, decide(Existing(1000, 2000, "activity-file")))
    }

    @Test
    fun alreadyImportedWinsOverSentFromNoopAndToleranceIsInclusive() {
        assertEquals(
            Decision.ALREADY_IMPORTED,
            decide(Existing(1000, 2000, "manual"), Existing(998, 2000, "garmin-connect")),
        )
    }
}
