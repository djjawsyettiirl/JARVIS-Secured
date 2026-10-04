package com.jarvis.secured

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class PrivateUpdateWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result = try {
        val directPublicEdition =
            BuildConfig.JARVIS_EDITION == "public_beta" &&
                BuildConfig.JARVIS_DISTRIBUTION == "direct"
        val update = if (directPublicEdition) {
            try {
                OfficialReleaseUpdater(applicationContext).downloadIfAvailable()
            } catch (_: ReleaseSourceUnavailable) {
                null
            }
        } else {
            BackgroundJarvisClient(applicationContext).downloadUpdateIfAvailable()
        }
        update?.let { PrivateUpdateInstaller.install(applicationContext, it) }
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        private const val PERIODIC_WORK = "jarvis-private-app-update"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<PrivateUpdateWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .build()
            )
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<PrivateUpdateWorker>()
                    .setConstraints(constraints)
                    .build()
            )
        }
    }
}
