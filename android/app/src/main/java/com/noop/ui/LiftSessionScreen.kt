package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RemoveCircleOutline
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.data.LiftExerciseRow
import com.noop.data.LiftExerciseVocabularyFull
import com.noop.data.LiftMuscle
import com.noop.lift.LiftFormat
import com.noop.lift.LiftPlanItem
import com.noop.lift.LiftSessionController
import com.noop.lift.LiftSessionEngine
import com.noop.lift.LiftSlot
import com.noop.lift.LiftStage
import kotlinx.coroutines.launch

// The workout sheet: every exercise and every set of the session, on one scrollable page. Compose twin of
// `LiftSessionView.swift`.
//
// A GYM IS NOT A QUEUE: every set is a row, any pending row can be started, and finished rows stay on screen
// with what was lifted. COLOUR CARRIES STATE, readable from arm's length: green is the set being worked, the
// amber band between two rows is the rest running there, and done rows carry a check; numbers nobody typed
// stay grey.
//
// The session lives in `LiftSessionController`, owned by the process. Leaving this screen leaves the session
// running: the clock, the strap double-tap and the buzzes all carry on, and the bar under the navigation
// brings it back.

@Composable
fun LiftSessionScreen(vm: AppViewModel, onClose: () -> Unit) {
    val controller = vm.liftSession
    val state by controller.state.collectAsStateWithLifecycle()
    val system = UnitPrefs.system(LocalContext.current)
    val engine = state.engine
    var finishing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }

    // Last time's numbers, re-read whenever the session's exercises change (an added exercise done before
    // shows its last numbers in grey like every other line).
    val exercises = engine?.plan?.map { it.exercise }
    LaunchedEffect(exercises, engine?.startTs) {
        val e = controller.state.value.engine ?: return@LaunchedEffect
        controller.setLastSession(vm.liftLog.lastTime(e.plan, e.startTs))
    }

    if (engine == null) {
        LaunchedEffect(Unit) { onClose() }
        Box(Modifier.fillMaxSize().background(Palette.surfaceBase), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.lift_no_session), style = NoopType.body, color = Palette.textSecondary)
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.surfaceBase)
            .imePadding(),
    ) {
        val listState = rememberLazyListState()
        // Follow the session down the sheet when it moves on (item 0 is the header).
        val current = engine.currentSlot
        LaunchedEffect(current?.exerciseIndex) {
            current?.let { listState.animateScrollToItem(it.exerciseIndex + 1) }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = Metrics.screenPadding, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(Metrics.sectionGap),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
                    Text(state.programName ?: stringResource(R.string.lift_session), style = NoopType.title1, color = Palette.textPrimary)
                    Text(
                        stringResource(R.string.lift_sets_done, engine.completedWorkingSets, engine.plannedWorkingSets),
                        style = NoopType.caption, color = Palette.textSecondary,
                    )
                }
            }
            itemsIndexed(engine.plan) { index, item -> ExerciseCard(controller, state, engine, index, item, system) }
            item {
                val canAdd = engine.plan.size < LiftSessionEngine.MAX_EXERCISES
                Row(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, Palette.textTertiary.copy(alpha = 0.35f), RoundedCornerShape(Metrics.cardRadius))
                        .clickable(enabled = canAdd) { adding = true }
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val tint = if (canAdd) Palette.effortColor else Palette.textTertiary
                    Icon(Icons.Filled.AddCircle, contentDescription = null, tint = tint)
                    Spacer(Modifier.width(Metrics.space8))
                    Text(stringResource(R.string.lift_add_exercise), style = NoopType.body, color = tint)
                }
            }
        }
        ControlBar(controller, engine, vm, onFinish = { finishing = true })
    }

    if (adding) {
        LiftSessionExerciseSheet(vm, onDismiss = { adding = false }) { name, primary, secondaries ->
            controller.addExercise(name, primary, secondaries)
        }
    }
    if (finishing) {
        LiftFinishSheet(vm, onDismiss = { finishing = false }, onDone = { finishing = false; onClose() })
    }
}

