package smart.pantry.smartpantrykotlincompose

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.core.app.AlarmManagerCompat
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

object AlarmScheduler {
    private const val TAG = "AlarmScheduler"
    private const val ALARM_ACTION = "smart.pantry.ALARM_EXPIRATION"

    // Notification intervals in days (from expiration date going backwards)
    private val NOTIFICATION_INTERVALS = listOf(
        1,      // 1 day out
        3,      // 3 days out
        7,      // 1 week out
        14,     // 2 weeks out
        30,     // 1 month out
        60,     // 2 months out
        90,     // 3 months out
        120,    // 4 months out
        150,    // 5 months out
        180,    // 6 months out
        365,    // 1 year out
        730,    // 2 years out
        1095,   // 3 years out
        1460,   // 4 years out
        1825,   // 5 years out
        2190,   // 6 years out
        2555,   // 7 years out
        2920,   // 8 years out
        3285,   // 9 years out
        3650,   // 10 years out
        // Years continue: 1460 (4 years), 1825 (5 years), etc.
    ).sorted()

    fun scheduleAll(context: Context) {
        Log.d(TAG, "Starting scheduleAll...")
        val prefs = context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
        val allKeys = prefs.getStringSet("all_alarm_keys", emptySet()) ?: emptySet()

        // Get all saved list files
        val fileRoot = context.filesDir.resolve("Smart Inventory").resolve("All Lists")
        if (!fileRoot.exists()) {
            Log.d(TAG, "No inventory folder found. Cancelling all existing alarms.")
            cancelAllAlarms(context)
            return
        }

        val listFiles = fileRoot.listFiles { file ->
            file.isFile && file.name.endsWith(".json")
        } ?: emptyArray()

        val currentListNames = listFiles.map { it.name }.toSet()

        // Cancel alarms for lists that no longer exist
        val keysToCancel = allKeys.filter { !currentListNames.contains(it) }.toSet()
        if (keysToCancel.isNotEmpty()) {
            Log.d(TAG, "Cancelling alarms for deleted lists: $keysToCancel")
            keysToCancel.forEach { listName ->
                cancelAlarmsForList(context, prefs, listName)
            }
        }

        // Schedule alarms for existing lists
        listFiles.forEach { listFile ->
            val listName = listFile.name
            scheduleAlarmsForList(context, listFile, listName)
        }
    }

