package com.noop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.LiftMetrics
import com.noop.data.LiftMuscle
import com.noop.data.LiftSessionRow
import com.noop.data.LiftSetEntity
import com.noop.lift.LiftFormat
import com.noop.lift.LiftLogRepository
import com.noop.lift.LiftStage
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// A finished session read back: its figures, each exercise as performed, sets per muscle, and how hard it
// felt. Compose twin of `LiftSessionDetailSheet.swift` (editing a finished session's sets is not ported yet).

@Composable
internal fun LiftSessionDetailSheet(vm: AppViewModel, session: LiftSessionRow, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val system = UnitPrefs.system(LocalContext.current)
    var detail by remember { mutableStateOf<LiftLogRepository.Detail?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    LaunchedEffect(session.id) { detail = vm.liftLog.detail(session) }

    val date = remember(session.startTs) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(session.startTs * 1000))
    }
    val subtitle = session.programName?.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.lift_two_parts, it, date) } ?: date
    val performed = detail?.sets.orEmpty().filter { LiftMetrics.isPerformed(it.reps) }
    val rows = performed.map(LiftLogRepository::metricsRow)

    LiftSheet(onDismiss) {
        LiftSheetTitle(stringResource(R.string.lift_session), subtitle)
        val d = detail
        if (d == null) {
            Text(stringResource(R.string.lift_reading_session), style = NoopType.footnote, color = Palette.textTertiary)
            return@LiftSheet
        }
        Figures(session, d, rows, system)
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            SectionHeader(stringResource(R.string.lift_exercises), overline = stringResource(R.string.lift_as_performed))
            if (performed.isEmpty()) {
                NoopCard { Text(stringResource(R.string.lift_none_performed), style = NoopType.footnote, color = Palette.textSecondary) }
            }
            for (summary in LiftMetrics.perExercise(rows)) {
                ExerciseCard(summary, performed.filter { it.exercise == summary.exercise }.sortedBy { it.ord }, d.previousVolume[summary.exercise], system)
            }
        }
        if (performed.isNotEmpty()) {
            Muscles(rows)
            Rpe(rows)
        }
        Text(stringResource(R.string.lift_figures_footnote), style = NoopType.footnote, color = Palette.textTertiary)
        NoopButton(
            stringResource(R.string.lift_delete_session),
            leadingIcon = Icons.Filled.Delete,
            kind = NoopButtonKind.Tertiary,
            enabled = !deleting,
            fullWidth = true,
        ) { confirmingDelete = true }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.lift_delete_session_q)) },
            text = { Text(stringResource(R.string.lift_delete_session_body, performed.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    deleting = true
                    scope.launch {
                        vm.liftLog.deleteSession(session, detail?.workout)
                        onChanged()
                        onDismiss()
                    }
                }) { Text(stringResource(R.string.lift_delete), color = Palette.statusCritical) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.lift_cancel)) } },
        )
    }
}

@Composable
private fun Figures(session: LiftSessionRow, d: LiftLogRepository.Detail, rows: List<LiftMetrics.Row>, system: UnitSystem) {
    val durationSec = session.endTs?.let { maxOf(0L, it - session.startTs).toInt() } ?: 0
    val working = rows.count { !it.isWarmup }
    val load = LiftMetrics.sessionLoad(session.sessionRpe, durationSec)
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(stringResource(R.string.lift_this_session), overline = stringResource(R.string.lift_figures))
        Tile(
            stringResource(R.string.lift_volume),
            LiftFormat.weight(LiftMetrics.volumeLoadKg(rows), system),
            pluralStringResource(R.plurals.lift_working_sets_caption, working, working),
        )
        Tile(
            stringResource(R.string.lift_session_load),
            load?.roundToInt()?.toString() ?: "—",
            session.sessionRpe?.let {
                // Two resources joined rather than one "RPE %s × %s min": each half is a single word,
                // so a locale that keeps "RPE" and "min" verbatim is not counted as untranslated.
                stringResource(R.string.lift_rpe_n, LiftFormat.trim(it)) + " × " +
                    stringResource(R.string.lift_n_min, LiftFormat.trim(durationSec / 60.0))
            } ?: stringResource(R.string.lift_not_rated),
        )
        Tile(
            stringResource(R.string.lift_effort),
            d.workout?.strain?.let { LiftFormat.trim(it) } ?: "—",
            stringResource(R.string.lift_measured_hr),
        )
    }
}

@Composable
private fun Tile(label: String, value: String, caption: String) {
    NoopCard(padding = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Overline(label)
            Text(value, style = NoopType.title2, color = Palette.textPrimary)
            Text(caption, style = NoopType.caption, color = Palette.textTertiary)
        }
    }
}

