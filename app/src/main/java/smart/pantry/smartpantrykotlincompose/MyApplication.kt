// smart.pantry.smartpantrykotlincompose/MyApplication.kt
package smart.pantry.smartpantrykotlincompose

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.serialization.json.Json

/**
 * Global State Container and Service Locator for the application.
 * Initializes the Kotlinx JSON serializer, Notification Channel, and the InventoryRepository.
 */
class MyApplication : Application() {

    private val TAG = "MyApplication"


    // Private lazy property to initialize SharedPreferences once
    private val sharedPreferences by lazy {
        applicationContext.getSharedPreferences(UserSettings.PREFERENCES, Context.MODE_PRIVATE)
    }

    //val cloudBackupEnabled = mutableStateOf(false)
    val scanBeepEnabled = mutableStateOf(false)
    val perishableFilterEnabled = mutableStateOf(true)
    // Add new state to hold the list temporarily and trigger the dialog
    val importedListToSave = mutableStateOf<InventoryList?>(null)

    // Add a new state variable to hold the suggested name
    val suggestedListName = mutableStateOf("")

    val itemsList: SnapshotStateList<InventoryItem> = mutableStateListOf()

    val filteredList = mutableStateListOf<InventoryItem>()

    val nextID = mutableIntStateOf(0)

    val repository: InventoryRepository by lazy {
        // Assuming InventoryRepository's constructor is InventoryRepository(Context, Application)
        InventoryRepository(applicationContext, this)
    }

    // Public accessor for the initialized repository
    @JvmName("getRepositoryInstance")
    fun getRepository(): InventoryRepository {
        return repository
    }

    /** Saves a boolean preference to the shared preferences file. */
    fun savePreference(key: String, value: Boolean) {
        sharedPreferences.edit().putBoolean(key, value).apply()
    }

    /** Retrieves a boolean preference from the shared preferences file. */
    fun getPreference(key: String, defaultValue: Boolean): Boolean {
        return sharedPreferences.getBoolean(key, defaultValue)
    }

    // --- Core List Processing Function ---

    /**
     * UNIFIED LIST LOADER: Loads item data, updates the next ID counter,
     * updates the perishable filter state, and persists the new filter state.
     * This function is called after loading from internal storage or importing a file.
     */
    fun loadList(list: InventoryList) {
        // 1. Update the actual item list
        itemsList.clear()
        itemsList.addAll(list.items)

        // 2. Update the perishable filter state and persist it
        val isPerishable = list.perishable
        perishableFilterEnabled.value = isPerishable

        savePreference(UserSettings.KEY_PERISHABLE_ENABLED, isPerishable)
        Log.d(TAG, "List loaded. Perishable state set to: $isPerishable and persisted.")

        // 3. Update the Next ID counter
        val maxId = list.items.maxOfOrNull { it.id } ?: 0
        setNextID(maxId)

        // DO NOT Schedule alarms after loading list
        //scheduleAlarmsIfAllowed()
    }

    // --- List Management Functions ---

    // Function called by MainActivity after import (now uses loadList)
    fun handleImportedList(list: InventoryList, suggestedName: String = "") {
        // 1. Process and load the list, which handles state and persistence
        loadList(list)

        // 2. Store the suggested name
        suggestedListName.value = suggestedName

        // 3. Set the state to trigger the Save dialog in WorkingListScreen
        importedListToSave.value = list
    }

    /** Saves the current working list and current perishables setting. */
    suspend fun saveWorkingList(listName: String) {
        val perishableState = perishableFilterEnabled.value
        val listToSave = InventoryList(
            perishable = perishableState,
            items = itemsList.toList()
        )
        Log.d("MyApplication", "Saving list: $listName with perishable=$perishableState")
        ListFileManager.saveList(applicationContext, listName, listToSave)

        // Schedule alarms after saving list only if Smart Pantry has the permissions to do so
        scheduleAlarmsIfAllowed()
    }

    /** Loads a list and returns the loaded list object. */
    suspend fun loadWorkingList(filename: String): InventoryList? {
        return ListFileManager.loadList(applicationContext, filename)
    }

    /** Deletes a saved list file. */
    suspend fun deleteSavedList(filename: String): Boolean {
        val result = ListFileManager.deleteList(applicationContext, filename)

        if (result) {
            // Schedule alarms after saving list only if Smart Pantry has the permissions to do so
            scheduleAlarmsIfAllowed()
        }
        return result
    }

    /** Gets the names of all saved lists. */
    suspend fun getSavedListNames(): List<String> {
        return ListFileManager.listSavedLists(applicationContext)
    }

    // Check for permissions before trying to schedule alarms from saved lists:
    fun scheduleAlarmsIfAllowed() {
        val ctx = applicationContext
        val notif = NotificationPermissionHelper.isNotificationPermissionGranted(ctx)
        val exact = NotificationPermissionHelper.isExactAlarmPermissionGranted(ctx)

        if (notif && exact) {
            Log.d(TAG, "Permissions OK — scheduling alarms")
            AlarmScheduler.scheduleAll(ctx)
        } else {
            Log.w(TAG, "Permissions missing — NOT scheduling alarms")
        }
    }

    override fun onCreate() {
        super.onCreate()

        instance = this
        createNotificationChannel()

        // 1. Load the initial perishable state from shared preferences at startup
        //cloudBackupEnabled.value = getPreference(UserSettings.KEY_CLOUD_BACKUP_ENABLED, false)
        perishableFilterEnabled.value = getPreference(UserSettings.KEY_PERISHABLE_ENABLED, true)
        scanBeepEnabled.value = getPreference(UserSettings.KEY_SCAN_BEEP_ENABLED, false)

        Log.d(TAG, "Initial Perishable state loaded from preferences: ${perishableFilterEnabled.value}")

        // Note: The list data itself is loaded later in InventoryRepository.loadInventory()
        // which should ideally call 'loadList(list)' when it succeeds.
    }

    fun getCurrentID(): Int {
        return nextID.value
    }

    // Helper to generate the next ID safely.
    fun getNextID(): Int {
        nextID.value += 1
        return nextID.value
    }

    // For when you load a list from storage that has a max ID
    fun setNextID(newMaxId: Int) {
        if (newMaxId >= nextID.value) {
            nextID.value = newMaxId + 1
        }
    }

    // --- Companion Object (Static-like members) ---

    companion object {
        const val EXPIRATION_CHANNEL_ID = "EXPIRATION_CHANNEL"

        // STATIC: Kotlinx Json serializer singleton
        val jsonSerializer = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        private lateinit var instance: MyApplication

        // Used by other classes (like NotificationReceiver) to access the list
        @JvmStatic
        fun getItemsList(): List<InventoryItem> {
            return instance.itemsList.toList()
        }

        @JvmStatic
        fun getFilteredList(): List<InventoryItem> {
            return instance.filteredList.toList()
        }

        @JvmStatic
        fun setFilteredList(newList: List<InventoryItem>) {
            instance.filteredList.clear()
            instance.filteredList.addAll(newList)
        }
    }

    // Your getInventoryItemById() and private utility methods...
    fun getInventoryItemById(id: Int): InventoryItem? {
        return itemsList.find { it.id == id }
    }

    // --- Private Setup Methods ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Expiration Reminders"
            val description = "Reminders for your expiring inventory items."
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(EXPIRATION_CHANNEL_ID, name, importance).apply {
                this.description = description
            }

            // Register the channel with the system
            val notificationManager: NotificationManager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification Channel Created.")
        }
    }
}