package smart.pantry.smartpantrykotlincompose

import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object NotificationPermissionHelper {
    private const val TAG = "NotifPermHelper"

    const val REQ_CODE_POST_NOTIFICATIONS = 1001

    // 💡 UPDATED: Use constants from UserSettings
    const val PREF_FILE_NAME = UserSettings.PREFERENCES
    private const val PREF_ASKED_NOTIFICATION = "asked_notification" // Internal key, keeping private
    const val KEY_DO_NOT_ASK_PERMISSIONS = UserSettings.KEY_DO_NOT_ASK_PERMISSIONS
    const val KEY_DO_NOT_ASK_EXACT = UserSettings.KEY_DO_NOT_ASK_EXACT

    @Volatile
    private var sRequestInProgress = false

    // --- Core Flow Logic ---

    /**
     * Start the permission flow.
     * @param onShowPrompt Lambda called to trigger the *Compose* dialog UI in the Activity.
     */
    fun startPermissionFlow(
        activity: Activity,
        forceShow: Boolean,
        onShowPrompt: (PermissionPrompt) -> Unit
    ) {
        if (sRequestInProgress) {
            Log.d(TAG, "startPermissionFlow: request already in progress - skipping preface")
            return
        }

        val prefs = activity.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)
        val doNotAsk = prefs.getBoolean(KEY_DO_NOT_ASK_PERMISSIONS, false)

        if (!forceShow && doNotAsk) {
            Log.d(TAG, "Dialog skipped due to user opt-out")
            return
        }

        if (isNotificationPermissionGranted(activity)) {
            Log.d(TAG, "Notifications already granted; proceeding to exact alarm check")
            checkExactAlarmAndPrompt(activity, forceShow, onShowPrompt)
            return
        }

        // Tell the Activity to show the Notifications Preface Dialog (Compose UI)
        onShowPrompt(PermissionPrompt.NotificationsPreface)
    }

    /**
     * Call this from your Activity.onRequestPermissionsResult(...) to continue the flow.
     */
    fun handleNotificationPermissionResult(
        activity: Activity,
        isGranted: Boolean, // Result directly from the launcher
        onShowPrompt: (PermissionPrompt) -> Unit
    ) {
        // Clear the "in progress" guard
        sRequestInProgress = false

        Log.d(TAG, "handleNotificationPermissionResult: notifications granted=$isGranted")

        if (!isGranted) {
            val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                activity, android.Manifest.permission.POST_NOTIFICATIONS
            )
            if (!shouldShowRationale) {
                // Permanently denied -> open app's notification settings
                Log.d(TAG, "Notifications permanently denied -> opening App Notification Settings")
                openAppNotificationSettings(activity)
            }
            return
        } else {
            // Granted -> immediately check exact alarms and show preface dialog if needed
            checkExactAlarmAndPrompt(activity, false, onShowPrompt)
        }
    }

    fun checkExactAlarmAndPrompt(activity: Activity, forceShow: Boolean, onShowPrompt: (PermissionPrompt) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return
        }

        val am = activity.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        if (am.canScheduleExactAlarms()) {
            return
        }

        val prefs = activity.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)
        val doNotAskExact = prefs.getBoolean(KEY_DO_NOT_ASK_EXACT, false)

        if (!forceShow && doNotAskExact) {
            return
        }

        // Tell the Activity to show the Exact Alarm Preface Dialog (Compose UI)
        onShowPrompt(PermissionPrompt.ExactAlarmPreface)
    }

    fun openExactAlarmSettings(activity: Activity) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.parse("package:${activity.packageName}"))
            activity.startActivity(intent)
        } catch (ex: Exception) {
            // Fallback to app details
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${activity.packageName}"))
            activity.startActivity(intent)
        }
    }

    // --- Utility/Check Methods ---

    fun isNotificationPermissionGranted(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                ctx,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun isExactAlarmPermissionGranted(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            am?.canScheduleExactAlarms() ?: false
        } else {
            true
        }
    }

    fun openAppNotificationSettings(activity: Activity) {
        try {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            activity.startActivity(intent)
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${activity.packageName}"))
            activity.startActivity(intent)
        }
    }

    fun checkAndScheduleOnResume(activity: Activity) {
        sRequestInProgress = false

        val notifGranted = isNotificationPermissionGranted(activity)
        val alarmAllowed = isExactAlarmPermissionGranted(activity)

        if (notifGranted && alarmAllowed) {
            try {
                // You may need to uncomment and update this line
                // AlarmScheduler.scheduleAll(activity)
                Log.d(TAG, "Notification Permissions granted!")
            } catch (ignored: Exception) {}
        }
    }
}