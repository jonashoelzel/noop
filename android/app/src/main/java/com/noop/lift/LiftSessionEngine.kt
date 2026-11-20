package com.noop.lift

import com.noop.data.LiftMuscle

// The session state machine: the heart of the Lift Log. Kotlin twin of `Strand/Data/LiftSessionEngine.swift`.
//
// A GYM IS NOT A QUEUE. The plan is a SHEET of slots, every set of every exercise, all visible, and any
// pending slot can be started at any time. Order is a suggestion the session follows by default.
//
// PURE (no timers, no store, no UI): it is the piece most likely to be wrong in a way that costs someone a
// logged set, and the only part testable with no strap, no database and no device.
//
// TIME ENTERS ONLY AS A PARAMETER, so a test can drive a whole session through a known timeline. Rest is
// an ABSOLUTE end instant, never a decrementing counter: a phone that sleeps through a rest must wake up
// telling the truth.
//
// THERE IS NO COOL-DOWN STAGE. Warm-up is the time before the first set and cool-down the time after the
// last; both fall out of the timestamps.
//
// Behaviour is pinned to the Swift engine by `LiftSessionEngineOracleTest`, whose expected output is the
// Swift engine's own stdout for the same scripted sessions.

/** One set of one exercise: a position on the sheet. [setIndex] is 1-based within its exercise. */
data class LiftSlot(val exerciseIndex: Int, val setIndex: Int)

/** One planned exercise line, flattened from a program for the session to run. */
data class LiftPlanItem(
    val exercise: String,
    val primaryMuscle: LiftMuscle? = null,
    val secondaryMuscles: List<LiftMuscle> = emptyList(),
    /** Planned working sets. Always >= 1: a line scheduling zero sets could not be tapped through. */
    val targetSets: Int = 1,
    /** Intended rest after each set, in seconds. */
    val restSec: Int = DEFAULT_REST_SEC,
    val targetRepsLow: Int? = null,
    val targetRepsHigh: Int? = null,
    val targetRpe: Double? = null,
    /** The weight the program plans, in kilograms. */
    val targetWeightKg: Double? = null,
    val note: String? = null,
    /** The `liftProgramItem.id` this line was flattened from; null for a line with no program behind it. */
    val programItemId: String? = null,
    /** Added while the session ran, so the program has no line for it yet. */
    val addedInSession: Boolean = false,
) {
    companion object {
        /** Rest used when a program line does not specify one; only a starting value. */
        const val DEFAULT_REST_SEC = 120

        /** Build a line the way the Swift initializer normalises one: at least one set, no negative rest. */
        fun of(
            exercise: String,
            primaryMuscle: LiftMuscle? = null,
            secondaryMuscles: List<LiftMuscle> = emptyList(),
            targetSets: Int? = null,
            restSec: Int? = null,
            targetRepsLow: Int? = null,
            targetRepsHigh: Int? = null,
            targetRpe: Double? = null,
            targetWeightKg: Double? = null,
            note: String? = null,
            programItemId: String? = null,
            addedInSession: Boolean = false,
        ) = LiftPlanItem(
            exercise = exercise,
            primaryMuscle = primaryMuscle,
            secondaryMuscles = secondaryMuscles,
            targetSets = maxOf(1, targetSets ?: 1),
            restSec = maxOf(0, restSec ?: DEFAULT_REST_SEC),
            targetRepsLow = targetRepsLow,
            targetRepsHigh = targetRepsHigh,
            targetRpe = targetRpe,
            targetWeightKg = targetWeightKg,
            note = note,
            programItemId = programItemId,
            addedInSession = addedInSession,
        )
    }
}

/** One set as actually performed. */
data class LiftRecordedSet(
    val exerciseIndex: Int,
    val setIndex: Int,
    val weightKg: Double? = null,
    val reps: Int? = null,
    val rpe: Double? = null,
    val isWarmup: Boolean = false,
    val startTs: Long,
    val endTs: Long,
    /** Rest actually taken after this set; null while the rest is still running. */
    val restSec: Int? = null,
) {
    val slot: LiftSlot get() = LiftSlot(exerciseIndex, setIndex)
}

