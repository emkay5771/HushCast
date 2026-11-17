package app.hushcast.automute.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.hushcast.automute.dsp.MuteActionMode
import app.hushcast.automute.pipeline.AutoMuteAnalyzer
import app.hushcast.automute.storage.AutoMuteDatabase

/**
 * Re-evaluates mute rules across all cached episode word indices in the background
 * using ID-only queries (E15).
 */
class RuleReevaluationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prePadMs = inputData.getLong(KEY_PRE_PAD_MS, 80L)
        val postPadMs = inputData.getLong(KEY_POST_PAD_MS, 100L)
        val defaultActionMode = runCatching {
            MuteActionMode.valueOf(inputData.getString(KEY_ACTION_MODE) ?: MuteActionMode.HARD_SILENCE.name)
        }.getOrDefault(MuteActionMode.HARD_SILENCE)

        val db = AutoMuteDatabase.getInstance(applicationContext)
        val analyzer = AutoMuteAnalyzer(applicationContext, db)
        val refs = db.dao().getAllWordIndexRefs()
        for (ref in refs) {
            analyzer.reevaluateMuteRules(
                fileSha256 = ref.fileSha256,
                episodeId = ref.episodeId,
                prePadMs = prePadMs,
                postPadMs = postPadMs,
                defaultActionMode = defaultActionMode
            )
        }
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "hushcast_rule_reevaluation"
        const val KEY_PRE_PAD_MS = "pre_pad_ms"
        const val KEY_POST_PAD_MS = "post_pad_ms"
        const val KEY_ACTION_MODE = "action_mode"

        fun enqueue(
            context: Context,
            prePadMs: Long = 80L,
            postPadMs: Long = 100L,
            defaultActionMode: MuteActionMode = MuteActionMode.HARD_SILENCE
        ) {
            val request = OneTimeWorkRequestBuilder<RuleReevaluationWorker>()
                .setInputData(
                    workDataOf(
                        KEY_PRE_PAD_MS to prePadMs,
                        KEY_POST_PAD_MS to postPadMs,
                        KEY_ACTION_MODE to defaultActionMode.name
                    )
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