    fun scheduleAlarmsForList(context: Context, listFile: File, listName: String) {
        Log.d(TAG, "Scheduling alarms for '$listName'")
        val prefs = context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
        val spKey = "alarms_$listName"
        val oldAlarms = prefs.getStringSet(spKey, emptySet()) ?: emptySet()
        val currentAlarms = mutableSetOf<String>()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Check permissions
        val notificationGranted = NotificationPermissionHelper.isNotificationPermissionGranted(context)
        val exactAlarmGranted = NotificationPermissionHelper.isExactAlarmPermissionGranted(context)

        if (!notificationGranted || !exactAlarmGranted) {
            Log.w(TAG, "Skipping alarm scheduling: permissions not granted")
            return
        }

        // TEST BLOCK
        // TEST BLOCK
        // TEST: Send test broadcast immediately to verify notifications work

        /**
        val testIntent = Intent(context, NotificationReceiver::class.java).apply {
            action = ALARM_ACTION
            data = Uri.parse("custom://test123?listName=$listName")
            putExtra("listName", listName)
            putExtra("itemData", MyApplication.jsonSerializer.encodeToString(
                InventoryItem.serializer(),
                InventoryItem(
                    id = 999,
                    name = "TEST ITEM",
                    code = "",
                    expDate = LocalDate.now().plusDays(500),
                    quantity = 1,
                    daysNotice = 400,
                    // Make sure expSoonDate is also in the future, not just expDate
                )
            ))
        }
        context.sendBroadcast(testIntent)
        Log.d(TAG, "TEST: Sent test notification broadcast")
        **/

        // TEST BLOCK END
        // TEST BLOCK END


        // Read the InventoryList (not just items array)
        val inventoryList = try {
            val jsonString = listFile.readText()
            MyApplication.jsonSerializer.decodeFromString(InventoryList.serializer(), jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading inventory file: ${listFile.absolutePath}", e)
            return
        }

        val items = inventoryList.items
        val today = LocalDate.now()

        items.forEach { item ->
            if (item.isExpired) {
                return@forEach // Skip expired items
            }

            val daysUntilExp = ChronoUnit.DAYS.between(today, item.expDate)

            // Only schedule if within the "going" window
            if (daysUntilExp <= item.daysNotice) {
                // Find all intervals that fit within the going window
                NOTIFICATION_INTERVALS.forEach { intervalDays ->
                    // Check if this interval is within the going window
                    if (intervalDays <= item.daysNotice && intervalDays <= daysUntilExp) {
                        val notificationDate = item.expDate.minusDays(intervalDays.toLong())

                        // Only schedule if notification date is today or in the future

                        // SET ALARM HERE:
                        // ALSO SET IN NotificationReceiver.kt!
                        // Schedule at 3 PM on the notification date
                        val triggerTime = notificationDate.atTime(15, 0) // 3:00 PM
                        val triggerTimeMillis = triggerTime
                            .atZone(ZoneId.systemDefault())
                            .toInstant()
                            .toEpochMilli()

                        val lookBackMillis = 5 * 60 * 1000 // 5 minutes
                        if (triggerTimeMillis > System.currentTimeMillis() - lookBackMillis) {
                            val alarmId = getUniqueID(listName, intervalDays, item.name, item.expDate.toString())
                            currentAlarms.add(alarmId.toString())

                            val intent = Intent(context, NotificationReceiver::class.java).apply {
                                action = ALARM_ACTION
                                data = Uri.parse("custom://$alarmId")
                                putExtra("listName", listName)
                                putExtra("itemData", MyApplication.jsonSerializer.encodeToString(
                                    InventoryItem.serializer(), item
                                ))
                                putExtra("daysUntilExpiration", intervalDays)
                            }

                            val pendingIntent = PendingIntent.getBroadcast(
                                context,
                                alarmId,
                                intent,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )

                            AlarmManagerCompat.setExactAndAllowWhileIdle(
                                alarmManager,
                                AlarmManager.RTC_WAKEUP,
                                triggerTimeMillis,
                                pendingIntent
                            )

                            val hour = triggerTime.hour
                            val minute = triggerTime.minute
                            val amPm = if (hour >= 12) "PM" else "AM"
                            val displayHour = if (hour > 12) hour - 12 else if (hour == 0) 12 else hour

                            Log.d(TAG, "Scheduled alarm ID $alarmId for ${item.name}: " +
                                    "$intervalDays days before expiration (${notificationDate} at $displayHour:$minute $amPm)")
                        }
                    }
                }
            }
        }

        // Cancel stale alarms
        val staleAlarms = oldAlarms.filter { it !in currentAlarms }
        staleAlarms.forEach { idStr ->
            val id = idStr.toIntOrNull() ?: return@forEach
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                id,
                Intent(context, NotificationReceiver::class.java)
                    .setAction(ALARM_ACTION)
                    .setData(Uri.parse("custom://$id")),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            pendingIntent?.let {
                alarmManager.cancel(it)
                Log.d(TAG, "Cancelled stale alarm ID $id")
            }
        }

        // Persist new alarm set
        prefs.edit().putStringSet(spKey, HashSet(currentAlarms)).apply()

        // Maintain global index
        val allKeysSet = HashSet(prefs.getStringSet("all_alarm_keys", emptySet()) ?: emptySet())
        allKeysSet.add(listName)
        prefs.edit().putStringSet("all_alarm_keys", allKeysSet).apply()

        Log.d(TAG, "Finished scheduling for '$listName'. Persisted ${currentAlarms.size} alarm IDs.")
    }

    private fun cancelAlarmsForList(context: Context, prefs: SharedPreferences, listName: String) {
        val spKey = "alarms_$listName"
        val oldAlarms = prefs.getStringSet(spKey, emptySet()) ?: emptySet()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        oldAlarms.forEach { idStr ->
            val id = idStr.toIntOrNull() ?: return@forEach
            val intent = Intent(context, NotificationReceiver::class.java)
                .setAction(ALARM_ACTION)
                .setData(Uri.parse("custom://$id"))
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                id,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            pendingIntent?.let {
                alarmManager.cancel(it)
            }
        }

        prefs.edit().remove(spKey).apply()
        val allKeys = HashSet(prefs.getStringSet("all_alarm_keys", emptySet()) ?: emptySet())
        allKeys.remove(listName)
        prefs.edit().putStringSet("all_alarm_keys", allKeys).apply()
    }

    private fun cancelAllAlarms(context: Context) {
        val prefs = context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
        val allKeys = prefs.getStringSet("all_alarm_keys", emptySet()) ?: emptySet()

        allKeys.forEach { listName ->
            cancelAlarmsForList(context, prefs, listName)
        }
        prefs.edit().remove("all_alarm_keys").apply()
        Log.d(TAG, "All alarms cancelled and keys cleaned up.")
    }

    private fun getUniqueID(listName: String, daysBefore: Int, itemName: String, expDate: String): Int {
        return "${listName}_${itemName}_${expDate}_${daysBefore}".hashCode()
    }
}