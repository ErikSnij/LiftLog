package com.example.gym.ui

import android.app.Application
import androidx.room.withTransaction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gym.LiftLogApp
import com.example.gym.data.Flag
import com.example.gym.data.LiftLogDao
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

// ---- Display models (read-only, assembled from the DAO flows) ------------

data class SetRowUi(
    val id: Long,
    val reps: Float?,
    val weight: Float?,
    val note: String?,
    val flag: Flag,
    val date: LocalDate?,
    val hasEntry: Boolean,
    val archived: Boolean,
)

data class ExerciseUi(
    val id: Long,
    val name: String,
    val lastPerformed: LocalDate?,
    val setRows: List<SetRowUi>,
)

data class AreaUi(
    val id: Long,
    val name: String,
    val lastPerformed: LocalDate?,
    val exercises: List<ExerciseUi>,
)

data class MuscleGroupUi(
    val id: Long,
    val name: String,
    val lastPerformed: LocalDate?,
    val areas: List<AreaUi>,
)

data class CategoryUi(
    val id: Long,
    val name: String,
    val lastPerformed: LocalDate?,
    val muscleGroups: List<MuscleGroupUi>,
)

data class TreeUi(
    val categories: List<CategoryUi> = emptyList(),
    val categoryCount: Int = 0,
    val areaCount: Int = 0,
    val exerciseCount: Int = 0,
    val setRowCount: Int = 0,
    val logEntryCount: Int = 0,
    val loaded: Boolean = false,
)

class TreeViewModel(app: Application) : AndroidViewModel(app) {

    private val db = (app as LiftLogApp).database
    private val dao: LiftLogDao = db.dao()

