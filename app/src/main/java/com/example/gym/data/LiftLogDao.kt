package com.example.gym.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LiftLogDao {

    // ---- Tree reads ------------------------------------------------------

    @Query("SELECT * FROM category ORDER BY sort_order ASC, id ASC")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM muscle_group ORDER BY sort_order ASC, id ASC")
    fun observeMuscleGroups(): Flow<List<MuscleGroupEntity>>

    @Query("SELECT * FROM area ORDER BY sort_order ASC, id ASC")
    fun observeAreas(): Flow<List<AreaEntity>>

    @Query("SELECT * FROM exercise ORDER BY id")
    fun observeExercises(): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM set_row WHERE archived = 0 ORDER BY id")
    fun observeActiveSetRows(): Flow<List<SetRowEntity>>

    @Query("SELECT * FROM set_row ORDER BY id")
    fun observeAllSetRows(): Flow<List<SetRowEntity>>

    // ---- Current value & history ----------------------------------------

    /** The newest log entry per set row (ties broken by id). The row's displayed value. */
    @Query(
        """
        SELECT le.setRowId AS setRowId, le.reps AS reps, le.weight AS weight, le.date AS date
        FROM log_entry le
        WHERE le.id = (
            SELECT le2.id FROM log_entry le2
            WHERE le2.setRowId = le.setRowId
            ORDER BY le2.date DESC, le2.id DESC
            LIMIT 1
        )
        """
    )
    fun observeLatestEntryPerSetRow(): Flow<List<LatestEntry>>

    /** Full ordered history for one set row (newest first). */
    @Query("SELECT * FROM log_entry WHERE setRowId = :setRowId ORDER BY date DESC, id DESC")
    fun observeSetRowHistory(setRowId: Long): Flow<List<LogEntryEntity>>

    // ---- Derived last-performed dates (non-archived rows only) -----------

    @Query(
        """
        SELECT e.id AS parentId, MAX(le.date) AS lastPerformed
        FROM exercise e
        LEFT JOIN set_row sr ON sr.exerciseId = e.id AND sr.archived = 0
        LEFT JOIN log_entry le ON le.setRowId = sr.id
        GROUP BY e.id
        """
    )
    fun observeExerciseLastPerformed(): Flow<List<LastPerformed>>

    @Query(
        """
        SELECT a.id AS parentId, MAX(le.date) AS lastPerformed
        FROM area a
        LEFT JOIN exercise e ON e.areaId = a.id
        LEFT JOIN set_row sr ON sr.exerciseId = e.id AND sr.archived = 0
        LEFT JOIN log_entry le ON le.setRowId = sr.id
        GROUP BY a.id
        """
    )
    fun observeAreaLastPerformed(): Flow<List<LastPerformed>>

    @Query(
        """
        SELECT mg.id AS parentId, MAX(le.date) AS lastPerformed
        FROM muscle_group mg
        LEFT JOIN area a ON a.muscleGroupId = mg.id
        LEFT JOIN exercise e ON e.areaId = a.id
        LEFT JOIN set_row sr ON sr.exerciseId = e.id AND sr.archived = 0
        LEFT JOIN log_entry le ON le.setRowId = sr.id
        GROUP BY mg.id
        """
    )
    fun observeMuscleGroupLastPerformed(): Flow<List<LastPerformed>>

    @Query(
        """
        SELECT c.id AS parentId, MAX(le.date) AS lastPerformed
        FROM category c
        LEFT JOIN muscle_group mg ON mg.categoryId = c.id
        LEFT JOIN area a ON a.muscleGroupId = mg.id
        LEFT JOIN exercise e ON e.areaId = a.id
        LEFT JOIN set_row sr ON sr.exerciseId = e.id AND sr.archived = 0
        LEFT JOIN log_entry le ON le.setRowId = sr.id
        GROUP BY c.id
        """
    )
    fun observeCategoryLastPerformed(): Flow<List<LastPerformed>>

    // ---- Counts ----------------------------------------------------------

    @Query("SELECT COUNT(*) FROM category")
    suspend fun categoryCount(): Int

    @Query("SELECT COUNT(*) FROM log_entry")
    fun observeLogEntryCount(): Flow<Int>

    // ---- Inserts (used by the seeder) ------------------------------------

    @Insert
    suspend fun insertCategory(category: CategoryEntity): Long

    @Insert
    suspend fun insertMuscleGroup(muscleGroup: MuscleGroupEntity): Long

    @Insert
    suspend fun insertArea(area: AreaEntity): Long

    @Insert
    suspend fun insertExercise(exercise: ExerciseEntity): Long

    @Insert
    suspend fun insertSetRow(setRow: SetRowEntity): Long

    @Insert
    suspend fun insertLogEntry(entry: LogEntryEntity): Long

    // ---- Fast-write-loop mutations ---------------------------------------

    @Query("UPDATE set_row SET flag = :flag WHERE id = :setRowId")
    suspend fun updateFlag(setRowId: Long, flag: Flag)

    @Query("UPDATE category SET sort_order = :order WHERE id = :id")
    suspend fun updateCategorySortOrder(id: Long, order: Int)

    @Query("UPDATE muscle_group SET sort_order = :order WHERE id = :id")
    suspend fun updateMuscleGroupSortOrder(id: Long, order: Int)

    @Query("UPDATE area SET sort_order = :order WHERE id = :id")
    suspend fun updateAreaSortOrder(id: Long, order: Int)

    // ---- History screen (Phase 4) ----------------------------------------

    @Query("SELECT * FROM set_row WHERE id = :setRowId")
    fun observeSetRow(setRowId: Long): Flow<SetRowEntity?>

    @Query(
        "SELECT e.name FROM set_row sr JOIN exercise e ON e.id = sr.exerciseId WHERE sr.id = :setRowId"
    )
    fun observeExerciseNameForSetRow(setRowId: Long): Flow<String?>

    @Update
    suspend fun updateLogEntry(entry: LogEntryEntity)

    @Query("DELETE FROM log_entry WHERE id = :entryId")
    suspend fun deleteLogEntry(entryId: Long)

    // ---- Long-press actions + export (Phase 5) ---------------------------

    @Query("UPDATE set_row SET note = :note WHERE id = :setRowId")
    suspend fun updateNote(setRowId: Long, note: String?)

    @Query("UPDATE set_row SET archived = :archived WHERE id = :setRowId")
    suspend fun setArchived(setRowId: Long, archived: Boolean)

    @Query("UPDATE exercise SET name = :name WHERE id = :exerciseId")
    suspend fun renameExercise(exerciseId: Long, name: String)

    @Query("UPDATE area SET name = :name WHERE id = :areaId")
    suspend fun renameArea(areaId: Long, name: String)

    @Query("UPDATE muscle_group SET name = :name WHERE id = :id")
    suspend fun renameMuscleGroup(id: Long, name: String)

    @Query("UPDATE category SET name = :name WHERE id = :categoryId")
    suspend fun renameCategory(categoryId: Long, name: String)

    @Query("DELETE FROM set_row WHERE id = :setRowId")
    suspend fun deleteSetRow(setRowId: Long)

    // ---- Delete exercise / area / category (cascade removes children) ----
    // Paired one-shot reads capture the subtree first so the action is undoable.

    @Query("SELECT * FROM exercise WHERE id = :id")
    suspend fun getExercise(id: Long): ExerciseEntity?

    @Query("SELECT * FROM set_row WHERE exerciseId = :exerciseId ORDER BY id")
    suspend fun setRowsOf(exerciseId: Long): List<SetRowEntity>

    @Query("DELETE FROM exercise WHERE id = :id")
    suspend fun deleteExercise(id: Long)

    @Query("SELECT * FROM area WHERE id = :id")
    suspend fun getArea(id: Long): AreaEntity?

    @Query("SELECT * FROM exercise WHERE areaId = :areaId ORDER BY id")
    suspend fun exercisesOf(areaId: Long): List<ExerciseEntity>

    @Query("DELETE FROM area WHERE id = :id")
    suspend fun deleteArea(id: Long)

    @Query("SELECT * FROM muscle_group WHERE id = :id")
    suspend fun getMuscleGroup(id: Long): MuscleGroupEntity?

    @Query("SELECT * FROM area WHERE muscleGroupId = :muscleGroupId ORDER BY sort_order ASC, id ASC")
    suspend fun areasOf(muscleGroupId: Long): List<AreaEntity>

    @Query("DELETE FROM muscle_group WHERE id = :id")
    suspend fun deleteMuscleGroup(id: Long)

    @Query("SELECT * FROM category WHERE id = :id")
    suspend fun getCategory(id: Long): CategoryEntity?

    @Query("SELECT * FROM muscle_group WHERE categoryId = :categoryId ORDER BY sort_order ASC, id ASC")
    suspend fun muscleGroupsOf(categoryId: Long): List<MuscleGroupEntity>

    @Query("DELETE FROM category WHERE id = :id")
    suspend fun deleteCategory(id: Long)

    @Query("SELECT exerciseId FROM set_row WHERE id = :setRowId")
    suspend fun exerciseIdOf(setRowId: Long): Long?

    // One-shot reads used to capture state for undo / export.
    @Query("SELECT * FROM set_row WHERE id = :setRowId")
    suspend fun getSetRow(setRowId: Long): SetRowEntity?

    @Query("SELECT * FROM log_entry WHERE setRowId = :setRowId ORDER BY date, id")
    suspend fun getEntriesFor(setRowId: Long): List<LogEntryEntity>

    @Query("SELECT * FROM category ORDER BY id")
    suspend fun allCategories(): List<CategoryEntity>

    @Query("SELECT * FROM muscle_group ORDER BY id")
    suspend fun allMuscleGroups(): List<MuscleGroupEntity>

    @Query("SELECT * FROM area ORDER BY id")
    suspend fun allAreas(): List<AreaEntity>

    @Query("SELECT * FROM exercise ORDER BY id")
    suspend fun allExercises(): List<ExerciseEntity>

    @Query("SELECT * FROM set_row ORDER BY id")
    suspend fun allSetRows(): List<SetRowEntity>

    @Query("SELECT * FROM log_entry ORDER BY id")
    suspend fun allLogEntries(): List<LogEntryEntity>
}