/** Width of the set-number column, shared by the heading and every row. */
private val SET_COLUMN = 34.dp

/** Width of the trailing tick column. */
private val TICK_COLUMN = 30.dp

@Composable
private fun ExerciseCard(
    controller: LiftSessionController,
    state: LiftSessionController.State,
    engine: LiftSessionEngine,
    index: Int,
    item: LiftPlanItem,
    system: UnitSystem,
) {
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
                Text(item.exercise, style = NoopType.headline, color = Palette.textPrimary)
                Text(liftMuscleLine(item.primaryMuscle, item.secondaryMuscles), style = NoopType.caption, color = Palette.textTertiary)
            }
            item.note?.takeIf { it.isNotEmpty() }?.let {
                Text(
                    it,
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                    maxLines = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Palette.metricAmber.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                )
            }
            Row(Modifier.padding(horizontal = Metrics.space8), horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                Overline(stringResource(R.string.lift_col_set), Modifier.width(SET_COLUMN))
                Overline(stringResource(if (system == UnitSystem.IMPERIAL) R.string.lift_col_lb else R.string.lift_col_kg), Modifier.weight(1f))
                Overline(stringResource(R.string.lift_reps), Modifier.weight(1f))
                Overline(stringResource(R.string.lift_col_rpe), Modifier.weight(1f))
                Spacer(Modifier.width(TICK_COLUMN))
            }
            for (slot in engine.slots(index)) {
                SetRow(controller, state, engine, slot, system)
                val stage = engine.stage
                if (stage is LiftStage.Resting && stage.slot == slot) RestBand(engine)
            }
            val canAdd = item.targetSets < LiftSessionEngine.MAX_SETS_PER_EXERCISE
            val canRemove = engine.canRemoveSet(index)
            val addA11y = stringResource(R.string.lift_add_set_a11y, item.exercise)
            val removeA11y = stringResource(R.string.lift_remove_set_a11y, item.exercise)
            Row(Modifier.padding(horizontal = Metrics.space8), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier
                        .weight(1f)
                        .clickable(enabled = canAdd) { controller.addSet(index) }
                        .semantics(mergeDescendants = true) { contentDescription = addA11y },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val tint = if (canAdd) Palette.effortColor else Palette.textTertiary
                    Icon(Icons.Filled.AddCircleOutline, contentDescription = null, tint = tint)
                    Spacer(Modifier.width(Metrics.space6))
                    Text(stringResource(R.string.lift_add_set), style = NoopType.caption, color = tint)
                }
                // Dimmed rather than gone: the pair reads as one control.
                IconButton(onClick = { controller.removeSet(index) }, enabled = canRemove, modifier = Modifier.width(TICK_COLUMN)) {
                    Icon(
                        Icons.Filled.RemoveCircleOutline,
                        contentDescription = removeA11y,
                        tint = if (canRemove) Palette.textSecondary else Palette.textTertiary.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SetRow(
    controller: LiftSessionController,
    state: LiftSessionController.State,
    engine: LiftSessionEngine,
    slot: LiftSlot,
    system: UnitSystem,
) {
    val recorded = engine.recordedSet(slot)
    val working = engine.stage == LiftStage.Working(slot)
    val warmup = state.isWarmup(slot)
    val entered = state.enteredValues(slot)
    val grey = state.carry(slot)

    val background = when {
        working -> Palette.statusPositive.copy(alpha = 0.20f)
        recorded != null -> Palette.surfaceRaised.copy(alpha = 0.5f)
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(8.dp))
            .padding(horizontal = Metrics.space8, vertical = Metrics.space6),
        horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The set number IS the warm-up toggle: warm-ups are excluded from volume and the per-muscle counts.
        val warmA11y = if (warmup) stringResource(R.string.lift_warmup_set_a11y) else stringResource(R.string.lift_set_n_a11y, slot.setIndex)
        Text(
            if (warmup) stringResource(R.string.lift_warmup_mark) else slot.setIndex.toString(),
            style = NoopType.captionNumber,
            color = when {
                warmup -> Palette.metricAmber
                working -> Palette.textPrimary
                else -> Palette.textSecondary
            },
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .width(SET_COLUMN)
                .clickable { controller.setWarmup(slot, !warmup) }
                .semantics { contentDescription = warmA11y },
        )
        NumberField(
            value = entered.weightKg?.let { LiftFormat.trim(LiftFormat.display(it, system)) } ?: "",
            ghost = grey.weightKg?.let { LiftFormat.trim(LiftFormat.display(it, system)) } ?: "—",
            modifier = Modifier.weight(1f),
        ) { text ->
            val now = controller.state.value.enteredValues(slot)
            controller.updateSet(slot, LiftFormat.number(text)?.let { LiftFormat.kilograms(it, system) }, now.reps, now.rpe, controller.state.value.isWarmup(slot))
        }
        NumberField(
            value = entered.reps?.toString() ?: "",
            ghost = grey.reps?.toString() ?: "—",
            modifier = Modifier.weight(1f),
        ) { text ->
            val now = controller.state.value.enteredValues(slot)
            controller.updateSet(slot, now.weightKg, text.trim().toIntOrNull(), now.rpe, controller.state.value.isWarmup(slot))
        }
        // Grey RPE is the line's max RPE (what an unrated set saves); else a previous set's rating as a
        // reminder, which is never saved.
        val rpeGhost = engine.planItem(slot)?.targetRpe?.let { LiftFormat.trim(it) }
            ?: engine.previousSetInSession(slot)?.rpe?.let { LiftFormat.trim(it) } ?: "—"
        NumberField(
            value = entered.rpe?.let { LiftFormat.trim(it) } ?: "",
            ghost = rpeGhost,
            modifier = Modifier.weight(1f),
        ) { text ->
            val now = controller.state.value.enteredValues(slot)
            controller.updateSet(slot, now.weightKg, now.reps, LiftFormat.number(text), controller.state.value.isWarmup(slot))
        }
        // The tick both REPORTS and ACTS: filled when done, and tappable to start (or redo) this set.
        IconButton(onClick = { controller.start(slot) }, modifier = Modifier.width(TICK_COLUMN)) {
            Icon(
                if (recorded == null) Icons.Filled.RadioButtonUnchecked else Icons.Filled.CheckCircle,
                contentDescription = stringResource(if (recorded == null) R.string.lift_start_set_a11y else R.string.lift_redo_set_a11y),
                tint = if (recorded == null) Palette.textTertiary else Palette.statusPositive,
            )
        }
    }
}

/**
 * A number field that does not fight the user while they type: while focused it shows exactly what was
 * typed (so "45." keeps its point), and the parsed value goes to the controller on every keystroke. When
 * focus leaves, it returns to the canonical rendering. A typed comma becomes a point.
 */
@Composable
private fun NumberField(value: String, ghost: String, modifier: Modifier, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(value) }
    if (!focused && draft != value) draft = value
    BasicTextField(
        value = draft,
        onValueChange = {
            val text = it.replace(',', '.')
            draft = text
            onChange(text)
        },
        singleLine = true,
        textStyle = NoopType.bodyNumber.copy(color = Palette.textPrimary),
        cursorBrush = SolidColor(Palette.accent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.onFocusChanged {
            focused = it.isFocused
            if (!it.isFocused) draft = value
        },
        decorationBox = { inner ->
            Box {
                if (draft.isEmpty()) Text(ghost, style = NoopType.bodyNumber, color = Palette.textTertiary)
                inner()
            }
        },
    )
}

/** The running rest, drawn as an amber band BETWEEN the set that ended and the next: where a rest is. */
@Composable
private fun RestBand(engine: LiftSessionEngine) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Metrics.space8)
            .background(Palette.metricAmber.copy(alpha = 0.22f), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Overline(stringResource(R.string.lift_rest_period), Modifier.weight(1f), color = Palette.metricAmber)
        LiftRunningClock({ engine.restRemaining(it) ?: 0L }, style = NoopType.captionNumber, color = Palette.metricAmber)
    }
}

