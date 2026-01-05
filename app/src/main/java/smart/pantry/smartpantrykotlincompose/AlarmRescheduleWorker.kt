package smart.pantry.smartpantrykotlincompose

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class AlarmRescheduleWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            Log.d("AlarmRescheduleWorker", "Starting alarm reschedule...")
            AlarmScheduler.scheduleAll(applicationContext)
            Log.d("AlarmRescheduleWorker", "Alarm reschedule complete.")
            Result.success()
        } catch (e: Exception) {
            Log.e("AlarmRescheduleWorker", "Error rescheduling alarms", e)
            Result.retry()  // Retry if it fails
        }
    }
}