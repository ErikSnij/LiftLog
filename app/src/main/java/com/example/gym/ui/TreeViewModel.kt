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

data class CategoryUi(
    val id: Long,
    val name: String,
    val lastPerformed: LocalDate?,
    val areas: List<AreaUi>,
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
        dao.observeAreas(),
        dao.observeExercises(),
        dao.observeAllSetRows(),
        dao.observeLatestEntryPerSetRow(),
        dao.observeExerciseLastPerformed(),
        dao.observeAreaLastPerformed(),
        dao.observeCategoryLastPerformed(),
        dao.observeLogEntryCount(),
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val categories = values[0] as List<com.example.gym.data.CategoryEntity>
        @Suppress("UNCHECKED_CAST")
        val areas = values[1] as List<com.example.gym.data.AreaEntity>
        @Suppress("UNCHECKED_CAST")
        val exercises = values[2] as List<com.example.gym.data.ExerciseEntity>
        @Suppress("UNCHECKED_CAST")
        val setRows = values[3] as List<com.example.gym.data.SetRowEntity>
        @Suppress("UNCHECKED_CAST")
        val latest = values[4] as List<com.example.gym.data.LatestEntry>
        @Suppress("UNCHECKED_CAST")
        val exLast = values[5] as List<com.example.gym.data.LastPerformed>
        @Suppress("UNCHECKED_CAST")
        val areaLast = values[6] as List<com.example.gym.data.LastPerformed>
        @Suppress("UNCHECKED_CAST")
        val catLast = values[7] as List<com.example.gym.data.LastPerformed>
        val logEntryCount = values[8] as Int

        val latestBySetRow = latest.associateBy { it.setRowId }
        val exLastById = exLast.associate { it.parentId to it.lastPerformed }
        val areaLastById = areaLast.associate { it.parentId to it.lastPerformed }
        val catLastById = catLast.associate { it.parentId to it.lastPerformed }

        val setRowsByExercise = setRows.groupBy { it.exerciseId }
        val exercisesByArea = exercises.groupBy { it.areaId }
        val areasByCategory = areas.groupBy { it.categoryId }

        val categoryUis = categories.map { category ->
            val areaUis = areasByCategory[category.id].orEmpty().map { area ->
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
                }
                AreaUi(area.id, area.name, areaLastById[area.id], exerciseUis)
            }
            CategoryUi(category.id, category.name, catLastById[category.id], areaUis)
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

    /** Tap the checkmark → write a new immutable LogEntry stamped today. */
    fun confirm() {
        val state = edit ?: return
        edit = null
        viewModelScope.launch {
            dao.insertLogEntry(
                com.example.gym.data.LogEntryEntity(
                    setRowId = state.setRowId,
                    reps = state.reps,
                    weight = state.weight,
                    date = LocalDate.now(),
                ),
            )
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
        data object AddCategory : RowDialog
        data class AddArea(val categoryId: Long) : RowDialog
        data class AddExercise(val areaId: Long) : RowDialog
    }

    /** A reversible action surfaced via snackbar. */
    class UndoRequest(val message: String, val perform: suspend () -> Unit)

    var menuForSetRow by mutableStateOf<Long?>(null)
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

    fun showDialog(d: RowDialog) {
        menuForSetRow = null
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
    fun promptAddArea(categoryId: Long) { dialog = RowDialog.AddArea(categoryId) }
    fun promptAddExercise(areaId: Long) { dialog = RowDialog.AddExercise(areaId) }

    fun addCategory(name: String) {
        dialog = null
        val clean = name.trim().ifEmpty { return }
        viewModelScope.launch {
            dao.insertCategory(com.example.gym.data.CategoryEntity(name = clean))
        }
    }

    fun addArea(categoryId: Long, name: String) {
        dialog = null
        val clean = name.trim().ifEmpty { return }
        viewModelScope.launch {
            dao.insertArea(com.example.gym.data.AreaEntity(categoryId = categoryId, name = clean))
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
fun areaKey(id: Long) = "A$id"
fun exerciseKey(id: Long) = "E$id"
