package com.noop.lift

import com.noop.data.LiftMuscle
import com.noop.data.LiftProgramItemRow
import com.noop.lift.LiftSessionPersistence.PendingSetValues
import com.noop.ui.UnitSystem
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the controller's finishing rules, the strap-knock and rest-timer rules, and [LiftFormat] to the
 * Swift implementation by oracle. `lift/controller_oracle_expected.txt` is the stdout of the real Swift
 * `LiftSessionController` / `LiftFormat` compiled standalone over the same session; the driver and the
 * commands that regenerate it are in `lift/LiftControllerOracle.swift.txt`.
 *
 * The session is built through the engine with fixed timestamps (done sets with typed and untyped numbers,
 * a warm-up, a discarded zero-rep set, sets added to two lines, two exercises added mid-session, one line
 * whose program row has since been deleted), resumed into the controller with numbers typed ahead and a
 * warm-up marked ahead, and then saved both ways and written back onto a program.
 */
class LiftSessionControllerOracleTest {

    private fun d(v: Double?) = v?.let { String.format(Locale.ROOT, "%.4f", it) } ?: "-"
    private fun i(v: Number?) = v?.toString() ?: "-"
    private fun slot(s: LiftSlot) = "${s.exerciseIndex}.${s.setIndex}"
    private fun fs(f: LiftSessionController.FinishedSet) =
        "[${slot(f.slot)} w=${d(f.weightKg)} r=${i(f.reps)} rpe=${d(f.rpe)} wu=${f.isWarmup} ${i(f.startTs)}-${i(f.endTs)} rest=${i(f.restSec)}]"
    private fun row(r: LiftProgramItemRow) =
        "{${r.id} ord=${r.ord} ${r.exercise} sets=${i(r.targetSets)} reps=${i(r.targetRepsLow)}-${i(r.targetRepsHigh)} " +
            "rpe=${d(r.targetRpe)} w=${d(r.targetWeightKg)} rest=${i(r.restSec)} note=${r.note ?: "-"}}"

    private fun resource(name: String): String =
        requireNotNull(javaClass.classLoader!!.getResource("lift/$name")) { "missing lift/$name" }.readText()

    private fun item(id: String, ord: Int, exercise: String, sets: Int?, low: Int?, high: Int?, rpe: Double?, w: Double?, rest: Int?, note: String?) =
        LiftProgramItemRow(id, "dev", "p1", ord, exercise, sets, low, high, rpe, w, rest, note)