@Composable
private fun ExerciseCard(summary: LiftMetrics.ExerciseSummary, sets: List<LiftSetEntity>, previousVolume: Double?, system: UnitSystem) {
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
                Text(summary.exercise, style = NoopType.headline, color = Palette.textPrimary)
                sets.firstOrNull()?.let {
                    Text(
                        liftMuscleLine(LiftMuscle.fromRaw(it.primaryMuscle), LiftMuscle.decodeList(it.secondaryMuscles)),
                        style = NoopType.caption, color = Palette.textTertiary,
                    )
                }
            }
            for (row in sets) {
                Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space12), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (row.isWarmup) stringResource(R.string.lift_warmup_mark) else row.setIndex.toString(),
                        style = NoopType.captionNumber,
                        color = if (row.isWarmup) Palette.metricAmber else Palette.textSecondary,
                        modifier = Modifier.width(24.dp),
                    )
                    Text(setValueText(row.weightKg, row.reps, system), style = NoopType.bodyNumber, color = Palette.textPrimary, modifier = Modifier.weight(1f))
                    row.rpe?.let { Text(stringResource(R.string.lift_rpe_n, LiftFormat.trim(it)), style = NoopType.caption, color = Palette.textSecondary) }
                    row.restSec?.let { Text(LiftFormat.duration(it), style = NoopType.caption, color = Palette.textTertiary) }
                }
            }
            HorizontalDivider(color = Palette.textTertiary.copy(alpha = 0.2f))
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Overline(stringResource(R.string.lift_best_set))
                    Text(setValueText(summary.bestWeightKg, summary.bestReps, system), style = NoopType.bodyNumber, color = Palette.textPrimary)
                    summary.bestEstimatedOneRepMaxKg?.let {
                        Text(stringResource(R.string.lift_e1rm, LiftFormat.weight(it, system)), style = NoopType.caption, color = Palette.textTertiary)
                    }
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Overline(stringResource(R.string.lift_volume))
                    Text(LiftFormat.weight(summary.volumeKg, system), style = NoopType.bodyNumber, color = Palette.textPrimary)
                    val now = summary.volumeKg
                    if (now != null && previousVolume != null && previousVolume > 0) {
                        val delta = now - previousVolume
                        val text = if (abs(delta) < 0.5) {
                            stringResource(R.string.lift_same_as_last)
                        } else {
                            val sign = if (delta > 0) "+" else "−"
                            stringResource(R.string.lift_vs_last_time, sign + LiftFormat.weight(abs(delta), system))
                        }
                        Text(
                            text,
                            style = NoopType.caption,
                            color = when {
                                now > previousVolume -> Palette.statusPositive
                                now < previousVolume -> Palette.textSecondary
                                else -> Palette.textTertiary
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun setValueText(weightKg: Double?, reps: Int?, system: UnitSystem): String {
    if (reps == null && weightKg == null) return "—"
    val r = reps?.toString() ?: "—"
    if (weightKg == null) return if (reps != null) pluralStringResource(R.plurals.lift_n_reps, reps, reps) else r
    return "${LiftFormat.weight(weightKg, system)} × $r"
}

@Composable
private fun Muscles(rows: List<LiftMetrics.Row>) {
    val counts = LiftMetrics.muscleCounts(rows)
    val ordered = LiftMuscle.entries.filter { (counts.fractional[it] ?: 0.0) > 0 }
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(stringResource(R.string.lift_sets_per_muscle), overline = stringResource(R.string.lift_this_session_estimated))
        NoopCard {
            if (ordered.isEmpty()) {
                Text(stringResource(R.string.lift_no_muscles_yet), style = NoopType.footnote, color = Palette.textSecondary)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                    for (m in ordered) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space12), verticalAlignment = Alignment.CenterVertically) {
                            Text(liftMuscleName(m), style = NoopType.caption, color = Palette.textSecondary, modifier = Modifier.weight(1f))
                            Text(LiftFormat.trim(counts.fractional[m] ?: 0.0), style = NoopType.captionNumber, color = Palette.textPrimary)
                            val dn = counts.direct[m] ?: 0
                            val ind = counts.indirect[m] ?: 0
                            Text(
                                when {
                                    dn > 0 && ind > 0 -> stringResource(R.string.lift_direct_indirect, dn, ind)
                                    dn > 0 -> stringResource(R.string.lift_direct, dn)
                                    else -> stringResource(R.string.lift_indirect, ind)
                                },
                                style = NoopType.caption, color = Palette.textTertiary,
                            )
                        }
                    }
                    Text(stringResource(R.string.lift_session_muscles_caption), style = NoopType.footnote, color = Palette.textTertiary)
                }
            }
        }
    }
}

