package io.github.prithirajn.readync.ai

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.prithirajn.readync.MainActivity
import io.github.prithirajn.readync.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * A model being downloaded, apart from any screen: it carries on while you use the rest of the app
 * or leave it, with its progress in a notification (and on Services › On-device AI). A download
 * that's cut off (no network, the phone restarting) carries on where it stopped.
 */
object ModelDownload {
    /** Where a download is: the model, how far (0–1, null while starting), and how it ended. */
    data class State(val name: String, val progress: Float? = null, val done: Boolean = false, val error: String? = null)

    val state = MutableStateFlow<State?>(null)

    private const val WORK = "model-download"
    internal const val CHANNEL = "model-download"
    internal const val NOTE = 4100

    fun start(context: Context, choice: LocalModel.Choice) {
        if (state.value?.let { !it.done && it.error == null } == true) return
        state.value = State(choice.name)
        val req = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf("name" to choice.name))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, req)
    }

    /** Stops the download (what's come so far is kept, to carry on if it's started again). */
    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        state.value = null
    }

    /** Said, and cleared: for the screen once it has shown how it ended. */
    fun clear() { state.value = null }

    internal fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    internal fun channel(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(NotificationChannel(CHANNEL, "AI model download", NotificationManager.IMPORTANCE_LOW))
    }

    internal fun openAi(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 74,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Downloads one model (see [ModelDownload]), then loads it once to check it runs on this phone. */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val name get() = inputData.getString("name") ?: "the model"

    private fun progressNote(p: Float?): ForegroundInfo {
        ModelDownload.channel(applicationContext)
        val n = NotificationCompat.Builder(applicationContext, ModelDownload.CHANNEL)
            .setSmallIcon(R.drawable.lucide_download)
            .setContentTitle("Downloading $name")
            .setContentText(if (p == null) "Starting…" else "${(p * 100).toInt()}% · you can keep using your phone")
            .setProgress(100, ((p ?: 0f) * 100).toInt(), p == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(ModelDownload.openAi(applicationContext))
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(ModelDownload.NOTE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(ModelDownload.NOTE, n)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = progressNote(null)

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val choice = LocalModel.catalog.firstOrNull { it.name == name } ?: return Result.failure()
        ModelDownload.state.value = ModelDownload.State(choice.name, 0f)
        runCatching { setForeground(progressNote(null)) }
        var last = -1
        var netDrop = false
        val error = withContext(Dispatchers.IO) {
            // The model already in use stays if the new one can't be fetched or won't run.
            val before = LocalModel.file(ctx)?.path
            runCatching {
                LocalModel.download(ctx, choice, cancelled = { isStopped }) { p ->
                    val pct = (p * 100).toInt()
                    if (pct != last) {
                        last = pct
                        ModelDownload.state.value = ModelDownload.State(choice.name, p)
                        runCatching { setForegroundAsync(progressNote(p)) }
                    }
                }
                ModelDownload.state.value = ModelDownload.State(choice.name, 1f)
                LocalModel.get(ctx) ?: throw IllegalStateException("The model didn't load")
                Unit
            }.exceptionOrNull()?.let { e ->
                // The network went mid-download: what came is kept, and it carries on when it's back.
                if (e is java.io.IOException && LocalModel.file(ctx)?.path == before) { netDrop = true; return@let e.message ?: "The network dropped" }
                val now = LocalModel.file(ctx)?.path
                if (now != null && now != before) LocalModel.remove(ctx)
                e.message ?: "Couldn't download that model"
            }
        }
        if (isStopped) return Result.success()
        if (netDrop && runAttemptCount < 8) {
            ModelDownload.state.value = ModelDownload.State(choice.name, if (last >= 0) last / 100f else null)
            return Result.retry()
        }
        ModelDownload.state.value = ModelDownload.State(choice.name, 1f, done = error == null, error = error)
        if (ModelDownload.allowed(ctx)) {
            ModelDownload.channel(ctx)
            NotificationManagerCompat.from(ctx).notify(
                ModelDownload.NOTE + 1,
                NotificationCompat.Builder(ctx, ModelDownload.CHANNEL)
                    .setSmallIcon(R.drawable.lucide_sparkles)
                    .setContentTitle(if (error == null) "On-device AI is ready" else "Couldn't add $name")
                    .setContentText(error ?: "$name is downloaded and runs on this phone")
                    .setContentIntent(ModelDownload.openAi(ctx))
                    .setAutoCancel(true)
                    .build(),
            )
        }
        return Result.success()
    }
}
