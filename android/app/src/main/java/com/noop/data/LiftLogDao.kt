package com.noop.data

import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

/**
 * The in-app strength log's reads and writes: the Room twin of the Swift `LiftLogStore`
 * (`Packages/WhoopStore/Sources/WhoopStore/LiftLogStore.swift`).
 *
 * Split into its own interface, which [WhoopDao] extends, the same way [DeviceRegistryDao] is: Room
 * flattens the inherited annotated methods into the generated DAO, so they behave exactly as if they
 * were declared on [WhoopDao].
 *
 * UPSERTS ARE WRITTEN AS TRANSACTIONS, NOT `ON CONFLICT … DO UPDATE`. The Swift store uses SQLite's
 * upsert clause, which arrived in SQLite 3.24; `minSdk` 26 ships 3.18, where that statement is a
 * syntax error. Each upsert below reads the natural key first and then inserts or updates inside one
 * transaction, which is the same outcome on every SQLite this app can meet. The merge rules (which
 * columns an update keeps, and which it overwrites) follow the Swift statements column for column,
 * and each function names the Swift statement it reproduces.
 *
 * Nothing here computes or stores a load score; volume and set counts are derived on read.
 */
interface LiftLogDao {

    // MARK: - Exercises (the user's own vocabulary)

    @Query("SELECT * FROM liftExercise WHERE deviceId = :deviceId AND name = :name LIMIT 1")
    suspend fun liftExerciseByName(deviceId: String, name: String): LiftExerciseRow?

