package com.example.gym.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

/** Direction marker shown next to a set row: NONE → no marker, UP → +, DOWN → −. */
enum class Flag { NONE, UP, DOWN }

@Entity(tableName = "category")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
)

/** Muscle group (e.g. Chest, Arms) — sits between a category (UPPER/LOWER) and a muscle (area). */
@Entity(
    tableName = "muscle_group",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("categoryId")],
)
data class MuscleGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long,
    val name: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
)

/** A muscle (e.g. Upper chest, Biceps). Historically called "area"; now nested under a muscle group. */
@Entity(
    tableName = "area",
    foreignKeys = [
        ForeignKey(
            entity = MuscleGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["muscleGroupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("muscleGroupId")],
)
data class AreaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val muscleGroupId: Long,
    val name: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
)

@Entity(
    tableName = "exercise",
    foreignKeys = [
        ForeignKey(
            entity = AreaEntity::class,
            parentColumns = ["id"],
            childColumns = ["areaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("areaId")],
)
data class ExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val areaId: Long,
    val name: String,
)

/**
 * A thing tracked over time (e.g. "Bench press working set A"). Multiple parallel set rows
 * under one exercise are normal. Owns an immutable history of [LogEntryEntity] rows.
 */
@Entity(
    tableName = "set_row",
    foreignKeys = [
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exerciseId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("exerciseId")],
)
data class SetRowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exerciseId: Long,
    val note: String? = null,
    val flag: Flag = Flag.NONE,
    val archived: Boolean = false,
)

/**
 * One immutable logged data point for a set row. reps/weight are nullable: the seed contains
 * genuine nulls (bodyweight movements have no weight; one row has unknown reps).
 */
@Entity(
    tableName = "log_entry",
    foreignKeys = [
        ForeignKey(
            entity = SetRowEntity::class,
            parentColumns = ["id"],
            childColumns = ["setRowId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("setRowId"), Index("date")],
)
data class LogEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val setRowId: Long,
    val reps: Float?,
    val weight: Float?,
    @ColumnInfo(name = "date") val date: LocalDate,
)
