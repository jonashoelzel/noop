package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.LiftMetrics
import com.noop.data.LiftExerciseRow
import com.noop.data.LiftMuscle
import com.noop.data.LiftProgramRow
import com.noop.data.LiftSessionRow
import com.noop.lift.LiftFormat
import com.noop.lift.LiftLogRepository
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

// The Lift Log: build a program once, then run it in the gym by tapping through it. Compose twin of
// `LiftLogView.swift`. It lives in the Effort colour world, like Workouts, because a finished session lands
// in the workout table beside every other workout.
//
// EFFORT IS NEVER MODIFIED HERE. NOOP's Effort is computed from heart rate alone, and there is no validated
// public path from typed sets, reps and weight to a cardiovascular-strain equivalent. The lifting figures
// are shown BESIDE Effort and never folded into it.

@Composable
fun LiftLogScreen(vm: AppViewModel, onOpenSession: () -> Unit) {
    val scope = rememberCoroutineScope()
    val session by vm.liftSession.state.collectAsStateWithLifecycle()

    var hub by remember { mutableStateOf<LiftLogRepository.Hub?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<ProgramEditTarget?>(null) }
    var viewing by remember { mutableStateOf<LiftSessionRow?>(null) }
    var history by remember { mutableStateOf<String?>(null) }

    // Also on a saved session: the session screen cannot call back into this one.
    LaunchedEffect(reload, session.savedSessions) { hub = vm.liftLog.hub() }

    ScreenScaffold(
        title = stringResource(R.string.nav_lift_log),
        subtitle = stringResource(R.string.lift_hub_subtitle, stringResource(R.string.lift_this_device)),
    ) {
        HeaderCard()
        ProgramsSection(
            hub = hub,
            running = session.isActive,
            onEdit = { editing = ProgramEditTarget(it) },
            onNew = { editing = ProgramEditTarget(null) },
            onStart = { program ->
                scope.launch {
                    // Refuse to start a second session over a running one: two live sessions would both
                    // claim the strap gesture and both write the in-flight snapshot.
                    if (vm.liftSession.isActive) {
                        onOpenSession()
                        return@launch
                    }
                    val plan = vm.liftLog.plan(program)
                    if (plan.isEmpty()) return@launch
                    vm.liftSession.start(plan, program.id, program.name)
                    onOpenSession()
                }
            },
        )
        WeekSection(hub?.weekCounts.orEmpty())
        HistorySection(hub?.history.orEmpty()) { viewing = it }
        ExercisesSection(hub?.exercises.orEmpty()) { history = it }
    }

    editing?.let { target ->
        LiftProgramEditorSheet(vm, target.program, onDismiss = { editing = null; reload++ })
    }
    viewing?.let { row ->
        LiftSessionDetailSheet(vm, row, onDismiss = { viewing = null }, onChanged = { reload++ })
    }
    history?.let { name -> LiftExerciseHistorySheet(vm, name, onDismiss = { history = null }) }
}

private class ProgramEditTarget(val program: LiftProgramRow?)

@Composable
private fun HeaderCard() {
    NoopCard(tint = Palette.effortColor) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                Box(
                    Modifier
                        .size(30.dp)
                        .background(Palette.effortColor.copy(alpha = 0.14f), RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.FitnessCenter, contentDescription = null, tint = Palette.effortColor, modifier = Modifier.size(16.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
                    Text(stringResource(R.string.lift_your_log_book), style = NoopType.headline, color = Palette.textPrimary)
                    Text(stringResource(R.string.lift_log_book_caption), style = NoopType.caption, color = Palette.textSecondary)
                }
            }
            Text(stringResource(R.string.lift_effort_note), style = NoopType.footnote, color = Palette.textSecondary)
        }
    }
}

