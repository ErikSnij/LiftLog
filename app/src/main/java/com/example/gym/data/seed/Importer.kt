package com.example.gym.data.seed

import androidx.room.withTransaction
import com.example.gym.data.AreaEntity
import com.example.gym.data.BodyWeightEntity
import com.example.gym.data.CategoryEntity
import com.example.gym.data.ExerciseEntity
import com.example.gym.data.Flag
import com.example.gym.data.LiftLogDatabase
import com.example.gym.data.LogEntryEntity
import com.example.gym.data.MuscleGroupEntity
import com.example.gym.data.SetRowEntity
import kotlinx.serialization.json.Json
import java.time.LocalDate

object Importer {

    private val json = Json { ignoreUnknownKeys = true }

    sealed class Result {
        data class Success(val categories: Int, val entries: Int) : Result()
        data class Failure(val message: String) : Result()
    }

    /** Wipes the database and replaces it entirely with the contents of [jsonText]. */
    suspend fun import(db: LiftLogDatabase, jsonText: String): Result {
        return try {
            val root = json.decodeFromString<ExportRoot>(jsonText)
            val dao = db.dao()
            var entryCount = 0
            db.withTransaction {
                dao.deleteAllCategories()    // CASCADE removes everything underneath
                dao.deleteAllBodyWeights()
                for ((cIdx, c) in root.categories.withIndex()) {
                    val catId = dao.insertCategory(CategoryEntity(name = c.name, sortOrder = cIdx))
                    for ((mgIdx, mg) in c.muscleGroups.withIndex()) {
                        val mgId = dao.insertMuscleGroup(
                            MuscleGroupEntity(categoryId = catId, name = mg.name, sortOrder = mgIdx)
                        )
                        for ((aIdx, area) in mg.muscles.withIndex()) {
                            val areaId = dao.insertArea(
                                AreaEntity(muscleGroupId = mgId, name = area.name, sortOrder = aIdx)
                            )
                            for (ex in area.exercises) {
                                val exId = dao.insertExercise(
                                    ExerciseEntity(areaId = areaId, name = ex.name, archived = ex.archived)
                                )
                                for (sr in ex.setRows) {
                                    val srId = dao.insertSetRow(
                                        SetRowEntity(
                                            exerciseId = exId,
                                            note = sr.note,
                                            flag = runCatching { Flag.valueOf(sr.flag) }.getOrDefault(Flag.NONE),
                                        )
                                    )
                                    for (e in sr.entries) {
                                        dao.insertLogEntry(
                                            LogEntryEntity(
                                                setRowId = srId,
                                                reps = e.reps,
                                                weight = e.weight,
                                                date = LocalDate.parse(e.date),
                                            )
                                        )
                                        entryCount++
                                    }
                                }
                            }
                        }
                    }
                }
                for (bw in root.bodyWeights) {
                    dao.upsertBodyWeight(BodyWeightEntity(weight = bw.weight, date = LocalDate.parse(bw.date)))
                }
            }
            Result.Success(root.categories.size, entryCount)
        } catch (e: Exception) {
            Result.Failure(e.message ?: "Import failed")
        }
    }
}
