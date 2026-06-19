package com.example.gym.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LiftLogDao {

    // ---- Tree reads ------------------------------------------------------

    @Query("SELECT * FROM category ORDER BY id")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM area ORDER BY id")
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
        SELECT c.id AS parentId, MAX(le.date) AS lastPerformed
        FROM category c
        LEFT JOIN area a ON a.categoryId = c.id
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

    @Query("DELETE FROM set_row WHERE id = :setRowId")
    suspend fun deleteSetRow(setRowId: Long)

    @Query("SELECT exerciseId FROM set_row WHERE id = :setRowId")
    suspend fun exerciseIdOf(setRowId: Long): Long?

    // One-shot reads used to capture state for undo / export.
    @Query("SELECT * FROM set_row WHERE id = :setRowId")
    suspend fun getSetRow(setRowId: Long): SetRowEntity?

    @Query("SELECT * FROM log_entry WHERE setRowId = :setRowId ORDER BY date, id")
    suspend fun getEntriesFor(setRowId: Long): List<LogEntryEntity>

    @Query("SELECT * FROM category ORDER BY id")
    suspend fun allCategories(): List<CategoryEntity>

    @Query("SELECT * FROM area ORDER BY id")
    suspend fun allAreas(): List<AreaEntity>

    @Query("SELECT * FROM exercise ORDER BY id")
    suspend fun allExercises(): List<ExerciseEntity>

    @Query("SELECT * FROM set_row ORDER BY id")
    suspend fun allSetRows(): List<SetRowEntity>

    @Query("SELECT * FROM log_entry ORDER BY id")
    suspend fun allLogEntries(): List<LogEntryEntity>
}
