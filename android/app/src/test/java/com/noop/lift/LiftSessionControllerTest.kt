package com.noop.lift

import com.noop.lift.LiftSessionPersistence.PendingSetValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The controller's runtime behaviour on the plain JVM: what the Swift `LiftSessionPendingInputTests`,
 * `LiftSessionStrapTapTests` and `LiftSessionPersistenceTests` cover. The finishing rules are pinned to
 * Swift separately, by `LiftSessionControllerOracleTest`.
 */
class LiftSessionControllerTest {

    private var now = 1_700_000_000L
    private val buzzes = mutableListOf<Int>()
    private val logs = mutableListOf<String>()

    private fun controller() = LiftSessionController(
        prefs = null,
        scope = CoroutineScope(Dispatchers.Unconfined),
        buzz = { buzzes += it },
        log = { logs += it },
        clock = { now },
    )

    private val plan = listOf(
        LiftPlanItem.of("Bench", targetSets = 3, restSec = 90, targetRepsLow = 8, targetWeightKg = 60.0),
        LiftPlanItem.of("Row", targetSets = 2, restSec = 60, targetRepsLow = 10, targetWeightKg = 50.0),
    )

    private val engine get() = c.state.value.engine!!
    private val c = controller()

    @Test
    fun numbersTypedIntoAPendingSetAreHeldThenAppliedWhenItIsDone() {
        c.start(plan, "p1", "Upper")
        val set2 = LiftSlot(0, 2)
        c.updateSet(set2, 62.5, null, 8.0, false)
        assertNull("typing never invents a set", engine.recordedSet(set2))
        assertEquals(PendingSetValues(62.5, null, 8.0), c.state.value.enteredValues(set2))
        assertEquals(LiftSetCarry(62.5, 8), c.state.value.shownNumbers(set2))

        now += 10; c.advance() // start 0.1
        now += 40; c.advance() // done 0.1
        now += 90; c.advance() // start 0.2
        now += 40; c.advance() // done 0.2: the typed numbers land
        val recorded = engine.recordedSet(set2)!!
        assertEquals(62.5, recorded.weightKg!!, 0.0)
        assertNull("an untyped field stays grey", recorded.reps)
        assertEquals(8.0, recorded.rpe!!, 0.0)
        assertFalse("the entry is consumed", set2 in c.state.value.pendingValues)
    }

    @Test
    fun clearingEveryFieldDropsTheEntry() {
        c.start(plan, null, null)
        c.updateSet(LiftSlot(1, 1), 40.0, null, null, false)
        c.updateSet(LiftSlot(1, 1), null, null, null, false)
        assertTrue(c.state.value.pendingValues.isEmpty())
    }

    @Test
    fun aWarmupMarkedAheadIsAppliedWhenTheSetIsDone() {
        c.start(plan, null, null)
        c.setWarmup(LiftSlot(0, 1), true)
        assertTrue(c.state.value.isWarmup(LiftSlot(0, 1)))
        c.advance(); now += 30; c.advance()
        assertTrue(engine.recordedSet(LiftSlot(0, 1))!!.isWarmup)
    }

    @Test
    fun removingASetForgetsWhatWasTypedIntoIt() {
        c.start(plan, null, null)
        c.updateSet(LiftSlot(0, 3), 70.0, 5, null, false)
        c.setWarmup(LiftSlot(0, 3), true)
        assertTrue(c.removeSet(0))
        assertTrue(c.addSet(0))
        assertTrue(c.state.value.pendingValues.isEmpty())
        assertFalse(c.state.value.isWarmup(LiftSlot(0, 3)))
    }

    @Test
    fun aStrapTapBuzzesOnceAndAKnockIsHeldBackSilently() {
        c.start(plan, null, null)
        c.advance(fromStrap = true) // starts set 1
        assertEquals(listOf(LiftSessionController.ADVANCE_CONFIRM_BUZZES), buzzes)
        now += 3
        c.advance(fromStrap = true) // the arm going onto the bar
        assertEquals("a knock gets no buzz", 1, buzzes.size)
        assertEquals(LiftStage.Working(LiftSlot(0, 1)), engine.stage)
        assertTrue(logs.single().contains("not acted on"))

        now += 30
        c.advance(fromStrap = true)
        assertEquals(2, buzzes.size)
        assertTrue(engine.stage is LiftStage.Resting)

        // The on-screen button is never held back.
        now += 1
        c.advance()
        assertEquals(LiftStage.Working(LiftSlot(0, 2)), engine.stage)
    }

