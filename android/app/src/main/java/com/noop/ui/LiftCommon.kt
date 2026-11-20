package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.data.LiftExerciseRow
import com.noop.data.LiftMuscle
import kotlinx.coroutines.delay

// Shared pieces of the Lift Log screens: muscle names, the muscle picker, the exercise suggestions, the
// text-field styling and the running clock. Compose twins of `LiftMuscleNames.swift`,
// `LiftExercisePicking.swift` and `LiftLiveReadouts.swift`.

/** A muscle's display name. Swift `LiftMuscle.displayName`. */
@Composable
internal fun liftMuscleName(m: LiftMuscle): String = stringResource(
    when (m) {
        LiftMuscle.chest -> R.string.lift_m_chest
        LiftMuscle.frontDelts -> R.string.lift_m_frontDelts
        LiftMuscle.sideDelts -> R.string.lift_m_sideDelts
        LiftMuscle.rearDelts -> R.string.lift_m_rearDelts
        LiftMuscle.triceps -> R.string.lift_m_triceps
        LiftMuscle.lats -> R.string.lift_m_lats
        LiftMuscle.upperBack -> R.string.lift_m_upperBack
        LiftMuscle.traps -> R.string.lift_m_traps
        LiftMuscle.biceps -> R.string.lift_m_biceps
        LiftMuscle.forearms -> R.string.lift_m_forearms
        LiftMuscle.quads -> R.string.lift_m_quads
        LiftMuscle.hamstrings -> R.string.lift_m_hamstrings
        LiftMuscle.glutes -> R.string.lift_m_glutes
        LiftMuscle.adductors -> R.string.lift_m_adductors
        LiftMuscle.abductors -> R.string.lift_m_abductors
        LiftMuscle.calves -> R.string.lift_m_calves
        LiftMuscle.abs -> R.string.lift_m_abs
        LiftMuscle.obliques -> R.string.lift_m_obliques
        LiftMuscle.lowerBack -> R.string.lift_m_lowerBack
        LiftMuscle.neck -> R.string.lift_m_neck
    },
)

@Composable
private fun liftRegionName(r: LiftMuscle.Region): String = stringResource(
    when (r) {
        LiftMuscle.Region.push -> R.string.lift_region_push
        LiftMuscle.Region.pull -> R.string.lift_region_pull
        LiftMuscle.Region.legs -> R.string.lift_region_legs
        LiftMuscle.Region.trunk -> R.string.lift_region_trunk
    },
)

/** "Chest · Triceps, Front delts", or "Not classified". Swift `LiftMuscleSummary.line`. */
@Composable
internal fun liftMuscleLine(primary: LiftMuscle?, secondaries: List<LiftMuscle>): String {
    if (primary == null) return stringResource(R.string.lift_not_classified)
    val names = secondaries.filter { it != primary }.map { liftMuscleName(it) }
    val head = liftMuscleName(primary)
    return if (names.isEmpty()) head else "$head · ${names.joinToString(", ")}"
}

/** The vocabulary rows to offer under an exercise field. Swift `LiftExerciseVocabulary.suggestions`. */
internal fun liftSuggestions(vocabulary: List<LiftExerciseRow>, typed: String, limit: Int = 6): List<LiftExerciseRow> {
    val query = typed.trim().lowercase()
    if (query.isEmpty()) return vocabulary.take(limit)
    return vocabulary.filter { it.name.lowercase().contains(query) && it.name.lowercase() != query }.take(limit)
}

/** Secondaries in canonical muscle order, never including the primary. Swift `LiftExerciseVocabulary.ordered`. */
internal fun liftOrderedSecondaries(secondaries: Set<LiftMuscle>, primary: LiftMuscle?): List<LiftMuscle> =
    LiftMuscle.entries.filter { it in secondaries && it != primary }

@Composable
internal fun liftFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Palette.textPrimary,
    unfocusedTextColor = Palette.textPrimary,
    disabledTextColor = Palette.textTertiary,
    cursorColor = Palette.accent,
    focusedBorderColor = Palette.accent,
    unfocusedBorderColor = Palette.hairline,
    disabledBorderColor = Palette.hairline,
    focusedContainerColor = Palette.surfaceInset,
    unfocusedContainerColor = Palette.surfaceInset,
    disabledContainerColor = Palette.surfaceInset,
    focusedPlaceholderColor = Palette.textTertiary,
    unfocusedPlaceholderColor = Palette.textTertiary,
)

/** A labelled text field in the Lift Log's style. */
@Composable
internal fun LiftTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    singleLine: Boolean = true,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Metrics.space6)) {
        Overline(label)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, style = NoopType.body) },
            textStyle = if (numeric) NoopType.bodyNumber else NoopType.body,
            singleLine = singleLine,
            keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
            colors = liftFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** One exercise from the vocabulary, as a tappable suggestion. Swift `LiftExerciseSuggestionLabel`. */
