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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.data.LiftExerciseRow
import com.noop.data.LiftMuscle
import com.noop.lift.LiftCatalog

// Choosing an exercise: the name field with suggestions from the user's own exercises AND the bundled
// library, plus a full library browser filtered by muscle group and equipment. Picking from either fills
// the name and the muscles; the user can still change both before saving.

/** What picking an exercise fills in. */
internal data class LiftPickedExercise(val name: String, val primary: LiftMuscle?, val secondaries: List<LiftMuscle>)

/**
 * The exercise name field and everything that helps fill it. [onForget], when given, offers to forget a
 * remembered exercise (the program editor has it, the in-session sheet does not).
 */
@Composable
internal fun LiftExerciseChooser(
    vocabulary: List<LiftExerciseRow>,
    exercise: String,
    onExercise: (String) -> Unit,
    onPick: (LiftPickedExercise) -> Unit,
    onForget: ((LiftExerciseRow) -> Unit)? = null,
) {
    val context = LocalContext.current
    val catalog = remember { LiftCatalog.load(context) }
    var browsing by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(stringResource(R.string.lift_exercise), overline = stringResource(R.string.lift_movement))
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                LiftTextField(stringResource(R.string.lift_exercise), exercise, onExercise, stringResource(R.string.lift_exercise_placeholder))
                val typed = exercise.trim()
                val own = liftSuggestions(vocabulary, exercise, limit = 6)
                if (own.isNotEmpty()) {
                    Overline(stringResource(R.string.lift_used_before))
                    for (row in own) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LiftExerciseSuggestion(row, onClick = {
                                onPick(
                                    LiftPickedExercise(
                                        row.name,
                                        LiftMuscle.fromRaw(row.primaryMuscle),
                                        LiftMuscle.decodeList(row.secondaryMuscles),
                                    ),
                                )
                            }, modifier = Modifier.weight(1f))
                            if (onForget != null) {
                                IconButton(onClick = { onForget(row) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.lift_forget), tint = Palette.textTertiary)
                                }
                            }
                        }
                    }
                }
                // While typing, the library's matches follow the user's own (minus names already remembered).
                if (typed.isNotEmpty()) {
                    val known = vocabulary.map { it.name.lowercase() }.toSet()
                    val fromLibrary = LiftCatalog.search(catalog, typed)
                        .filter { it.name.lowercase() !in known && it.name.lowercase() != typed.lowercase() }
                        .take(5)
                    if (fromLibrary.isNotEmpty()) {
                        Overline(stringResource(R.string.lift_library))
                        for (e in fromLibrary) LiftCatalogRow(e) { onPick(e.picked()) }
                    }
                }
                if (catalog.isNotEmpty()) {
                    NoopButton(
                        stringResource(R.string.lift_browse_library),
                        leadingIcon = Icons.Filled.Search,
                        kind = NoopButtonKind.Secondary,
                        onClick = { browsing = true },
                    )
                }
            }
        }
    }

    if (browsing) {
        LiftCatalogSheet(catalog, onDismiss = { browsing = false }) {
            browsing = false
            onPick(it.picked())
        }
    }
}

private fun LiftCatalog.Entry.picked() = LiftPickedExercise(name, primary, secondaries)

/** One library entry: name, then muscles and equipment. */
@Composable
private fun LiftCatalogRow(entry: LiftCatalog.Entry, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Metrics.space6),
        verticalArrangement = Arrangement.spacedBy(Metrics.space2),
    ) {
        Text(entry.name, style = NoopType.body, color = Palette.textPrimary)
        Text(
            stringResource(R.string.lift_record_line, liftMuscleLine(entry.primary, entry.secondaries), liftEquipmentName(entry.equipment)),
            style = NoopType.caption,
            color = Palette.textTertiary,
            maxLines = 1,
        )
    }
}

