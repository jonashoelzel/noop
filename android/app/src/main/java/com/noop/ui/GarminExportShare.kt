package com.noop.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.noop.R
import com.noop.data.WorkoutRow
import com.noop.garmin.GarminExportPlan
import com.noop.garmin.GarminSettings
import com.noop.ingest.GarminFitExport
import com.noop.location.RouteTrackStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Send to Garmin Connect": build a full Garmin-ready FIT file for one workout (timed GPS + measured HR)
 * and hand it to the system share sheet. Nothing is uploaded by NOOP; the user uploads the file on Garmin
 * Connect's Import Data page. Same cache dir + FileProvider path as [RouteExportShare].
 */
object GarminExportShare {

    suspend fun share(context: Context, vm: AppViewModel, row: WorkoutRow) {
        runCatching {
            // Read + build + write off the main thread; only the chooser launch stays on Main.
            val file = withContext(Dispatchers.IO) {
                val timed = RouteTrackStore.load(context, row.startTs, row.sport)
                val hr = vm.garminExportHr(row)
                val workout = GarminExportPlan.workout(row, timed, hr)
                val bytes = GarminFitExport.build(
                    workout, GarminSettings.exportDevice(context), GarminExportPlan.utcOffsetS(row.endTs),
                )
                File(File(context.cacheDir, "exports").apply { mkdirs() }, GarminFitExport.fileName(row.startTs))
                    .apply { writeBytes(bytes) }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, uiString(R.string.garmin_send_title)))
        }.onFailure {
            Toast.makeText(context, uiString(R.string.garmin_send_failed, it.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }
}
