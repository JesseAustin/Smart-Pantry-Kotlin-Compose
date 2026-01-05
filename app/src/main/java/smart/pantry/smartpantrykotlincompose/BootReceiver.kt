package smart.pantry.smartpantrykotlincompose

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    private val TAG = "BootReceiver"

    // AFTER: Move to background thread
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            Log.d(TAG, "Boot completed event received. Rescheduling all alarms...")

            // Use a coroutine on IO dispatcher
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            scope.launch {
                try {
                    AlarmScheduler.scheduleAll(context)
                    Log.d(TAG, "Alarm rescheduling complete.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error rescheduling alarms", e)
                }
            }
        }
    }
}