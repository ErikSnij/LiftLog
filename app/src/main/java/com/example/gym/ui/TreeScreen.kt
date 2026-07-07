package com.example.gym.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.gym.data.Flag
import com.example.gym.data.seed.Exporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TreeScreen(
    onOpenHistory: (Long) -> Unit,
    onOpenBodyWeight: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: TreeViewModel = viewModel()
    val tree by vm.tree.collectAsStateWithLifecycle()
    val showArchived = vm.showArchived
    val edit = vm.edit

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = context.applicationContext as com.example.gym.LiftLogApp

    val pickJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }
                if (text != null) vm.offerImport(text)
            }
        }
    }

    val importResult = vm.importResult
    LaunchedEffect(importResult) {
        if (importResult == null) return@LaunchedEffect
        snackbarHostState.showSnackbar(importResult)
        vm.clearImportResult()
    }

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = vm.savedScrollIndex,
        initialFirstVisibleItemScrollOffset = vm.savedScrollOffset,
    )

    val pinnedIdsMap = remember(tree, vm.collapsedMuscleGroups, vm.collapsedAreas, showArchived) {
        buildPinnedIdsMap(tree, vm.collapsedMuscleGroups, vm.collapsedAreas, showArchived)
    }
    // No size filter this time — real headers always keep their natural height (just invisible
    // when pinned), so visibleItemsInfo never gets distorted by a zero-height item and this
    // stays in sync every frame, not just after an extra scroll nudge.
    val pinnedIds by remember(pinnedIdsMap) {
        derivedStateOf { pinnedIdsMap[listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String] }
    }
    val pinnedCategory = pinnedIds?.let { ids -> tree.categories.find { it.id == ids.categoryId } }
    val pinnedGroup = pinnedIds?.muscleGroupId?.let { mgId ->
        pinnedCategory?.muscleGroups?.find { it.id == mgId }
    }
    val pinnedArea = pinnedIds?.areaId?.let { aId -> pinnedGroup?.areas?.find { it.id == aId } }
    val density = LocalDensity.current
    // Stable heights measured from the real (non-animated) headers in the list — NOT from the
    // animated overlay below. Using the overlay's own live size caused the list's top padding to
    // fluctuate every frame during a slide/fade transition, visibly dragging the whole list up
    // and down in sync with the animation. These only change when a header's actual layout
    // changes (e.g. text wraps differently), never mid-transition.
    var catHeightPx by remember { mutableIntStateOf(0) }
    var groupHeightPx by remember { mutableIntStateOf(0) }
    var areaHeightPx by remember { mutableIntStateOf(0) }
    val overlayHeightPx = (if (pinnedCategory != null) catHeightPx else 0) +
        (if (pinnedGroup != null) groupHeightPx else 0) +
        (if (pinnedArea != null) areaHeightPx else 0)


    // Scrolling the page dismisses a pending edit — so an accidental tap on a
    // value/date is easily shrugged off by just scrolling on.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && vm.edit != null) vm.cancelEdit()
        }
    }

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
                .imePadding()
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
                    onImport = { pickJson.launch(arrayOf("application/json", "text/plain")) },
                    onAddCategory = vm::promptAddCategory,
                    onBodyWeight = onOpenBodyWeight,
                    onCollapseAll = vm::toggleCollapseAllMuscleGroups,
                    onOpenSettings = onOpenSettings,
                )
                HorizontalDivider()

                Box(modifier = Modifier.weight(1f)) {
                val overlayHeightDp = with(density) { overlayHeightPx.toDp() }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(top = overlayHeightDp),
                ) {
                    for (category in tree.categories) {
                        item(key = categoryKey(category.id)) {
                            // Invisible (not zero-height) whenever the overlay above is showing this
                            // same category — keeping its real height means visibleItemsInfo never
                            // gets distorted, so the overlay stays in sync every frame. Also reports
                            // its natural height for the list's (non-animated) top padding.
                            CategoryHeader(
                                modifier = Modifier
                                    .onGloballyPositioned { catHeightPx = it.size.height }
                                    .alpha(if (pinnedCategory?.id == category.id) 0f else 1f),
                                name = category.name,
                                onAddMuscleGroup = { vm.promptAddMuscleGroup(category.id) },
                                menuExpanded = vm.menuForCategory == category.id,
                                onLongPress = { vm.openCategoryMenu(category.id) },
                                onDismissMenu = vm::closeCategoryMenu,
                                onRename = {
                                    vm.showDialog(TreeViewModel.RowDialog.RenameCategory(category.id, category.name))
                                },
                                onDelete = { vm.deleteCategory(category.id) },
                                onMoveUp = { vm.moveCategory(category.id, -1) },
                                onMoveDown = { vm.moveCategory(category.id, 1) },
                            )
                        }
                        for (group in category.muscleGroups) {
                            val groupCollapsed = group.id in vm.collapsedMuscleGroups
                            item(key = muscleGroupKey(group.id)) {
                                MuscleGroupHeader(
                                    modifier = Modifier
                                        .onGloballyPositioned { groupHeightPx = it.size.height }
                                        .alpha(if (pinnedGroup?.id == group.id) 0f else 1f),
                                    name = group.name,
                                    date = group.lastPerformed,
                                    collapsed = groupCollapsed,
                                    onToggle = { vm.toggleMuscleGroup(group.id) },
                                    onAddArea = { vm.promptAddArea(group.id) },
                                    menuExpanded = vm.menuForMuscleGroup == group.id,
                                    onLongPress = { vm.openMuscleGroupMenu(group.id) },
                                    onDismissMenu = vm::closeMuscleGroupMenu,
                                    onRename = {
                                        vm.showDialog(TreeViewModel.RowDialog.RenameMuscleGroup(group.id, group.name))
                                    },
                                    onDelete = { vm.deleteMuscleGroup(group.id) },
                                    onMoveUp = { vm.moveMuscleGroup(group.id, -1) },
                                    onMoveDown = { vm.moveMuscleGroup(group.id, 1) },
                                    onCollapseAllAreas = { vm.collapseAllAreasInGroup(group.id) },
                                )
                            }
                        if (!groupCollapsed) {
                        for (area in group.areas) {
                            val areaCollapsed = area.id in vm.collapsedAreas
                            item(key = areaKey(area.id)) {
                                AreaHeader(
                                    modifier = Modifier
                                        .onGloballyPositioned { areaHeightPx = it.size.height }
                                        .alpha(if (pinnedArea?.id == area.id) 0f else 1f),
                                    name = area.name,
                                    date = area.lastPerformed,
                                    collapsed = areaCollapsed,
                                    onToggle = { vm.toggleArea(area.id) },
                                    onAddExercise = { vm.promptAddExercise(area.id) },
                                    menuExpanded = vm.menuForArea == area.id,
                                    onLongPress = { vm.openAreaMenu(area.id) },
                                    onDismissMenu = vm::closeAreaMenu,
                                    onRename = {
                                        vm.showDialog(TreeViewModel.RowDialog.RenameArea(area.id, area.name))
                                    },
                                    onDelete = { vm.deleteArea(area.id) },
                                    onMoveUp = { vm.moveArea(area.id, -1) },
                                    onMoveDown = { vm.moveArea(area.id, 1) },
                                )
                            }
                            if (!areaCollapsed) {
                            for (exercise in area.exercises) {
                                if (!showArchived && exercise.archived) continue
                                val rows = exercise.setRows
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
                                            onHistory = {
                                                vm.saveScrollPosition(
                                                    listState.firstVisibleItemIndex,
                                                    listState.firstVisibleItemScrollOffset,
                                                )
                                                vm.cancelEdit()
                                                onOpenHistory(exercise.id)
                                            },
                                            onNoteTap = {
                                                vm.showDialog(TreeViewModel.RowDialog.EditNote(row.id, row.note))
                                            },
                                            onLongPress = { vm.openMenu(row.id) },
                                        )
                                        RowMenu(
                                            expanded = vm.menuForSetRow == row.id,
                                            archived = exercise.archived,
                                            isFirstRow = index == 0,
                                            rowCount = rows.size,
                                            onDismiss = vm::closeMenu,
                                            onEditNote = {
                                                vm.showDialog(TreeViewModel.RowDialog.EditNote(row.id, row.note))
                                            },
                                            onRename = {
                                                vm.showDialog(TreeViewModel.RowDialog.Rename(exercise.id, exercise.name))
                                            },
                                            onAddSetRow = { vm.addSetRow(row.id) },
                                            onArchive = { vm.archive(exercise.id) },
                                            onResurrect = { vm.resurrect(exercise.id) },
                                            onDelete = { vm.deleteRow(row.id) },
                                            onDeleteExercise = { vm.deleteExercise(exercise.id) },
                                        )
                                    }
                                }
                            }
                            }  // if (!areaCollapsed)
                        }
                        }  // if (!groupCollapsed)
                        }
                    }
                }

                // Pinned breadcrumb: shows the category/muscle-group/muscle owning whatever is
                // currently topmost in the list. The LazyColumn above is padded by overlayHeightPx
                // (stable per-level heights, computed above — deliberately NOT this Column's own
                // live/animated size, which fluctuates during a transition and would otherwise drag
                // the whole list up and down in sync with the animation).
                // Each level animates its own handoff (slide + fade) via AnimatedContent instead
                // of an instant swap — native LazyColumn stickyHeader can't stack 3 independent
                // levels with scroll-synced push physics (confirmed: a second stickyHeader just
                // evicts the first), and hand-rolling exact pixel-synced push against internal
                // LazyList offset semantics proved too fragile to ship reliably. This trades exact
                // scroll-speed sync for a well-tested, low-risk animation primitive.
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth(),
                ) {
                    AnimatedContent(
                        targetState = pinnedCategory?.id,
                        transitionSpec = {
                            (slideInVertically(tween(220)) { h -> h } + fadeIn(tween(220))) togetherWith
                                (slideOutVertically(tween(180)) { h -> -h } + fadeOut(tween(180)))
                        },
                        label = "pinnedCategory",
                    ) { id ->
                        val cat = id?.let { catId -> tree.categories.find { it.id == catId } }
                        if (cat != null) {
                            CategoryHeader(
                                name = cat.name,
                                onAddMuscleGroup = { vm.promptAddMuscleGroup(cat.id) },
                                menuExpanded = vm.menuForCategory == cat.id,
                                onLongPress = { vm.openCategoryMenu(cat.id) },
                                onDismissMenu = vm::closeCategoryMenu,
                                onRename = {
                                    vm.showDialog(TreeViewModel.RowDialog.RenameCategory(cat.id, cat.name))
                                },
                                onDelete = { vm.deleteCategory(cat.id) },
                                onMoveUp = { vm.moveCategory(cat.id, -1) },
                                onMoveDown = { vm.moveCategory(cat.id, 1) },
                            )
                        }
                    }
                    AnimatedContent(
                        targetState = pinnedGroup?.id,
                        transitionSpec = {
                            (slideInVertically(tween(220)) { h -> h } + fadeIn(tween(220))) togetherWith
                                (slideOutVertically(tween(180)) { h -> -h } + fadeOut(tween(180)))
                        },
                        label = "pinnedGroup",
                    ) { id ->
                        val group = id?.let { gId -> pinnedCategory?.muscleGroups?.find { it.id == gId } }
                        if (group != null) {
                            MuscleGroupHeader(
                                name = group.name,
                                date = group.lastPerformed,
                                collapsed = group.id in vm.collapsedMuscleGroups,
                                onToggle = { vm.toggleMuscleGroup(group.id) },
                                onAddArea = { vm.promptAddArea(group.id) },
                                menuExpanded = vm.menuForMuscleGroup == group.id,
                                onLongPress = { vm.openMuscleGroupMenu(group.id) },
                                onDismissMenu = vm::closeMuscleGroupMenu,
                                onRename = {
                                    vm.showDialog(TreeViewModel.RowDialog.RenameMuscleGroup(group.id, group.name))
                                },
                                onDelete = { vm.deleteMuscleGroup(group.id) },
                                onMoveUp = { vm.moveMuscleGroup(group.id, -1) },
                                onMoveDown = { vm.moveMuscleGroup(group.id, 1) },
                                onCollapseAllAreas = { vm.collapseAllAreasInGroup(group.id) },
                            )
                        }
                    }
                    AnimatedContent(
                        targetState = pinnedArea?.id,
                        transitionSpec = {
                            (slideInVertically(tween(220)) { h -> h } + fadeIn(tween(220))) togetherWith
                                (slideOutVertically(tween(180)) { h -> -h } + fadeOut(tween(180)))
                        },
                        label = "pinnedArea",
                    ) { id ->
                        val area = id?.let { aId -> pinnedGroup?.areas?.find { it.id == aId } }
                        if (area != null) {
                            AreaHeader(
                                name = area.name,
                                date = area.lastPerformed,
                                collapsed = area.id in vm.collapsedAreas,
                                onToggle = { vm.toggleArea(area.id) },
                                onAddExercise = { vm.promptAddExercise(area.id) },
                                menuExpanded = vm.menuForArea == area.id,
                                onLongPress = { vm.openAreaMenu(area.id) },
                                onDismissMenu = vm::closeAreaMenu,
                                onRename = {
                                    vm.showDialog(TreeViewModel.RowDialog.RenameArea(area.id, area.name))
                                },
                                onDelete = { vm.deleteArea(area.id) },
                                onMoveUp = { vm.moveArea(area.id, -1) },
                                onMoveDown = { vm.moveArea(area.id, 1) },
                            )
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
        is TreeViewModel.RowDialog.RenameArea -> TextFieldDialog(
            title = "Rename muscle",
            initial = d.current,
            onConfirm = { vm.saveRenameArea(d.areaId, it) },
            onDismiss = vm::dismissDialog,
        )
        is TreeViewModel.RowDialog.RenameMuscleGroup -> TextFieldDialog(
            title = "Rename muscle group",
            initial = d.current,
            onConfirm = { vm.saveRenameMuscleGroup(d.muscleGroupId, it) },
            onDismiss = vm::dismissDialog,
        )
        is TreeViewModel.RowDialog.RenameCategory -> TextFieldDialog(
            title = "Rename category",
            initial = d.current,
            onConfirm = { vm.saveRenameCategory(d.categoryId, it) },
            onDismiss = vm::dismissDialog,
        )
        TreeViewModel.RowDialog.AddCategory -> TextFieldDialog(
            title = "New category",
            initial = "",
            onConfirm = { vm.addCategory(it) },
            onDismiss = vm::dismissDialog,
        )
        is TreeViewModel.RowDialog.AddMuscleGroup -> TextFieldDialog(
            title = "New muscle group",
            initial = "",
            onConfirm = { vm.addMuscleGroup(d.categoryId, it) },
            onDismiss = vm::dismissDialog,
        )
        is TreeViewModel.RowDialog.AddArea -> TextFieldDialog(
            title = "New muscle",
            initial = "",
            onConfirm = { vm.addArea(d.muscleGroupId, it) },
            onDismiss = vm::dismissDialog,
        )
        is TreeViewModel.RowDialog.AddExercise -> TextFieldDialog(
            title = "New exercise",
            initial = "",
            onConfirm = { vm.addExercise(d.areaId, it) },
            onDismiss = vm::dismissDialog,
        )
        null -> Unit
    }

    if (vm.pendingImportJson != null) {
        AlertDialog(
            onDismissRequest = vm::dismissImport,
            title = { Text("Replace all data?") },
            text = {
                Text("This will delete everything currently in the app and replace it with the imported file. There is no undo.")
            },
            confirmButton = {
                TextButton(onClick = vm::confirmImport) { Text("Replace") }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissImport) { Text("Cancel") }
            },
        )
    }
}