/** The control strip, pinned: at the rack the clock and the one action have to be under the thumb. */
@Composable
private fun ControlBar(controller: LiftSessionController, engine: LiftSessionEngine, vm: AppViewModel, onFinish: () -> Unit) {
    val bpm by vm.bpm.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.surfaceOverlay)
            .navigationBarsPadding(),
    ) {
        HorizontalDivider(color = Palette.textTertiary.copy(alpha = 0.15f), thickness = 0.5.dp)
        Column(
            Modifier.padding(horizontal = Metrics.screenPadding, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(Metrics.space12),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                LiftLabelled(stringResource(R.string.lift_session)) { LiftRunningClock({ it - engine.startTs }) }
                when (engine.stage) {
                    is LiftStage.Working -> LiftLabelled(stringResource(R.string.lift_this_set)) {
                        LiftRunningClock({ it - engine.stageStartedAt }, color = Palette.statusPositive)
                    }
                    is LiftStage.Resting -> LiftLabelled(stringResource(R.string.lift_rest_period)) {
                        LiftRunningClock({ engine.restRemaining(it) ?: 0L }, color = Palette.metricAmber)
                    }
                    else -> LiftLabelled(stringResource(R.string.lift_warmup)) {
                        LiftRunningClock({ it - engine.stageStartedAt }, color = Palette.textSecondary)
                    }
                }
                LiftLabelled(stringResource(R.string.lift_hr)) {
                    Text(bpm?.toString() ?: "—", style = NoopType.bodyNumber, color = if (bpm == null) Palette.textTertiary else Palette.metricRose)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { controller.undo() }, enabled = engine.canUndo) {
                    Icon(
                        Icons.AutoMirrored.Filled.Undo,
                        contentDescription = stringResource(R.string.lift_undo),
                        tint = if (engine.canUndo) Palette.textSecondary else Palette.textTertiary,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                val label = when (engine.stage) {
                    LiftStage.Warmup -> R.string.lift_start_first_set
                    is LiftStage.Working -> R.string.lift_set_done
                    is LiftStage.Resting -> if (engine.allCompleted) R.string.lift_all_sets_done else R.string.lift_start_next_set
                    LiftStage.Finished -> R.string.lift_saving
                }
                NoopButton(stringResource(label), modifier = Modifier.weight(1f), fullWidth = true) { controller.advance() }
                NoopButton(stringResource(R.string.lift_finish), kind = NoopButtonKind.Secondary, onClick = onFinish)
            }
        }
    }
}

/** Add an exercise the program does not have, at the end of the sheet. Swift `LiftSessionExerciseSheet`. */
@Composable
private fun LiftSessionExerciseSheet(
    vm: AppViewModel,
    onDismiss: () -> Unit,
    onAdd: (String, LiftMuscle?, List<LiftMuscle>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var exercise by remember { mutableStateOf("") }
    var primary by remember { mutableStateOf<LiftMuscle?>(null) }
    var secondaries by remember { mutableStateOf<Set<LiftMuscle>>(emptySet()) }
    var vocabulary by remember { mutableStateOf<List<LiftExerciseRow>>(emptyList()) }
    var vocabularyFull by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vocabulary = vm.liftLog.exercises() }
    val trimmed = exercise.trim()

    LiftSheet(onDismiss) {
        LiftSheetTitle(stringResource(R.string.lift_add_exercise), stringResource(R.string.lift_add_exercise_subtitle))
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            SectionHeader(stringResource(R.string.lift_exercise), overline = stringResource(R.string.lift_movement))
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    LiftTextField(stringResource(R.string.lift_exercise), exercise, { typed ->
                        exercise = typed
                        // Typing a known name fills its muscles, unless some are already chosen.
                        if (primary == null && secondaries.isEmpty()) {
                            vocabulary.firstOrNull { it.name == typed.trim() }?.let {
                                primary = LiftMuscle.fromRaw(it.primaryMuscle)
                                secondaries = LiftMuscle.decodeList(it.secondaryMuscles).toSet()
                            }
                        }
                    }, stringResource(R.string.lift_exercise_placeholder))
                    val suggestions = liftSuggestions(vocabulary, exercise, limit = 8)
                    if (suggestions.isNotEmpty()) {
                        Overline(stringResource(R.string.lift_used_before))
                        for (row in suggestions) {
                            LiftExerciseSuggestion(row, onClick = {
                                exercise = row.name
                                primary = LiftMuscle.fromRaw(row.primaryMuscle)
                                secondaries = LiftMuscle.decodeList(row.secondaryMuscles).toSet()
                            })
                        }
                    }
                }
            }
        }
        LiftMusclePicker(primary, secondaries, { primary = it }, { secondaries = it })
        Text(stringResource(R.string.lift_add_exercise_note), style = NoopType.footnote, color = Palette.textTertiary)
        LiftEditorFooter(
            primary = stringResource(R.string.lift_add_to_session),
            enabled = trimmed.isNotEmpty() && !adding,
            onCancel = onDismiss,
            onPrimary = {
                adding = true
                scope.launch {
                    val ordered = liftOrderedSecondaries(secondaries, primary)
                    try {
                        vm.liftLog.rememberExercise(trimmed, primary, ordered, vocabulary)
                    } catch (_: LiftExerciseVocabularyFull) {
                        vocabularyFull = true
                        adding = false
                        return@launch
                    } catch (_: Exception) {
                        // Remembering is a convenience; the session still takes the exercise.
                    }
                    onAdd(trimmed, primary, ordered)
                    onDismiss()
                }
            },
        )
    }
    if (vocabularyFull) LiftVocabularyFullDialog { vocabularyFull = false }
}

