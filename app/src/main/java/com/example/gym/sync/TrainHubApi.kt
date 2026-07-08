package com.example.gym.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/** One logged set, matching the local SetRow/LogEntry shape (reps/weight/note/flag). */
@Serializable
data class SetPayload(
    val reps: Float? = null,
    val weight: Float? = null,
    val note: String? = null,
    val flag: String = "NONE",
)

/** One exercise performed that day, with all its logged sets. */
@Serializable
data class ExercisePayload(
    val name: String,
    val sets: List<SetPayload>,
)

/** A single raw log_entry row, kept verbatim (with local ids) inside raw_payload for safekeeping. */
@Serializable
data class RawLogEntry(
    val id: Long,
    val setRowId: Long,
    val exerciseId: Long,
    val exerciseName: String,
    val reps: Float?,
    val weight: Float?,
    val date: String,
    val note: String?,
    val flag: String,
)

@Serializable
data class RawPayload(val entries: List<RawLogEntry>)

@Serializable
data class StrengthSessionRequest(
    val source: String = "liftlog",
    @SerialName("session_date") val sessionDate: String,
    val exercises: List<ExercisePayload>,
    @SerialName("raw_payload") val rawPayload: RawPayload,
)

/** Echoed-back record from the server; `exercises` is JSONB there so kept loose here too. */
@Serializable
data class StrengthSessionResponse(
    val id: Long,
    val source: String,
    @SerialName("session_date") val sessionDate: String,
    val exercises: JsonElement,
    @SerialName("created_at") val createdAt: String,
)

/** Body shape confirmed against the backend's `POST /body-metrics` (no auth required, upserts by date). */
@Serializable
data class BodyMetricsRequest(
    val date: String,
    @SerialName("weight_kg") val weightKg: Float,
    @SerialName("body_fat_pct") val bodyFatPct: Float? = null,
    val source: String = "liftlog",
)

interface TrainHubApi {
    /** Auth header + host are applied per-request by TrainHubClient's interceptor. */
    @POST("strength-sessions")
    suspend fun postStrengthSession(@Body body: StrengthSessionRequest): Response<StrengthSessionResponse>

    @POST("body-metrics")
    suspend fun postBodyMetrics(@Body body: BodyMetricsRequest): Response<Unit>
}
