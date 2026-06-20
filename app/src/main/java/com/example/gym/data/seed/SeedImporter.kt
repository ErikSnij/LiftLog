package com.example.gym.data.seed

import android.content.Context
import androidx.room.withTransaction
import com.example.gym.data.AREA_TO_MUSCLE_GROUP
import com.example.gym.data.AreaEntity
import com.example.gym.data.CategoryEntity
import com.example.gym.data.ExerciseEntity
import com.example.gym.data.Flag
import com.example.gym.data.LiftLogDatabase
import com.example.gym.data.LogEntryEntity
import com.example.gym.data.MuscleGroupEntity
import com.example.gym.data.SetRowEntity
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * Seeds the database from assets/liftlog_seed.json on first launch only.
 *
 * The seed file is flat (Category → Area → Exercise → SetRow); there is no Movement tier, so the
 * schema drops it. Per the brief, each set row's flag is forced to NONE and archived to false at
 * seed time, and a non-null seedEntry becomes the set row's single first LogEntry.
 */
object SeedImporter {

    private const val ASSET = "liftlog_seed.json"

    private val json = Json { ignoreUnknownKeys = true }

    private val areaToGroup = AREA_TO_MUSCLE_GROUP.toMap()

    suspend fun seedIfEmpty(context: Context, db: LiftLogDatabase) {
        val dao = db.dao()
        if (dao.categoryCount() > 0) return

        val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        val root = json.decodeFromString<SeedRoot>(text)

        db.withTransaction {
            for (category in root.categories) {
                val categoryId = dao.insertCategory(CategoryEntity(name = category.name))
                // Insert the muscle-group level on demand, derived from each muscle's name.
                val groupIds = HashMap<String, Long>()
                for (area in category.areas) {
                    val groupName = areaToGroup[area.name] ?: "Other"
                    val groupId = groupIds.getOrPut(groupName) {
                        dao.insertMuscleGroup(MuscleGroupEntity(categoryId = categoryId, name = groupName))
                    }
                    val areaId = dao.insertArea(AreaEntity(muscleGroupId = groupId, name = area.name))
                    for (exercise in area.exercises) {
                        val exerciseId =
                            dao.insertExercise(ExerciseEntity(areaId = areaId, name = exercise.name))
                        for (setRow in exercise.setRows) {
                            val setRowId = dao.insertSetRow(
                                SetRowEntity(
                                    exerciseId = exerciseId,
                                    note = setRow.note,
                                    flag = Flag.NONE,
                                ),
                            )
                            val entry = setRow.seedEntry
                            if (entry != null) {
                                dao.insertLogEntry(
                                    LogEntryEntity(
                                        setRowId = setRowId,
                                        reps = entry.reps,
                                        weight = entry.weight,
                                        date = LocalDate.parse(entry.date),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
