package smart.pantry.smartpantrykotlincompose

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.serialization.decodeFromString
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Receives the alarm broadcast for an expiring item and updates/shows a notification.
 */
class NotificationReceiver : BroadcastReceiver() {

    companion object {
        private val TAG = "NotificationReceiver"
        private const val CHANNEL_ID = MyApplication.EXPIRATION_CHANNEL_ID

        // CRITICAL: This static map aggregates reminders and is highly prone to being killed
        // by the OS (which is why WorkManager/JobScheduler is preferred).
        // However, to maintain functional parity with the original Java code's logic (which used this map
        // to aggregate multiple items into one notification), we use a ConcurrentHashMap.
        // Structure: listName (String) -> Map<ItemName (String), ExpirationEpochMillis (Long)>

        //private val listReminders = ConcurrentHashMap<String, MutableMap<String, Long>>()
        private val listReminders = ConcurrentHashMap<String, MutableMap<String, Pair<Long, Int>>>()

        // Simple date formatter for notification text (e.g., "MM/dd/yy")
        private val dateFormatter: DateTimeFormatter = DateTimeFormatter
            .ofPattern("MM/dd/yy")
            .withZone(ZoneId.systemDefault())

        const val ITEM_DATA_EXTRA = "itemData"
        const val LIST_NAME_EXTRA = "listName"
    }

    private fun formatTimeLeft(days: Int): String {
        return when {
            days <= 1 -> "1 day"
            days < 7 -> "$days days"
            days < 14 -> "1 week"
            days < 30 -> "${days / 7} weeks"
            days < 60 -> "1 month"
            days < 365 -> "${days / 30} months"
            days < 730 -> "1 year"
            else -> "${days / 365} years"
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "=== NotificationReceiver.onReceive() called at ${System.currentTimeMillis()} ===")
        Log.d(TAG, "Intent action: ${intent.action}")
        Log.d(TAG, "Intent data: ${intent.data}")

        // Check if this is the alarm action we scheduled
        if (intent.action != "smart.pantry.ALARM_EXPIRATION") {
            Log.d(TAG, "Not our alarm action, ignoring")
            return
        }

        // TEST BLOCK
        /**
        val listName = intent.data?.getQueryParameter("listName")  // ← Get from Uri
        val itemDataString = intent.getStringExtra(ITEM_DATA_EXTRA)

        if (listName == null || itemDataString == null) {
            Log.e(TAG, "Missing listName or itemData in intent.")
            return
        }
        **/
        // TEST BLOCK

        val listName = intent.getStringExtra(LIST_NAME_EXTRA).toString()  // Get from extras, not Uri
        val itemDataString = intent.getStringExtra(ITEM_DATA_EXTRA).toString()


        try {
            // Decode the InventoryItem using Kotlinx Serialization
            val item: InventoryItem = MyApplication.jsonSerializer.decodeFromString(itemDataString)
            Log.d(TAG, "Decoded item: ${item.name} for list: $listName")

            // 1. Update the static map for aggregation
            // Use expSoonDate for the notification time, converting to milliseconds
            //val expDateEpoch = item.expSoonDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

            val daysUntilExpiration = intent.getIntExtra("daysUntilExpiration", 0)
            val notificationDate = item.expDate.minusDays(daysUntilExpiration.toLong())

            // SET ALARM HERE:
            // ALSO SET IN AlarmScheduler.kt!
            // Schedule alarms for 3:00PM
            val expDateEpoch = notificationDate.atTime(15, 0)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()

            // Get the reminder map for this list, creating if it doesn't exist
            //val listMap = listReminders.getOrPut(listName) { ConcurrentHashMap() }

            val listMap = listReminders.getOrPut(listName) { ConcurrentHashMap() }

            // Add/Update the item's reminder entry
            //listMap[item.name] = expDateEpoch
            listMap[item.name] = Pair(expDateEpoch, daysUntilExpiration)

            Log.d(TAG, "Reminder map updated for $listName. Current size: ${listMap.size}")

            // 2. Build and show the aggregated notification
            buildAndShowNotification(context, listName)

        } catch (e: Exception) {
            Log.e(TAG, "Error decoding InventoryItem or processing alarm", e)
        }
    }

