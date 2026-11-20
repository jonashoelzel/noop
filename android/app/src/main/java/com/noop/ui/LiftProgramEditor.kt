package com.noop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.data.LiftExerciseRow
import com.noop.data.LiftExerciseVocabularyFull
import com.noop.data.LiftLogDao
import com.noop.data.LiftMuscle
import com.noop.data.LiftProgramItemRow
import com.noop.data.LiftProgramRow
import com.noop.lift.LiftFormat
import java.util.UUID
import kotlinx.coroutines.launch

// The program editor and, swapped into the same sheet, the editor for one exercise line. Compose twins of
// `LiftProgramEditorSheet.swift` and `LiftProgramItemSheet.swift`.

@Composable
internal fun LiftProgramEditorSheet(vm: AppViewModel, program: LiftProgramRow?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val system = UnitPrefs.system(LocalContext.current)
    var name by remember { mutableStateOf(program?.name ?: "") }
    var note by remember { mutableStateOf(program?.note ?: "") }
    val items = remember { mutableStateListOf<LiftProgramItemRow>() }
    var saving by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    // Which line the exercise editor is open on: null = closed, a row with id NEW_ITEM = a new line.
    var editingItem by remember { mutableStateOf<LiftProgramItemRow?>(null) }

    LaunchedEffect(program?.id) {
        if (program != null) items.addAll(vm.liftLog.programItems(program.id))
    }

    LiftSheet(onDismiss) {
        val line = editingItem
        if (line != null) {
            LiftProgramItemEditor(
                vm = vm,
                item = line.takeIf { it.id != NEW_ITEM },
                onCancel = { editingItem = null },
                onSave = { saved ->
                    val index = items.indexOfFirst { it.id == saved.id }
                    if (index >= 0) items[index] = saved else items.add(saved)
                    editingItem = null
                },
            )
            return@LiftSheet
        }

        LiftSheetTitle(
            stringResource(if (program == null) R.string.lift_new_program else R.string.lift_edit_program),
            stringResource(R.string.lift_program_editor_subtitle),
        )
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            SectionHeader(stringResource(R.string.lift_program), overline = stringResource(R.string.lift_details))
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space14)) {
                    LiftTextField(stringResource(R.string.lift_name), name, { name = it }, stringResource(R.string.lift_name_placeholder))
                    LiftTextField(
                        stringResource(R.string.lift_note_optional),
                        note,
                        { note = it.take(LiftLogDao.MAX_PROGRAM_NOTE_LENGTH) },
                        stringResource(R.string.lift_note_placeholder),
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            SectionHeader(stringResource(R.string.lift_exercises), overline = stringResource(R.string.lift_in_order))
            if (items.isEmpty()) {
                NoopCard { Text(stringResource(R.string.lift_no_exercises), style = NoopType.footnote, color = Palette.textSecondary) }
            }
            items.forEachIndexed { index, item ->
                NoopCard {
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                        Column(
                            Modifier
                                .weight(1f)
                                .clickable { editingItem = item },
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(item.exercise, style = NoopType.headline, color = Palette.textPrimary)
                            Text(liftTargetSummary(item, system), style = NoopType.caption, color = Palette.textSecondary)
                            item.note?.takeIf { it.isNotEmpty() }?.let {
                                Text(it, style = NoopType.footnote, color = Palette.textTertiary, maxLines = 2)
                            }
                        }
                        Column {
                            IconButton(onClick = { swap(items, index, index - 1) }, enabled = index > 0) {
                                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, tint = Palette.textSecondary)
                            }
                            IconButton(onClick = { swap(items, index, index + 1) }, enabled = index < items.size - 1) {
                                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Palette.textSecondary)
                            }
                            IconButton(onClick = { items.removeAt(index) }) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.lift_delete), tint = Palette.statusCritical)
                            }
                        }
                    }
                }
            }
            NoopButton(
                stringResource(R.string.lift_add_exercise),
                leadingIcon = Icons.Filled.Add,
                kind = NoopButtonKind.Secondary,
                onClick = { editingItem = newItem() },
            )
        }
        if (program != null) {
            NoopButton(
                stringResource(R.string.lift_delete_program),
                leadingIcon = Icons.Filled.Delete,
                kind = NoopButtonKind.Tertiary,
                onClick = { confirmingDelete = true },
            )
        }
        LiftEditorFooter(
            primary = stringResource(R.string.lift_save),
            enabled = name.isNotBlank() && !saving,
            onCancel = onDismiss,
            onPrimary = {
                saving = true
                scope.launch {
                    vm.liftLog.saveProgram(program, name.trim(), note.trim().ifEmpty { null }, items.toList())
                    onDismiss()
                }
            },
        )
    }

    if (confirmingDelete && program != null) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.lift_delete_program_q)) },
            text = { Text(stringResource(R.string.lift_delete_program_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    scope.launch {
                        vm.liftLog.deleteProgram(program.id)
                        onDismiss()
                    }
                }) { Text(stringResource(R.string.lift_delete), color = Palette.statusCritical) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.lift_cancel)) } },
        )
    }
}

