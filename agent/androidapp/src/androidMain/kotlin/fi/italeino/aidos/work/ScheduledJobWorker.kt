package fi.italeino.aidos.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.aidos.androidapp.scheduling.InMemoryScheduledJobManager
import dev.aidos.androidapp.scheduling.JobScheduler
import dev.aidos.androidapp.scheduling.RuntimeClientWorkDispatcher
import fi.italeino.aidos.AndroidRuntimeClientFactory
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that periodically runs due scheduled jobs (RFC-0044 §Background work classes).
 *
 * Respects Android's 15-minute WorkManager periodic floor: timers on MOBILE are best-effort
 * `EVENTUAL` hints, never exact alarms.
 */
class ScheduledJobWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            val client = AndroidRuntimeClientFactory.createRuntimeClient(applicationContext)
            val jobManager = InMemoryScheduledJobManager()
            val dispatcher = RuntimeClientWorkDispatcher(client, jobManager)
            JobScheduler(jobManager, dispatcher).runScheduleRound()
            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "aidos-scheduled-jobs"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()
            val request = PeriodicWorkRequestBuilder<ScheduledJobWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