@Composable
internal fun liftEquipmentName(e: LiftCatalog.Equipment): String = stringResource(
    when (e) {
        LiftCatalog.Equipment.BARBELL -> R.string.lift_eq_barbell
        LiftCatalog.Equipment.DUMBBELL -> R.string.lift_eq_dumbbell
        LiftCatalog.Equipment.CABLE -> R.string.lift_eq_cable
        LiftCatalog.Equipment.MACHINE -> R.string.lift_eq_machine
        LiftCatalog.Equipment.BODYWEIGHT -> R.string.lift_eq_bodyweight
        LiftCatalog.Equipment.KETTLEBELL -> R.string.lift_eq_kettlebell
        LiftCatalog.Equipment.BAND -> R.string.lift_eq_band
        LiftCatalog.Equipment.EZ_BAR -> R.string.lift_eq_ez_bar
        LiftCatalog.Equipment.OTHER -> R.string.lift_eq_other
    },
)

/** A filter pill: filled when selected, outlined otherwise so an unselected pill still reads as tappable. */
@Composable
internal fun LiftPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        label,
        style = NoopType.caption,
        color = if (selected) Palette.effortColor else Palette.textSecondary,
        maxLines = 1,
        modifier = Modifier
            .background(if (selected) Palette.effortColor.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent, shape)
            .border(1.dp, if (selected) Palette.effortColor.copy(alpha = 0.5f) else Palette.hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = Metrics.space12, vertical = Metrics.space6),
    )
}

/** The whole library: search, muscle group, primary muscle and equipment filters, one tap to pick. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiftCatalogSheet(catalog: List<LiftCatalog.Entry>, onDismiss: () -> Unit, onPick: (LiftCatalog.Entry) -> Unit) {
    var query by remember { mutableStateOf("") }
    var region by remember { mutableStateOf<LiftMuscle.Region?>(null) }
    var muscle by remember { mutableStateOf<LiftMuscle?>(null) }
    var equipment by remember { mutableStateOf<LiftCatalog.Equipment?>(null) }
    val hits = remember(query, region, muscle, equipment) { LiftCatalog.search(catalog, query, region, muscle, equipment) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.surfaceOverlay,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .imePadding(),
        ) {
            Column(
                Modifier.padding(horizontal = Metrics.screenPadding),
                verticalArrangement = Arrangement.spacedBy(Metrics.space12),
            ) {
                LiftSheetTitle(stringResource(R.string.lift_library_title), stringResource(R.string.lift_library_subtitle, catalog.size))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.lift_search_exercises), style = NoopType.body) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Palette.textTertiary) },
                    singleLine = true,
                    textStyle = NoopType.body,
                    colors = liftFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.size(Metrics.space12))
            val all = stringResource(R.string.lift_filter_all)
            // Muscle group, then (inside a group) the single muscles, then equipment: three rows of pills.
            LazyRow(
                contentPadding = PaddingValues(horizontal = Metrics.screenPadding),
                horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
            ) {
                item { LiftPill(all, region == null) { region = null; muscle = null } }
                items(LiftMuscle.Region.entries) { r ->
                    LiftPill(liftRegionName(r), region == r) { region = if (region == r) null else r; muscle = null }
                }
            }
            region?.let { r ->
                Spacer(Modifier.size(Metrics.space8))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Metrics.screenPadding),
                    horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
                ) {
                    items(LiftMuscle.entries.filter { it.region == r }) { m ->
                        LiftPill(liftMuscleName(m), muscle == m) { muscle = if (muscle == m) null else m }
                    }
                }
            }
            Spacer(Modifier.size(Metrics.space8))
            LazyRow(
                contentPadding = PaddingValues(horizontal = Metrics.screenPadding),
                horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
            ) {
                item { LiftPill(all, equipment == null) { equipment = null } }
                items(LiftCatalog.Equipment.entries) { e ->
                    LiftPill(liftEquipmentName(e), equipment == e) { equipment = if (equipment == e) null else e }
                }
            }
            Spacer(Modifier.size(Metrics.space8))
            HorizontalDivider(color = Palette.hairline)
            if (hits.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(Metrics.screenPadding)) {
                    Text(stringResource(R.string.lift_no_matches), style = NoopType.footnote, color = Palette.textSecondary)
                }
            }
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = Metrics.screenPadding, vertical = Metrics.space8),
            ) {
                items(hits, key = { it.name }) { e ->
                    LiftCatalogRow(e) { onPick(e) }
                    HorizontalDivider(color = Palette.hairline.copy(alpha = 0.5f))
                }
            }
        }
    }
}
