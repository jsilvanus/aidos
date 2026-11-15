package fi.italeino.aidos.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Background deterministic prep worker (RFC-0050).
 *
 * Strictly performs deterministic, non-LLM housekeeping (pruning stale cache/temp files in
 * app-private storage). Agent runs and inference remain bound to [fi.italeino.aidos.service.AidosService].
 */
class DeterministicPrepWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            val cutoffMs = System.currentTimeMillis() - STALE_CACHE_AGE_MS
            pruneOldFiles(applicationContext.cacheDir, cutoffMs)
            val tmpDir = File(applicationContext.filesDir, "tmp")
            if (tmpDir.isDirectory) {
                pruneOldFiles(tmpDir, cutoffMs)
            }
            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }

    private fun pruneOldFiles(dir: File, cutoffMs: Long) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                pruneOldFiles(child, cutoffMs)
                if (child.listFiles()?.isEmpty() == true) {
                    child.delete()
                }
            } else if (child.lastModified() in 1 until cutoffMs) {
                child.delete()
            }
        }
    }

    companion object {
        private const val WORK_NAME = "aidos-deterministic-prep"
        private const val STALE_CACHE_AGE_MS = 7L * 24L * 60L * 60L * 1000L

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()
            val request = PeriodicWorkRequestBuilder<DeterministicPrepWorker>(12, TimeUnit.HOURS)
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