@Composable
private fun Rpe(rows: List<LiftMetrics.Row>) {
    val p = LiftMetrics.rpeProfile(rows)
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(stringResource(R.string.lift_how_hard), overline = stringResource(R.string.lift_col_rpe))
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                Row {
                    Text(stringResource(R.string.lift_mean_rpe), style = NoopType.body, color = Palette.textSecondary, modifier = Modifier.weight(1f))
                    Text(p.mean?.let { LiftFormat.trim(it) } ?: "—", style = NoopType.bodyNumber, color = Palette.textPrimary)
                }
                Row {
                    Text(stringResource(R.string.lift_sets_at_rpe, LiftFormat.trim(p.threshold)), style = NoopType.body, color = Palette.textSecondary, modifier = Modifier.weight(1f))
                    Text(p.setsAtOrAboveThreshold.toString(), style = NoopType.bodyNumber, color = Palette.textPrimary)
                }
                if (p.unratedSets > 0) {
                    Text(stringResource(R.string.lift_unrated_note, p.unratedSets, p.ratedSets), style = NoopType.footnote, color = Palette.textTertiary)
                }
            }
        }
    }
}

/**
 * The running session, minimised: reachable from every screen while one runs, and the way back into it.
 * Twin of `LiftSessionBar.swift`; the words come from the same state the session screen draws from.
 */
@Composable
fun LiftSessionBar(vm: AppViewModel, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val controller = vm.liftSession
    val state by controller.state.collectAsStateWithLifecycle()
    val engine = state.engine ?: return
    if (engine.isFinished) return
    val bpm by vm.bpm.collectAsStateWithLifecycle()
    val system = UnitPrefs.system(LocalContext.current)
    val now = rememberUnixSecond()
    val stage = engine.stage
    val tint = when (stage) {
        is LiftStage.Working -> Palette.statusPositive
        is LiftStage.Resting -> Palette.metricAmber
        else -> Palette.effortColor
    }
    val slot = engine.currentSlot
    val item = slot?.let { engine.planItem(it) }
    val exercise = item?.exercise ?: state.programName ?: stringResource(R.string.lift_session)
    val status = when {
        slot == null -> stringResource(R.string.lift_warmup)
        stage is LiftStage.Resting && stage.endsAt <= now -> stringResource(R.string.lift_ready_next)
        stage is LiftStage.Resting -> stringResource(R.string.lift_resting_after, slot.setIndex)
        else -> stringResource(R.string.lift_set_n, slot.setIndex)
    }
    val detail = slot?.let {
        val shown = state.shownNumbers(it)
        val w = shown.weightKg?.let { kg -> LiftFormat.weight(kg, system) }
        when {
            shown.reps != null && w != null -> "${shown.reps} x $w"
            shown.reps != null -> pluralStringResource(R.plurals.lift_n_reps, shown.reps, shown.reps)
            else -> w
        }
    }
    val upcoming = engine.upcomingSlot
    val next = when {
        upcoming != null -> stringResource(R.string.lift_next_line, upcoming.setIndex, engine.planItem(upcoming)?.exercise ?: "")
        engine.allCompleted -> stringResource(R.string.lift_all_sets_done)
        else -> stringResource(R.string.lift_last_set)
    }
    val clock = (stage as? LiftStage.Resting)?.let { maxOf(0L, it.endsAt - now) } ?: (now - engine.stageStartedAt)
    val openA11y = stringResource(R.string.lift_open_session_a11y)

    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(Palette.surfaceOverlay)
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(50))
            .clickable(onClick = onOpen)
            .semantics { contentDescription = openA11y }
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.FitnessCenter, contentDescription = null, tint = tint)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(exercise, style = NoopType.caption, color = Palette.textPrimary, maxLines = 1)
            Text(detail?.let { stringResource(R.string.lift_status_detail, status, it) } ?: status, style = NoopType.footnote, color = Palette.textSecondary, maxLines = 1)
            Text(next, style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(horizontal = 6.dp)) {
            Text(bpm?.toString() ?: "—", style = NoopType.captionNumber, color = if (bpm == null) Palette.textTertiary else Palette.metricRose)
            Text(elapsedClock(clock), style = NoopType.bodyNumber, color = tint)
        }
        IconButton(onClick = { controller.advance() }) {
            Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.lift_next_a11y), tint = tint)
        }
    }
}
