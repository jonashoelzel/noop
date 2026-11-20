package com.noop.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [LiftLogDao] against the real Room database: the Kotlin half of the Swift `LiftLogStoreTests`. Each
 * test here names the Swift test it mirrors, so the two suites can be read side by side. The merge
 * rules are re-implemented as transactions (see the [LiftLogDao] header), so these are what prove
 * they land the same rows the Swift `ON CONFLICT … DO UPDATE` statements do.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LiftLogDaoTest {

    private val db = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        WhoopDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val dao = db.whoopDao()

    @After
    fun close() = db.close()

    private val dev = "my-whoop"
    private val sport = "Strength Training"

    private fun exercise(
        id: String,
        name: String,
        deviceId: String = dev,
        primary: LiftMuscle? = null,
        secondary: List<LiftMuscle> = emptyList(),
        createdAt: Long = 100,
        lastUsedTs: Long? = null,
    ) = LiftExerciseRow(
        id = id, deviceId = deviceId, name = name, primaryMuscle = primary?.name,
        secondaryMuscles = LiftMuscle.encodeList(secondary, primary), createdAt = createdAt, lastUsedTs = lastUsedTs,
    )

    private fun program(id: String, name: String, updatedAt: Long, archived: Boolean = false) =
        LiftProgramRow(id = id, deviceId = dev, name = name, note = null, createdAt = 1, updatedAt = updatedAt, archived = archived)

    private fun item(id: String, programId: String, ord: Int, exercise: String) =
        LiftProgramItemRow(id = id, deviceId = dev, programId = programId, ord = ord, exercise = exercise)

    private fun session(id: String, startTs: Long, endTs: Long? = null) =
        LiftSessionRow(id = id, deviceId = dev, startTs = startTs, endTs = endTs, sport = sport)

    private fun set(
        id: String,
        sessionId: String,
        ord: Int,
        setIndex: Int,
        exercise: String = "Leg Press",
        weightKg: Double? = 100.0,
        reps: Int? = 10,
        primary: LiftMuscle? = LiftMuscle.quads,
        secondary: List<LiftMuscle> = listOf(LiftMuscle.glutes),
        rpe: Double? = null,
        isWarmup: Boolean = false,
    ) = LiftSetEntity(
        id = id, deviceId = dev, sessionId = sessionId, ord = ord, exercise = exercise,
        primaryMuscle = primary?.name, secondaryMuscles = LiftMuscle.encodeList(secondary, primary),
        setIndex = setIndex, weightKg = weightKg, reps = reps, rpe = rpe, isWarmup = isWarmup,
    )

    // MARK: - The vocabulary cap

    /** testAnExistingExerciseAlwaysUpdatesEvenAtTheCap */
    @Test
    fun anExistingExerciseAlwaysUpdates() = runBlocking {
        dao.upsertLiftExercises(listOf(exercise("e1", "Bench press", primary = LiftMuscle.chest)))
        dao.upsertLiftExercises(listOf(exercise("e1", "Bench press", primary = LiftMuscle.triceps)))
        val back = dao.liftExercises(dev)
        assertEquals(1, back.size)
        assertEquals("triceps", back[0].primaryMuscle)
    }

    /** testANewExerciseIsRefusedOnceTheVocabularyIsFull */
    @Test
    fun aNewExerciseIsRefusedOnceTheVocabularyIsFull() = runBlocking {
        val limit = LiftLogDao.MAX_REMEMBERED_EXERCISES
        dao.upsertLiftExercises((0 until limit).map { exercise("id$it", "Exercise $it") })
        try {
            dao.upsertLiftExercises(listOf(exercise("over", "One too many")))
            fail("a new name past the cap should be refused, not silently dropped")
        } catch (e: LiftExerciseVocabularyFull) {
            assertEquals(limit, e.limit)
        }
        assertEquals(limit, dao.liftExercises(dev).size)
        // An existing name still updates at the cap.
        dao.upsertLiftExercises(listOf(exercise("x", "Exercise 0", primary = LiftMuscle.lats)))
        assertEquals("lats", dao.liftExerciseByName(dev, "Exercise 0")?.primaryMuscle)
    }

    /** testTheCapIsPerDeviceNotGlobal */
    @Test
    fun theVocabularyIsPerDevice() = runBlocking {
        dao.upsertLiftExercises(listOf(exercise("a", "Row", deviceId = "dev-a")))
        dao.upsertLiftExercises(listOf(exercise("b", "Row", deviceId = "dev-b")))
        assertEquals(1, dao.liftExercises("dev-a").size)
        assertEquals(1, dao.liftExercises("dev-b").size)
    }

    // MARK: - The user's own exercise vocabulary

    /** testReusingAnExerciseUpdatesRecencyAndKeepsItsMuscleGroup */
    @Test
    fun reusingAnExerciseUpdatesRecencyAndKeepsItsMuscleGroup() = runBlocking {
        dao.upsertLiftExercises(
            listOf(exercise("e1", "Leg Press", primary = LiftMuscle.quads, secondary = listOf(LiftMuscle.glutes), lastUsedTs = 100)),
        )
        dao.upsertLiftExercises(listOf(exercise("fresh-id", "Leg Press", createdAt = 900, lastUsedTs = 900)))
        val back = dao.liftExercises(dev)
        assertEquals(1, back.size)
        assertEquals("quads", back[0].primaryMuscle)
        assertEquals("glutes", back[0].secondaryMuscles)
        assertEquals(900L, back[0].lastUsedTs)
        assertEquals("e1", back[0].id)
        assertEquals(100L, back[0].createdAt)

        // Recency only ever moves forward.
        dao.upsertLiftExercises(listOf(exercise("x", "Leg Press", lastUsedTs = 50)))
        assertEquals(900L, dao.liftExercises(dev)[0].lastUsedTs)
    }

    /** testExercisePickerOrdersByRecencyThenName */
    @Test
    fun pickerOrdersByRecencyThenName() = runBlocking {
        dao.upsertLiftExercises(
            listOf(
                exercise("e1", "Zercher Squat", lastUsedTs = 500),
                exercise("e2", "Pec Deck"),
                exercise("e3", "Dead Bug"),
            ),
        )
        assertEquals(listOf("Zercher Squat", "Dead Bug", "Pec Deck"), dao.liftExercises(dev).map { it.name })
    }

    /** The Swift initializer strips the primary out of the secondary list; the upsert does the same. */
    @Test
    fun theSecondaryListNeverRepeatsThePrimary() = runBlocking {
        dao.upsertLiftExercises(
            listOf(exercise("e1", "Squat").copy(primaryMuscle = "quads", secondaryMuscles = "quads,glutes,glutes")),
        )
        assertEquals("glutes", dao.liftExercises(dev)[0].secondaryMuscles)
    }

    /** testForgettingAnExerciseKeepsLoggedSets */
    @Test
    fun forgettingAnExerciseKeepsLoggedSets() = runBlocking {
        dao.upsertLiftExercises(listOf(exercise("e1", "Leg Press", primary = LiftMuscle.quads)))
        dao.upsertLiftSessions(listOf(session("s1", 1_000)))
        dao.upsertLiftSets(listOf(set("x1", "s1", 0, 1)))
        assertEquals(1, dao.deleteLiftExercise("e1"))
        val sets = dao.liftSets("s1")
        assertEquals(listOf("Leg Press"), sets.map { it.exercise })
        assertEquals("quads", sets[0].primaryMuscle)
    }

    // MARK: - Programs

    /** testProgramRoundTripAndArchiveFilter + testProgramUpsertIsIdempotentById */
    @Test
    fun programsUpsertByIdAndHideArchived() = runBlocking {
        dao.upsertLiftPrograms(listOf(program("p1", "Upper A", 10), program("p2", "Old", 20, archived = true)))
        assertEquals(listOf("p1"), dao.liftPrograms(dev).map { it.id })

        dao.upsertLiftPrograms(listOf(program("p1", "Upper A (renamed)", 30).copy(createdAt = 999)))
        val p1 = dao.liftProgramById("p1")!!
        assertEquals("Upper A (renamed)", p1.name)
        assertEquals(30L, p1.updatedAt)
        assertEquals("createdAt is kept on update", 1L, p1.createdAt)
        assertEquals("p2", dao.liftProgramById("p2")?.id)
    }

    /** testReplaceProgramItemsSwapsTheWholeList */
    @Test
    fun replacingItemsSwapsTheWholeList() = runBlocking {
        dao.upsertLiftPrograms(listOf(program("p1", "Upper A", 10)))
        dao.replaceLiftProgramItems("p1", listOf(item("i1", "p1", 0, "Bench"), item("i2", "p1", 1, "Row")))
        dao.replaceLiftProgramItems("p1", listOf(item("i3", "p1", 0, "Dip"), item("i1", "p1", 1, "Bench")))
        assertEquals(listOf("Dip", "Bench"), dao.liftProgramItems("p1").map { it.exercise })
    }

    /** testDeletingAProgramKeepsItsSessions */
    @Test
    fun deletingAProgramKeepsItsSessions() = runBlocking {
        dao.upsertLiftPrograms(listOf(program("p1", "Upper A", 10)))
        dao.replaceLiftProgramItems("p1", listOf(item("i1", "p1", 0, "Bench")))
        dao.upsertLiftSessions(listOf(session("s1", 1_000).copy(programId = "p1", programName = "Upper A")))
        assertTrue(dao.deleteLiftProgram("p1"))
        assertFalse(dao.deleteLiftProgram("p1"))
        assertTrue(dao.liftProgramItems("p1").isEmpty())
        assertEquals("Upper A", dao.liftSessions(dev, 0, 9_999).single().programName)
    }

    // MARK: - Sessions

    /** testSessionUpsertIsIdempotentByNaturalKey */
    @Test
    fun sessionUpsertIsIdempotentByNaturalKey() = runBlocking {
        dao.upsertLiftSessions(listOf(session("s1", 1_000)))
        dao.upsertLiftSessions(listOf(session("different-id", 1_000, endTs = 4_600)))
        val sessions = dao.liftSessions(dev, 0, 9_999)
        assertEquals(1, sessions.size)
        assertEquals(4_600L, sessions[0].endTs)
        assertEquals("s1", sessions[0].id)
    }

    /** testDeletingASessionDeletesItsSets */
    @Test
    fun deletingASessionDeletesItsSets() = runBlocking {
        dao.upsertLiftSessions(listOf(session("s1", 1_000)))
        dao.upsertLiftSets(listOf(set("x1", "s1", 0, 1), set("x2", "s1", 1, 2)))
        assertTrue(dao.deleteLiftSession("s1"))
        assertTrue(dao.liftSets("s1").isEmpty())
    }

    /** testSessionRpeIsOptionalSoASkippedRatingIsNotAZero */
    @Test
    fun aSkippedSessionRatingStaysNull() = runBlocking {
        dao.upsertLiftSessions(listOf(session("s1", 1_000)))
        assertNull(dao.liftSessions(dev, 0, 9_999).single().sessionRpe)
    }

    // MARK: - Sets

    /** testSetsReadBackInPerformedOrder, plus the upsert-by-id rule. */
    @Test
    fun setsReadBackInPerformedOrderAndUpsertById() = runBlocking {
        dao.upsertLiftSessions(listOf(session("s1", 1_000)))
        dao.upsertLiftSets(listOf(set("x2", "s1", 1, 2, weightKg = 62.5, reps = 9), set("x1", "s1", 0, 1, weightKg = 60.0)))
        assertEquals(listOf("x1", "x2"), dao.liftSets("s1").map { it.id })

        dao.upsertLiftSets(listOf(set("x1", "s1", 0, 1, weightKg = 65.0, reps = 8)))
        val x1 = dao.liftSetById("x1")!!
        assertEquals(65.0, x1.weightKg!!, 0.0)
        assertEquals(8, x1.reps)
        assertEquals(2, dao.liftSets("s1").size)
    }

    // MARK: - The read the feature exists for

    /** testLastLiftSetsReturnsTheMostRecentSessionForThatExercise */
    @Test
    fun lastLiftSetsReturnsTheMostRecentSessionForThatExercise() = runBlocking {
        dao.upsertLiftSessions(listOf(session("old", 1_000), session("recent", 5_000), session("newest", 9_000)))
        dao.upsertLiftSets(
            listOf(
                set("a", "old", 0, 1, weightKg = 100.0),
                set("b", "recent", 0, 1, weightKg = 110.0),
                set("c", "recent", 1, 2, weightKg = 110.0, reps = 9),
                set("d", "newest", 0, 1, exercise = "Lat Pulldown", weightKg = 55.0),
            ),
        )
        assertEquals(listOf("b", "c"), dao.lastLiftSets(dev, "Leg Press").map { it.id })
        assertEquals(listOf("a"), dao.lastLiftSets(dev, "Leg Press", before = 5_000).map { it.id })
        assertTrue(dao.lastLiftSets(dev, "Nordic Curl").isEmpty())
    }

    /** testLastLiftSetsSkipsSetsWithZeroReps */
    @Test
    fun lastLiftSetsSkipsSetsWithZeroReps() = runBlocking {
        dao.upsertLiftSessions(listOf(session("real", 1_000), session("discarded", 5_000)))
        dao.upsertLiftSets(listOf(set("a", "real", 0, 1, reps = 8), set("b", "discarded", 0, 1, weightKg = 0.0, reps = 0)))
        assertEquals(listOf("a"), dao.lastLiftSets(dev, "Leg Press").map { it.id })
    }

    // MARK: - Per-muscle set counts

    /** testSetCountsUseTheFractionalMethod */
    @Test
    fun setCountsUseTheFractionalMethod() = runBlocking {
        dao.upsertLiftSessions(listOf(session("s1", 1_000)))
        dao.upsertLiftSets(
            listOf(
                set("a", "s1", 0, 1),
                set("b", "s1", 1, 2),
                set("c", "s1", 2, 1, primary = LiftMuscle.glutes, secondary = emptyList()),
            ),
        )
        val counts = dao.liftSetCounts(dev, 0, 9_999)
        assertEquals(2, counts.direct[LiftMuscle.quads])
        assertEquals(2.0, counts.fractional[LiftMuscle.quads]!!, 1e-9)
        assertEquals(1, counts.direct[LiftMuscle.glutes])
        assertEquals(2, counts.indirect[LiftMuscle.glutes])
        assertEquals(2.0, counts.fractional[LiftMuscle.glutes]!!, 1e-9)
    }

    /** testSetCountsExcludeWarmupsAndDoNotFilterByRpe + testSetCountsLeaveOutSetsWithZeroReps */
    @Test
    fun setCountsExcludeWarmupsAndZeroRepsButNotRpe() = runBlocking {
        dao.upsertLiftSessions(listOf(session("s1", 1_000)))
        val chest = LiftMuscle.chest
        dao.upsertLiftSets(
            listOf(
                set("warm", "s1", 0, 1, primary = chest, secondary = emptyList(), rpe = 9.0, isWarmup = true),
                set("easy", "s1", 1, 2, primary = chest, secondary = emptyList(), rpe = 4.0),
                set("hard", "s1", 2, 3, primary = chest, secondary = emptyList(), rpe = 9.0),
                set("unrated", "s1", 3, 4, primary = chest, secondary = emptyList()),
                set("discarded", "s1", 4, 5, primary = chest, secondary = emptyList(), weightKg = 0.0, reps = 0),
                set("untyped", "s1", 5, 6, primary = chest, secondary = emptyList(), weightKg = null, reps = null),
            ),
        )
        assertEquals(4, dao.liftSetCounts(dev, 0, 9_999).direct[chest])
    }

    /** testSetCountsRespectTheWindow */
    @Test
    fun setCountsRespectTheWindow() = runBlocking {
        dao.upsertLiftSessions(listOf(session("inside", 5_000), session("outside", 50_000)))
        dao.upsertLiftSets(
            listOf(
                set("a", "inside", 0, 1, primary = LiftMuscle.lats, secondary = emptyList()),
                set("b", "outside", 0, 1, primary = LiftMuscle.lats, secondary = emptyList()),
            ),
        )
        assertEquals(1, dao.liftSetCounts(dev, 0, 9_999).direct[LiftMuscle.lats])
    }

    /** A primary that is also stored as a secondary is credited once, as direct. */
    @Test
    fun aMuscleListedTwiceIsCreditedOnce() {
        val counts = LiftSetCounts.from(listOf(LiftSetMuscles("chest", "chest,triceps")))
        assertEquals(1, counts.direct[LiftMuscle.chest])
        assertNull(counts.indirect[LiftMuscle.chest])
        assertEquals(1, counts.indirect[LiftMuscle.triceps])
    }
}
