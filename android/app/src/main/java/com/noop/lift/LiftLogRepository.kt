package com.noop.lift

import com.noop.data.LiftExerciseRow
import com.noop.data.LiftLogDao
import com.noop.data.LiftMuscle
import com.noop.data.LiftProgramItemRow
import com.noop.data.LiftProgramRow
import com.noop.data.LiftSessionRow
import com.noop.data.LiftSetCounts
import com.noop.data.LiftSetEntity
import com.noop.data.WorkoutRow
import java.util.UUID

/**
 * What the Lift Log screens read and write, in one place. The Swift screens each talk to the store
 * directly; collecting the same calls here keeps the Compose screens thin and gives the load and save
 * rules (the parts that decide what lands on disk) a single home.
 *
 * [deviceId] is the registry's active device id, which is what the Apple screens key the log on
 * (`Repository.deviceId`). [saveWorkout] files the paired workout through the same path a manual workout
 * takes (overlap dedup, the engine's HR-derived strain fill), and [deleteWorkout] removes it.
 */
class LiftLogRepository(
    private val dao: LiftLogDao,
    private val deviceId: () -> String,
    private val saveWorkout: (WorkoutRow) -> Unit,
    private val deleteWorkout: (WorkoutRow) -> Unit,
    private val workoutsAt: suspend (deviceId: String, startTs: Long) -> List<WorkoutRow>,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    val activeDeviceId: String get() = deviceId()

    // MARK: - The hub

    data class Hub(
        val programs: List<LiftProgramRow>,
        /** Finished sessions from the last 180 days, newest first. */
        val history: List<LiftSessionRow>,
        /** This week's fractional sets per muscle. */
        val weekCounts: Map<LiftMuscle, Double>,
    )

    suspend fun hub(): Hub {
        val id = deviceId()
        val now = clock()
        return Hub(
            programs = dao.liftPrograms(id),
            // An abandoned session is not history.
            history = dao.liftSessions(id, now - 180 * 86_400L, now).filter { it.endTs != null }.sortedByDescending { it.startTs },
            weekCounts = dao.liftSetCounts(id, now - 7 * 86_400L, now).fractional,
        )
    }

    // MARK: - Programs

    suspend fun programItems(programId: String): List<LiftProgramItemRow> = dao.liftProgramItems(programId)

    suspend fun exercises(): List<LiftExerciseRow> = dao.liftExercises(deviceId())

    suspend fun forgetExercise(id: String) {
        dao.deleteLiftExercise(id)
    }

    /**
     * Remember an exercise with its muscles, refreshing its recency. Throws
     * [com.noop.data.LiftExerciseVocabularyFull] for a new name once the vocabulary is full.
     * Swift `LiftExerciseVocabulary.remember`.
     */
    suspend fun rememberExercise(name: String, primary: LiftMuscle?, secondaries: List<LiftMuscle>, known: List<LiftExerciseRow>) {
        val now = clock()
        val existing = known.firstOrNull { it.name == name }
        dao.upsertLiftExercises(
            listOf(
                LiftExerciseRow(
                    id = existing?.id ?: newId(),
                    deviceId = deviceId(),
                    name = name,
                    primaryMuscle = primary?.name,
                    secondaryMuscles = LiftMuscle.encodeList(secondaries, primary),
                    createdAt = existing?.createdAt ?: now,
                    lastUsedTs = now,
                ),
            ),
        )
    }

    /** Save a program and its lines (renumbered in the order given). Returns the program's id. */
    suspend fun saveProgram(existing: LiftProgramRow?, name: String, note: String?, items: List<LiftProgramItemRow>): String {
        val now = clock()
        val id = existing?.id ?: newId()
        dao.upsertLiftPrograms(
            listOf(
                LiftProgramRow(
                    id = id, deviceId = deviceId(), name = name, note = note,
                    createdAt = existing?.createdAt ?: now, updatedAt = now, archived = existing?.archived ?: false,
                ),
            ),
        )
        dao.replaceLiftProgramItems(
            id,
            items.mapIndexed { index, item -> item.copy(deviceId = deviceId(), programId = id, ord = index) },
        )
        return id
    }

    suspend fun deleteProgram(id: String) {
        dao.deleteLiftProgram(id)
    }

    /**
     * Flatten a program into the plan a session runs, SNAPSHOT at start so editing the program
     * mid-session changes nothing being tapped through. Muscles come from the exercise vocabulary, the
     * one place that owns them. Empty when the program has no lines.
     */
    suspend fun plan(program: LiftProgramRow): List<LiftPlanItem> {
        val items = dao.liftProgramItems(program.id)
        if (items.isEmpty()) return emptyList()
        val vocabulary = dao.liftExercises(deviceId())
        return items.map { item ->
            val known = vocabulary.firstOrNull { it.name == item.exercise }
            LiftPlanItem.of(
                exercise = item.exercise,
                primaryMuscle = LiftMuscle.fromRaw(known?.primaryMuscle),
                secondaryMuscles = LiftMuscle.decodeList(known?.secondaryMuscles),
                targetSets = item.targetSets,
                restSec = item.restSec,
                targetRepsLow = item.targetRepsLow,
                targetRepsHigh = item.targetRepsHigh,
                targetRpe = item.targetRpe,
                targetWeightKg = item.targetWeightKg,
                note = item.note,
                programItemId = item.id,
            )
        }
    }

    // MARK: - Running a session

    /**
     * What was lifted for each of the plan's exercises LAST time, by set number: the middle layer of the
     * grey numbers. One query per distinct exercise. Warm-ups are left out.
     */
    suspend fun lastTime(plan: List<LiftPlanItem>, before: Long): Map<String, Map<Int, LiftSetCarry>> {
        val out = LinkedHashMap<String, Map<Int, LiftSetCarry>>()
        for (exercise in plan.map { it.exercise }.distinct()) {
            val bySet = LinkedHashMap<Int, LiftSetCarry>()
            for (r in dao.lastLiftSets(deviceId(), exercise, before)) {
                if (!r.isWarmup) bySet[r.setIndex] = LiftSetCarry(r.weightKg, r.reps)
            }
            out[exercise] = bySet
        }
        return out
    }

    /** Program lines whose set count the running session changed. */
    suspend fun setCountChanges(state: LiftSessionController.State): List<LiftSessionController.SetCountChange> {
        val programId = state.programId ?: return emptyList()
        val plan = state.engine?.plan ?: return emptyList()
        return LiftSessionController.setCountChanges(plan, dao.liftProgramItems(programId))
    }

    /**
     * Write a finished session. [state] must already be finished (`LiftSessionController.finish`). Writes
     * nothing but the program when no set was performed: an hour that recorded nothing must not file a
     * session or a workout. Swift `LiftSessionView.save`.
     */
    suspend fun saveSession(
        state: LiftSessionController.State,
        completingUnfinished: Boolean,
        keepingProgramChanges: Boolean,
        sessionRpe: Double?,
    ) {
        val engine = state.engine ?: return
        val id = deviceId()
        val endTs = clock()
        val finished = LiftSessionController.setsToSave(state, completingUnfinished)
        if (LiftSessionController.anyPerformed(finished)) {
            val sessionId = newId()
            dao.upsertLiftSessions(
                listOf(
                    LiftSessionRow(
                        id = sessionId, deviceId = id, startTs = engine.startTs, endTs = endTs, sport = SPORT,
                        programId = state.programId,
                        // Snapshot the name: renaming or deleting the program never rewrites this session.
                        programName = state.programName,
                        sessionRpe = sessionRpe,
                        note = state.programName,
                    ),
                ),
            )
            // ord is COMPLETION order; sets completed at finish without being started come last.
            dao.upsertLiftSets(
                finished.mapIndexed { ord, s ->
                    val item = engine.planItem(s.slot)
                    LiftSetEntity(
                        id = newId(), deviceId = id, sessionId = sessionId, ord = ord,
                        exercise = item?.exercise ?: "",
                        primaryMuscle = item?.primaryMuscle?.name,
                        secondaryMuscles = LiftMuscle.encodeList(item?.secondaryMuscles ?: emptyList(), item?.primaryMuscle),
                        setIndex = s.slot.setIndex, weightKg = s.weightKg, reps = s.reps, rpe = s.rpe,
                        isWarmup = s.isWarmup, startTs = s.startTs, endTs = s.endTs, restSec = s.restSec,
                    )
                },
            )
            writeProgram(state, finished, keepingProgramChanges)
            // strain stays null: the engine fills it from the heart rate the strap MEASURED, never from sets.
            saveWorkout(
                WorkoutRow(
                    deviceId = id, startTs = engine.startTs, endTs = endTs, sport = SPORT, source = "manual",
                    durationS = maxOf(0L, endTs - engine.startTs).toDouble(), notes = state.programName,
                ),
            )
        } else {
            writeProgram(state, finished, keepingProgramChanges)
        }
    }

    /**
     * Carry the session onto its program: heaviest done sets always; set counts and added exercises only
     * when the user chose to keep them. Re-reads the lines so a program edited elsewhere keeps its other
     * changes, and writes nothing when no line differs.
     */
    private suspend fun writeProgram(
        state: LiftSessionController.State,
        sets: List<LiftSessionController.FinishedSet>,
        keepingChanges: Boolean,
    ) {
        val programId = state.programId ?: return
        val plan = state.engine?.plan ?: return
        val rows = dao.liftProgramItems(programId)
        val edited = LiftSessionController.programAfterSession(sets, plan, rows, keepingChanges, programId, deviceId())
        if (edited != rows) dao.replaceLiftProgramItems(programId, edited)
    }

    // MARK: - A finished session

    data class Detail(val sets: List<LiftSetEntity>, val workout: WorkoutRow?, val previousVolume: Map<String, Double>)

    suspend fun detail(session: LiftSessionRow): Detail {
        val sets = dao.liftSets(session.id)
        val workout = workoutsAt(deviceId(), session.startTs).firstOrNull { it.startTs == session.startTs && it.sport == SPORT }
        // Last time's volume per exercise, for "vs last time".
        val previous = LinkedHashMap<String, Double>()
        for (exercise in sets.map { it.exercise }.distinct()) {
            val rows = dao.lastLiftSets(deviceId(), exercise, session.startTs)
            val volume = com.noop.analytics.LiftMetrics.volumeLoadKg(rows.map(::metricsRow))
            if (volume != null) previous[exercise] = volume
        }
        return Detail(sets, workout, previous)
    }

    /** Delete a session, its sets, and the workout it created. */
    suspend fun deleteSession(session: LiftSessionRow, workout: WorkoutRow?) {
        dao.deleteLiftSession(session.id)
        workout?.let(deleteWorkout)
    }

    suspend fun weekCounts(): LiftSetCounts {
        val now = clock()
        return dao.liftSetCounts(deviceId(), now - 7 * 86_400L, now)
    }

    companion object {
        /** Same token the Hevy/Liftosaur importer uses, so typed and imported sessions share a bucket. */
        const val SPORT = "Strength Training"

        private fun newId() = UUID.randomUUID().toString().uppercase()

        /** A stored set as the analytics row type. */
        fun metricsRow(s: LiftSetEntity) = com.noop.analytics.LiftMetrics.Row(
            ord = s.ord,
            exercise = s.exercise,
            weightKg = s.weightKg,
            reps = s.reps,
            rpe = s.rpe,
            isWarmup = s.isWarmup,
            primaryMuscle = LiftMuscle.fromRaw(s.primaryMuscle),
            secondaryMuscles = LiftMuscle.decodeList(s.secondaryMuscles),
        )
    }
}
