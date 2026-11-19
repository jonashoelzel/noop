package com.noop.garmin

import android.content.Context
import com.noop.data.SecurePrefs
import com.noop.ingest.GarminFitExport
import com.noop.ui.NoopPrefs

/**
 * Garmin Connect integration constants and settings, shared by the Personal API sync and the FIT
 * export. Every value here is identical to the Swift twin (`Strand/Garmin/`): the workout source and
 * HR-stream device id, the preference keys, and the Garmin URLs.
 *
 * Fork feature (jonashoelzel/noop). Off by default: nothing talks to Garmin until the user saves a
 * Personal API key in Data Sources.
 */
object GarminConnect {
    /** Workout `source` AND HR-stream `deviceId` of every Garmin Connect import. */
    const val SOURCE_ID = "garmin-connect"

    /** Device / registry display name. */
    const val DISPLAY_NAME = "Garmin Connect"

    /** Personal API base URL. */
    const val API_BASE = "https://personal.connectapi.garmin.com"

    /** Where the user creates a Personal API key. */
    const val KEY_PAGE_URL = "https://connect.garmin.com/app/settings/personalApiToken"

    /** Garmin Connect's web "Import Data" page, where an exported FIT file is uploaded. */
    const val IMPORT_PAGE_URL = "https://connect.garmin.com/modern/import-data"
}

/**
 * The Garmin preferences, in the shared `noop_prefs` file under the cross-platform `garmin.*` keys.
 * Timestamps are unix seconds.
 */
object GarminSettings {
    const val KEY_EXPORT_DEVICE = "garmin.exportDevice"
    const val KEY_AUTO_SYNC = "garmin.autoSync"
    const val KEY_LAST_SYNC_ATTEMPT_TS = "garmin.lastSyncAttemptTs"
    const val KEY_LAST_SYNC_CURSOR_TS = "garmin.lastSyncCursorTs"
    const val KEY_LAST_SYNC_SUMMARY = "garmin.lastSyncSummary"

    fun exportDevice(context: Context): GarminFitExport.DeviceIdentity =
        GarminFitExport.DeviceIdentity.fromKey(NoopPrefs.of(context).getString(KEY_EXPORT_DEVICE, null))

    fun setExportDevice(context: Context, device: GarminFitExport.DeviceIdentity) {
        NoopPrefs.of(context).edit().putString(KEY_EXPORT_DEVICE, device.key).apply()
    }

    /** Import automatically on app foreground. Default true; only meaningful once a key is saved. */
    fun autoSync(context: Context): Boolean = NoopPrefs.of(context).getBoolean(KEY_AUTO_SYNC, true)

    fun setAutoSync(context: Context, enabled: Boolean) {
        NoopPrefs.of(context).edit().putBoolean(KEY_AUTO_SYNC, enabled).apply()
    }

    fun lastSyncAttemptTs(context: Context): Long? = readLong(context, KEY_LAST_SYNC_ATTEMPT_TS)

    fun lastSyncCursorTs(context: Context): Long? = readLong(context, KEY_LAST_SYNC_CURSOR_TS)

    fun lastSyncSummary(context: Context): String? =
        NoopPrefs.of(context).getString(KEY_LAST_SYNC_SUMMARY, null)

    /** Persist one sync's outcome. [cursorTs] null leaves the stored cursor as it was. */
    fun recordSync(context: Context, attemptTs: Long, cursorTs: Long?, summary: String) {
        NoopPrefs.of(context).edit().apply {
            putLong(KEY_LAST_SYNC_ATTEMPT_TS, attemptTs)
            if (cursorTs != null) putLong(KEY_LAST_SYNC_CURSOR_TS, cursorTs)
            putString(KEY_LAST_SYNC_SUMMARY, summary)
        }.apply()
    }

    /** Forget the sync history (used when the key is removed, so a new account starts a fresh window). */
    fun clearSyncState(context: Context) {
        NoopPrefs.of(context).edit()
            .remove(KEY_LAST_SYNC_ATTEMPT_TS)
            .remove(KEY_LAST_SYNC_CURSOR_TS)
            .remove(KEY_LAST_SYNC_SUMMARY)
            .apply()
    }

    private fun readLong(context: Context, key: String): Long? {
        val prefs = NoopPrefs.of(context)
        return if (prefs.contains(key)) runCatching { prefs.getLong(key, 0L) }.getOrNull() else null
    }
}

/**
 * The Garmin Personal API key, encrypted at rest (`EncryptedSharedPreferences` file `garmin_secure`, key
 * `personalApiKey`, via the audited [SecurePrefs] helper). Never logged, never shown back to the user.
 */
object GarminKeyStore {
    private const val FILE = "garmin_secure"
    private const val KEY = "personalApiKey"

    fun load(context: Context): String? =
        runCatching { SecurePrefs.of(context, FILE).getString(KEY, null) }.getOrNull()?.takeIf { it.isNotBlank() }

    fun hasKey(context: Context): Boolean = load(context) != null

    fun save(context: Context, key: String): Boolean = runCatching {
        SecurePrefs.of(context, FILE).edit().putString(KEY, key.trim()).commit()
    }.getOrDefault(false)

    fun remove(context: Context) {
        runCatching { SecurePrefs.of(context, FILE).edit().remove(KEY).commit() }
    }
}