    /**
     * Aggregates all items currently in the listReminders map for a given listName
     * and displays a single notification using NotificationCompat.InboxStyle.
     */
    private fun buildAndShowNotification(context: Context, listName: String) {

        Log.d(TAG, "=== buildAndShowNotification called for $listName ===")
        val listMap = listReminders[listName]
        if (listMap.isNullOrEmpty()) {
            Log.d(TAG, "No reminders left for $listName, skipping notification.")
            return
        }

        // Filter out items that have passed their notification time, just in case.
        val currentTimeMillis = System.currentTimeMillis()
        Log.d(TAG, "Current time millis: $currentTimeMillis")

        /*
        listMap.forEach { (itemName, expTime) ->
            Log.d(TAG, "  Item: $itemName, expTime: $expTime, diff: ${expTime - currentTimeMillis}ms")
        }
        */

        listMap.forEach { (itemName, pair) ->
            Log.d(TAG, "  Item: $itemName, expTime: ${pair.first}, diff: ${pair.first - currentTimeMillis}ms")
        }

        // Allow notifications within the last hour to still show
        val oneHourAgo = currentTimeMillis - (60 * 60 * 1000)

        //val currentReminders = listMap.filter { (_, expTime) -> expTime >= oneHourAgo }
        val currentReminders = listMap.filter { (_, pair) -> pair.first >= oneHourAgo }

        // Filter out items that have passed their notification time, just in case.
        //val currentReminders = listMap.filter { (_, expTime) -> expTime > currentTimeMillis }

        if (currentReminders.isEmpty()) {
            listReminders.remove(listName) // Clean up the empty map
            Log.d(TAG, "All items in $listName are past their notification time. Not showing notification.")
            return
        }

        // Sort items by notification time (ascending)
        //val sortedReminders = currentReminders.toList().sortedBy { (_, expTime) -> expTime }
        val sortedReminders = currentReminders.toList().sortedBy { (_, pair) -> pair.first }

        // --- Notification Content ---
        val itemCount = currentReminders.size

        // Removes ".json" from the list name display text:
        val strippedListName = listName.dropLast(5)

        val title = "$itemCount Item${if (itemCount > 1) "s" else ""} in $strippedListName expiring soon!"
        val summary = "Items in $strippedListName going!"

        // Build InboxStyle lines
        val inboxStyle = NotificationCompat.InboxStyle().setBigContentTitle(title)//.setSummaryText(summary)

        /*
        sortedReminders.forEach { (itemName, expTime) ->
            // Format the date for the notification line
            val dateString = Instant.ofEpochMilli(expTime).atZone(ZoneId.systemDefault()).toLocalDate()
                .let { dateFormatter.format(it) }
            val line = "$itemName expiring on $dateString"
            inboxStyle.addLine(line)
        }
        */

        // Build notification lines with time left
        sortedReminders.forEach { (itemName, pair) ->
            val (expTime, daysLeft) = pair
            val timeLeftStr = formatTimeLeft(daysLeft)
            val line = "$timeLeftStr left: $itemName"
            inboxStyle.addLine(line)
        }

        // --- Notification Intent ---
        // Intent to launch the app (MainActivity) when the user taps the notification
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            // Pass along which list triggered this, so MainActivity can load it
            putExtra("listName", listName)
        }

        val uniqueID = listName.hashCode() // Unique ID for the list's notification

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            uniqueID,
            openAppIntent,
            pendingIntentFlags
        )

        // --- Build and Show Notification ---
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher) // Placeholder for your small icon
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(inboxStyle)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(uniqueID, builder.build())
        Log.d(TAG, "Notification $uniqueID built and shown for $listName with $itemCount item(s).")
    }
}