@Composable
internal fun LiftExerciseSuggestion(row: LiftExerciseRow, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Metrics.space4),
    ) {
        Text(row.name, style = NoopType.body, color = Palette.textPrimary)
        Text(
            liftMuscleLine(LiftMuscle.fromRaw(row.primaryMuscle), LiftMuscle.decodeList(row.secondaryMuscles)),
            style = NoopType.caption,
            color = Palette.textTertiary,
        )
    }
}

/** Primary muscle menu and the secondary chips. Swift `LiftMusclePicker`. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LiftMusclePicker(
    primary: LiftMuscle?,
    secondaries: Set<LiftMuscle>,
    onPrimary: (LiftMuscle?) -> Unit,
    onSecondaries: (Set<LiftMuscle>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(stringResource(R.string.lift_muscles), overline = stringResource(R.string.lift_counted_once))
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space14)) {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space6)) {
                    Overline(stringResource(R.string.lift_primary))
                    var open by remember { mutableStateOf(false) }
                    val a11y = stringResource(R.string.lift_primary_muscle_a11y)
                    Box {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { open = true }
                                .semantics { contentDescription = a11y }
                                .padding(vertical = Metrics.space6),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                primary?.let { liftMuscleName(it) } ?: stringResource(R.string.lift_not_classified),
                                style = NoopType.body,
                                color = if (primary == null) Palette.textTertiary else Palette.textPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(Icons.Filled.UnfoldMore, contentDescription = null, tint = Palette.textTertiary)
                        }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.lift_not_classified)) },
                                onClick = { onPrimary(null); open = false },
                            )
                            for (region in LiftMuscle.Region.entries) {
                                HorizontalDivider()
                                Text(
                                    liftRegionName(region),
                                    style = NoopType.caption,
                                    color = Palette.textTertiary,
                                    modifier = Modifier.padding(horizontal = Metrics.space16, vertical = Metrics.space6),
                                )
                                for (m in LiftMuscle.entries.filter { it.region == region }) {
                                    DropdownMenuItem(
                                        text = { Text(liftMuscleName(m)) },
                                        onClick = {
                                            onPrimary(m)
                                            onSecondaries(secondaries - m)
                                            open = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space6)) {
                    Overline(stringResource(R.string.lift_also_works))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
                        verticalArrangement = Arrangement.spacedBy(Metrics.space8),
                    ) {
                        for (m in LiftMuscle.entries) {
                            if (m == primary) continue
                            val on = m in secondaries
                            Text(
                                liftMuscleName(m),
                                style = NoopType.caption,
                                color = if (on) Palette.effortColor else Palette.textSecondary,
                                modifier = Modifier
                                    .background(
                                        if (on) Palette.effortColor.copy(alpha = 0.14f) else Palette.surfaceRaised,
                                        RoundedCornerShape(Metrics.space8),
                                    )
                                    .clickable { onSecondaries(if (on) secondaries - m else secondaries + m) }
                                    .padding(horizontal = Metrics.space10, vertical = Metrics.space6),
                            )
                        }
                    }
                }
                Text(stringResource(R.string.lift_muscles_footnote), style = NoopType.footnote, color = Palette.textTertiary)
            }
        }
    }
}

/** The current unix second, ticking once a second while composed. Swift `LiftRunningClock`'s timeline. */
@Composable
internal fun rememberUnixSecond(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis() / 1000
            delay(1000 - System.currentTimeMillis() % 1000)
        }
    }
    return now
}

/** A running clock: [seconds] turns the current unix second into what it reads. */
@Composable
internal fun LiftRunningClock(seconds: (Long) -> Long, style: TextStyle = NoopType.bodyNumber, color: androidx.compose.ui.graphics.Color = Palette.textPrimary) {
    val now = rememberUnixSecond()
    Text(elapsedClock(seconds(now)), style = style, color = color)
}

/** A label over a value, as the session's control strip lays its readouts out. */
@Composable
internal fun LiftLabelled(label: String, value: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Overline(label)
        value()
    }
}

/** Cancel / primary pair at the foot of an editor. */
@Composable
internal fun LiftEditorFooter(primary: String, enabled: Boolean, onCancel: () -> Unit, onPrimary: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        NoopButton(stringResource(R.string.lift_cancel), kind = NoopButtonKind.Tertiary, onClick = onCancel)
        Spacer(Modifier.weight(1f))
        NoopButton(primary, enabled = enabled, modifier = Modifier.width(200.dp), onClick = onPrimary)
    }
}
