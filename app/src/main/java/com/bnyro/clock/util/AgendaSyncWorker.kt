package com.bnyro.clock.util

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class AgendaSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): androidx.work.ListenableWorker.Result = when (val result = AgendaSyncer(applicationContext).sync()) {
        is AgendaSyncer.Result.Success -> if (result.failedAccountIds.isEmpty()) Result.success() else Result.retry()
        AgendaSyncer.Result.PermissionRequired,
        AgendaSyncer.Result.SourceSelectionRequired,
        AgendaSyncer.Result.OAuthUnavailable -> Result.success()
    }
}