@Composable
private fun ProgramsSection(
    hub: LiftLogRepository.Hub?,
    running: Boolean,
    onEdit: (LiftProgramRow) -> Unit,
    onNew: () -> Unit,
    onStart: (LiftProgramRow) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(stringResource(R.string.lift_programs), overline = stringResource(R.string.lift_saved))
        when {
            hub == null -> Text(stringResource(R.string.lift_reading_programs), style = NoopType.footnote, color = Palette.textTertiary)
            hub.programs.isEmpty() -> NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                    Text(stringResource(R.string.lift_no_programs), style = NoopType.headline, color = Palette.textPrimary)
                    Text(stringResource(R.string.lift_no_programs_body), style = NoopType.footnote, color = Palette.textSecondary)
                }
            }
            else -> for (program in hub.programs) {
                NoopCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                        Column(
                            Modifier
                                .weight(1f)
                                .clickable { onEdit(program) },
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(program.name, style = NoopType.headline, color = Palette.textPrimary)
                            program.note?.takeIf { it.isNotEmpty() }?.let {
                                Text(it, style = NoopType.caption, color = Palette.textSecondary, maxLines = 3)
                            }
                            // What the program holds, so the right one is picked without opening it.
                            val items = hub.programItems[program.id].orEmpty()
                            if (items.isNotEmpty()) {
                                Text(
                                    items.joinToString(", ") { it.exercise },
                                    style = NoopType.caption,
                                    color = Palette.textSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val sets = items.sumOf { it.targetSets ?: 0 }
                                Text(
                                    if (sets > 0) {
                                        stringResource(
                                            R.string.lift_record_line,
                                            pluralStringResource(R.plurals.lift_n_exercises, items.size, items.size),
                                            pluralStringResource(R.plurals.lift_n_sets, sets, sets),
                                        )
                                    } else {
                                        pluralStringResource(R.plurals.lift_n_exercises, items.size, items.size)
                                    },
                                    style = NoopType.footnote,
                                    color = Palette.textTertiary,
                                )
                            } else {
                                Text(stringResource(R.string.lift_tap_to_edit), style = NoopType.footnote, color = Palette.textTertiary)
                            }
                        }
                        val a11y = stringResource(R.string.lift_start_program_a11y)
                        NoopButton(
                            stringResource(if (running) R.string.lift_running else R.string.lift_start),
                            modifier = Modifier
                                .widthIn(max = 110.dp)
                                .semantics { contentDescription = a11y },
                            onClick = { onStart(program) },
                        )
                    }
                }
            }
        }
        NoopButton(stringResource(R.string.lift_new_program), leadingIcon = Icons.Filled.Add, kind = NoopButtonKind.Secondary, onClick = onNew)
    }
}

/**
 * The span the weekly bar is drawn across. A DRAWING choice, not a dose: the evidence puts a floor at about
 * 4 sets a week and no ceiling, so the number beside the bar is the truth and the bar is context for it.
 */
private const val WEEKLY_SETS_BAR_SPAN = 20.0

@Composable
private fun WeekSection(counts: Map<LiftMuscle, Double>) {
    val ordered = LiftMuscle.entries.filter { (counts[it] ?: 0.0) > 0 }
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(stringResource(R.string.lift_sets_per_muscle), overline = stringResource(R.string.lift_last_7_days))
        NoopCard {
            if (ordered.isEmpty()) {
                Text(stringResource(R.string.lift_week_empty), style = NoopType.footnote, color = Palette.textSecondary)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                    for (m in ordered) MuscleBar(m, counts[m] ?: 0.0)
                    // The research caveat matters, but once read it need not take six lines every visit.
                    var explained by remember { mutableStateOf(false) }
                    Row(
                        Modifier.clickable { explained = !explained },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Metrics.space6),
                    ) {
                        Icon(Icons.Outlined.Info, contentDescription = null, tint = Palette.textTertiary, modifier = Modifier.size(16.dp))
                        Text(stringResource(R.string.lift_about_these_numbers), style = NoopType.footnote, color = Palette.textSecondary)
                    }
                    if (explained) {
                        Text(stringResource(R.string.lift_week_caption), style = NoopType.footnote, color = Palette.textTertiary)
                    }
                }
            }
        }
    }
}

/**
 * One muscle's week. Four sets is a TICK a fifth of the way along, never a full or green bar: the research
 * says growth merely becomes detectable there, so nothing on the bar ever reads as complete.
 */