/** Which category/muscle-group/muscle the topmost visible list item belongs to. */
private data class PinnedIds(val categoryId: Long, val muscleGroupId: Long?, val areaId: Long?)

/**
 * Maps every item key emitted into the LazyColumn to its owning category/muscle-group/muscle,
 * mirroring the exact skip logic (collapsed groups/areas, archived filter) used to build the
 * list itself. Drives the pinned breadcrumb overlay: whichever category/group/muscle the
 * topmost visible item belongs to is what the overlay shows.
 */
private fun buildPinnedIdsMap(
    tree: TreeUi,
    collapsedMuscleGroups: Set<Long>,
    collapsedAreas: Set<Long>,
    showArchived: Boolean,
): Map<String, PinnedIds> {
    val map = HashMap<String, PinnedIds>()
    for (category in tree.categories) {
        map[categoryKey(category.id)] = PinnedIds(category.id, null, null)
        for (group in category.muscleGroups) {
            map[muscleGroupKey(group.id)] = PinnedIds(category.id, group.id, null)
            if (group.id in collapsedMuscleGroups) continue
            for (area in group.areas) {
                map[areaKey(area.id)] = PinnedIds(category.id, group.id, area.id)
                if (area.id in collapsedAreas) continue
                for (exercise in area.exercises) {
                    if (!showArchived && exercise.archived) continue
                    val ids = PinnedIds(category.id, group.id, area.id)
                    if (exercise.setRows.isEmpty()) map[exerciseKey(exercise.id)] = ids
                    exercise.setRows.forEach { row -> map["S${row.id}"] = ids }
                }
            }
        }
    }
    return map
}