    val tree: StateFlow<TreeUi> = combine(
        dao.observeCategories(),
        dao.observeMuscleGroups(),
        dao.observeAreas(),
        dao.observeExercises(),
        dao.observeAllSetRows(),
        dao.observeLatestEntryPerSetRow(),
        dao.observeExerciseLastPerformed(),
        dao.observeAreaLastPerformed(),
        dao.observeMuscleGroupLastPerformed(),
        dao.observeCategoryLastPerformed(),
        dao.observeLogEntryCount(),
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val categories = values[0] as List<com.example.gym.data.CategoryEntity>
        @Suppress("UNCHECKED_CAST")
        val muscleGroups = values[1] as List<com.example.gym.data.MuscleGroupEntity>
        @Suppress("UNCHECKED_CAST")
        val areas = values[2] as List<com.example.gym.data.AreaEntity>
        @Suppress("UNCHECKED_CAST")
        val exercises = values[3] as List<com.example.gym.data.ExerciseEntity>
        @Suppress("UNCHECKED_CAST")
        val setRows = values[4] as List<com.example.gym.data.SetRowEntity>
        @Suppress("UNCHECKED_CAST")
        val latest = values[5] as List<com.example.gym.data.LatestEntry>
        @Suppress("UNCHECKED_CAST")
        val exLast = values[6] as List<com.example.gym.data.LastPerformed>
        @Suppress("UNCHECKED_CAST")
        val areaLast = values[7] as List<com.example.gym.data.LastPerformed>
        @Suppress("UNCHECKED_CAST")
        val mgLast = values[8] as List<com.example.gym.data.LastPerformed>
        @Suppress("UNCHECKED_CAST")
        val catLast = values[9] as List<com.example.gym.data.LastPerformed>
        val logEntryCount = values[10] as Int

        val latestBySetRow = latest.associateBy { it.setRowId }
        val exLastById = exLast.associate { it.parentId to it.lastPerformed }
        val areaLastById = areaLast.associate { it.parentId to it.lastPerformed }
        val mgLastById = mgLast.associate { it.parentId to it.lastPerformed }
        val catLastById = catLast.associate { it.parentId to it.lastPerformed }

        val setRowsByExercise = setRows.groupBy { it.exerciseId }
        val exercisesByArea = exercises.groupBy { it.areaId }
        val areasByMuscleGroup = areas.groupBy { it.muscleGroupId }
        val muscleGroupsByCategory = muscleGroups.groupBy { it.categoryId }

        // Categories, muscle groups, and muscles keep their user-defined sort_order (from DB).
        // Only exercises are sorted by most-recent activity (never-performed last).
        val categoryUis = categories.map { category ->
            val groupUis = muscleGroupsByCategory[category.id].orEmpty().map { group ->
                val areaUis = areasByMuscleGroup[group.id].orEmpty().map { area ->
                    val exerciseUis = exercisesByArea[area.id].orEmpty().map { exercise ->
                        val rowUis = setRowsByExercise[exercise.id].orEmpty().map { row ->
                            val entry = latestBySetRow[row.id]
                            SetRowUi(
                                id = row.id,
                                reps = entry?.reps,
                                weight = entry?.weight,
                                note = row.note,
                                flag = row.flag,
                                date = entry?.date,
                                hasEntry = entry != null,
                                archived = row.archived,
                            )
                        }
                        ExerciseUi(exercise.id, exercise.name, exLastById[exercise.id], rowUis)
                    }.sortedWith(compareByDescending { it.lastPerformed })
                    AreaUi(area.id, area.name, areaLastById[area.id], exerciseUis)
                }
                MuscleGroupUi(group.id, group.name, mgLastById[group.id], areaUis)
            }
            CategoryUi(category.id, category.name, catLastById[category.id], groupUis)
        }

        TreeUi(
            categories = categoryUis,
            categoryCount = categories.size,
            areaCount = areas.size,
            exerciseCount = exercises.size,
            setRowCount = setRows.size,
            logEntryCount = logEntryCount,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TreeUi())

    // ---- UI state: show-archived (survives recomposition) ----

    var showArchived by mutableStateOf(false)
        private set

    fun updateShowArchived(value: Boolean) {
        showArchived = value
    }

    // ---- Collapse state (in-memory; resets on restart) -------------------

    var collapsedMuscleGroups by mutableStateOf<Set<Long>>(emptySet())
        private set
    var collapsedAreas by mutableStateOf<Set<Long>>(emptySet())
        private set

    fun toggleMuscleGroup(id: Long) {
        collapsedMuscleGroups = if (id in collapsedMuscleGroups) collapsedMuscleGroups - id
        else collapsedMuscleGroups + id
    }

    fun toggleArea(id: Long) {
        collapsedAreas = if (id in collapsedAreas) collapsedAreas - id
        else collapsedAreas + id
    }

    // ---- Move sections (swap sort_order of adjacent siblings) ------------

    fun moveCategory(id: Long, direction: Int) {
        val cats = tree.value.categories
        val idx = cats.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        val other = idx + direction
        if (other !in cats.indices) return
        viewModelScope.launch {
            val newOrder = cats.toMutableList().also { it.add(other, it.removeAt(idx)) }
            newOrder.forEachIndexed { i, c -> dao.updateCategorySortOrder(c.id, i) }
        }
    }

    fun moveMuscleGroup(id: Long, direction: Int) {
        val groups = tree.value.categories
            .firstOrNull { it.muscleGroups.any { g -> g.id == id } }
            ?.muscleGroups ?: return
        val idx = groups.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        val other = idx + direction
        if (other !in groups.indices) return
        viewModelScope.launch {
            val newOrder = groups.toMutableList().also { it.add(other, it.removeAt(idx)) }
            newOrder.forEachIndexed { i, g -> dao.updateMuscleGroupSortOrder(g.id, i) }
        }
    }

    fun moveArea(id: Long, direction: Int) {
        val areas = tree.value.categories.flatMap { it.muscleGroups }
            .firstOrNull { it.areas.any { a -> a.id == id } }
            ?.areas ?: return
        val idx = areas.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        val other = idx + direction
        if (other !in areas.indices) return
        viewModelScope.launch {
            val newOrder = areas.toMutableList().also { it.add(other, it.removeAt(idx)) }
            newOrder.forEachIndexed { i, a -> dao.updateAreaSortOrder(a.id, i) }
        }
    }

    // ---- Fast write loop -------------------------------------------------

    /**
     * Transient per-row edit state. At most one row is "active" at a time. `reps`/`weight` are the
     * pending values the next confirm will log (initialised from the row's current value, null =
     * unchanged/bodyweight). `armed` shows the commit checkmark; `showWheels` shows the pickers.
     */
    data class EditState(
        val setRowId: Long,
        val reps: Float?,
        val weight: Float?,
        val armed: Boolean = false,
        val showWheels: Boolean = false,
    )

    var edit by mutableStateOf<EditState?>(null)
        private set

    /** Tap the date → arm this row for commit (show checkmark), keeping any pending edits. */
    fun armDate(setRowId: Long, currentReps: Float?, currentWeight: Float?) {
        val current = edit?.takeIf { it.setRowId == setRowId }
        edit = (current ?: EditState(setRowId, currentReps, currentWeight)).copy(armed = true)
    }

    /** Tap reps × weight → reveal the inline wheels, keeping any pending/armed state. */
    fun openWheels(setRowId: Long, currentReps: Float?, currentWeight: Float?) {
        val current = edit?.takeIf { it.setRowId == setRowId }
        edit = (current ?: EditState(setRowId, currentReps, currentWeight)).copy(showWheels = true)
    }

    fun setPendingReps(reps: Float?) {
        edit = edit?.copy(reps = reps)
    }

    fun setPendingWeight(weight: Float?) {
        edit = edit?.copy(weight = weight)
    }

    /** Tap anywhere else → cancel, no write. */
    fun cancelEdit() {
        edit = null
    }

    /** Tap the checkmark → write a new immutable LogEntry stamped today (undoable). */
    fun confirm() {
        val state = edit ?: return
        edit = null
        viewModelScope.launch {
            val id = dao.insertLogEntry(
                com.example.gym.data.LogEntryEntity(
                    setRowId = state.setRowId,
                    reps = state.reps,
                    weight = state.weight,
                    date = LocalDate.now(),
                ),
            )
            undoRequest = UndoRequest("Logged today") { dao.deleteLogEntry(id) }
        }
    }

    /** Tap the ± cell → cycle NONE → UP → DOWN, persisted immediately. */
    fun cycleFlag(setRowId: Long, current: Flag) {
        val next = when (current) {
            Flag.NONE -> Flag.UP
            Flag.UP -> Flag.DOWN
            Flag.DOWN -> Flag.NONE
        }
        viewModelScope.launch { dao.updateFlag(setRowId, next) }
    }

    // ---- Phase 5: long-press menu, dialogs, undo -------------------------

    sealed interface RowDialog {
        data class EditNote(val setRowId: Long, val current: String?) : RowDialog
        data class Rename(val exerciseId: Long, val current: String) : RowDialog
        data class RenameArea(val areaId: Long, val current: String) : RowDialog
        data class RenameMuscleGroup(val muscleGroupId: Long, val current: String) : RowDialog
        data class RenameCategory(val categoryId: Long, val current: String) : RowDialog
        data object AddCategory : RowDialog
        data class AddMuscleGroup(val categoryId: Long) : RowDialog
        data class AddArea(val muscleGroupId: Long) : RowDialog
        data class AddExercise(val areaId: Long) : RowDialog
    }

    /** A reversible action surfaced via snackbar. */
    class UndoRequest(val message: String, val perform: suspend () -> Unit)

    var menuForSetRow by mutableStateOf<Long?>(null)
        private set
    var menuForArea by mutableStateOf<Long?>(null)
        private set
    var menuForMuscleGroup by mutableStateOf<Long?>(null)
        private set
    var menuForCategory by mutableStateOf<Long?>(null)
        private set
    var dialog by mutableStateOf<RowDialog?>(null)
        private set
    var undoRequest by mutableStateOf<UndoRequest?>(null)
        private set

    fun openMenu(setRowId: Long) {
        cancelEdit()
        menuForSetRow = setRowId
    }

    fun closeMenu() {
        menuForSetRow = null
    }

    fun openAreaMenu(areaId: Long) {
        cancelEdit()
        menuForArea = areaId
    }

    fun closeAreaMenu() {
        menuForArea = null
    }

    fun openMuscleGroupMenu(muscleGroupId: Long) {
        cancelEdit()
        menuForMuscleGroup = muscleGroupId
    }

    fun closeMuscleGroupMenu() {
        menuForMuscleGroup = null
    }

    fun openCategoryMenu(categoryId: Long) {
        cancelEdit()
        menuForCategory = categoryId
    }

    fun closeCategoryMenu() {
        menuForCategory = null
    }

    fun showDialog(d: RowDialog) {
        menuForSetRow = null
        menuForArea = null
        menuForMuscleGroup = null
        menuForCategory = null
        dialog = d
    }

    fun dismissDialog() {
        dialog = null
    }

    fun saveNote(setRowId: Long, note: String?) {
        dialog = null
        val clean = note?.trim()?.takeUnless { it.isEmpty() }
        viewModelScope.launch { dao.updateNote(setRowId, clean) }
    }

    fun saveRename(exerciseId: Long, name: String) {
        dialog = null
        val clean = name.trim()
        if (clean.isNotEmpty()) viewModelScope.launch { dao.renameExercise(exerciseId, clean) }
    }

    // ---- Add category / area / exercise ----------------------------------

    fun promptAddCategory() { dialog = RowDialog.AddCategory }
    fun promptAddMuscleGroup(categoryId: Long) { dialog = RowDialog.AddMuscleGroup(categoryId) }
    fun promptAddArea(muscleGroupId: Long) { dialog = RowDialog.AddArea(muscleGroupId) }
    fun promptAddExercise(areaId: Long) { dialog = RowDialog.AddExercise(areaId) }

    fun addCategory(name: String) {
        dialog = null
        val clean = name.trim().ifEmpty { return }
        viewModelScope.launch {
            val sortOrder = tree.value.categories.size
            dao.insertCategory(com.example.gym.data.CategoryEntity(name = clean, sortOrder = sortOrder))
        }
    }

    fun addMuscleGroup(categoryId: Long, name: String) {
        dialog = null
        val clean = name.trim().ifEmpty { return }
        viewModelScope.launch {
            val sortOrder = tree.value.categories.find { it.id == categoryId }?.muscleGroups?.size ?: 0
            dao.insertMuscleGroup(
                com.example.gym.data.MuscleGroupEntity(categoryId = categoryId, name = clean, sortOrder = sortOrder),
            )
        }
    }

    fun addArea(muscleGroupId: Long, name: String) {
        dialog = null
        val clean = name.trim().ifEmpty { return }
        viewModelScope.launch {
            val sortOrder = tree.value.categories.flatMap { it.muscleGroups }
                .find { it.id == muscleGroupId }?.areas?.size ?: 0
            dao.insertArea(com.example.gym.data.AreaEntity(muscleGroupId = muscleGroupId, name = clean, sortOrder = sortOrder))
        }
    }

    fun addExercise(areaId: Long, name: String) {
        dialog = null
        val clean = name.trim().ifEmpty { return }
        viewModelScope.launch {
            // Seed one empty set row so the new exercise is immediately loggable.
            db.withTransaction {
                val exerciseId = dao.insertExercise(
                    com.example.gym.data.ExerciseEntity(areaId = areaId, name = clean),
                )
                dao.insertSetRow(com.example.gym.data.SetRowEntity(exerciseId = exerciseId))
            }
        }
    }

    fun saveRenameArea(areaId: Long, name: String) {
        dialog = null
        val clean = name.trim()
        if (clean.isNotEmpty()) viewModelScope.launch { dao.renameArea(areaId, clean) }
    }

    fun saveRenameMuscleGroup(muscleGroupId: Long, name: String) {
        dialog = null
        val clean = name.trim()
        if (clean.isNotEmpty()) viewModelScope.launch { dao.renameMuscleGroup(muscleGroupId, clean) }
    }

    fun saveRenameCategory(categoryId: Long, name: String) {
        dialog = null
        val clean = name.trim()
        if (clean.isNotEmpty()) viewModelScope.launch { dao.renameCategory(categoryId, clean) }
    }

    // ---- Delete exercise / area / category (undoable; restores the subtree) ----

    fun deleteExercise(exerciseId: Long) {
        menuForSetRow = null
        viewModelScope.launch {
            val exercise = dao.getExercise(exerciseId) ?: return@launch
            val rows = dao.setRowsOf(exerciseId)
            val entries = rows.flatMap { dao.getEntriesFor(it.id) }
            dao.deleteExercise(exerciseId)
            undoRequest = UndoRequest("Exercise deleted") {
                db.withTransaction {
                    dao.insertExercise(exercise)
                    rows.forEach { dao.insertSetRow(it) }
                    entries.forEach { dao.insertLogEntry(it) }
                }
            }
        }
    }

    fun deleteArea(areaId: Long) {
        menuForArea = null
        viewModelScope.launch {
            val area = dao.getArea(areaId) ?: return@launch
            val exercises = dao.exercisesOf(areaId)
            val rows = exercises.flatMap { dao.setRowsOf(it.id) }
            val entries = rows.flatMap { dao.getEntriesFor(it.id) }
            dao.deleteArea(areaId)
            undoRequest = UndoRequest("Area deleted") {
                db.withTransaction {
                    dao.insertArea(area)
                    exercises.forEach { dao.insertExercise(it) }
                    rows.forEach { dao.insertSetRow(it) }
                    entries.forEach { dao.insertLogEntry(it) }
                }
            }
        }
    }

    fun deleteMuscleGroup(muscleGroupId: Long) {
        menuForMuscleGroup = null
        viewModelScope.launch {
            val group = dao.getMuscleGroup(muscleGroupId) ?: return@launch
            val areas = dao.areasOf(muscleGroupId)
            val exercises = areas.flatMap { dao.exercisesOf(it.id) }
            val rows = exercises.flatMap { dao.setRowsOf(it.id) }
            val entries = rows.flatMap { dao.getEntriesFor(it.id) }
            dao.deleteMuscleGroup(muscleGroupId)
            undoRequest = UndoRequest("Muscle group deleted") {
                db.withTransaction {
                    dao.insertMuscleGroup(group)
                    areas.forEach { dao.insertArea(it) }
                    exercises.forEach { dao.insertExercise(it) }
                    rows.forEach { dao.insertSetRow(it) }
                    entries.forEach { dao.insertLogEntry(it) }
                }
            }
        }
    }

    fun deleteCategory(categoryId: Long) {
        menuForCategory = null
        viewModelScope.launch {
            val category = dao.getCategory(categoryId) ?: return@launch
            val groups = dao.muscleGroupsOf(categoryId)
            val areas = groups.flatMap { dao.areasOf(it.id) }
            val exercises = areas.flatMap { dao.exercisesOf(it.id) }
            val rows = exercises.flatMap { dao.setRowsOf(it.id) }
            val entries = rows.flatMap { dao.getEntriesFor(it.id) }
            dao.deleteCategory(categoryId)
            undoRequest = UndoRequest("Category deleted") {
                db.withTransaction {
                    dao.insertCategory(category)
                    groups.forEach { dao.insertMuscleGroup(it) }
                    areas.forEach { dao.insertArea(it) }
                    exercises.forEach { dao.insertExercise(it) }
                    rows.forEach { dao.insertSetRow(it) }
                    entries.forEach { dao.insertLogEntry(it) }
                }
            }
        }
    }

    fun addSetRow(setRowId: Long) {
        menuForSetRow = null
        viewModelScope.launch {
            val exerciseId = dao.exerciseIdOf(setRowId) ?: return@launch
            dao.insertSetRow(com.example.gym.data.SetRowEntity(exerciseId = exerciseId))
        }
    }

    fun archive(setRowId: Long) {
        menuForSetRow = null
        viewModelScope.launch { dao.setArchived(setRowId, true) }
        undoRequest = UndoRequest("Archived") { dao.setArchived(setRowId, false) }
    }

    fun resurrect(setRowId: Long) {
        menuForSetRow = null
        viewModelScope.launch { dao.setArchived(setRowId, false) }
    }

    fun deleteRow(setRowId: Long) {
        menuForSetRow = null
        viewModelScope.launch {
            // Capture for undo (the row plus its full history) before the cascade delete.
            val row = dao.getSetRow(setRowId)
            val entries = dao.getEntriesFor(setRowId)
            dao.deleteSetRow(setRowId)
            if (row != null) {
                undoRequest = UndoRequest("Row deleted") {
                    db.withTransaction {
                        dao.insertSetRow(row)
                        entries.forEach { dao.insertLogEntry(it) }
                    }
                }
            }
        }
    }

    fun performUndo() {
        val req = undoRequest ?: return
        undoRequest = null
        viewModelScope.launch { req.perform() }
    }

    fun clearUndo() {
        undoRequest = null
    }
}

// Stable keys for expansion state, namespaced by level so ids never collide.
fun categoryKey(id: Long) = "C$id"
fun muscleGroupKey(id: Long) = "G$id"
fun areaKey(id: Long) = "A$id"
fun exerciseKey(id: Long) = "E$id"
