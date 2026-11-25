package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.analytics.LiftMetrics
import com.noop.data.LiftSetEntity
import com.noop.lift.LiftFormat
import com.noop.lift.LiftLogRepository
import com.noop.lift.LiftRecords
import java.text.DateFormat
import java.util.Date

// One exercise across every session: its personal records, the estimated-1RM trend, and each session's sets.
// Opened from an exercise card in a running session, from a finished session's detail, and from the hub.

/** A record kind's name. */
@Composable
internal fun liftRecordName(kind: LiftRecords.Kind): String = stringResource(
    when (kind) {
        LiftRecords.Kind.HEAVIEST_WEIGHT -> R.string.lift_record_weight
        LiftRecords.Kind.BEST_ONE_REP_MAX -> R.string.lift_record_one_rep_max
        LiftRecords.Kind.BEST_SET_VOLUME -> R.string.lift_record_set_volume
    },
)

/** What one set is worth under [kind], as text: "100 kg × 5", "≈ 117 kg", "500 kg". */
@Composable
internal fun liftRecordValue(kind: LiftRecords.Kind, weightKg: Double?, reps: Int?, system: UnitSystem): String {
    val w = weightKg ?: return "—"
    val r = reps ?: return "—"
    return when (kind) {
        LiftRecords.Kind.HEAVIEST_WEIGHT -> liftSetText(w, r, system)
        LiftRecords.Kind.BEST_ONE_REP_MAX ->
            LiftMetrics.estimatedOneRepMaxKg(w, r)?.let { liftApproxWeight(it, system) } ?: "—"
        LiftRecords.Kind.BEST_SET_VOLUME -> stringResource(R.string.lift_record_line, LiftFormat.weight(w * r, system), liftSetText(w, r, system))
    }
}

/**
 * An ESTIMATE in whole display units, "≈ 117 kg": the Epley figure is a fit, and two decimals on it would
 * claim a precision it does not have.
 */
@Composable
internal fun liftApproxWeight(kg: Double, system: UnitSystem): String {
    val whole = kotlin.math.round(LiftFormat.display(kg, system))
    return stringResource(R.string.lift_approx_value, LiftFormat.weight(LiftFormat.kilograms(whole, system), system))
}

/** "100 kg × 5". */
@Composable
internal fun liftSetText(weightKg: Double, reps: Int, system: UnitSystem): String =
    stringResource(R.string.lift_set_value, LiftFormat.weight(weightKg, system), reps)

/** One session read for the history: its sets, which of them set records, and its best estimated 1RM. */
private class HistoryRow(
    val entry: LiftLogRepository.ExerciseSession,
    val records: Map<String, Set<LiftRecords.Kind>>,
    val bestOneRepMaxKg: Double?,
)

/** The all-time bests, with the set that holds each. */
private class RecordHolder(val set: LiftSetEntity, val startTs: Long)

