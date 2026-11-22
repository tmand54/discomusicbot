package app.tcgportfolio.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.tcgportfolio.AppGraph
import app.tcgportfolio.data.Settings
import java.util.concurrent.TimeUnit

/** Daily background price refresh for the collection, wantlist and alerts. */
class PriceRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = AppGraph.instance
        return try {
            val fired = graph.collection.refreshAll()
            Notifications.showAlerts(applicationContext, fired)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val NAME = "price_refresh"

        fun schedule(context: Context, settings: Settings) {
            val wm = WorkManager.getInstance(context)
            if (!settings.backgroundRefresh.value) {
                wm.cancelUniqueWork(NAME); return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (settings.wifiOnly.value) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<PriceRefreshWorker>(24, TimeUnit.HOURS, 4, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInitialDelay(2, TimeUnit.HOURS)
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