@Composable
private fun MuscleBar(muscle: LiftMuscle, sets: Double) {
    val floor = LiftMetrics.ReferenceDose.hypertrophyMinimumSetsPerWeek
    val atFloor = sets >= floor
    val fill = minOf(1.0, sets / WEEKLY_SETS_BAR_SPAN).toFloat()
    val tick = minOf(1.0, floor / WEEKLY_SETS_BAR_SPAN).toFloat()
    val name = liftMuscleName(muscle)
    val a11y = stringResource(
        if (atFloor) R.string.lift_week_at_floor_a11y else R.string.lift_week_below_floor_a11y,
        name, LiftFormat.trim(sets), LiftFormat.trim(floor),
    )
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = a11y }, verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = NoopType.caption, color = Palette.textSecondary, modifier = Modifier.weight(1f))
            Text(LiftFormat.trim(sets), style = NoopType.captionNumber, color = Palette.textPrimary)
        }
        var widthPx by remember { mutableIntStateOf(0) }
        val density = LocalDensity.current
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Palette.surfaceRaised)
                .onSizeChanged { widthPx = it.width },
        ) {
            val w = with(density) { widthPx.toDp() }
            Box(
                Modifier
                    .width(maxOf(2.dp, w * fill))
                    .height(6.dp)
                    .background(Palette.effortColor.copy(alpha = if (atFloor) 1f else 0.45f), RoundedCornerShape(3.dp)),
            )
            Box(
                Modifier
                    .offset(x = maxOf(0.dp, w * tick - 1.dp))
                    .width(2.dp)
                    .height(6.dp)
                    .background(Palette.textPrimary.copy(alpha = 0.45f)),
            )
        }
    }
}

@Composable
private fun HistorySection(history: List<LiftSessionRow>, onOpen: (LiftSessionRow) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(stringResource(R.string.lift_sessions), overline = stringResource(R.string.lift_recent))
        if (history.isEmpty()) {
            NoopCard { Text(stringResource(R.string.lift_sessions_empty), style = NoopType.footnote, color = Palette.textSecondary) }
        }
        val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
        for (row in history) {
            NoopCard(Modifier.clickable { onOpen(row) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(row.programName ?: stringResource(R.string.lift_session), style = NoopType.headline, color = Palette.textPrimary)
                        Text(fmt.format(Date(row.startTs * 1000)), style = NoopType.caption, color = Palette.textSecondary)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Palette.textSecondary)
                }
            }
        }
    }
}

/** The remembered exercises; each opens its records and history. */
@Composable
private fun ExercisesSection(exercises: List<LiftExerciseRow>, onOpen: (String) -> Unit) {
    if (exercises.isEmpty()) return
    var showAll by remember { mutableStateOf(false) }
    val shown = if (showAll) exercises else exercises.take(EXERCISES_SHOWN)
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(stringResource(R.string.lift_exercises), overline = stringResource(R.string.lift_records_and_history))
        NoopCard {
            Column {
                shown.forEachIndexed { i, row ->
                    if (i > 0) HorizontalDivider(color = Palette.hairline.copy(alpha = 0.6f))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(row.name) }
                            .padding(vertical = Metrics.space10),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
                            Text(row.name, style = NoopType.body, color = Palette.textPrimary)
                            Text(
                                liftMuscleLine(LiftMuscle.fromRaw(row.primaryMuscle), LiftMuscle.decodeList(row.secondaryMuscles)),
                                style = NoopType.caption,
                                color = Palette.textTertiary,
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Palette.textSecondary)
                    }
                }
                if (exercises.size > EXERCISES_SHOWN) {
                    HorizontalDivider(color = Palette.hairline.copy(alpha = 0.6f))
                    Text(
                        stringResource(if (showAll) R.string.lift_show_fewer else R.string.lift_show_all_n, exercises.size),
                        style = NoopType.body,
                        color = Palette.effortColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAll = !showAll }
                            .padding(vertical = Metrics.space10),
                    )
                }
            }
        }
    }
}

/** How many exercises the hub lists before "Show all". */
private const val EXERCISES_SHOWN = 5

/** A full-height sheet holding one scrollable editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiftSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.surfaceOverlay,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = Metrics.screenPadding)
                .padding(bottom = Metrics.sectionGap),
            verticalArrangement = Arrangement.spacedBy(Metrics.sectionGap),
            content = content,
        )
    }
}

/** A sheet's title block. */
@Composable
internal fun LiftSheetTitle(title: String, subtitle: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
        Text(title, style = NoopType.title1, color = Palette.textPrimary)
        subtitle?.let { Text(it, style = NoopType.caption, color = Palette.textSecondary) }
        Spacer(Modifier.height(Metrics.space2))
    }
}