@Composable
internal fun LiftExerciseHistorySheet(vm: AppViewModel, exercise: String, onDismiss: () -> Unit) {
    val system = UnitPrefs.system(LocalContext.current)
    var history by remember { mutableStateOf<List<LiftLogRepository.ExerciseSession>?>(null) }
    LaunchedEffect(exercise) { history = vm.liftLog.exerciseHistory(exercise) }

    // Walk the sessions oldest first so each session's records are judged against what came before it.
    val rows = remember(history) {
        var running: LiftRecords.Best? = null
        history.orEmpty().reversed().map { entry ->
            val records = LiftRecords.recordsIn(
                entry.sets.map { LiftRecords.Candidate(it.id, it.weightKg, it.reps, it.isWarmup) },
                running,
            )
            var best = running ?: LiftRecords.Best()
            for (s in entry.sets) best = best.adding(s.weightKg, s.reps, s.isWarmup)
            running = best
            HistoryRow(
                entry,
                records,
                entry.sets.filter { !it.isWarmup }.mapNotNull { LiftMetrics.estimatedOneRepMaxKg(it.weightKg, it.reps) }.maxOrNull(),
            )
        }.reversed()
    }
    val holders = remember(history) {
        LiftRecords.Kind.entries.associateWith { kind ->
            var holder: RecordHolder? = null
            var value = Double.NEGATIVE_INFINITY
            // Oldest first, strictly greater: the FIRST set to reach a best keeps it.
            for (entry in history.orEmpty().reversed()) {
                for (s in entry.sets) {
                    val v = LiftRecords.value(kind, s.weightKg, s.reps, s.isWarmup) ?: continue
                    if (v > value) {
                        value = v
                        holder = RecordHolder(s, entry.session.startTs)
                    }
                }
            }
            holder
        }
    }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }

    LiftSheet(onDismiss) {
        LiftSheetTitle(exercise, stringResource(R.string.lift_exercise_history_subtitle))
        val loaded = history
        when {
            loaded == null -> Text(stringResource(R.string.lift_reading_history), style = NoopType.footnote, color = Palette.textTertiary)
            loaded.isEmpty() -> NoopCard {
                Text(stringResource(R.string.lift_no_history), style = NoopType.footnote, color = Palette.textSecondary)
            }
            else -> {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    SectionHeader(stringResource(R.string.lift_personal_records), overline = stringResource(R.string.lift_all_time))
                    NoopCard {
                        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                            for (kind in LiftRecords.Kind.entries) {
                                val holder = holders[kind]
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
                                        Text(liftRecordName(kind), style = NoopType.caption, color = Palette.textSecondary)
                                        holder?.let {
                                            Text(dateFormat.format(Date(it.startTs * 1000)), style = NoopType.footnote, color = Palette.textTertiary)
                                        }
                                    }
                                    Text(
                                        holder?.let { liftRecordValue(kind, it.set.weightKg, it.set.reps, system) } ?: "—",
                                        style = NoopType.bodyNumber,
                                        color = Palette.textPrimary,
                                    )
                                }
                            }
                        }
                    }
                }
                val trend = rows.reversed().mapNotNull { it.bestOneRepMaxKg }
                if (trend.size >= 2) {
                    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                        SectionHeader(stringResource(R.string.lift_e1rm_trend), overline = stringResource(R.string.lift_per_session))
                        NoopCard {
                            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                                TrendLine(trend, system)
                                Row {
                                    Text(liftApproxWeight(trend.min(), system), style = NoopType.captionNumber, color = Palette.textTertiary, modifier = Modifier.weight(1f))
                                    Text(liftApproxWeight(trend.max(), system), style = NoopType.captionNumber, color = Palette.textTertiary)
                                }
                            }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    SectionHeader(stringResource(R.string.lift_history), overline = stringResource(R.string.lift_every_session))
                    for (row in rows) HistoryCard(row, system, dateFormat)
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(row: HistoryRow, system: UnitSystem, dateFormat: DateFormat) {
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space6)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
                    Text(dateFormat.format(Date(row.entry.session.startTs * 1000)), style = NoopType.headline, color = Palette.textPrimary)
                    row.entry.session.programName?.let { Text(it, style = NoopType.caption, color = Palette.textSecondary) }
                }
                row.bestOneRepMaxKg?.let {
                    Text(liftApproxWeight(it, system), style = NoopType.captionNumber, color = Palette.textSecondary)
                }
            }
            HorizontalDivider(color = Palette.hairline.copy(alpha = 0.6f))
            var number = 0
            for (s in row.entry.sets) {
                val label = if (s.isWarmup) stringResource(R.string.lift_warmup_mark) else (++number).toString()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label,
                        style = NoopType.captionNumber,
                        color = if (s.isWarmup) Palette.metricAmber else Palette.textTertiary,
                        modifier = Modifier.width(28.dp),
                    )
                    Text(
                        when {
                            s.weightKg != null && s.reps != null -> liftSetText(s.weightKg, s.reps, system)
                            s.reps != null -> pluralStringResource(R.plurals.lift_n_reps, s.reps, s.reps)
                            else -> "—"
                        },
                        style = NoopType.bodyNumber,
                        color = Palette.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    s.rpe?.let { Text(stringResource(R.string.lift_rpe_n, LiftFormat.trim(it)), style = NoopType.caption, color = Palette.textTertiary) }
                    row.records[s.id]?.takeIf { it.isNotEmpty() }?.let { kinds ->
                        val names = kinds.map { liftRecordName(it) }.joinToString(", ")
                        Icon(
                            Icons.Filled.EmojiEvents,
                            contentDescription = stringResource(R.string.lift_record_badge_a11y, names),
                            tint = Palette.metricAmber,
                            modifier = Modifier.padding(start = Metrics.space6).size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The best estimated 1RM of each session, oldest to newest, as a line with a dot per session. */
@Composable
private fun TrendLine(values: List<Double>, system: UnitSystem) {
    val color = Palette.effortColor
    val a11y = stringResource(
        R.string.lift_trend_a11y,
        LiftFormat.weight(values.first(), system),
        LiftFormat.weight(values.last(), system),
        values.size,
    )
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .semantics { contentDescription = a11y },
    ) {
        val lo = values.min()
        val hi = values.max()
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val pad = 6.dp.toPx()
        fun point(i: Int): Offset {
            val x = pad + (size.width - 2 * pad) * i / (values.size - 1)
            val y = pad + (size.height - 2 * pad) * (1 - ((values[i] - lo) / span)).toFloat()
            return Offset(x, y)
        }
        val path = Path()
        values.indices.forEach { i ->
            val p = point(i)
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path, color, style = Stroke(width = 2.5.dp.toPx()))
        values.indices.forEach { i -> drawCircle(color, radius = 3.5.dp.toPx(), center = point(i)) }
    }
}
