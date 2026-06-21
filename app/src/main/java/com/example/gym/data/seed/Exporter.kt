package com.example.gym.data.seed

import com.example.gym.data.LiftLogDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

@Serializable
data class ExportBodyWeight(val weight: Float, val date: String)

@Serializable
data class ExportRoot(
    val exportedAt: String,
    val categories: List<ExportCategory>,
    val bodyWeights: List<ExportBodyWeight> = emptyList(),
)

@Serializable
data class ExportCategory(val name: String, val muscleGroups: List<ExportMuscleGroup>)

@Serializable
data class ExportMuscleGroup(val name: String, val muscles: List<ExportArea>)

@Serializable
data class ExportArea(val name: String, val exercises: List<ExportExercise>)

@Serializable
data class ExportExercise(val name: String, val archived: Boolean = false, val setRows: List<ExportSetRow>)

@Serializable
data class ExportSetRow(
    val note: String?,
    val flag: String,
    val entries: List<ExportEntry>,
)

@Serializable
data class ExportEntry(val reps: Float?, val weight: Float?, val date: String)

/** Serialises the entire database (full history included) to pretty JSON. */
object Exporter {
    private val json = Json { prettyPrint = true }

    suspend fun export(db: LiftLogDatabase): String {
        val dao = db.dao()
        val categories = dao.allCategories()
        val muscleGroups = dao.allMuscleGroups().groupBy { it.categoryId }
        val areas = dao.allAreas().groupBy { it.muscleGroupId }
        val exercises = dao.allExercises().groupBy { it.areaId }
        val setRows = dao.allSetRows().groupBy { it.exerciseId }
        val entries = dao.allLogEntries().groupBy { it.setRowId }
        val bodyWeights = dao.allBodyWeights()

        val root = ExportRoot(
            exportedAt = LocalDate.now().toString(),
            bodyWeights = bodyWeights.map { ExportBodyWeight(it.weight, it.date.toString()) },
            categories = categories.map { c ->
                ExportCategory(
                    name = c.name,
                    muscleGroups = muscleGroups[c.id].orEmpty().map { mg ->
                        ExportMuscleGroup(
                            name = mg.name,
                            muscles = areas[mg.id].orEmpty().map { a ->
                                ExportArea(
                                    name = a.name,
                                    exercises = exercises[a.id].orEmpty().map { e ->
                                        ExportExercise(
                                            name = e.name,
                                            archived = e.archived,
                                            setRows = setRows[e.id].orEmpty().map { sr ->
                                                ExportSetRow(
                                                    note = sr.note,
                                                    flag = sr.flag.name,
                                                    entries = entries[sr.id].orEmpty()
                                                        .sortedBy { it.date }
                                                        .map { en ->
                                                            ExportEntry(en.reps, en.weight, en.date.toString())
                                                        },
                                                )
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
        return json.encodeToString(root)
    }
}
