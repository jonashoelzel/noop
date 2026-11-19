package com.noop.ui

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.garmin.GarminConnect
import com.noop.garmin.GarminKeyStore
import com.noop.garmin.GarminSettings
import com.noop.garmin.GarminSync
import com.noop.ingest.GarminFitExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "Garmin Connect" card in Data Sources: Personal API key (saved encrypted, never shown back), the
 * automatic-import toggle, "Sync now" with its status line, and which device exported FIT files claim to
 * come from. Twin of the Garmin card in the Swift DataSourcesView.
 */
@Composable
internal fun GarminConnectCard(vm: AppViewModel) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val running by GarminSync.running.collectAsStateWithLifecycle()
    val completed by GarminSync.completed.collectAsStateWithLifecycle()

    // null until the encrypted store has been read, so the key field never flashes for a saved key.
    var hasKey by remember { mutableStateOf<Boolean?>(null) }
    var keyDraft by remember { mutableStateOf("") }
    var autoSync by remember { mutableStateOf(GarminSettings.autoSync(context)) }
    var device by remember { mutableStateOf(GarminSettings.exportDevice(context)) }
    var lastAttempt by remember { mutableStateOf(GarminSettings.lastSyncAttemptTs(context)) }
    var summary by remember { mutableStateOf(GarminSettings.lastSyncSummary(context)) }

    // The encrypted store opens off the main thread; re-read the status after every finished sync.
    LaunchedEffect(completed) {
        hasKey = withContext(Dispatchers.IO) { GarminKeyStore.hasKey(context) }
        lastAttempt = GarminSettings.lastSyncAttemptTs(context)
        summary = GarminSettings.lastSyncSummary(context)
    }

    fun openUrl(url: String) {
        runCatching { uriHandler.openUri(url) }
    }

    SourceCard(
        title = GarminConnect.DISPLAY_NAME,
        icon = Icons.Filled.Sync,
        tint = Palette.metricAmber,
        subtitle = uiString(R.string.garmin_card_explanation),
    ) {
        StatePill(
            title = if (hasKey == true) uiString(R.string.garmin_key_saved) else uiString(R.string.garmin_no_key),
            tone = if (hasKey == true) StrandTone.Accent else StrandTone.Neutral,
            showsDot = true,
        )

        if (hasKey == false) {
            OutlinedTextField(
                value = keyDraft,
                onValueChange = { keyDraft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = uiString(R.string.garmin_api_key_label) },
                placeholder = {
                    Text(uiString(R.string.garmin_api_key_label), style = NoopType.body, color = Palette.textTertiary)
                },
                textStyle = NoopType.mono(13f),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = coachFieldColors(),
                shape = RoundedCornerShape(Metrics.space14),
            )
            NoopButton(
                text = uiString(R.string.garmin_save_key),
                kind = NoopButtonKind.Primary,
                fullWidth = true,
                enabled = keyDraft.isNotBlank(),
                onClick = {
                    val key = keyDraft.trim()
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { GarminKeyStore.save(context, key) }
                        if (ok) {
                            keyDraft = ""
                            hasKey = true
                            GarminSync.resetClient()
                            GarminSync.syncNow(context)
                        } else {
                            Toast.makeText(context, uiString(R.string.garmin_key_save_failed), Toast.LENGTH_LONG).show()
                        }
                    }
                },
            )
            LinkRow(uiString(R.string.garmin_create_key_link)) { openUrl(GarminConnect.KEY_PAGE_URL) }
        } else if (hasKey == true) {
            SettingsToggleRow(
                title = uiString(R.string.garmin_auto_import),
                detail = uiString(R.string.garmin_auto_import_detail),
                checked = autoSync,
                onCheckedChange = { on ->
                    autoSync = on
                    GarminSettings.setAutoSync(context, on)
                },
            )
            NoopButton(
                text = if (running) uiString(R.string.garmin_syncing) else uiString(R.string.garmin_sync_now),
                leadingIcon = Icons.Filled.Sync,
                kind = NoopButtonKind.Primary,
                fullWidth = true,
                enabled = !running,
                onClick = { scope.launch { GarminSync.syncNow(context) } },
            )
            Text(
                statusLine(lastAttempt, summary),
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
            NoopButton(
                text = uiString(R.string.garmin_remove_key),
                kind = NoopButtonKind.Tertiary,
                fullWidth = true,
                enabled = !running,
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { GarminKeyStore.remove(context) }
                        GarminSync.resetClient()
                        GarminSettings.clearSyncState(context)
                        hasKey = false
                        lastAttempt = null
                        summary = null
                    }
                },
            )
        }

        Text(uiString(R.string.garmin_export_identity_label), style = NoopType.subhead, color = Palette.textPrimary)
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
            for (option in GarminFitExport.DeviceIdentity.entries) {
                IdentityRow(
                    label = identityLabel(option),
                    selected = option == device,
                ) {
                    device = option
                    GarminSettings.setExportDevice(context, option)
                }
            }
        }
        Text(uiString(R.string.garmin_export_identity_help), style = NoopType.footnote, color = Palette.textTertiary)
    }
}

@Composable
private fun statusLine(lastAttempt: Long?, summary: String?): String {
    if (lastAttempt == null) return uiString(R.string.garmin_never_synced)
    val rel = DateUtils.getRelativeTimeSpanString(
        lastAttempt * 1000, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
    ).toString()
    return uiString(R.string.garmin_status_line, rel, summary.orEmpty())
}

/** A product name, identical in every language, so it is not a string resource. */
private const val TACX_TRAINING_APP = "Tacx Training App"

private fun identityLabel(d: GarminFitExport.DeviceIdentity): String = when (d) {
    GarminFitExport.DeviceIdentity.FORERUNNER_945 -> uiString(R.string.garmin_identity_forerunner945)
    GarminFitExport.DeviceIdentity.TACX_TRAINING_APP -> TACX_TRAINING_APP
    GarminFitExport.DeviceIdentity.NOOP -> uiString(R.string.garmin_identity_noop)
}

@Composable
private fun IdentityRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Metrics.space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
    ) {
        Icon(
            Icons.Filled.Check,
            contentDescription = null,
            tint = if (selected) Palette.accent else Palette.accent.copy(alpha = 0f),
            modifier = Modifier.size(18.dp),
        )
        Text(
            label,
            style = NoopType.subhead,
            color = if (selected) Palette.textPrimary else Palette.textSecondary,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Metrics.space6),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space6),
    ) {
        Text(label, style = NoopType.subhead, color = Palette.accent)
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = Palette.accent,
            modifier = Modifier.size(16.dp),
        )
    }
}
