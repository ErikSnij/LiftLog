package com.example.gym.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.gym.LiftLogApp
import com.example.gym.data.LiftLogDao
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Periodically delivers queued (unsynced) days to TrainHub. The PC running TrainHub may be
 * asleep or unreachable over Tailscale, so failures are expected and not fatal: each date stays
 * queued (synced=false) until a 201 actually lands, and the worker itself asks WorkManager for a
 * backoff-scheduled retry whenever a transient (network or 5xx) failure occurs, on top of the
 * normal periodic cadence.
 */
class TrainHubSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as LiftLogApp
        val config = SyncSettingsStore.current(applicationContext)
        TrainHubClient.updateConfig(config)
        if (config == null) return Result.success() // not configured yet — nothing to do until the user sets it up

        val dao = app.database.dao()
        val pendingSessions = dao.pendingSyncs()
        val pendingBodyMetrics = dao.pendingBodyMetricsSyncs()
        if (pendingSessions.isEmpty() && pendingBodyMetrics.isEmpty()) return Result.success()

        var transientFailure = false
        for (queued in pendingSessions) {
            transientFailure = transientFailure or deliverOne(dao, queued.date)
        }
        for (queued in pendingBodyMetrics) {
            transientFailure = transientFailure or deliverBodyMetricsOne(dao, queued.date)
        }
        return if (transientFailure) Result.retry() else Result.success()
    }

    /** Delivers one date's session. Returns true if the failure was transient (worth a fast retry). */
    private suspend fun deliverOne(dao: LiftLogDao, date: LocalDate): Boolean {
        val entries = dao.entriesForDate(date)
        if (entries.isEmpty()) {
            // Everything for this date was deleted since it was queued — nothing left to report.
            dao.markSynced(date)
            return false
        }

        val exercises = entries.groupBy { it.exerciseId to it.exerciseName }.map { (key, rows) ->
            ExercisePayload(
                name = key.second,
                sets = rows.map { row ->
                    SetPayload(reps = row.reps, weight = row.weight, note = row.note, flag = row.flag.name)
                },
            )
        }
        val rawEntries = entries.map { row ->
            RawLogEntry(
                id = row.id,
                setRowId = row.setRowId,
                exerciseId = row.exerciseId,
                exerciseName = row.exerciseName,
                reps = row.reps,
                weight = row.weight,
                date = row.date.toString(),
                note = row.note,
                flag = row.flag.name,
            )
        }
        val body = StrengthSessionRequest(
            sessionDate = date.toString(),
            exercises = exercises,
            rawPayload = RawPayload(rawEntries),
        )

        return try {
            val response = TrainHubClient.api.postStrengthSession(body)
            if (response.code() == 201) {
                dao.markSynced(date)
                false
            } else {
                dao.recordSyncFailure(date, System.currentTimeMillis(), "HTTP ${response.code()}")
                // 5xx is worth a fast retry; a persistent 4xx (bad request/auth) won't fix itself
                // by hammering the server, so just leave it queued for the next normal period.
                response.code() in 500..599
            }
        } catch (e: IOException) {
            dao.recordSyncFailure(date, System.currentTimeMillis(), e.message)
            true
        }
    }

    /** Delivers one date's body weight. Returns true if the failure was transient (worth a fast retry). */
    private suspend fun deliverBodyMetricsOne(dao: LiftLogDao, date: LocalDate): Boolean {
        val entry = dao.getBodyWeightForDate(date)
        if (entry == null) {
            // Deleted since it was queued — nothing left to report.
            dao.markBodyMetricsSynced(date)
            return false
        }

        val body = BodyMetricsRequest(date = date.toString(), weightKg = entry.weight)
        return try {
            val response = TrainHubClient.api.postBodyMetrics(body)
            if (response.isSuccessful) {
                dao.markBodyMetricsSynced(date)
                false
            } else {
                dao.recordBodyMetricsSyncFailure(date, System.currentTimeMillis(), "HTTP ${response.code()}")
                response.code() in 500..599
            }
        } catch (e: IOException) {
            dao.recordBodyMetricsSyncFailure(date, System.currentTimeMillis(), e.message)
            true
        }
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "trainhub-sync-periodic"
        private const val ONE_TIME_WORK_NAME = "trainhub-sync-now"

        private fun networkConstraints() =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Registers the periodic sync job (idempotent — safe to call on every app start). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TrainHubSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Requests an immediate best-effort attempt (e.g. right after saving a workout). */
        fun requestImmediateSync(context: Context) {
            val request = OneTimeWorkRequestBuilder<TrainHubSyncWorker>()
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