private enum class UnfinishedChoice { Complete, Discard }
private enum class ProgramChoice { Update, Keep }

/** Session RPE, what happens to sets never started, and whether the program keeps this session's changes. */
@Composable
private fun LiftFinishSheet(vm: AppViewModel, onDismiss: () -> Unit, onDone: () -> Unit) {
    val controller = vm.liftSession
    val state by controller.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var rpeText by remember { mutableStateOf("") }
    var unfinishedChoice by remember { mutableStateOf<UnfinishedChoice?>(null) }
    var programChoice by remember { mutableStateOf<ProgramChoice?>(null) }
    var changes by remember { mutableStateOf<List<LiftSessionController.SetCountChange>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }
    var confirmingDiscard by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { changes = vm.liftLog.setCountChanges(controller.state.value) }

    val engine = state.engine ?: return
    val unfinished = engine.unperformedSlots.size
    val added = engine.plan.filter { it.addedInSession }
    val asksAboutProgram = changes.isNotEmpty() || added.isNotEmpty()
    val answered = (unfinished == 0 || unfinishedChoice != null) && (!asksAboutProgram || programChoice != null)

    LiftSheet(onDismiss) {
        LiftSheetTitle(stringResource(R.string.lift_finish_session), stringResource(R.string.lift_finish_subtitle))
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                LiftTextField(stringResource(R.string.lift_session_rpe_q), rpeText, { rpeText = it.replace(',', '.') }, "7", numeric = true)
                Text(stringResource(R.string.lift_session_rpe_help), style = NoopType.footnote, color = Palette.textTertiary)
            }
        }
        if (unfinished > 0) {
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    Overline(stringResource(R.string.lift_unfinished_sets))
                    Text(stringResource(R.string.lift_sets_not_started, unfinished), style = NoopType.body, color = Palette.textPrimary)
                    val complete = stringResource(R.string.lift_complete_them)
                    val discard = stringResource(R.string.lift_discard_them)
                    SegmentedPillControl(
                        items = listOf(UnfinishedChoice.Complete, UnfinishedChoice.Discard),
                        selection = unfinishedChoice,
                        label = { if (it == UnfinishedChoice.Complete) complete else discard },
                        onSelect = { unfinishedChoice = it },
                        adaptsToAvailableWidth = true,
                    )
                    Text(stringResource(R.string.lift_unfinished_help), style = NoopType.footnote, color = Palette.textTertiary)
                    if (unfinishedChoice == UnfinishedChoice.Discard &&
                        !LiftSessionController.anyPerformed(controller.setsToSave(completingUnfinished = false))
                    ) {
                        Text(stringResource(R.string.lift_all_zero_warning), style = NoopType.footnote, color = Palette.statusWarning)
                    }
                }
            }
        }
        if (asksAboutProgram) {
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    Overline(stringResource(R.string.lift_program))
                    val question = when {
                        changes.isNotEmpty() && added.isNotEmpty() -> R.string.lift_q_both
                        added.isNotEmpty() -> R.string.lift_q_added
                        else -> R.string.lift_q_counts
                    }
                    Text(stringResource(question), style = NoopType.body, color = Palette.textPrimary)
                    for (c in changes) {
                        Text(stringResource(R.string.lift_count_change, c.exercise, c.from, c.to), style = NoopType.bodyNumber, color = Palette.textSecondary)
                    }
                    for (line in added) {
                        Text(stringResource(R.string.lift_new_line, line.exercise, line.targetSets), style = NoopType.bodyNumber, color = Palette.textSecondary)
                    }
                    val update = stringResource(R.string.lift_update_program)
                    val keep = stringResource(R.string.lift_keep_program)
                    SegmentedPillControl(
                        items = listOf(ProgramChoice.Update, ProgramChoice.Keep),
                        selection = programChoice,
                        label = { if (it == ProgramChoice.Update) update else keep },
                        onSelect = { programChoice = it },
                        adaptsToAvailableWidth = true,
                    )
                }
            }
        }
        NoopButton(stringResource(R.string.lift_save_session), enabled = answered && !saving, fullWidth = true) {
            saving = true
            controller.finish()
            scope.launch {
                vm.liftLog.saveSession(
                    controller.state.value,
                    completingUnfinished = unfinishedChoice == UnfinishedChoice.Complete,
                    keepingProgramChanges = programChoice == ProgramChoice.Update,
                    sessionRpe = LiftFormat.number(rpeText),
                )
                controller.finishedSaving()
                onDone()
            }
        }
        if (!answered) Text(stringResource(R.string.lift_choose_option), style = NoopType.footnote, color = Palette.textTertiary)
        NoopButton(
            stringResource(R.string.lift_discard_session),
            leadingIcon = Icons.Filled.Delete,
            kind = NoopButtonKind.Tertiary,
            enabled = !saving,
            fullWidth = true,
        ) { confirmingDiscard = true }
    }

    if (confirmingDiscard) {
        AlertDialog(
            onDismissRequest = { confirmingDiscard = false },
            title = { Text(stringResource(R.string.lift_discard_session_q)) },
            text = { Text(stringResource(R.string.lift_discard_body, engine.completedWorkingSets)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDiscard = false
                    controller.discard()
                    onDone()
                }) { Text(stringResource(R.string.lift_discard), color = Palette.statusCritical) }
            },
            dismissButton = { TextButton(onClick = { confirmingDiscard = false }) { Text(stringResource(R.string.lift_keep_going)) } },
        )
    }
}
