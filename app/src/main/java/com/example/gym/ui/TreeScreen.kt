package com.example.gym.ui

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.gym.data.Flag
import com.example.gym.data.seed.Exporter
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TreeScreen(onOpenHistory: (Long) -> Unit, modifier: Modifier = Modifier) {
    val vm: TreeViewModel = viewModel()
    val tree by vm.tree.collectAsStateWithLifecycle()
    val showArchived = vm.showArchived
    val edit = vm.edit

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = context.applicationContext as com.example.gym.LiftLogApp

    // Surface reversible actions (archive / delete row) as an Undo snackbar.
    val undo = vm.undoRequest
    LaunchedEffect(undo) {
        if (undo == null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = undo.message,
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) vm.performUndo() else vm.clearUndo()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                // Tap anywhere not consumed by an edit control → cancel the pending edit.
                .pointerInput(edit != null) {
                    if (edit != null) detectTapGestures { vm.cancelEdit() }
                },
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopBar(
                    showArchived = showArchived,
                    onToggleArchived = vm::updateShowArchived,
                    onExport = { scope.launch { shareExport(context, app) } },
                )
                HorizontalDivider()

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    for (category in tree.categories) {
                        stickyHeader(key = categoryKey(category.id)) {
                            CategoryHeader(category.name)
                        }
                        for (area in category.areas) {
                            item(key = areaKey(area.id)) {
                                AreaHeader(area.name, area.lastPerformed)
                            }
                            for (exercise in area.exercises) {
                                val rows = if (showArchived) exercise.setRows
                                else exercise.setRows.filter { !it.archived }
                                // An exercise with no visible rows still shows its name.
                                if (rows.isEmpty()) {
                                    item(key = exerciseKey(exercise.id)) {
                                        ExerciseHeader(exercise.name, exercise.lastPerformed)
                                    }
                                }
                                itemsIndexed(rows, key = { _, it -> "S${it.id}" }) { index, row ->
                                    Box {
                                        SetRowLine(
                                            // Name shows on the first row only; the rest wrap.
                                            exerciseName = if (index == 0) exercise.name else null,
                                            row = row,
                                            edit = edit?.takeIf { it.setRowId == row.id },
                                            onValueTap = { vm.openWheels(row.id, row.reps, row.weight) },
                                            onDateTap = { vm.armDate(row.id, row.reps, row.weight) },
                                            onFlagTap = { vm.cycleFlag(row.id, row.flag) },
                                            onConfirm = vm::confirm,
                                            onCancel = vm::cancelEdit,
                                            onRepsSelected = vm::setPendingReps,
                                            onWeightSelected = vm::setPendingWeight,
                                            onHistory = { vm.cancelEdit(); onOpenHistory(row.id) },
                                            onNoteTap = {
                                                vm.showDialog(TreeViewModel.RowDialog.EditNote(row.id, row.note))
                                            },
                                            onLongPress = { vm.openMenu(row.id) },
                                        )
                                        RowMenu(
                                            expanded = vm.menuForSetRow == row.id,
                                            archived = row.archived,
                                            onDismiss = vm::closeMenu,
                                            onEditNote = {
                                                vm.showDialog(TreeViewModel.RowDialog.EditNote(row.id, row.note))
                                            },
                                            onRename = {
                                                vm.showDialog(TreeViewModel.RowDialog.Rename(exercise.id, exercise.name))
                                            },
                                            onAddSetRow = { vm.addSetRow(row.id) },
                                            onArchive = { vm.archive(row.id) },
                                            onResurrect = { vm.resurrect(row.id) },
                                            onDelete = { vm.deleteRow(row.id) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    when (val d = vm.dialog) {
        is TreeViewModel.RowDialog.EditNote -> TextFieldDialog(
            title = "Edit note",
            initial = d.current.orEmpty(),
            onConfirm = { vm.saveNote(d.setRowId, it) },
            onDismiss = vm::dismissDialog,
        )
        is TreeViewModel.RowDialog.Rename -> TextFieldDialog(
            title = "Rename exercise",
            initial = d.current,
            onConfirm = { vm.saveRename(d.exerciseId, it) },
            onDismiss = vm::dismissDialog,
        )
        null -> Unit
    }
}

// ---- Headers (no folding; hierarchy conveyed by colour + indent) ---------

@Composable
private fun CategoryHeader(name: String) {
    // Sticky + opaque so the current category stays visible while scrolling.
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = name.uppercase(),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun AreaHeader(name: String, date: LocalDate?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(start = 14.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatDate(date),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExerciseHeader(name: String, date: LocalDate?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 12.dp, top = 5.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatDate(date),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SetRowLine(
    exerciseName: String?,
    row: SetRowUi,
    edit: TreeViewModel.EditState?,
    onValueTap: () -> Unit,
    onDateTap: () -> Unit,
    onFlagTap: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onRepsSelected: (Float?) -> Unit,
    onWeightSelected: (Float?) -> Unit,
    onHistory: () -> Unit,
    onNoteTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val baseColor =
        if (row.archived) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        else MaterialTheme.colorScheme.onSurface
    val mutedColor =
        if (row.archived) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        else MaterialTheme.colorScheme.onSurfaceVariant

    // While editing, show the pending values (initialised from current).
    val shownReps = edit?.reps ?: row.reps
    val shownWeight = edit?.weight ?: row.weight

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (row.archived)
                    Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                else Modifier
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 22.dp, end = 12.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Exercise name on the left (first row only); long-press → actions menu.
            // Fixed width so the set values line up across an exercise's rows.
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .combinedClickable(onClick = {}, onLongClick = onLongPress)
                    .padding(vertical = 5.dp),
            ) {
                if (exerciseName != null) {
                    Text(
                        text = exerciseName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = baseColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Reps × weight sit right next to the name.
            if (edit?.showWheels == true) {
                ValueWheels(
                    reps = edit.reps,
                    weight = edit.weight,
                    onRepsSelected = onRepsSelected,
                    onWeightSelected = onWeightSelected,
                )
            } else {
                Text(
                    text = formatValue(shownReps, shownWeight),
                    fontSize = 13.sp,
                    fontWeight = if (edit != null) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (edit != null) MaterialTheme.colorScheme.primary else baseColor,
                    modifier = Modifier.clickable(onClick = onValueTap),
                )
                // ± flag sits right next to the reps × weight.
                FlagCell(row.flag, dim = row.archived, onClick = onFlagTap)
            }

            // Flexible gap pushes the graph + date to the right edge; long-press here too.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(onClick = {}, onLongClick = onLongPress),
            ) { Spacer(Modifier.fillMaxWidth().padding(vertical = 8.dp)) }

            // Graph icon opens the history chart for this row.
            GraphIcon(tint = mutedColor, onClick = onHistory)

            if (edit?.armed == true) {
                Text(
                    text = "✓",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(onClick = onConfirm)
                        .padding(horizontal = 6.dp),
                )
                Text(
                    text = "✕",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clickable(onClick = onCancel)
                        .padding(horizontal = 6.dp),
                )
            } else {
                // Fixed-width, end-aligned so the graph icons line up across rows.
                Box(modifier = Modifier.width(56.dp), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        text = formatDate(row.date),
                        fontSize = 12.sp,
                        color = mutedColor,
                        maxLines = 1,
                        modifier = Modifier.clickable(onClick = onDateTap),
                    )
                }
            }
        }

        // Note lives on its own line beneath the row; tap to edit it.
        if (!row.note.isNullOrBlank()) {
            Text(
                text = row.note,
                fontSize = 11.sp,
                fontStyle = FontStyle.Italic,
                color = mutedColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onNoteTap)
                    .padding(start = 30.dp, end = 12.dp, bottom = 3.dp),
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

@Composable
private fun ValueWheels(
    reps: Float?,
    weight: Float?,
    onRepsSelected: (Float?) -> Unit,
    onWeightSelected: (Float?) -> Unit,
) {
    val repsValues = remember { wheelValues(60f) }
    val weightValues = remember { wheelValues(300f) }
    // Swallow stray taps so they don't bubble up and cancel the edit.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.pointerInput(Unit) { detectTapGestures { } },
    ) {
        WheelPicker(
            items = repsValues,
            initialIndex = indexOfValue(repsValues, reps),
            modifier = Modifier.width(56.dp),
            label = { it?.let(::trimFloat) ?: "—" },
            onSelected = { onRepsSelected(repsValues[it]) },
        )
        Text("×", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        WheelPicker(
            items = weightValues,
            initialIndex = indexOfValue(weightValues, weight),
            modifier = Modifier.width(64.dp),
            label = { it?.let(::trimFloat) ?: "BW" },
            onSelected = { onWeightSelected(weightValues[it]) },
        )
    }
}

/** Tiny line-chart glyph; taps open the per-row history graph. */
@Composable
private fun GraphIcon(tint: Color, onClick: () -> Unit) {
    Canvas(
        modifier = Modifier
            .size(20.dp)
            .clickable(onClick = onClick)
            .padding(3.dp),
    ) {
        val w = size.width
        val h = size.height
        // Normalised polyline (y is fraction from top; lower fraction = higher point).
        val pts = listOf(0f to 0.75f, 0.34f to 0.45f, 0.66f to 0.6f, 1f to 0.18f)
        val path = Path()
        pts.forEachIndexed { i, (x, y) ->
            if (i == 0) path.moveTo(x * w, y * h) else path.lineTo(x * w, y * h)
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
private fun FlagCell(flag: Flag, dim: Boolean, onClick: () -> Unit) {
    val (glyph, color) = when (flag) {
        Flag.NONE -> "±" to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        Flag.UP -> "+" to MaterialTheme.colorScheme.primary
        Flag.DOWN -> "−" to MaterialTheme.colorScheme.error
    }
    Text(
        text = glyph,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = if (dim) color.copy(alpha = 0.4f) else color,
        modifier = Modifier
            .clickable(onClick = onClick)
            .width(20.dp),
    )
}

@Composable
private fun RowMenu(
    expanded: Boolean,
    archived: Boolean,
    onDismiss: () -> Unit,
    onEditNote: () -> Unit,
    onRename: () -> Unit,
    onAddSetRow: () -> Unit,
    onArchive: () -> Unit,
    onResurrect: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (archived) {
            DropdownMenuItem(text = { Text("Resurrect") }, onClick = onResurrect)
        } else {
            DropdownMenuItem(text = { Text("Edit note") }, onClick = onEditNote)
            DropdownMenuItem(text = { Text("Rename exercise") }, onClick = onRename)
            DropdownMenuItem(text = { Text("Add set row") }, onClick = onAddSetRow)
            DropdownMenuItem(text = { Text("Archive") }, onClick = onArchive)
            DropdownMenuItem(
                text = { Text("Delete row", color = MaterialTheme.colorScheme.error) },
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun TextFieldDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = false,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Build the export JSON, write to cache, and fire a share chooser. */
private suspend fun shareExport(context: android.content.Context, app: com.example.gym.LiftLogApp) {
    val jsonText = Exporter.export(app.database)
    val file = File(context.cacheDir, "liftlog-export-${LocalDate.now()}.json")
    file.writeText(jsonText)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(intent, "Export LiftLog").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

@Composable
private fun TopBar(
    showArchived: Boolean,
    onToggleArchived: (Boolean) -> Unit,
    onExport: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("LiftLog", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.weight(1f))
        Text(
            "Export",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onExport).padding(horizontal = 8.dp),
        )
        Text(
            "Archived",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Switch(checked = showArchived, onCheckedChange = onToggleArchived)
    }
}
