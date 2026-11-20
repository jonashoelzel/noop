package com.noop.lift

import com.noop.data.LiftMuscle
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [LiftSessionEngine] to the Swift engine (`Strand/Data/LiftSessionEngine.swift`) by oracle, as
 * AGENTS.md asks for twins: the Swift engine runs `lift/engine_oracle_script.txt` and prints its whole
 * state after every op, and that stdout is `lift/engine_oracle_expected.txt`, verbatim. This test runs the
 * same script through the Kotlin engine, prints the same dump, and compares. The Swift driver and the
 * commands that regenerate the expected file are in `lift/LiftEngineOracle.swift.txt`.
 *
 * The script covers the warm-up, set → rest → set on one exercise, a zero-rest line, out-of-order starts,
 * a redo, typed numbers and warm-up flags, grey-number carry from this session / last session / the
 * program, adding and removing sets at and past their bounds, adding an exercise, undo across a set-count
 * change, finishing mid-rest, and the refusals after finishing.
 */
class LiftSessionEngineOracleTest {

    private fun d(v: Double?) = v?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "-"
    private fun i(v: Number?) = v?.toString() ?: "-"
    private fun slot(s: LiftSlot?) = s?.let { "${it.exerciseIndex}.${it.setIndex}" } ?: "-"
    private fun stage(s: LiftStage) = when (s) {
        LiftStage.Warmup -> "warmup"
        is LiftStage.Working -> "working(${slot(s.slot)})"
        is LiftStage.Resting -> "resting(${slot(s.slot)},${s.endsAt})"
        LiftStage.Finished -> "finished"
    }

    private val last: Map<String, Map<Int, LiftSetCarry>> = mapOf(
        "Bench" to mapOf(1 to LiftSetCarry(57.5, 8), 2 to LiftSetCarry(57.5, 7)),
        "Curl" to mapOf(1 to LiftSetCarry(20.0, 12)),
    )

    private fun dump(e: LiftSessionEngine, now: Long): String {
        val sb = StringBuilder()
        sb.append("stage=${stage(e.stage)} at=${e.stageStartedAt} plan=")
        sb.append(e.plan.joinToString(",") { "${it.exercise}:${it.targetSets}" })
        sb.append(" next=${slot(e.nextPendingSlot)} up=${slot(e.upcomingSlot)} cur=${slot(e.currentSlot)}")
        sb.append(" undo=${e.canUndo} done=${e.allCompleted} fin=${e.isFinished} planned=${e.plannedWorkingSets}")
        sb.append(" completed=${e.completedWorkingSets} unperf=${e.unperformedSlots.size} rest=${i(e.restRemaining(now + 30))}\n")
        sb.append("  sets=")
        for (s in e.sets) {
            sb.append("[${slot(s.slot)} w=${d(s.weightKg)} r=${i(s.reps)} rpe=${d(s.rpe)} wu=${s.isWarmup} ${s.startTs}-${s.endTs} rest=${i(s.restSec)}]")
        }
        sb.append("\n  carry=")
        sb.append(
            e.allSlots.joinToString(" ") { s ->
                val ls = last[e.planItem(s)!!.exercise] ?: emptyMap()
                val c = e.carry(s, ls)
                val v = e.values(s, ls)
                "${slot(s)}:${d(c.weightKg)}x${i(c.reps)}/${d(v.weightKg)}x${i(v.reps)}>${slot(e.slotAfter(s))}"
            },
        )
        sb.append(" canRm=")
        sb.append(e.plan.indices.joinToString("") { if (e.canRemoveSet(it)) "1" else "0" })
        return sb.toString()
    }

    private fun resource(name: String): String =
        requireNotNull(javaClass.classLoader!!.getResource("lift/$name")) { "missing lift/$name" }.readText()

    @Test
    fun kotlinEngineMatchesTheSwiftEngineOpForOp() {
        val plan = listOf(
            LiftPlanItem.of(
                "Bench", primaryMuscle = LiftMuscle.chest, targetSets = 3, restSec = 90,
                targetRepsLow = 8, targetRepsHigh = 10, targetRpe = 8.0, targetWeightKg = 60.0,
            ),
            LiftPlanItem.of(
                "Squat", primaryMuscle = LiftMuscle.quads, secondaryMuscles = listOf(LiftMuscle.glutes),
                targetSets = 2, restSec = 0, targetRepsLow = 10,
            ),
            LiftPlanItem.of("Curl", targetSets = null, restSec = null),
            LiftPlanItem.of("Zero", targetSets = 0, restSec = -5),
        )
        val e = LiftSessionEngine(plan, 1_000)
        val out = StringBuilder()
        fun println(line: String) = out.append(line).append('\n')

        println(dump(e, 1_000))
        for (raw in resource("engine_oracle_script.txt").split("\n")) {
            val p = raw.split(" ").filter { it.isNotEmpty() }
            if (p.isEmpty()) continue
            fun n(k: Int) = p[k].toLong()
            var now = 0L
            when (p[0]) {
                "advance" -> { now = n(1); e.advance(now) }
                "start" -> { now = n(3); e.start(LiftSlot(n(1).toInt(), n(2).toInt()), now) }
                "update" -> e.updateSet(
                    LiftSlot(n(1).toInt(), n(2).toInt()),
                    weightKg = p[3].takeIf { it != "-" }?.toDouble(),
                    reps = p[4].takeIf { it != "-" }?.toInt(),
                    rpe = p[5].takeIf { it != "-" }?.toDouble(),
                    isWarmup = p[6] == "1",
                )
                "addset" -> println("  -> ${e.addSet(n(1).toInt())}")
                "rmset" -> println("  -> ${e.removeSet(n(1).toInt())}")
                "addex" -> println(
                    "  -> " + e.addExercise(
                        LiftPlanItem.of(
                            p[1], targetSets = 4, targetRepsLow = 0, targetWeightKg = 0.0,
                            programItemId = "new", addedInSession = true,
                        ),
                    ),
                )
                "finish" -> { now = n(1); e.finish(now) }
                "undo" -> e.undo()
                else -> error(raw)
            }
            println("> $raw")
            println(dump(e, now))
        }
        assertEquals(resource("engine_oracle_expected.txt"), out.toString())
    }

    @Test
    fun aCopyIsIndependent() {
        val e = LiftSessionEngine(listOf(LiftPlanItem.of("Bench", targetSets = 2)), 1_000)
        e.advance(1_100)
        val copy = e.copy()
        copy.advance(1_200)
        assertEquals(LiftStage.Working(LiftSlot(0, 1)), e.stage)
        assertEquals(1, copy.sets.size)
        copy.undo()
        assertEquals(e, copy)
    }
}