private const val NEW_ITEM = "new"

private fun newItem() = LiftProgramItemRow(id = NEW_ITEM, deviceId = "", programId = "", ord = 0, exercise = "")

private fun swap(items: MutableList<LiftProgramItemRow>, a: Int, b: Int) {
    if (a !in items.indices || b !in items.indices) return
    val t = items[a]
    items[a] = items[b]
    items[b] = t
}

/** "4 × 8 · 60 kg · max RPE 8 · 2:00 rest". Swift `LiftProgramEditorSheet.targetSummary`. */
@Composable
internal fun liftTargetSummary(item: LiftProgramItemRow, system: UnitSystem): String {
    val parts = mutableListOf<String>()
    item.targetSets?.let { sets ->
        val reps = item.targetRepsLow
        parts += if (reps != null) stringResource(R.string.lift_reps_times, sets, reps) else pluralStringResource(R.plurals.lift_n_sets, sets, sets)
    }
    item.targetWeightKg?.let { parts += LiftFormat.weight(it, system) }
    item.targetRpe?.let { parts += stringResource(R.string.lift_max_rpe_n, LiftFormat.trim(it)) }
    item.restSec?.let { parts += stringResource(R.string.lift_n_rest, LiftFormat.duration(it)) }
    return if (parts.isEmpty()) stringResource(R.string.lift_no_targets) else parts.joinToString(" · ")
}