// ---- Headers (no folding; hierarchy conveyed by colour + indent) ---------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryHeader(
    name: String,
    onAddMuscleGroup: () -> Unit,
    menuExpanded: Boolean,
    onLongPress: () -> Unit,
    onDismissMenu: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Sticky + opaque so the current category stays visible while scrolling.
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.primaryContainer) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = {}, onLongClick = onLongPress)
                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = name.uppercase(),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                AddButton(tint = MaterialTheme.colorScheme.onPrimaryContainer, onClick = onAddMuscleGroup)
            }
            HeaderMenu(
                expanded = menuExpanded,
                onDismiss = onDismissMenu,
                renameLabel = "Rename category",
                deleteLabel = "Delete category",
                onRename = onRename,
                onDelete = onDelete,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
            )
        }
    }
}

/** Muscle-group header — the new mid level between a category (UPPER/LOWER) and a muscle. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MuscleGroupHeader(
    name: String,
    date: LocalDate?,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onAddArea: () -> Unit,
    menuExpanded: Boolean,
    onLongPress: () -> Unit,
    onDismissMenu: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onCollapseAllAreas: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Opaque: this header can be pinned (sticky), so content scrolling
                // underneath must not show through.
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .combinedClickable(onClick = onToggle, onLongClick = onLongPress)
                .padding(start = 16.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (collapsed) "▸" else "▾",
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.55f),
                modifier = Modifier.padding(end = 6.dp),
            )
            Text(
                text = name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatDate(date),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
            )
            AddButton(tint = MaterialTheme.colorScheme.onSecondaryContainer, onClick = onAddArea)
        }
        HeaderMenu(
            expanded = menuExpanded,
            onDismiss = onDismissMenu,
            renameLabel = "Rename muscle group",
            deleteLabel = "Delete muscle group",
            onRename = onRename,
            onDelete = onDelete,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            onCollapseAllAreas = onCollapseAllAreas,
        )
    }
}

/** Long-press menu shared by category, muscle group, and area headers. */
@Composable
private fun HeaderMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    renameLabel: String,
    deleteLabel: String,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    onCollapseAllAreas: (() -> Unit)? = null,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        onMoveUp?.let { action ->
            DropdownMenuItem(text = { Text("Move up") }, onClick = { onDismiss(); action() })
        }
        onMoveDown?.let { action ->
            DropdownMenuItem(text = { Text("Move down") }, onClick = { onDismiss(); action() })
        }
        onCollapseAllAreas?.let { action ->
            DropdownMenuItem(text = { Text("Collapse / expand all areas") }, onClick = { onDismiss(); action() })
        }
        DropdownMenuItem(text = { Text(renameLabel) }, onClick = onRename)
        DropdownMenuItem(
            text = { Text(deleteLabel, color = MaterialTheme.colorScheme.error) },
            onClick = onDelete,
        )
    }
}

