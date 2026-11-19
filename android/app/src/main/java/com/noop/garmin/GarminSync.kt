package com.noop.garmin

import android.content.Context
import com.noop.NoopApplication
import com.noop.data.DeviceStatus
import com.noop.data.Metric
import com.noop.data.PairedDeviceRow
import com.noop.data.SourceKind
import com.noop.data.WhoopRepository
import com.noop.ingest.ActivityFileImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Where a sync reads stored workouts from and writes imports to; the app wires the repository. */
interface GarminImportSink {
    /** Stored workouts whose start falls in [from, to] that the skip rule needs (garmin + manual rows). */
    suspend fun existing(from: Long, to: Long): List<GarminImportRule.Existing>

    /** Write one parsed activity under `garmin-connect`. */
    suspend fun import(activity: ActivityFileImporter.Activity)
}

/**
 * One Garmin Connect sync, independent of Android so it is unit-testable with a fake [GarminApi] and
 * [GarminImportSink]. Twin of the Swift sync coordinator's core.
 *
 * Window: the first sync (no cursor) looks back [FIRST_SYNC_LOOKBACK_S]; later syncs start at the cursor
 * minus [CURSOR_OVERLAP_S]. Pages of [PAGE_SIZE] (at most [MAX_PAGES]) are listed newest first until a page
 * is empty or reaches before the window. Activities in the window are decided oldest first by
 * [GarminImportRule]; an imported one is downloaded, parsed with [ActivityFileImporter] and written.
 */
class GarminSyncEngine(private val api: GarminApi, private val sink: GarminImportSink) {

    data class Outcome(
        val imported: Int,
        val skipped: Int,
        /** max(start) over every listed activity; null when nothing was listed. */
        val cursorTs: Long?,
    )

    suspend fun run(nowS: Long, cursorTs: Long?): Outcome {
        val windowStart = windowStart(nowS, cursorTs)
        val listed = ArrayList<GarminActivity>()
        for (page in 0 until MAX_PAGES) {
            val items = api.listActivities(page * PAGE_SIZE, PAGE_SIZE)
            if (items.isEmpty()) break
            listed += items
            if (items.minOf { it.startTs } < windowStart || items.size < PAGE_SIZE) break
        }
        val inWindow = listed
            .filter { it.startTs >= windowStart }
            .distinctBy { it.activityId }
            .sortedBy { it.startTs }
        var imported = 0
        var skipped = 0
        for (a in inWindow) {
            val existing = sink.existing(a.startTs - EXISTING_MARGIN_S, a.endTs + EXISTING_MARGIN_S)
            if (GarminImportRule.decide(a.startTs, a.endTs, existing) != GarminImportRule.Decision.IMPORT) {
                skipped++
                continue
            }
            val bytes = try {
                api.downloadFit(a.activityId)
            } catch (e: GarminApiException) {
                // A manual Garmin entry has no file, and a deleted one 404s: skip it, keep syncing.
                if (e.fatal) throw e
                skipped++
                continue
            }
            val activity = ActivityFileImporter.parse(bytes, "${a.activityId}.fit").activity
            if (!ActivityFileImporter.isUsable(activity)) {
                skipped++
                continue
            }
            sink.import(activity!!)
            imported++
        }
        return Outcome(imported, skipped, listed.maxOfOrNull { it.startTs })
    }

    companion object {
        const val PAGE_SIZE = 20
        const val MAX_PAGES = 10
        const val FIRST_SYNC_LOOKBACK_S = 30L * 86_400L
        const val CURSOR_OVERLAP_S = 2L * 86_400L

        /** Stored rows are fetched by START time; a manual row may start up to a day before the activity. */
        const val EXISTING_MARGIN_S = 86_400L

        fun windowStart(nowS: Long, cursorTs: Long?): Long =
            if (cursorTs == null || cursorTs <= 0) nowS - FIRST_SYNC_LOOKBACK_S else cursorTs - CURSOR_OVERLAP_S

        /** The status line for a successful sync. */
        fun summary(imported: Int): String = when (imported) {
            0 -> "Up to date"
            1 -> "Imported 1 activity"
            else -> "Imported $imported activities"
        }
    }
}

/**
 * Process-level Garmin Connect sync coordinator: runs [GarminSyncEngine] against the real client and
 * repository, persists the cursor / attempt time / status line, and never runs two syncs at once.
 *
 * Triggers: the "Sync now" button, and [maybeAutoSync] on app foreground when a key is saved, auto-sync is
 * on and the last attempt is at least [AUTO_SYNC_INTERVAL_S] old. No background scheduling.
 */
object GarminSync {

    /** Minimum gap between automatic syncs. */
    const val AUTO_SYNC_INTERVAL_S = 60L * 60L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = AtomicBoolean(false)

    private val _running = MutableStateFlow(false)
    /** True while a sync is in flight (drives the "Sync now" progress). */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _completed = MutableStateFlow(0L)
    /** Bumped after every finished sync, so observers can re-read the status and reload workouts. */
    val completed: StateFlow<Long> = _completed.asStateFlow()

