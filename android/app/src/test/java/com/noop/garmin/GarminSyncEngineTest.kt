package com.noop.garmin

import com.noop.ingest.ActivityFileImporter
import com.noop.ingest.GarminFitExport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Window, paging, skip rule and cursor of one sync, against a fake API and an in-memory store. */
class GarminSyncEngineTest {

    private val now = 1_800_000_000L

    /** A real (tiny) FIT file for an activity, built by the export builder so the importer can parse it. */
    private fun fitFor(a: GarminActivity): ByteArray = GarminFitExport.build(
        GarminFitExport.Workout(
            startTs = a.startTs, endTs = a.endTs, sport = "cycling", track = emptyList(),
            hr = (0..10).map { GarminFitExport.HrPoint(a.startTs + it, 120) },
        ),
        GarminFitExport.DeviceIdentity.NOOP, 0,
    )

    private class FakeApi(val all: List<GarminActivity>, val fit: (GarminActivity) -> ByteArray) : GarminApi {
        val listCalls = ArrayList<Int>()
        val downloads = ArrayList<Long>()
        val missing = HashSet<Long>()
        override suspend fun listActivities(start: Int, limit: Int): List<GarminActivity> {
            listCalls += start
            return all.sortedByDescending { it.startTs }.drop(start).take(limit)
        }
        override suspend fun downloadFit(activityId: Long): ByteArray {
            downloads += activityId
            if (activityId in missing) throw GarminApiException(404, "gone", fatal = false)
            return fit(all.first { it.activityId == activityId })
        }
    }

    private class FakeSink(val stored: MutableList<GarminImportRule.Existing>) : GarminImportSink {
        val imported = ArrayList<ActivityFileImporter.Activity>()
        override suspend fun existing(from: Long, to: Long) = stored.filter { it.startTs in from..to }
        override suspend fun import(activity: ActivityFileImporter.Activity) {
            imported += activity
            stored += GarminImportRule.Existing(activity.startTs, activity.endTs, GarminConnect.SOURCE_ID)
        }
    }

    private fun act(id: Long, start: Long, dur: Double = 600.0) = GarminActivity(id, start, dur, "road_biking", null, null)

    @Test
    fun firstSyncImportsLast30DaysOldestFirstAndSetsCursor() = runBlocking {
        val acts = listOf(
            act(1, now - 40 * 86_400L),   // outside the 30-day first window
            act(2, now - 20 * 86_400L),
            act(3, now - 1 * 86_400L),
        )
        val api = FakeApi(acts, ::fitFor)
        val sink = FakeSink(ArrayList())
        val out = GarminSyncEngine(api, sink).run(now, cursorTs = null)
        assertEquals(2, out.imported)
        assertEquals(listOf(2L, 3L), api.downloads)
        assertEquals(now - 86_400L, out.cursorTs)
        assertEquals(listOf(0), api.listCalls)        // one short page ends the listing
        assertEquals(acts[1].startTs, sink.imported[0].startTs)
    }

    @Test
    fun laterSyncUsesCursorMinusTwoDaysAndSkipsKnownRows() = runBlocking {
        val cursor = now - 5 * 86_400L
        val acts = listOf(
            act(10, cursor - 3 * 86_400L),   // before cursor - 2 d: ignored
            act(11, cursor - 1 * 86_400L),   // already imported
            act(12, cursor + 3600),          // a NOOP run sent to Garmin
            act(13, now - 600),              // new
        )
        val stored = mutableListOf(
            GarminImportRule.Existing(acts[1].startTs + 1, acts[1].endTs, "garmin-connect"),
            GarminImportRule.Existing(acts[2].startTs - 30, acts[2].endTs - 30, "manual"),
        )
        val api = FakeApi(acts, ::fitFor)
        val out = GarminSyncEngine(api, FakeSink(stored)).run(now, cursor)
        assertEquals(1, out.imported)
        assertEquals(2, out.skipped)
        assertEquals(listOf(13L), api.downloads)
        assertEquals(now - 600, out.cursorTs)
    }

    @Test
    fun pagesUntilTheWindowIsPassedAndSkipsActivitiesWithoutFile() = runBlocking {
        // 45 activities one hour apart, all inside the window: three pages (20 + 20 + 5).
        val acts = (0 until 45).map { act(100L + it, now - 3600L * (it + 1)) }
        val api = FakeApi(acts, ::fitFor).apply { missing += 100L }
        val out = GarminSyncEngine(api, FakeSink(ArrayList())).run(now, null)
        assertEquals(listOf(0, 20, 40), api.listCalls)
        assertEquals(44, out.imported)
        assertEquals(1, out.skipped)
    }

    @Test
    fun emptyListKeepsCursorNullAndSummaryText() = runBlocking {
        val out = GarminSyncEngine(FakeApi(emptyList(), ::fitFor), FakeSink(ArrayList())).run(now, null)
        assertEquals(0, out.imported)
        assertNull(out.cursorTs)
        assertEquals("Up to date", GarminSyncEngine.summary(0))
        assertEquals("Imported 1 activity", GarminSyncEngine.summary(1))
        assertEquals("Imported 2 activities", GarminSyncEngine.summary(2))
    }

    @Test
    fun windowStartRule() {
        assertEquals(now - 30 * 86_400L, GarminSyncEngine.windowStart(now, null))
        assertEquals(1_000_000L - 2 * 86_400L, GarminSyncEngine.windowStart(now, 1_000_000L))
    }
}