    @Test
    fun kotlinControllerMatchesTheSwiftController() {
        val out = StringBuilder()
        fun println(line: String) = out.append(line).append('\n')

        val plan = listOf(
            LiftPlanItem.of("Bench", primaryMuscle = LiftMuscle.chest, targetSets = 3, restSec = 90, targetRepsLow = 8, targetRepsHigh = 10, targetRpe = 8.0, targetWeightKg = 60.0, programItemId = "i1"),
            LiftPlanItem.of("Squat", targetSets = 2, restSec = 60, targetRepsLow = 10, programItemId = "i2"),
            LiftPlanItem.of("Row", targetSets = 1, targetRepsLow = 12, targetWeightKg = 40.0, programItemId = "gone"),
            LiftPlanItem.of("Free", targetSets = 1, targetWeightKg = 15.0),
        )
        val t0 = 1_700_000_000L
        val e = LiftSessionEngine(plan, t0)
        e.advance(t0 + 100)
        e.advance(t0 + 160)
        e.updateSet(LiftSlot(0, 1), 62.5, null, null, false)
        e.advance(t0 + 250)
        e.advance(t0 + 300)
        e.updateSet(LiftSlot(0, 2), 65.0, 6, 9.0, false)
        e.start(LiftSlot(1, 1), t0 + 320)
        e.advance(t0 + 400)
        e.updateSet(LiftSlot(1, 1), 100.0, 10, null, true)
        e.advance(t0 + 470)
        e.advance(t0 + 520)
        e.updateSet(LiftSlot(1, 2), 0.0, 0, null, false)
        e.addSet(0)
        e.addSet(1)
        e.addExercise(LiftPlanItem.of("Dips", targetSets = 3, targetRepsLow = 0, targetWeightKg = 0.0, programItemId = "new1", addedInSession = true))
        e.addExercise(LiftPlanItem.of("Plank", targetSets = 1, targetRepsLow = 0, targetWeightKg = 0.0, programItemId = "new2", addedInSession = true))
        e.start(LiftSlot(4, 1), t0 + 600)
        e.advance(t0 + 650)
        e.updateSet(LiftSlot(4, 1), null, 15, null, false)
        e.finish(t0 + 700)

        val snap = LiftSessionPersistence.snapshot(
            e, "p1", "Upper",
            pendingValues = mapOf(
                LiftSlot(0, 4) to PendingSetValues(70.0, null, 9.5),
                LiftSlot(2, 1) to PendingSetValues(null, 8, null),
            ),
            pendingWarmups = setOf(LiftSlot(1, 3)),
        )
        val c = LiftSessionController(null, CoroutineScope(Dispatchers.Unconfined), buzz = {}, clock = { t0 + 800 })
        c.resume(snap)
        c.setLastSession(mapOf("Bench" to mapOf(3 to LiftSetCarry(61.0, 7)), "Plank" to mapOf(1 to LiftSetCarry(null, 60))))

        for (complete in listOf(true, false)) {
            val sets = c.setsToSave(complete)
            println("setsToSave($complete) performed=${LiftSessionController.anyPerformed(sets)}")
            sets.forEach { println("  " + fs(it)) }
        }
        println("unfinished=" + c.unfinishedSlots.joinToString(",") { slot(it) })
        val none = c.setsToSave(false).filter { it.startTs == null }
        println("anyPerformed(only discarded)=${LiftSessionController.anyPerformed(none)}")

        val items = listOf(
            item("i1", 0, "Bench", 3, 5, 6, 8.0, 60.0, 90, "elbows"),
            item("i2", 1, "Squat", null, 10, 12, null, null, null, null),
            item("i9", 5, "Calf", 2, null, null, null, 30.0, 45, null),
        )
        val finalPlan = c.state.value.engine!!.plan
        println("changes=" + LiftSessionController.setCountChanges(finalPlan, items).joinToString(",") { "${it.itemId}:${it.exercise}:${it.from}->${it.to}" })
        val saved = c.setsToSave(true)
        for (keep in listOf(true, false)) {
            println("programAfterSession(keep=$keep)")
            LiftSessionController.programAfterSession(saved, finalPlan, items, keep, "p1", "dev").forEach { println("  " + row(it)) }
        }

        for (since in -1L..6L) {
            val now = 5_000L
            val s1 = LiftSlot(0, 1)
            val stages = listOf(
                LiftStage.Warmup, LiftStage.Working(s1), LiftStage.Resting(s1, now + 10),
                LiftStage.Resting(s1, now), LiftStage.Resting(s1, now - 3), LiftStage.Finished,
            )
            println("knock($since)=" + stages.joinToString("") { if (LiftSessionController.isKnock(since, it, now)) "1" else "0" })
        }
        for (ends in listOf(0L, 1, 3, 5, 6, 30, 120)) {
            val (warning, end) = LiftSessionController.restEventTimes(1_000 + ends, 1_000)
            println("rest(+$ends)=$warning,${i(end)}")
        }
        // Labels are Swift's own Double descriptions, written out: JDK 17's Double.toString differs for some.
        val trims = listOf(
            "0.0" to 0.0, "8.0" to 8.0, "8.0001" to 8.0001, "7.5" to 7.5, "12.25" to 12.25, "12.255" to 12.255,
            "45.5" to 45.5, "102.05796" to 102.05796, "0.005" to 0.005, "0.004" to 0.004, "1.995" to 1.995,
            "-2.5" to -2.5, "102.05840462301894" to 225 / 2.20462, "1000000000.0" to 1e9, "99.999" to 99.999,
        )
        for ((label, v) in trims) println("trim($label)=${LiftFormat.trim(v)}")
        for (s in listOf("7,5", " 12.25 ", "", "abc", "1e3", ".5", "5.", "-3", "+4", "1,000.5", "１２")) {
            println("number('$s')=${d(LiftFormat.number(s))}")
        }
        for (s in listOf(0, 5, 59, 60, 61, 90, 125, 600, 3599, 3600)) println("duration($s)=${LiftFormat.duration(s)}")
        for ((label, kg) in listOf("60.0" to 60.0, "102.05795" to 102.05795)) {
            println(
                "lb($label)=${LiftFormat.weight(kg, UnitSystem.IMPERIAL)} kg=${LiftFormat.weight(kg, UnitSystem.METRIC)} " +
                    "back=${d(LiftFormat.kilograms(225.0, UnitSystem.IMPERIAL))}",
            )
        }

        assertEquals(resource("controller_oracle_expected.txt"), out.toString())
    }

    /**
     * A deliberate divergence. Swift's `Double(String)` also accepts "0x10" (16), "inf" and "nan", so the
     * Apple `LiftFormat.number` lets a typed "nan" through as a weight. Kotlin refuses them: no gym number
     * is written in hex, and a NaN or infinite weight would poison every sum it reaches.
     */
    @Test
    fun hexInfinityAndNanAreNotWeights() {
        for (s in listOf("0x10", "inf", "-inf", "nan", "Infinity", "NaN")) assertNull(s, LiftFormat.number(s))
    }
}