/** One exercise line: name, muscles, targets and a technique note. */
@Composable
private fun LiftProgramItemEditor(
    vm: AppViewModel,
    item: LiftProgramItemRow?,
    onCancel: () -> Unit,
    onSave: (LiftProgramItemRow) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val system = UnitPrefs.system(LocalContext.current)
    var exercise by remember { mutableStateOf(item?.exercise ?: "") }
    var primary by remember { mutableStateOf<LiftMuscle?>(null) }
    var secondaries by remember { mutableStateOf<Set<LiftMuscle>>(emptySet()) }
    var setsText by remember { mutableStateOf(item?.targetSets?.toString() ?: "") }
    var repsText by remember { mutableStateOf(item?.targetRepsLow?.toString() ?: "") }
    var weightText by remember {
        mutableStateOf(item?.targetWeightKg?.let { LiftFormat.trim(LiftFormat.display(it, system)) } ?: "")
    }
    var restText by remember { mutableStateOf(item?.restSec?.toString() ?: "") }
    var maxRpeText by remember { mutableStateOf(item?.targetRpe?.let { LiftFormat.trim(it) } ?: "") }
    var note by remember { mutableStateOf(item?.note ?: "") }
    var vocabulary by remember { mutableStateOf<List<LiftExerciseRow>>(emptyList()) }
    var forgetting by remember { mutableStateOf<LiftExerciseRow?>(null) }
    var vocabularyFull by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vocabulary = vm.liftLog.exercises()
        vocabulary.firstOrNull { it.name == item?.exercise }?.let { known ->
            primary = LiftMuscle.fromRaw(known.primaryMuscle)
            secondaries = LiftMuscle.decodeList(known.secondaryMuscles).toSet()
        }
    }

    val maxRpe = LiftFormat.number(maxRpeText)?.takeIf { it in 1.0..10.0 }
    val maxRpeInvalid = maxRpeText.isNotBlank() && maxRpe == null
    val trimmed = exercise.trim()
    val canSave = trimmed.isNotEmpty() && !maxRpeInvalid

    LiftSheetTitle(
        stringResource(if (item == null) R.string.lift_add_exercise else R.string.lift_edit_exercise),
        stringResource(R.string.lift_item_subtitle),
    )
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(stringResource(R.string.lift_exercise), overline = stringResource(R.string.lift_movement))
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                LiftTextField(stringResource(R.string.lift_exercise), exercise, { exercise = it }, stringResource(R.string.lift_exercise_placeholder))
                val suggestions = liftSuggestions(vocabulary, exercise)
                if (suggestions.isNotEmpty()) {
                    Overline(stringResource(R.string.lift_used_before))
                    for (row in suggestions) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LiftExerciseSuggestion(row, onClick = {
                                exercise = row.name
                                primary = LiftMuscle.fromRaw(row.primaryMuscle)
                                secondaries = LiftMuscle.decodeList(row.secondaryMuscles).toSet()
                            }, modifier = Modifier.weight(1f))
                            IconButton(onClick = { forgetting = row }) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.lift_forget), tint = Palette.textTertiary)
                            }
                        }
                    }
                }
            }
        }
    }
    LiftMusclePicker(primary, secondaries, { primary = it }, { secondaries = it })
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(stringResource(R.string.lift_targets), overline = stringResource(R.string.lift_aiming_for))
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space14)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    LiftTextField(stringResource(R.string.lift_working_sets), setsText, { setsText = it }, "4", Modifier.weight(1f), numeric = true)
                    LiftTextField(stringResource(R.string.lift_reps), repsText, { repsText = it }, "8", Modifier.weight(1f), numeric = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    LiftTextField(
                        stringResource(if (system == UnitSystem.IMPERIAL) R.string.lift_weight_lb else R.string.lift_weight_kg),
                        weightText, { weightText = it.replace(',', '.') }, "60", Modifier.weight(1f), numeric = true,
                    )
                    LiftTextField(stringResource(R.string.lift_rest_seconds), restText, { restText = it }, "120", Modifier.weight(1f), numeric = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    LiftTextField(stringResource(R.string.lift_max_rpe_label), maxRpeText, { maxRpeText = it.replace(',', '.') }, "8", Modifier.weight(1f), numeric = true)
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                }
                if (maxRpeInvalid) {
                    Text(stringResource(R.string.lift_max_rpe_invalid), style = NoopType.footnote, color = Palette.statusWarning)
                }
                Text(stringResource(R.string.lift_max_rpe_help), style = NoopType.footnote, color = Palette.textTertiary)
                Text(stringResource(R.string.lift_targets_optional), style = NoopType.footnote, color = Palette.textTertiary)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(stringResource(R.string.lift_technique_note), overline = stringResource(R.string.lift_in_your_words))
        NoopCard {
            LiftTextField(
                stringResource(R.string.lift_technique_note), note, { note = it.take(LiftLogDao.MAX_EXERCISE_NOTE_LENGTH) },
                stringResource(R.string.lift_note_example), singleLine = false,
            )
        }
    }
    LiftEditorFooter(
        primary = stringResource(R.string.lift_save),
        enabled = canSave,
        onCancel = onCancel,
        onPrimary = {
            scope.launch {
                try {
                    vm.liftLog.rememberExercise(trimmed, primary, liftOrderedSecondaries(secondaries, primary), vocabulary)
                } catch (_: LiftExerciseVocabularyFull) {
                    vocabularyFull = true
                    return@launch
                }
                onSave(
                    LiftProgramItemRow(
                        id = item?.id ?: UUID.randomUUID().toString().uppercase(),
                        deviceId = item?.deviceId ?: "",
                        programId = item?.programId ?: "",
                        ord = item?.ord ?: 0,
                        exercise = trimmed,
                        targetSets = setsText.trim().toIntOrNull(),
                        targetRepsLow = repsText.trim().toIntOrNull(),
                        targetRepsHigh = null,
                        targetRpe = maxRpe,
                        targetWeightKg = LiftFormat.number(weightText)?.let { LiftFormat.kilograms(it, system) },
                        restSec = restText.trim().toIntOrNull(),
                        note = note.trim().ifEmpty { null },
                    ),
                )
            }
        },
    )

    forgetting?.let { row ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text(stringResource(R.string.lift_forget_q, row.name)) },
            text = { Text(stringResource(R.string.lift_forget_body)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        vm.liftLog.forgetExercise(row.id)
                        vocabulary = vocabulary.filter { it.id != row.id }
                        forgetting = null
                    }
                }) { Text(stringResource(R.string.lift_forget), color = Palette.statusCritical) }
            },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text(stringResource(R.string.lift_cancel)) } },
        )
    }
    if (vocabularyFull) LiftVocabularyFullDialog { vocabularyFull = false }
}

@Composable
internal fun LiftVocabularyFullDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lift_vocab_full)) },
        text = { Text(stringResource(R.string.lift_vocab_full_body)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.lift_ok)) } },
    )
}