/** The numbers a set shows in grey. Only weight and reps: RPE is never carried. */
data class LiftSetCarry(val weightKg: Double?, val reps: Int?) {
    companion object {
        val NONE = LiftSetCarry(null, null)
    }
}

/** Where a session stands. */
sealed class LiftStage {
    /** Before the first set: the warm-up. */
    object Warmup : LiftStage() {
        override fun toString() = "warmup"
    }

    /** Performing a set. */
    data class Working(val slot: LiftSlot) : LiftStage()

    /** Resting after a set. [endsAt] is an absolute unix second. */
    data class Resting(val slot: LiftSlot, val endsAt: Long) : LiftStage()

    /** Ended; ready to save. */
    object Finished : LiftStage() {
        override fun toString() = "finished"
    }
}

/**
 * The session state machine. Mutable, like the Swift struct's `mutating` methods; [copy] takes an
 * independent copy, which is how the controller publishes each new state to Compose.
 */
class LiftSessionEngine private constructor(
    plan: List<LiftPlanItem>,
    /** When the session began (unix seconds). */
    val startTs: Long,
    stage: LiftStage,
    sets: List<LiftRecordedSet>,
    stageStartedAt: Long,
    history: List<Snapshot>,
) {
    constructor(plan: List<LiftPlanItem>, startTs: Long) :
        this(plan, startTs, LiftStage.Warmup, emptyList(), startTs, emptyList())

    /** The lines being worked. Only a line's set count changes, and lines can be appended. */
    var plan: List<LiftPlanItem> = plan
        private set
    var stage: LiftStage = stage
        private set

    /** Completed sets in COMPLETION order, which is what `ord` is written from. */
    var sets: List<LiftRecordedSet> = sets
        private set

    /** When the current stage began, so a set's duration is measurable. */
    var stageStartedAt: Long = stageStartedAt
        private set

    /** Undo stack: whole-state snapshots, which cannot get the arithmetic wrong the way an inverse can. */
    private val history: ArrayList<Snapshot> = ArrayList(history)

    private data class Snapshot(
        val plan: List<LiftPlanItem>,
        val stage: LiftStage,
        val sets: List<LiftRecordedSet>,
        val stageStartedAt: Long,
    )

    /** An independent copy, undo history included. */
    fun copy(): LiftSessionEngine = LiftSessionEngine(plan, startTs, stage, sets, stageStartedAt, history)

    // MARK: - The sheet

    /** Every slot of one exercise, in order: the rows the sheet draws. */
    fun slots(forExercise: Int): List<LiftSlot> {
        if (forExercise !in plan.indices) return emptyList()
        return (1..plan[forExercise].targetSets).map { LiftSlot(forExercise, it) }
    }

    /** Every slot in the whole session, in plan order. */
    val allSlots: List<LiftSlot> get() = plan.indices.flatMap { slots(it) }

    fun recordedSet(slot: LiftSlot): LiftRecordedSet? = sets.firstOrNull { it.slot == slot }

    fun isCompleted(slot: LiftSlot): Boolean = recordedSet(slot) != null

    /** The slot being worked or rested from. */
    val currentSlot: LiftSlot?
        get() = when (val s = stage) {
            is LiftStage.Working -> s.slot
            is LiftStage.Resting -> s.slot
            else -> null
        }

    /** The first uncompleted slot in plan order; null when the whole sheet is done. */
    val nextPendingSlot: LiftSlot? get() = allSlots.firstOrNull { !isCompleted(it) }

    /**
     * Where the session goes after finishing [slot]: the next uncompleted set of the SAME exercise, and
     * only once that exercise is finished, the first uncompleted slot in plan order. Plan order alone
     * would throw a lifter back to a machine they skipped after every set.
     */
    fun slotAfter(slot: LiftSlot): LiftSlot? =
        slots(slot.exerciseIndex).firstOrNull { !isCompleted(it) } ?: nextPendingSlot

    /** The set the session is heading to after the current one; never a rest. */
    val upcomingSlot: LiftSlot?
        get() = when (val s = stage) {
            LiftStage.Warmup -> nextPendingSlot
            is LiftStage.Working -> upcomingAfter(s.slot)
            is LiftStage.Resting -> upcomingAfter(s.slot)
            LiftStage.Finished -> null
        }

    private fun upcomingAfter(slot: LiftSlot): LiftSlot? =
        slots(slot.exerciseIndex).firstOrNull { !isCompleted(it) && it != slot }
            ?: allSlots.firstOrNull { !isCompleted(it) && it != slot }

    val allCompleted: Boolean get() = nextPendingSlot == null
    val isFinished: Boolean get() = stage == LiftStage.Finished
    val canUndo: Boolean get() = history.isNotEmpty()

    val plannedWorkingSets: Int get() = plan.sumOf { it.targetSets }
    val completedWorkingSets: Int get() = sets.count { !it.isWarmup }

    fun planItem(slot: LiftSlot): LiftPlanItem? = plan.getOrNull(slot.exerciseIndex)

    /** Seconds left in the current rest, floored at zero; null when not resting. */
    fun restRemaining(now: Long): Long? {
        val s = stage as? LiftStage.Resting ?: return null
        return maxOf(0L, s.endsAt - now)
    }

    /** The nearest earlier set of this exercise already performed in THIS session. */
    fun previousSetInSession(slot: LiftSlot): LiftRecordedSet? {
        for (i in (slot.setIndex - 1) downTo 1) {
            recordedSet(LiftSlot(slot.exerciseIndex, i))?.let { return it }
        }
        return null
    }

    /**
     * The numbers a slot shows in grey, in order of preference: this exercise earlier in THIS session
     * (what that set counts as), then the same set number LAST session, then the program's target.
     * RPE is NOT carried. [lastSession] is this exercise's previous session by set number.
     */
    fun carry(slot: LiftSlot, lastSession: Map<Int, LiftSetCarry>): LiftSetCarry {
        val previous = previousSetInSession(slot)?.let { values(it.slot, lastSession) }
        val last = lastSession[slot.setIndex]
        val item = planItem(slot)
        return LiftSetCarry(
            weightKg = previous?.weightKg ?: last?.weightKg ?: item?.targetWeightKg,
            reps = previous?.reps ?: last?.reps ?: item?.targetRepsLow,
        )
    }

    /** What a slot counts as: each number typed into it, else its grey number. */
    fun values(slot: LiftSlot, lastSession: Map<Int, LiftSetCarry>): LiftSetCarry {
        val grey = carry(slot, lastSession)
        val set = recordedSet(slot) ?: return grey
        return LiftSetCarry(set.weightKg ?: grey.weightKg, set.reps ?: grey.reps)
    }

    /** Slots never performed. */
    val unperformedSlots: List<LiftSlot> get() = allSlots.filter { !isCompleted(it) }

    // MARK: - Actions

    /**
     * Begin a specific set, from any stage. Starting a set while another is in progress abandons that
     * one; starting a completed slot re-opens it for a redo and drops its previous record.
     */
    fun start(slot: LiftSlot, now: Long) {
        if (planItem(slot) == null) return
        pushHistory()
        sets = sets.filter { it.slot != slot }
        stage = LiftStage.Working(slot)
        stageStartedAt = now
    }

    /** The big button. Context decides what it means. */
    fun advance(now: Long) {
        when (val s = stage) {
            LiftStage.Warmup -> {
                val next = nextPendingSlot ?: return
                start(next, now)
            }
            is LiftStage.Working -> {
                pushHistory()
                // Timing only: the numbers stay grey until typed.
                sets = sets + LiftRecordedSet(
                    exerciseIndex = s.slot.exerciseIndex, setIndex = s.slot.setIndex,
                    startTs = stageStartedAt, endTs = now,
                )
                val rest = planItem(s.slot)?.restSec ?: LiftPlanItem.DEFAULT_REST_SEC
                stage = LiftStage.Resting(s.slot, now + rest)
                stageStartedAt = now
            }
            is LiftStage.Resting -> {
                pushHistory()
                // Record what was ACTUALLY rested.
                sets = sets.map { if (it.slot == s.slot) it.copy(restSec = maxOf(0L, now - stageStartedAt).toInt()) else it }
                val next = slotAfter(s.slot)
                if (next != null) {
                    stage = LiftStage.Working(next)
                    stageStartedAt = now
                } else {
                    // Sheet complete: stay put. The time until the user finishes is the cool-down.
                    stage = LiftStage.Resting(s.slot, now)
                    stageStartedAt = now
                }
            }
            LiftStage.Finished -> Unit
        }
    }

    /** Fill in or correct a completed set. Never appends; a no-op for a slot not yet completed. */
    fun updateSet(slot: LiftSlot, weightKg: Double?, reps: Int?, rpe: Double?, isWarmup: Boolean) {
        if (sets.none { it.slot == slot }) return
        sets = sets.map {
            if (it.slot == slot) it.copy(weightKg = weightKg, reps = reps, rpe = rpe, isWarmup = isWarmup) else it
        }
    }

    // MARK: - Changing how many sets a line holds

    /** Append one set to an exercise's line. False when the line is already at [MAX_SETS_PER_EXERCISE]. */
    fun addSet(toExercise: Int): Boolean {
        if (toExercise !in plan.indices || plan[toExercise].targetSets >= MAX_SETS_PER_EXERCISE) return false
        pushHistory()
        plan = plan.mapIndexed { i, item -> if (i == toExercise) item.copy(targetSets = item.targetSets + 1) else item }
        return true
    }

    /** Only the LAST set, only while pending, and never the one the session stands on. */
    fun canRemoveSet(fromExercise: Int): Boolean {
        if (fromExercise !in plan.indices || plan[fromExercise].targetSets <= 1) return false
        val last = LiftSlot(fromExercise, plan[fromExercise].targetSets)
        return !isCompleted(last) && currentSlot != last
    }

    /** Drop the last (pending) set of an exercise's line. */
    fun removeSet(fromExercise: Int): Boolean {
        if (!canRemoveSet(fromExercise)) return false
        pushHistory()
        plan = plan.mapIndexed { i, item -> if (i == fromExercise) item.copy(targetSets = item.targetSets - 1) else item }
        return true
    }

    // MARK: - Adding an exercise

    /** Append an exercise as ONE set at the end of the sheet; any set count given is ignored. */
    fun addExercise(item: LiftPlanItem): Boolean {
        if (stage == LiftStage.Finished || plan.size >= MAX_EXERCISES) return false
        pushHistory()
        plan = plan + item.copy(targetSets = 1)
        return true
    }

    /** End the session, closing out a running rest first so its measured duration is kept. */
    fun finish(now: Long) {
        if (stage == LiftStage.Finished) return
        pushHistory()
        val s = stage
        if (s is LiftStage.Resting) {
            sets = sets.map {
                if (it.slot == s.slot && it.restSec == null) it.copy(restSec = maxOf(0L, now - stageStartedAt).toInt()) else it
            }
        }
        stage = LiftStage.Finished
        stageStartedAt = now
    }

    fun undo() {
        if (history.isEmpty()) return
        val previous = history.removeAt(history.size - 1)
        plan = previous.plan
        stage = previous.stage
        sets = previous.sets
        stageStartedAt = previous.stageStartedAt
    }

    private fun pushHistory() {
        history.add(Snapshot(plan, stage, sets, stageStartedAt))
    }

    override fun equals(other: Any?): Boolean =
        other is LiftSessionEngine && plan == other.plan && startTs == other.startTs && stage == other.stage &&
            sets == other.sets && stageStartedAt == other.stageStartedAt && history == other.history

    override fun hashCode(): Int = listOf(plan, startTs, stage, sets, stageStartedAt).hashCode()

    companion object {
        /** A bound against a stuck finger, not a recommendation. */
        const val MAX_SETS_PER_EXERCISE = 20

        /** The importer's cap on one program, so a grown session stays inside its snapshot's bound. */
        const val MAX_EXERCISES = 200

        /**
         * Rebuild an interrupted session. The undo history is deliberately NOT restored: a stack that
         * survives a relaunch invites reaching back past a save boundary.
         */
        fun restoring(
            plan: List<LiftPlanItem>,
            startTs: Long,
            stage: LiftStage,
            sets: List<LiftRecordedSet>,
            stageStartedAt: Long,
        ) = LiftSessionEngine(plan, startTs, stage, sets, stageStartedAt, emptyList())
    }
}
