package com.noop.lift

import android.content.SharedPreferences
import com.noop.analytics.LiftMetrics
import com.noop.data.LiftMuscle
import com.noop.data.LiftProgramItemRow
import com.noop.lift.LiftSessionPersistence.PendingSetValues
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The live gym session, owned ABOVE any screen. Kotlin twin of `Strand/Data/LiftSessionController.swift`.
 *
 * A workout outlives the screen you happen to be looking at, so the session does too: this controller owns
 * the engine, the rest's timers, the buzz gating and the persistence, and is created once per process
 * (`NoopApplication.liftSession`). The session screen is a rendering of it, the bar on the Workouts tab
 * another; leaving either changes nothing about the session.
 *
 * While a session runs it takes the strap double-tap ([wantsStrapTap]); `AppViewModel.dispatchDoubleTap`
 * asks it first and hands the tap to the user's configured action only when no session is running.
 *
 * Kept free of Android UI and of strings, so it runs on the plain JVM: [prefs] is null in tests, time comes
 * from [clock], and the screens turn [State] into words.
 */
class LiftSessionController(
    private val prefs: SharedPreferences?,
    private val scope: CoroutineScope,
    /** Fires the strap buzz (already gated by the user's haptic setting). */
    private val buzz: (Int) -> Unit,
    /** Writes a line to the strap log. */
    private val log: (String) -> Unit = {},
    /** The current unix second. */
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    /** Everything about the running session a screen can show. [engine] is null when none is in flight. */
    data class State(
        val engine: LiftSessionEngine? = null,
        val programId: String? = null,
        val programName: String? = null,
        /** Numbers typed into a set BEFORE it was performed, applied the instant it is. */
        val pendingValues: Map<LiftSlot, PendingSetValues> = emptyMap(),
        /** Slots marked a warm-up before they were performed. */
        val pendingWarmups: Set<LiftSlot> = emptySet(),
        /** What the store holds for each exercise LAST session: exercise name → set number → numbers. */
        val lastSession: Map<String, Map<Int, LiftSetCarry>> = emptyMap(),
        /** Bumped when a running rest ends, the one moment a session's words change with time alone. */
        val restEndedTick: Int = 0,
        /** Bumped each time a finished session is written, so lists of sessions reload. */
        val savedSessions: Int = 0,
    ) {
        val isActive: Boolean get() = engine != null && !engine.isFinished

        private fun lastFor(slot: LiftSlot): Map<Int, LiftSetCarry> =
            engine?.planItem(slot)?.let { lastSession[it.exercise] } ?: emptyMap()

        /** The grey numbers a slot shows: the one chain every surface reads. */
        fun carry(slot: LiftSlot): LiftSetCarry = engine?.carry(slot, lastFor(slot)) ?: LiftSetCarry.NONE

        /** What a slot counts as: typed numbers, else its grey ones. */
        fun values(slot: LiftSlot): LiftSetCarry = engine?.values(slot, lastFor(slot)) ?: LiftSetCarry.NONE

        /** What the row shows: numbers typed in advance beat the grey ones (the bar must agree with the row). */
        fun shownNumbers(slot: LiftSlot): LiftSetCarry {
            val grey = values(slot)
            val typed = pendingValues[slot]
            return LiftSetCarry(typed?.weightKg ?: grey.weightKg, typed?.reps ?: grey.reps)
        }

        /** What a slot is showing as typed: what it recorded, or what was typed into it in advance. */
        fun enteredValues(slot: LiftSlot): PendingSetValues {
            engine?.recordedSet(slot)?.let { return PendingSetValues(it.weightKg, it.reps, it.rpe) }
            return pendingValues[slot] ?: PendingSetValues()
        }

        fun isWarmup(slot: LiftSlot): Boolean = engine?.recordedSet(slot)?.isWarmup ?: (slot in pendingWarmups)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val isActive: Boolean get() = _state.value.isActive

    /** Whether a strap double-tap should come here rather than to the user's configured action. */
    val wantsStrapTap: Boolean get() = isActive

    /** The rest end the warning has already fired for; lives here so reopening a screen cannot re-fire it. */
    private var warnedFor: Long? = null

    /** When the session last acted on a strap double-tap. Null until it has, and after a relaunch. */
    private var lastStrapStepAt: Long? = null

    private var restTimers: List<Job> = emptyList()
    private var scheduledRestEnd: Long? = null

    // MARK: - Lifecycle

    fun start(plan: List<LiftPlanItem>, programId: String?, programName: String?) {
        warnedFor = null
        lastStrapStepAt = null
        publish(State(engine = LiftSessionEngine(plan, clock()), programId = programId, programName = programName,
            savedSessions = _state.value.savedSessions))
    }

    /**
     * Pick up the session a previous process left going. Called when the process starts, before any
     * screen, so the session is back before anything asks about it.
     */
    fun resumeSaved() {
        if (isActive) return
        val snapshot = prefs?.let { LiftSessionPersistence.load(it) } ?: return
        resume(snapshot)
        log("Lift Log: session picked up again after NOOP restarted")
    }

    fun resume(snapshot: LiftSessionPersistence.Snapshot) {
        val engine = LiftSessionPersistence.engine(snapshot)
        // Suppress the warning for a rest already inside its final seconds: reopening mid-rest must not
        // greet the user with three buzzes for a rest they have watched count down.
        val stage = engine.stage
        warnedFor = if (stage is LiftStage.Resting && stage.endsAt - clock() <= REST_WARNING_LEAD_SEC) stage.endsAt else null
        lastStrapStepAt = null
        publish(
            State(
                engine = engine,
                programId = snapshot.programId,
                programName = snapshot.programName,
                pendingValues = LiftSessionPersistence.pendingValues(snapshot),
                pendingWarmups = LiftSessionPersistence.pendingWarmups(snapshot),
                savedSessions = _state.value.savedSessions,
            ),
            persist = false,
        )
    }

    /** Give up the session without saving. */
    fun discard() {
        teardown(saved = false)
    }

    /** Called once the session has been written to the store. */
    fun finishedSaving() {
        teardown(saved = true)
    }

    private fun teardown(saved: Boolean) {
        warnedFor = null
        lastStrapStepAt = null
        val count = _state.value.savedSessions + if (saved) 1 else 0
        publish(State(savedSessions = count), persist = false)
        prefs?.let { LiftSessionPersistence.clear(it) }
    }

    // MARK: - Actions

    /** The one action. [fromStrap] earns a single confirming buzz, unless the tap reads as a knock. */
    fun advance(fromStrap: Boolean = false) {
        val current = _state.value.engine ?: return
        val now = clock()
        if (fromStrap) {
            val last = lastStrapStepAt
            if (last != null && isKnock(now - last, current.stage, now)) {
                // No buzz: the missing confirmation is the lifter's cue to tap again.
                log(
                    "Lift Log: that double-tap was not acted on — ${now - last} s after the last one it acted on " +
                        "(under $STRAP_KNOCK_WINDOW_SEC s is taken as a knock)",
                )
                return
            }
            lastStrapStepAt = now
            // BUZZ FIRST: the confirmation is a latency signal and must not queue behind bookkeeping.
            buzz(ADVANCE_CONFIRM_BUZZES)
        }
        val engine = current.copy()
        engine.advance(now)
        warnedFor = null
        publish(applyPendingInput(_state.value.copy(engine = engine)))
    }

    /** Begin a specific set: the out-of-order path, for when a machine is occupied. */
    fun start(slot: LiftSlot) {
        val engine = _state.value.engine?.copy() ?: return
        engine.start(slot, clock())
        warnedFor = null
        publish(_state.value.copy(engine = engine))
    }

    /** Hand over what the store knows about previous sessions; safe to call again on reload. */
    fun setLastSession(values: Map<String, Map<Int, LiftSetCarry>>) {
        publish(_state.value.copy(lastSession = values), persist = false)
    }

    /** Mark a slot as a warm-up (or not): applied now when the set exists, remembered when it does not yet. */
    fun setWarmup(slot: LiftSlot, isWarmup: Boolean) {
        val s = _state.value
        val warmups = if (isWarmup) s.pendingWarmups + slot else s.pendingWarmups - slot
        val engine = s.engine?.copy()
        engine?.recordedSet(slot)?.let { engine.updateSet(slot, it.weightKg, it.reps, it.rpe, isWarmup) }
        publish(s.copy(engine = engine, pendingWarmups = warmups))
    }

    /**
     * Fill in or correct a set's numbers, ANY set, at any time. A performed set is edited in the engine; a
     * set not yet performed cannot be (that would invent it), so its numbers wait in pendingValues.
     */
    fun updateSet(slot: LiftSlot, weightKg: Double?, reps: Int?, rpe: Double?, isWarmup: Boolean) {
        val s = _state.value
        val engine = s.engine?.copy() ?: return
        if (engine.recordedSet(slot) != null) {
            engine.updateSet(slot, weightKg, reps, rpe, isWarmup)
            publish(s.copy(engine = engine))
        } else if (engine.planItem(slot) != null) {
            val values = PendingSetValues(weightKg, reps, rpe)
            // Clearing the last field clears the entry, so the row goes back to its grey ghost.
            val pending = if (values.isEmpty) s.pendingValues - slot else s.pendingValues + (slot to values)
            publish(s.copy(pendingValues = pending))
        }
    }

    /** Add one set to an exercise: the unplanned fifth set. */
    fun addSet(toExercise: Int): Boolean {
        val engine = _state.value.engine?.copy() ?: return false
        if (!engine.addSet(toExercise)) return false
        publish(_state.value.copy(engine = engine))
        return true
    }

    /**
     * Add an exercise the program does not have, at the end of the sheet: one set planned as 0 kg x 0 reps
     * (or last session's numbers, when it has been done before), carrying the id its program line will
     * have if finishing adds it.
     */
    fun addExercise(name: String, primaryMuscle: LiftMuscle?, secondaryMuscles: List<LiftMuscle>): Boolean {
        val engine = _state.value.engine?.copy() ?: return false
        val line = LiftPlanItem.of(
            exercise = name, primaryMuscle = primaryMuscle,
            secondaryMuscles = secondaryMuscles.filter { it != primaryMuscle },
            targetSets = 1, targetRepsLow = 0, targetWeightKg = 0.0,
            programItemId = UUID.randomUUID().toString().uppercase(), addedInSession = true,
        )
        if (!engine.addExercise(line)) return false
        publish(_state.value.copy(engine = engine))
        return true
    }

    /** Drop the last pending set of an exercise; a completed set is never removed this way. */
    fun removeSet(fromExercise: Int): Boolean {
        val s = _state.value
        val engine = s.engine?.copy() ?: return false
        if (!engine.canRemoveSet(fromExercise)) return false
        val dropped = LiftSlot(fromExercise, engine.plan[fromExercise].targetSets)
        engine.removeSet(fromExercise)
        // A slot that no longer exists must not keep a warm-up mark or typed numbers.
        publish(s.copy(engine = engine, pendingWarmups = s.pendingWarmups - dropped, pendingValues = s.pendingValues - dropped))
        return true
    }

    fun undo() {
        val engine = _state.value.engine?.copy() ?: return
        engine.undo()
        publish(_state.value.copy(engine = engine))
    }

    /** End the session (the rest that was running is closed out first). */
    fun finish() {
        val engine = _state.value.engine?.copy() ?: return
        engine.finish(clock())
        publish(_state.value.copy(engine = engine))
    }

    /** Sets never started. Finishing asks once whether to complete them with their grey numbers or discard them. */
    val unfinishedSlots: List<LiftSlot> get() = _state.value.engine?.unperformedSlots ?: emptyList()

    /** The sets the finished session saves. See the companion [setsToSave]. */
    fun setsToSave(completingUnfinished: Boolean): List<FinishedSet> = setsToSave(_state.value, completingUnfinished)

    /**
     * Carry a pre-marked warm-up, and numbers typed in advance, onto the set just recorded. The entry is
     * CONSUMED, so a redo of the set shows its ghosts again rather than resurrecting what was typed.
     */
    private fun applyPendingInput(s: State): State {
        val engine = s.engine ?: return s
        val last = engine.sets.lastOrNull() ?: return s
        val typed = s.pendingValues[last.slot]
        val warmup = last.isWarmup || last.slot in s.pendingWarmups
        if (typed == null && warmup == last.isWarmup) return s
        engine.updateSet(
            last.slot,
            weightKg = typed?.weightKg ?: last.weightKg,
            reps = typed?.reps ?: last.reps,
            rpe = typed?.rpe ?: last.rpe,
            isWarmup = warmup,
        )
        return s.copy(engine = engine, pendingValues = s.pendingValues - last.slot)
    }

    // MARK: - Publishing, persistence and the rest's timers

    private fun publish(next: State, persist: Boolean = true) {
        _state.value = next
        scheduleRestTimers(next.engine)
        if (persist) persist(next)
    }

    private fun persist(s: State) {
        val p = prefs ?: return
        val engine = s.engine ?: return
        if (engine.isFinished) return
        LiftSessionPersistence.store(
            p,
            LiftSessionPersistence.snapshot(engine, s.programId, s.programName, s.pendingValues, s.pendingWarmups),
        )
    }

    /**
     * A running session changes with time alone at two moments of each rest: the warning buzz
     * [REST_WARNING_LEAD_SEC] before it ends, and its end. Each gets a one-shot timer, replaced whenever the
     * rest does; between taps a session does no work at all.
     */
    private fun scheduleRestTimers(engine: LiftSessionEngine?) {
        val endsAt = (engine?.stage as? LiftStage.Resting)?.endsAt
        if (endsAt == scheduledRestEnd) return
        scheduledRestEnd = endsAt
        restTimers.forEach { it.cancel() }
        restTimers = emptyList()
        if (endsAt == null) return
        val now = clock()
        val times = restEventTimes(endsAt, now)
        val jobs = mutableListOf(
            scope.launch {
                delay((times.first - now) * 1000)
                fireRestWarningIfDue()
            },
        )
        times.second?.let { end ->
            jobs += scope.launch {
                delay((end - now) * 1000)
                _state.value = _state.value.copy(restEndedTick = _state.value.restEndedTick + 1)
            }
        }
        restTimers = jobs
    }

    private fun fireRestWarningIfDue() {
        val stage = _state.value.engine?.stage as? LiftStage.Resting ?: return
        if (warnedFor == stage.endsAt) return
        if (stage.endsAt - clock() > REST_WARNING_LEAD_SEC) return
        warnedFor = stage.endsAt
        buzz(REST_WARNING_BUZZES)
    }

    /** One set as the finished session saves it. Timing is null for a set completed at finish without being started. */
    data class FinishedSet(
        val slot: LiftSlot,
        val weightKg: Double?,
        val reps: Int?,
        val rpe: Double?,
        val isWarmup: Boolean,
        val startTs: Long?,
        val endTs: Long?,
        val restSec: Int?,
    )

    /** A program line whose set count this session changed. */
    data class SetCountChange(val itemId: String, val exercise: String, val from: Int, val to: Int)

    companion object {
        /** One pulse confirms a strap double-tap registered; three mean the rest is nearly up. */
        const val ADVANCE_CONFIRM_BUZZES = 1
        const val REST_WARNING_BUZZES = 3

        /** How long before the rest ends the warning fires. */
        const val REST_WARNING_LEAD_SEC = 5

        /** A strap double-tap this soon after the last one acted on is taken as a knock. */
        const val STRAP_KNOCK_WINDOW_SEC = 5

        /**
         * Whether a strap double-tap [secondsSinceLastStep] after the last one acted on is a knock (the arm
         * going onto the bar) rather than a tap. Under [STRAP_KNOCK_WINDOW_SEC] counts as a knock, except
         * from a rest that is already over, where going straight on is the plan.
         */
        fun isKnock(secondsSinceLastStep: Long, stage: LiftStage, now: Long): Boolean {
            if (secondsSinceLastStep !in 0 until STRAP_KNOCK_WINDOW_SEC) return false
            if (stage is LiftStage.Resting && stage.endsAt <= now) return false
            return true
        }

        /**
         * When a rest's warning and end fire, in unix seconds. The warning comes [REST_WARNING_LEAD_SEC]
         * before the end, or one second from now when the rest is already inside that window; the end
         * fires only for a rest still to run.
         */
        fun restEventTimes(endsAt: Long, now: Long): Pair<Long, Long?> =
            maxOf(now + 1, endsAt - REST_WARNING_LEAD_SEC) to (if (endsAt > now) endsAt else null)

        /**
         * The sets the session saves: every slot on the sheet. A set that was DONE saves what was typed,
         * a blank number taking the grey value shown, and an unrated set the line's max RPE. Sets never
         * started save their grey numbers (and anything typed in advance) when [completingUnfinished], else
         * 0 kg x 0 reps, which every figure leaves out. Done sets keep the order they happened in and their
         * timing; sets never started follow in plan order, with no timing.
         */
        fun setsToSave(s: State, completingUnfinished: Boolean): List<FinishedSet> {
            val engine = s.engine ?: return emptyList()
            fun planned(slot: LiftSlot) = engine.planItem(slot)?.targetRpe
            val out = engine.sets.map { set ->
                val shown = s.values(set.slot)
                FinishedSet(
                    set.slot, shown.weightKg, shown.reps, set.rpe ?: planned(set.slot), set.isWarmup,
                    set.startTs, set.endTs, set.restSec,
                )
            }.toMutableList()
            for (slot in engine.allSlots) {
                if (engine.isCompleted(slot)) continue
                val typed = s.pendingValues[slot]
                val grey = s.carry(slot)
                out += FinishedSet(
                    slot = slot,
                    weightKg = if (completingUnfinished) typed?.weightKg ?: grey.weightKg else 0.0,
                    reps = if (completingUnfinished) typed?.reps ?: grey.reps else 0,
                    rpe = if (completingUnfinished) typed?.rpe ?: planned(slot) else null,
                    isWarmup = slot in s.pendingWarmups,
                    startTs = null, endTs = null, restSec = null,
                )
            }
            return out
        }

        /** Whether any of [sets] was performed. When none was, there is nothing to file. */
        fun anyPerformed(sets: List<FinishedSet>): Boolean = sets.any { LiftMetrics.isPerformed(it.reps) }

        /**
         * Lines whose set count in this session differs from the program's current one. A line with no count
         * counts as one; a line deleted since, or added during the session, is skipped.
         */
        fun setCountChanges(plan: List<LiftPlanItem>, program: List<LiftProgramItemRow>): List<SetCountChange> {
            val byId = LinkedHashMap<String, LiftProgramItemRow>()
            for (row in program) byId.putIfAbsent(row.id, row)
            return plan.mapNotNull { line ->
                val id = line.programItemId ?: return@mapNotNull null
                val row = byId[id] ?: return@mapNotNull null
                val saved = maxOf(1, row.targetSets ?: 1)
                if (saved == line.targetSets) null else SetCountChange(id, line.exercise, saved, line.targetSets)
            }
        }

        /**
         * The program's lines with each one's HEAVIEST done set this session as its new weight and reps.
         * Only sets actually done count (not warm-ups, discards, or sets completed at finish); at equal
         * weight, more reps wins. A rep-range top below the new count is dropped.
         */
        fun applyingHeaviestSets(
            sets: List<FinishedSet>,
            plan: List<LiftPlanItem>,
            items: List<LiftProgramItemRow>,
        ): List<LiftProgramItemRow> {
            val heaviest = HashMap<String, FinishedSet>()
            for (set in sets) {
                if (set.startTs == null || set.isWarmup || !LiftMetrics.isPerformed(set.reps)) continue
                val id = plan.getOrNull(set.slot.exerciseIndex)?.programItemId ?: continue
                val best = heaviest[id]
                if (best != null && !isHeavier(set, best)) continue
                heaviest[id] = set
            }
            return items.map { row ->
                val top = heaviest[row.id] ?: return@map row
                var edited = row
                top.weightKg?.let { edited = edited.copy(targetWeightKg = it) }
                top.reps?.let { reps ->
                    val high = edited.targetRepsHigh
                    edited = edited.copy(targetRepsLow = reps, targetRepsHigh = if (high != null && high < reps) null else high)
                }
                edited
            }
        }

        private fun isHeavier(a: FinishedSet, b: FinishedSet): Boolean {
            val wa = a.weightKg ?: -1.0
            val wb = b.weightKg ?: -1.0
            if (wa != wb) return wa > wb
            return (a.reps ?: -1) > (b.reps ?: -1)
        }

        /**
         * The program's lines after this session. Every line takes its heaviest done set; with
         * [keepingChanges], changed set counts move too and each exercise added during the session becomes
         * a new line at the end, in the order added.
         */
        fun programAfterSession(
            sets: List<FinishedSet>,
            plan: List<LiftPlanItem>,
            program: List<LiftProgramItemRow>,
            keepingChanges: Boolean,
            programId: String,
            deviceId: String,
        ): List<LiftProgramItemRow> {
            var lines = program
            if (keepingChanges) {
                lines = applying(setCountChanges(plan, program), lines)
                val next = (program.maxOfOrNull { it.ord } ?: -1) + 1
                val added = lines.toMutableList()
                for ((offset, line) in plan.filter { it.addedInSession }.withIndex()) {
                    val id = line.programItemId ?: continue
                    if (added.any { it.id == id }) continue
                    added += LiftProgramItemRow(
                        id = id, deviceId = deviceId, programId = programId, ord = next + offset,
                        exercise = line.exercise, targetSets = line.targetSets,
                        targetRepsLow = 0, targetRepsHigh = null, targetRpe = null, targetWeightKg = 0.0,
                        restSec = null, note = null,
                    )
                }
                lines = added
            }
            return applyingHeaviestSets(sets, plan, lines)
        }

        /** The program's lines with [changes] applied. Only targetSets moves. */
        fun applying(changes: List<SetCountChange>, items: List<LiftProgramItemRow>): List<LiftProgramItemRow> {
            val counts = LinkedHashMap<String, Int>()
            for (c in changes) counts.putIfAbsent(c.itemId, c.to)
            return items.map { row -> counts[row.id]?.let { row.copy(targetSets = it) } ?: row }
        }
    }
}