    /** The client survives between syncs so its bearer token is reused until it nears expiry. */
    private var client: GarminApiClient? = null
    private var clientKey: String? = null

    /** Start an automatic sync if due. Returns whether one was started. */
    fun maybeAutoSync(context: Context): Boolean {
        val app = context.applicationContext
        if (!GarminSettings.autoSync(app)) return false
        val last = GarminSettings.lastSyncAttemptTs(app)
        val now = System.currentTimeMillis() / 1000
        if (last != null && now - last < AUTO_SYNC_INTERVAL_S) return false
        if (inFlight.get()) return false
        scope.launch { if (GarminKeyStore.hasKey(app)) syncNow(app) }
        return true
    }

    /** Run one sync now; returns the status line. A second call while one runs returns null at once. */
    suspend fun syncNow(context: Context): String? {
        val app = context.applicationContext
        if (!inFlight.compareAndSet(false, true)) return null
        _running.value = true
        try {
            return withContext(Dispatchers.IO) { runOnce(app) }
        } finally {
            inFlight.set(false)
            _running.value = false
            _completed.value = _completed.value + 1
        }
    }

    private suspend fun runOnce(app: Context): String {
        val now = System.currentTimeMillis() / 1000
        val key = GarminKeyStore.load(app)
        if (key == null) {
            val msg = "Save a Personal API key first."
            GarminSettings.recordSync(app, now, null, msg)
            return msg
        }
        val api = clientFor(key)
        val repo = (app as? NoopApplication)?.repository ?: WhoopRepository.from(app)
        val sink = RepositorySink(app, repo)
        // Same exported strap log the file imports write to: counts or an error class only, never the key,
        // a token, an activity name or any health value.
        fun log(detail: String) {
            runCatching { (app as? NoopApplication)?.ble?.externalLog("Import Garmin Connect: $detail") }
        }
        val summary = try {
            val outcome = GarminSyncEngine(api, sink).run(now, GarminSettings.lastSyncCursorTs(app))
            val text = GarminSyncEngine.summary(outcome.imported)
            GarminSettings.recordSync(app, now, outcome.cursorTs, text)
            log("imported=${outcome.imported} skipped=${outcome.skipped}")
            text
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: GarminApiException) {
            val text = e.message ?: GarminApiClient.UNEXPECTED_RESPONSE
            GarminSettings.recordSync(app, now, null, text)
            log("failed (${e.httpCode?.let { "HTTP $it" } ?: "no HTTP status"})")
            text
        } catch (e: Exception) {
            val text = "Import failed: ${e.message ?: e.javaClass.simpleName}"
            GarminSettings.recordSync(app, now, null, text)
            log("failed (${e.javaClass.simpleName})")
            text
        }
        return summary
    }

    @Synchronized
    private fun clientFor(key: String): GarminApiClient {
        val existing = client
        if (existing != null && clientKey == key) return existing
        return GarminApiClient(key).also { client = it; clientKey = key }
    }

    /** Drop the cached client (and its token), e.g. when the key is removed or replaced. */
    @Synchronized
    fun resetClient() {
        client = null
        clientKey = null
    }

    /** The repository-backed sink: reads garmin + manual rows, writes through [ActivityFileImporter.importParsed]. */
    private class RepositorySink(private val app: Context, private val repo: WhoopRepository) : GarminImportSink {
        private var registered = false

        override suspend fun existing(from: Long, to: Long): List<GarminImportRule.Existing> {
            val activeId = (app as? NoopApplication)?.activeDeviceId ?: "my-whoop"
            val rows = repo.workouts(GarminConnect.SOURCE_ID, from, to) + repo.workoutsUnion(activeId, from, to)
            return rows.map { GarminImportRule.Existing(it.startTs, it.endTs, it.source) }
        }

        override suspend fun import(activity: ActivityFileImporter.Activity) {
            ActivityFileImporter.importParsed(
                activity,
                repo,
                deviceId = GarminConnect.SOURCE_ID,
                source = GarminConnect.SOURCE_ID,
                deviceName = GarminConnect.DISPLAY_NAME,
                summaryLabel = GarminConnect.DISPLAY_NAME,
            )
            if (!registered) {
                registerDevice()
                registered = true
            }
        }

        /**
         * Register `garmin-connect` exactly like the activity-file import registers `activity-file`
         * (DataSourcesScreen, #137 B1): kind `activityFile`, capability `hr`, status `paired`, never active.
         */
        private suspend fun registerDevice() {
            val registry = (app as? NoopApplication)?.deviceRegistry ?: return
            val now = System.currentTimeMillis() / 1000
            runCatching {
                registry.add(
                    PairedDeviceRow(
                        id = GarminConnect.SOURCE_ID,
                        brand = GarminConnect.DISPLAY_NAME,
                        model = "",
                        nickname = null,
                        peripheralId = null,
                        sourceKind = SourceKind.activityFile.name,
                        capabilities = Metric.hr.name,
                        status = DeviceStatus.paired.name,
                        addedAt = now,
                        lastSeenAt = now,
                    ),
                )
            }
        }
    }
}