    @Test
    fun undoAndStartWorkOnAFreshCopySoEarlierStatesStayIntact() {
        c.start(plan, null, null)
        val before = c.state.value
        c.advance()
        assertEquals(LiftStage.Warmup, before.engine!!.stage)
        c.undo()
        assertEquals(LiftStage.Warmup, engine.stage)
        c.start(LiftSlot(1, 2))
        assertEquals(LiftStage.Working(LiftSlot(1, 2)), engine.stage)
    }

    @Test
    fun addedExercisesStartAtOneSetOfZerosAndCarryAProgramLineId() {
        c.start(plan, "p1", "Upper")
        assertTrue(c.addExercise("Dips", null, emptyList()))
        val line = engine.plan.last()
        assertEquals(1, line.targetSets)
        assertEquals(0, line.targetRepsLow)
        assertEquals(0.0, line.targetWeightKg!!, 0.0)
        assertTrue(line.addedInSession)
        assertNotNull(line.programItemId)
    }

    @Test
    fun discardingAndSavingEndTheSession() {
        c.start(plan, null, null)
        assertTrue(c.isActive)
        c.discard()
        assertFalse(c.isActive)
        assertEquals(0, c.state.value.savedSessions)
        c.start(plan, null, null)
        c.finishedSaving()
        assertNull(c.state.value.engine)
        assertEquals(1, c.state.value.savedSessions)
    }

    @Test
    fun aSnapshotRoundTripsThroughTheCodec() {
        c.start(plan, "p1", "Upper")
        c.advance(); now += 40; c.advance()
        c.updateSet(LiftSlot(0, 1), 60.0, 8, 7.5, false)
        c.updateSet(LiftSlot(1, 1), 52.5, null, null, false)
        c.setWarmup(LiftSlot(1, 2), true)
        c.addExercise("Dips", null, emptyList())
        val s = c.state.value
        val snap = LiftSessionPersistence.snapshot(s.engine!!, s.programId, s.programName, s.pendingValues, s.pendingWarmups)
        val back = LiftSessionPersistence.decode(LiftSessionPersistence.encode(snap), nowSec = now)
        assertEquals(snap, back)
        val restored = LiftSessionPersistence.engine(back!!)
        assertEquals(s.engine!!.plan, restored.plan)
        assertEquals(s.engine!!.stage, restored.stage)
        assertEquals(s.engine!!.sets, restored.sets)
        assertFalse("undo history is not restored", restored.canUndo)
    }

    @Test
    fun aCorruptOrImplausibleSnapshotReadsAsNoSession() {
        assertNull(LiftSessionPersistence.decode(null))
        assertNull(LiftSessionPersistence.decode(""))
        assertNull(LiftSessionPersistence.decode("{not json"))
        val good = LiftSessionPersistence.encode(
            LiftSessionPersistence.Snapshot(now, null, null, plan, LiftStage.Warmup, emptyList(), now),
        )
        assertNotNull(LiftSessionPersistence.decode(good, nowSec = now))
        assertNull("a start in the far future", LiftSessionPersistence.decode(good, nowSec = now - 200_000))
        val empty = LiftSessionPersistence.encode(
            LiftSessionPersistence.Snapshot(now, null, null, emptyList(), LiftStage.Warmup, emptyList(), now),
        )
        assertNull("an empty plan", LiftSessionPersistence.decode(empty, nowSec = now))
    }

    @Test
    fun anUnknownStageFallsBackToTheWarmupAndKeepsEverySet() {
        val raw = LiftSessionPersistence.encode(
            LiftSessionPersistence.Snapshot(
                now, null, null, plan, LiftStage.Working(LiftSlot(0, 2)),
                listOf(LiftRecordedSet(0, 1, startTs = now, endTs = now + 30)), now,
            ),
        ).replace("\"working\"", "\"cooldown\"")
        val back = LiftSessionPersistence.decode(raw, nowSec = now)!!
        assertEquals(LiftStage.Warmup, back.stage)
        assertEquals(1, back.sets.size)
    }

    @Test
    fun resumingInsideTheRestsFinalSecondsDoesNotBuzzTheWarning() {
        val snap = LiftSessionPersistence.Snapshot(
            now - 100, null, null, plan, LiftStage.Resting(LiftSlot(0, 1), now + 3),
            listOf(LiftRecordedSet(0, 1, startTs = now - 60, endTs = now - 30)), now - 30,
        )
        c.resume(snap)
        assertTrue(c.isActive)
        // Unconfined dispatch runs the timers up to their first delay; the warning was already suppressed.
        assertTrue(buzzes.isEmpty())
    }
}