/** A small "+" affordance used on headers to add a child (area / exercise / category). */
@Composable
private fun AddButton(tint: Color, onClick: () -> Unit) {
    Text(
        text = "+",
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        color = tint.copy(alpha = 0.8f),
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 1.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AreaHeader(
    name: String,
    date: LocalDate?,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onAddExercise: () -> Unit,
    menuExpanded: Boolean,
    onLongPress: () -> Unit,
    onDismissMenu: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Opaque: pinned via the breadcrumb overlay, so content scrolling
                // underneath must not show through.
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .combinedClickable(onClick = onToggle, onLongClick = onLongPress)
                .padding(start = 22.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (collapsed) "▸" else "▾",
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                modifier = Modifier.padding(end = 5.dp),
            )
            Text(
                text = name,
                fontSize = 12.5.sp,
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
            AddButton(tint = MaterialTheme.colorScheme.primary, onClick = onAddExercise)
        }
        HeaderMenu(
            expanded = menuExpanded,
            onDismiss = onDismissMenu,
            renameLabel = "Rename muscle",
            deleteLabel = "Delete muscle",
            onRename = onRename,
            onDelete = onDelete,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
        )
    }
}

@Composable
private fun ExerciseHeader(name: String, date: LocalDate?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 12.dp, top = 3.dp, bottom = 1.dp),
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

    val today = LocalDate.now()
    val daysSince = if (row.date != null) (today.toEpochDay() - row.date.toEpochDay()).coerceAtLeast(0L) else Long.MAX_VALUE
    // Fixed warm amber tint (not theme-derived, so it stays visible in dark mode) — deliberately
    // not blue, since the category/muscle-group/muscle headers are all blue-grey and a blue
    // highlight here read as "another section" rather than a highlight. Today gets an extra
    // boost on top of the day-based fade so it reads as clearly the most recent.
    val recencyColor = Color(0xFFFFA726)
    val recencyAlpha = when {
        row.archived -> 0f
        daysSince == 0L -> 0.32f
        daysSince < 7 -> 0.20f * (7f - daysSince) / 7f
        else -> 0f
    }

    var revealName by remember { mutableStateOf(false) }
    LaunchedEffect(revealName) {
        if (revealName) {
            delay(2500)
            revealName = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (row.archived)
                    Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                else if (recencyAlpha > 0f)
                    Modifier.background(recencyColor.copy(alpha = recencyAlpha))
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
            if (edit?.showWheels == true) {
                // Editing the value: a highlighted bar takes over the row entirely —
                // ✕ cancel on the left, the wheels in the middle, ✓ confirm on the right.
                // Everything else (name, graph, date) falls away while you work here.
                WheelEditBar(
                    modifier = Modifier.weight(1f),
                    reps = edit.reps,
                    weight = edit.weight,
                    onRepsSelected = onRepsSelected,
                    onWeightSelected = onWeightSelected,
                    onConfirm = onConfirm,
                    onCancel = onCancel,
                )
            } else {
                // ── Group 1: name + (reps × weight) + flag, kept tight together ──
                // Name fixed-width (first row only) so the values line up across the rows.
                Box(
                    modifier = Modifier
                        .width(130.dp)
                        .combinedClickable(
                            onClick = { if (exerciseName != null) revealName = !revealName },
                            onLongClick = onLongPress,
                        )
                        .padding(vertical = 3.dp),
                ) {
                    if (exerciseName != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = exerciseName,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = baseColor,
                                maxLines = if (revealName) Int.MAX_VALUE else 1,
                                overflow = if (revealName) TextOverflow.Visible else TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(4.dp))
                            // One graph icon per exercise (first row only) — opens the combined
                            // history across all of the exercise's set rows.
                            GraphIcon(tint = mutedColor, onClick = onHistory)
                        }
                    }
                }

                // Wider now that the graph icon moved up beside the name, freeing room here.
                Spacer(Modifier.width(28.dp))
                // Reps × weight, then the ± flag immediately beside it (tight group).
                if (shownReps == null && shownWeight == null) {
                    // Empty: an inviting outlined placeholder instead of a bare dash.
                    Text(
                        text = "reps × wt",
                        fontSize = 11.sp,
                        color = mutedColor,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .border(1.dp, mutedColor.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                            .clickable(onClick = onValueTap)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                } else {
                    Text(
                        text = formatValue(shownReps, shownWeight),
                        fontSize = 13.sp,
                        color = baseColor,
                        maxLines = 1,
                        modifier = Modifier.clickable(onClick = onValueTap),
                    )
                }
                FlagCell(row.flag, dim = row.archived, onClick = onFlagTap)

                // ── Whitespace separating the two groups (long-press = actions menu) ──
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .combinedClickable(onClick = {}, onLongClick = onLongPress),
                ) { Spacer(Modifier.fillMaxWidth().padding(vertical = 8.dp)) }

                // ── Group 2: date ──
                if (edit?.armed == true) {
                    // Armed by tapping the date: ✕ cancel, then ✓ confirm (check on the right).
                    Text(
                        text = "✕",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onCancel)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                    Text(
                        text = "✓",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onConfirm)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                } else {
                    // Old dates (previous years) show a compact, smaller, grey year.
                    val dateLabel = buildAnnotatedString {
                        if (row.date == null) {
                            append("—")
                        } else {
                            append(formatMonthDay(row.date))
                            olderYear(row.date)?.let { year ->
                                withStyle(
                                    SpanStyle(fontSize = 9.sp, color = mutedColor.copy(alpha = 0.6f)),
                                ) { append(" '%02d".format(year % 100)) }
                            }
                        }
                    }
                    Text(
                        text = dateLabel,
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

/** Highlighted in-row editing bar: ✕ cancel · wheels · ✓ confirm. */
@Composable
private fun WheelEditBar(
    modifier: Modifier,
    reps: Float?,
    weight: Float?,
    onRepsSelected: (Float?) -> Unit,
    onWeightSelected: (Float?) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "✕",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onCancel)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
        Spacer(Modifier.weight(1f))
        ValueWheels(
            reps = reps,
            weight = weight,
            onRepsSelected = onRepsSelected,
            onWeightSelected = onWeightSelected,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "✓",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onConfirm)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ValueWheels(
    reps: Float?,
    weight: Float?,
    onRepsSelected: (Float?) -> Unit,
    onWeightSelected: (Float?) -> Unit,
) {
    val repsValues = remember { wheelValues(60f, step = 1f) }
    val weightValues = remember { wheelValues(300f) }
    // Swallow stray taps so they don't bubble up and cancel the edit.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.pointerInput(Unit) { detectTapGestures { } },
    ) {
        WheelOrTypeField(
            items = repsValues,
            current = reps,
            width = 56.dp,
            blankLabel = "—",
            onSelected = onRepsSelected,
        )
        Text("×", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        WheelOrTypeField(
            items = weightValues,
            current = weight,
            width = 64.dp,
            blankLabel = "BW",
            onSelected = onWeightSelected,
        )
    }
}

/**
 * One value slot in the inline editor. Defaults to the scroll wheel; tapping the highlighted
 * number switches it to a numeric keypad so big jumps (e.g. 0 → 100) take a couple of taps
 * instead of a long scroll. Typed input updates the pending value live.
 */
@Composable
internal fun WheelOrTypeField(
    items: List<Float?>,
    current: Float?,
    width: Dp,
    blankLabel: String,
    onSelected: (Float?) -> Unit,
) {
    var typing by remember { mutableStateOf(false) }
    if (typing) {
        val focus = remember { FocusRequester() }
        val focusManager = LocalFocusManager.current
        var text by remember { mutableStateOf(current?.let(::trimFloat) ?: "") }
        LaunchedEffect(Unit) { focus.requestFocus() }
        Box(
            modifier = Modifier.width(width).height(96.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        RoundedCornerShape(6.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = {
                        // Some numpads only offer a comma; treat it as a decimal point.
                        val filtered = it.replace(',', '.').filter { c -> c.isDigit() || c == '.' }
                        text = filtered
                        onSelected(filtered.toFloatOrNull())
                    },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.focusRequester(focus).fillMaxWidth(),
                )
            }
        }
    } else {
        WheelPicker(
            items = items,
            initialIndex = indexOfValue(items, current),
            modifier = Modifier.width(width),
            label = { it?.let(::trimFloat) ?: blankLabel },
            onSelected = { onSelected(items[it]) },
            onCenterClick = { typing = true },
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
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = if (dim) color.copy(alpha = 0.4f) else color,
        modifier = Modifier
            .clickable(onClick = onClick)
            .width(24.dp),
    )
}

@Composable
private fun RowMenu(
    expanded: Boolean,
    archived: Boolean,
    isFirstRow: Boolean,
    rowCount: Int,
    onDismiss: () -> Unit,
    onEditNote: () -> Unit,
    onRename: () -> Unit,
    onAddSetRow: () -> Unit,
    onArchive: () -> Unit,
    onResurrect: () -> Unit,
    onDelete: () -> Unit,
    onDeleteExercise: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (archived) {
            DropdownMenuItem(text = { Text("Resurrect") }, onClick = onResurrect)
        } else {
            DropdownMenuItem(text = { Text("Edit note") }, onClick = onEditNote)
            DropdownMenuItem(text = { Text("Rename exercise") }, onClick = onRename)
            DropdownMenuItem(text = { Text("Add set row") }, onClick = onAddSetRow)
            DropdownMenuItem(text = { Text("Archive") }, onClick = onArchive)
            // "Delete row" available on any row when there are multiple rows.
            if (rowCount > 1) {
                DropdownMenuItem(
                    text = { Text("Delete row", color = MaterialTheme.colorScheme.error) },
                    onClick = onDelete,
                )
            }
            // "Delete exercise" available on the first (name-bearing) row.
            if (isFirstRow) {
                DropdownMenuItem(
                    text = { Text("Delete exercise", color = MaterialTheme.colorScheme.error) },
                    onClick = onDeleteExercise,
                )
            }
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
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 4.dp,
            shadowElevation = 12.dp,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = false,
                    shape = RoundedCornerShape(14.dp),
                    placeholder = {
                        Text("Type here…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                )
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirm(text) },
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("Save") }
                }
            }
        }
    }
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
    onImport: () -> Unit,
    onAddCategory: () -> Unit,
    onBodyWeight: () -> Unit,
    onCollapseAll: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val caretColor = MaterialTheme.colorScheme.onSurface
    val primaryColor = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { menuOpen = true }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("LiftLog", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.width(5.dp))
                Canvas(
                    modifier = Modifier
                        .size(13.dp)
                        .rotate(if (menuOpen) 180f else 0f),
                ) {
                    val path = Path().apply {
                        moveTo(size.width * 0.18f, size.height * 0.36f)
                        lineTo(size.width * 0.5f, size.height * 0.66f)
                        lineTo(size.width * 0.82f, size.height * 0.36f)
                    }
                    drawPath(
                        path,
                        color = caretColor,
                        style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }
            AppMenu(
                expanded = menuOpen,
                showArchived = showArchived,
                onDismiss = { menuOpen = false },
                onExport = { menuOpen = false; onExport() },
                onImport = { menuOpen = false; onImport() },
                onToggleArchived = onToggleArchived,
                onOpenSettings = { menuOpen = false; onOpenSettings() },
            )
        }
        Spacer(Modifier.weight(1f))
        // Collapse / expand all muscle groups — distinct from individual ▸/▾ chevrons
        Canvas(
            modifier = Modifier
                .size(28.dp, 22.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onCollapseAll)
                .padding(3.dp),
        ) {
            val w = size.width; val h = size.height
            val sw = 1.8.dp.toPx()
            val s = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawLine(primaryColor, Offset(0f, h * 0.15f), Offset(w, h * 0.15f), sw)
            drawLine(primaryColor, Offset(0f, h * 0.48f), Offset(w, h * 0.48f), sw)
            val chev = Path().apply {
                moveTo(w * 0.22f, h * 0.68f)
                lineTo(w * 0.5f, h * 0.90f)
                lineTo(w * 0.78f, h * 0.68f)
            }
            drawPath(chev, primaryColor, style = s)
        }
        Text(
            "BW",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onBodyWeight)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Text(
            "+ Category",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onAddCategory)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Styled overflow menu for app-level actions (export, import, show-archived toggle). */
@Composable
private fun AppMenu(
    expanded: Boolean,
    showArchived: Boolean,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onToggleArchived: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(22.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        shadowElevation = 14.dp,
        modifier = Modifier.width(268.dp),
    ) {
        Text(
            text = "LIFTLOG",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 22.dp, top = 12.dp, bottom = 2.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onExport)
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconChip { ExportGlyph(it) }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Export data", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    "Share your log as JSON",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onImport)
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconChip { ImportGlyph(it) }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Import data", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    "Restore from a JSON backup",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable { onToggleArchived(!showArchived) }
                .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconChip { ArchiveGlyph(it) }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Show archived", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    "Reveal retired exercises",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = showArchived,
                onCheckedChange = onToggleArchived,
                modifier = Modifier.scale(0.72f),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onOpenSettings)
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconChip { SettingsGlyph(it) }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("TrainHub sync", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    "Server URL and API key",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

/** A round tinted background behind a small line-drawn glyph, used in the app menu. */
@Composable
private fun IconChip(glyph: @Composable (Color) -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        glyph(MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** Upload-tray glyph for the export action. */
@Composable
private fun ExportGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val s = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val arrow = Path().apply {
            moveTo(w * 0.5f, h * 0.80f)
            lineTo(w * 0.5f, h * 0.16f)
            moveTo(w * 0.30f, h * 0.40f)
            lineTo(w * 0.5f, h * 0.16f)
            lineTo(w * 0.70f, h * 0.40f)
        }
        drawPath(arrow, tint, style = s)
        val tray = Path().apply {
            moveTo(w * 0.22f, h * 0.60f)
            lineTo(w * 0.22f, h * 0.86f)
            lineTo(w * 0.78f, h * 0.86f)
            lineTo(w * 0.78f, h * 0.60f)
        }
        drawPath(tray, tint, style = s)
    }
}

/** Download-tray glyph for the import action (arrow pointing down into tray). */
@Composable
private fun ImportGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val s = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val arrow = Path().apply {
            moveTo(w * 0.5f, h * 0.16f)
            lineTo(w * 0.5f, h * 0.72f)
            moveTo(w * 0.30f, h * 0.50f)
            lineTo(w * 0.5f, h * 0.72f)
            lineTo(w * 0.70f, h * 0.50f)
        }
        drawPath(arrow, tint, style = s)
        val tray = Path().apply {
            moveTo(w * 0.22f, h * 0.60f)
            lineTo(w * 0.22f, h * 0.86f)
            lineTo(w * 0.78f, h * 0.86f)
            lineTo(w * 0.78f, h * 0.60f)
        }
        drawPath(tray, tint, style = s)
    }
}

/** Archive-box glyph for the show-archived toggle. */
@Composable
private fun ArchiveGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val s = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val lid = Path().apply {
            moveTo(w * 0.16f, h * 0.40f)
            lineTo(w * 0.16f, h * 0.24f)
            lineTo(w * 0.84f, h * 0.24f)
            lineTo(w * 0.84f, h * 0.40f)
        }
        drawPath(lid, tint, style = s)
        val box = Path().apply {
            moveTo(w * 0.22f, h * 0.40f)
            lineTo(w * 0.22f, h * 0.80f)
            lineTo(w * 0.78f, h * 0.80f)
            lineTo(w * 0.78f, h * 0.40f)
        }
        drawPath(box, tint, style = s)
        drawLine(
            tint,
            androidx.compose.ui.geometry.Offset(w * 0.40f, h * 0.56f),
            androidx.compose.ui.geometry.Offset(w * 0.60f, h * 0.56f),
            strokeWidth = 1.8.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/** Simple gear glyph for the TrainHub sync settings row. */
@Composable
private fun SettingsGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        val cy = h * 0.5f
        val s = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawCircle(tint, radius = w * 0.16f, center = androidx.compose.ui.geometry.Offset(cx, cy))
        drawCircle(tint, radius = w * 0.34f, center = androidx.compose.ui.geometry.Offset(cx, cy), style = s)
        val teeth = 6
        repeat(teeth) { i ->
            val angle = (2 * Math.PI * i / teeth).toFloat()
            val innerR = w * 0.34f
            val outerR = w * 0.44f
            val x1 = cx + innerR * kotlin.math.cos(angle)
            val y1 = cy + innerR * kotlin.math.sin(angle)
            val x2 = cx + outerR * kotlin.math.cos(angle)
            val y2 = cy + outerR * kotlin.math.sin(angle)
            drawLine(tint, androidx.compose.ui.geometry.Offset(x1, y1), androidx.compose.ui.geometry.Offset(x2, y2), strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}
