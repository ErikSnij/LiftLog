package com.example.gym.data.seed

import kotlinx.serialization.Serializable

@Serializable
data class SeedRoot(
    val referenceYear: Int? = null,
    val categories: List<SeedCategory> = emptyList(),
)

@Serializable
data class SeedCategory(
    val name: String,
    val areas: List<SeedArea> = emptyList(),
)

@Serializable
data class SeedArea(
    val name: String,
    val exercises: List<SeedExercise> = emptyList(),
)

@Serializable
data class SeedExercise(
    val name: String,
    val setRows: List<SeedSetRow> = emptyList(),
)

@Serializable
data class SeedSetRow(
    val note: String? = null,
    val flag: String = "NONE",
    val archived: Boolean = false,
    val seedEntry: SeedEntry? = null,
)

@Serializable
data class SeedEntry(
    val reps: Float? = null,
    val weight: Float? = null,
    val date: String,
)