    @Query("SELECT COUNT(*) FROM liftExercise WHERE deviceId = :deviceId")
    suspend fun liftExerciseCount(deviceId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLiftExerciseRaw(row: LiftExerciseRow)

    @Update
    suspend fun updateLiftExerciseRaw(row: LiftExerciseRow)

    /**
     * Remember exercises, keyed on the natural key (deviceId, name). Recording a known name updates
     * it in place: a muscle field moves only when the caller supplies one, so using an exercise never
     * erases the classification the user gave it, and recency only ever moves forward. A NEW name is
     * refused with [LiftExerciseVocabularyFull] once the device already remembers
     * [MAX_REMEMBERED_EXERCISES]; an existing name always updates.
     *
     * Swift twin: `WhoopStore.upsertLiftExercises` (`ON CONFLICT(deviceId, name) DO UPDATE SET
     * primaryMuscle = COALESCE(…), secondaryMuscles = COALESCE(…), lastUsedTs = MAX(…)`).
     * Returns rows written.
     */
    @Transaction
    suspend fun upsertLiftExercises(rows: List<LiftExerciseRow>): Int {
        var n = 0
        for (r in rows) {
            // Stored the way the Swift initializer normalises it: the primary is never also a secondary.
            val secondaries = LiftMuscle.encodeList(
                LiftMuscle.decodeList(r.secondaryMuscles),
                LiftMuscle.fromRaw(r.primaryMuscle),
            )
            val existing = liftExerciseByName(r.deviceId, r.name)
            if (existing == null) {
                if (liftExerciseCount(r.deviceId) >= MAX_REMEMBERED_EXERCISES) {
                    throw LiftExerciseVocabularyFull(MAX_REMEMBERED_EXERCISES)
                }
                insertLiftExerciseRaw(r.copy(secondaryMuscles = secondaries))
            } else {
                updateLiftExerciseRaw(
                    existing.copy(
                        primaryMuscle = r.primaryMuscle ?: existing.primaryMuscle,
                        secondaryMuscles = secondaries ?: existing.secondaryMuscles,
                        lastUsedTs = maxOf(r.lastUsedTs ?: 0L, existing.lastUsedTs ?: 0L),
                    ),
                )
            }
            n += 1
        }
        return n
    }

    /** The picker's list: most recently used first, never-used after, alphabetical within each. */
    @Query(
        "SELECT * FROM liftExercise WHERE deviceId = :deviceId " +
            "ORDER BY COALESCE(lastUsedTs, 0) DESC, name ASC",
    )
    suspend fun liftExercises(deviceId: String): List<LiftExerciseRow>

    /** Forget one exercise. Sets already logged under the name keep their own copy of it. */
    @Query("DELETE FROM liftExercise WHERE id = :id")
    suspend fun deleteLiftExercise(id: String): Int

    // MARK: - Programs

    @Query("SELECT * FROM liftProgram WHERE id = :id LIMIT 1")
    suspend fun liftProgramById(id: String): LiftProgramRow?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLiftProgramRaw(row: LiftProgramRow)

    @Update
    suspend fun updateLiftProgramRaw(row: LiftProgramRow)

    /**
     * Upsert programs by `id`. An update moves name, note, updatedAt and archived, and keeps the
     * stored deviceId and createdAt. Swift twin: `WhoopStore.upsertLiftPrograms`.
     */
    @Transaction
    suspend fun upsertLiftPrograms(rows: List<LiftProgramRow>): Int {
        for (r in rows) {
            val existing = liftProgramById(r.id)
            if (existing == null) {
                insertLiftProgramRaw(r)
            } else {
                updateLiftProgramRaw(
                    existing.copy(name = r.name, note = r.note, updatedAt = r.updatedAt, archived = r.archived),
                )
            }
        }
        return rows.size
    }

    /** Live programs for a device, most recently touched first. */
    @Query("SELECT * FROM liftProgram WHERE deviceId = :deviceId AND archived = 0 ORDER BY updatedAt DESC")
    suspend fun liftPrograms(deviceId: String): List<LiftProgramRow>

    /** Every program for a device, archived included, most recently touched first. */
    @Query("SELECT * FROM liftProgram WHERE deviceId = :deviceId ORDER BY updatedAt DESC")
    suspend fun liftProgramsIncludingArchived(deviceId: String): List<LiftProgramRow>

    @Query("DELETE FROM liftProgramItem WHERE programId = :programId")
    suspend fun deleteLiftProgramItemsOf(programId: String)

    @Query("DELETE FROM liftProgram WHERE id = :id")
    suspend fun deleteLiftProgramRaw(id: String): Int

    /**
     * Delete a program and its lines. Sessions already run from it keep their own `programName`
     * snapshot, so history survives. Returns true if a program row went. Swift twin:
     * `WhoopStore.deleteLiftProgram`.
     */
    @Transaction
    suspend fun deleteLiftProgram(id: String): Boolean {
        deleteLiftProgramItemsOf(id)
        return deleteLiftProgramRaw(id) > 0
    }

    // MARK: - Program items

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLiftProgramItemsRaw(rows: List<LiftProgramItemRow>)

    /**
     * Replace a program's lines wholesale, inside one transaction: the editor hands back the whole
     * list, so removals and reorders need no row-by-row reconciling. Swift twin:
     * `WhoopStore.replaceLiftProgramItems`.
     */
    @Transaction
    suspend fun replaceLiftProgramItems(programId: String, items: List<LiftProgramItemRow>): Int {
        deleteLiftProgramItemsOf(programId)
        insertLiftProgramItemsRaw(items)
        return items.size
    }

    /** A program's lines in program order. */
    @Query("SELECT * FROM liftProgramItem WHERE programId = :programId ORDER BY ord ASC")
    suspend fun liftProgramItems(programId: String): List<LiftProgramItemRow>

    // MARK: - Sessions

    @Query(
        "SELECT * FROM liftSession WHERE deviceId = :deviceId AND startTs = :startTs AND sport = :sport LIMIT 1",
    )
    suspend fun liftSessionByNaturalKey(deviceId: String, startTs: Long, sport: String): LiftSessionRow?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLiftSessionRaw(row: LiftSessionRow)

    @Update
    suspend fun updateLiftSessionRaw(row: LiftSessionRow)

    /**
     * Upsert sessions on the NATURAL key (deviceId, startTs, sport), not the id, so re-saving the same
     * session updates it in place even under a freshly minted id, which keeps it paired 1:1 with its
     * `workout` row. An update keeps the stored id. Swift twin: `WhoopStore.upsertLiftSessions`.
     */
    @Transaction
    suspend fun upsertLiftSessions(rows: List<LiftSessionRow>): Int {
        for (r in rows) {
            val existing = liftSessionByNaturalKey(r.deviceId, r.startTs, r.sport)
            if (existing == null) {
                insertLiftSessionRaw(r)
            } else {
                updateLiftSessionRaw(
                    existing.copy(
                        endTs = r.endTs,
                        programId = r.programId,
                        programName = r.programName,
                        sessionRpe = r.sessionRpe,
                        note = r.note,
                    ),
                )
            }
        }
        return rows.size
    }

    /** Sessions that started within [fromTs, toTs] inclusive, most recent first. */
    @Query(
        "SELECT * FROM liftSession WHERE deviceId = :deviceId AND startTs >= :fromTs AND startTs <= :toTs " +
            "ORDER BY startTs DESC",
    )
    suspend fun liftSessions(deviceId: String, fromTs: Long, toTs: Long): List<LiftSessionRow>

    @Query("DELETE FROM liftSet WHERE sessionId = :sessionId")
    suspend fun deleteLiftSetsOf(sessionId: String)

    @Query("DELETE FROM liftSession WHERE id = :id")
    suspend fun deleteLiftSessionRaw(id: String): Int

    /** Delete a session and every set in it. Swift twin: `WhoopStore.deleteLiftSession`. */
    @Transaction
    suspend fun deleteLiftSession(id: String): Boolean {
        deleteLiftSetsOf(id)
        return deleteLiftSessionRaw(id) > 0
    }

    // MARK: - Sets

    @Query("SELECT * FROM liftSet WHERE id = :id LIMIT 1")
    suspend fun liftSetById(id: String): LiftSetEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLiftSetRaw(row: LiftSetEntity)

    @Update
    suspend fun updateLiftSetRaw(row: LiftSetEntity)

    /**
     * Upsert sets by `id`. An update rewrites every column except id, deviceId and sessionId. The
     * secondary list is stored normalised (primary stripped, duplicates dropped), as Swift's
     * `LiftMuscle.encodeList(_:excluding:)` stores it. Swift twin: `WhoopStore.upsertLiftSets`.
     */
    @Transaction
    suspend fun upsertLiftSets(rows: List<LiftSetEntity>): Int {
        for (r in rows) {
            val normalised = r.copy(
                secondaryMuscles = LiftMuscle.encodeList(
                    LiftMuscle.decodeList(r.secondaryMuscles),
                    LiftMuscle.fromRaw(r.primaryMuscle),
                ),
            )
            val existing = liftSetById(r.id)
            if (existing == null) {
                insertLiftSetRaw(normalised)
            } else {
                updateLiftSetRaw(normalised.copy(deviceId = existing.deviceId, sessionId = existing.sessionId))
            }
        }
        return rows.size
    }

    /** Every set in a session, in the order performed. */
    @Query("SELECT * FROM liftSet WHERE sessionId = :sessionId ORDER BY ord ASC")
    suspend fun liftSets(sessionId: String): List<LiftSetEntity>

    @Query(
        "SELECT s.sessionId FROM liftSet s JOIN liftSession sess ON sess.id = s.sessionId " +
            "WHERE s.deviceId = :deviceId AND s.exercise = :exercise AND sess.startTs < :before " +
            "AND (s.reps IS NULL OR s.reps <> 0) ORDER BY sess.startTs DESC LIMIT 1",
    )
    suspend fun lastLiftSessionIdFor(deviceId: String, exercise: String, before: Long): String?

    @Query(
        "SELECT * FROM liftSet WHERE sessionId = :sessionId AND exercise = :exercise " +
            "AND (reps IS NULL OR reps <> 0) ORDER BY ord ASC",
    )
    suspend fun performedLiftSetsOf(sessionId: String, exercise: String): List<LiftSetEntity>

    /**
     * The sets from the most recent session that contained [exercise], in performed order: what
     * pre-fills the next session. [before] excludes the running session (pass its startTs). Sets with
     * zero reps were never performed and are skipped. Swift twin: `WhoopStore.lastLiftSets`.
     */
    @Transaction
    suspend fun lastLiftSets(deviceId: String, exercise: String, before: Long? = null): List<LiftSetEntity> {
        val sessionId = lastLiftSessionIdFor(deviceId, exercise, before ?: Long.MAX_VALUE) ?: return emptyList()
        return performedLiftSetsOf(sessionId, exercise)
    }

    @Query(
        "SELECT s.primaryMuscle AS primaryMuscle, s.secondaryMuscles AS secondaryMuscles " +
            "FROM liftSet s JOIN liftSession sess ON sess.id = s.sessionId " +
            "WHERE s.deviceId = :deviceId AND sess.startTs >= :fromTs AND sess.startTs <= :toTs " +
            "AND s.isWarmup = 0 AND (s.reps IS NULL OR s.reps <> 0)",
    )
    suspend fun liftSetMuscles(deviceId: String, fromTs: Long, toTs: Long): List<LiftSetMuscles>

    /**
     * Per-muscle working-set counts for sessions that started in [fromTs, toTs]: direct sets count 1,
     * indirect 0.5. Warm-ups and zero-rep sets are left out, nothing else is. A muscle listed both as
     * primary and secondary is credited once, as direct. Swift twin: `WhoopStore.liftSetCounts`.
     */
    @Transaction
    suspend fun liftSetCounts(deviceId: String, fromTs: Long, toTs: Long): LiftSetCounts =
        LiftSetCounts.from(liftSetMuscles(deviceId, fromTs, toTs))

    companion object {
        /** Swift `WhoopStore.maxRememberedExercises`. */
        const val MAX_REMEMBERED_EXERCISES = 500

        /** Swift `WhoopStore.maxProgramNoteLength`. */
        const val MAX_PROGRAM_NOTE_LENGTH = 120

        /** Swift `WhoopStore.maxExerciseNoteLength`. */
        const val MAX_EXERCISE_NOTE_LENGTH = 200
    }
}

/** Thrown when the vocabulary is full and the name is new. Swift `LiftExerciseVocabularyFull`. */
class LiftExerciseVocabularyFull(val limit: Int) : Exception("Lift exercise vocabulary is full ($limit)")

/** The two classification columns of one counted set, as [LiftLogDao.liftSetMuscles] reads them. */
data class LiftSetMuscles(val primaryMuscle: String?, val secondaryMuscles: String?)

/** Per-muscle set counts. [fractional] is the headline figure; [direct] and [indirect] show the arithmetic. */
data class LiftSetCounts(
    val fractional: Map<LiftMuscle, Double>,
    val direct: Map<LiftMuscle, Int>,
    val indirect: Map<LiftMuscle, Int>,
) {
    companion object {
        fun from(rows: List<LiftSetMuscles>): LiftSetCounts {
            val direct = LinkedHashMap<LiftMuscle, Int>()
            val indirect = LinkedHashMap<LiftMuscle, Int>()
            for (row in rows) {
                val primary = LiftMuscle.fromRaw(row.primaryMuscle)
                if (primary != null) direct[primary] = (direct[primary] ?: 0) + 1
                for (m in LiftMuscle.decodeList(row.secondaryMuscles)) {
                    if (m != primary) indirect[m] = (indirect[m] ?: 0) + 1
                }
            }
            val fractional = LinkedHashMap<LiftMuscle, Double>()
            for ((m, n) in direct) fractional[m] = (fractional[m] ?: 0.0) + n * LiftMuscle.directSetCredit
            for ((m, n) in indirect) fractional[m] = (fractional[m] ?: 0.0) + n * LiftMuscle.indirectSetCredit
            return LiftSetCounts(fractional, direct, indirect)
        }
    }
}
