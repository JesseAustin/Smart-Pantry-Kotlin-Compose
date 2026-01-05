package smart.pantry.smartpantrykotlincompose

import android.content.Context
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

/**
 * Repository class to handle all persistence, file I/O, and data structure manipulation
 * for the inventory items.
 *
 * It encapsulates the complexities of file paths and Kotlinx Serialization.
 */
class InventoryRepository(private val context: Context, private val app: MyApplication) {

    private val TAG = "InventoryRepository"

    // Define consistent file path constants
    private val FOLDER_ROOT = "Smart Pantry"
    private val FOLDER_SUB = "All Lists"
    private val INVENTORY_FILE_NAME = "current_inventory.json"
    private val JSON_MIME_TYPE = "application/json"

    // File structure
    private val mainDirectory: File by lazy { File(context.filesDir, FOLDER_ROOT) }
    private val subDirectory: File by lazy { File(mainDirectory, FOLDER_SUB) }
    private val currentInventoryFile: File by lazy { File(subDirectory, INVENTORY_FILE_NAME) }

    init {
        // Ensure directories exist on startup
        if (!subDirectory.exists()) {
            subDirectory.mkdirs()
        }
    }

    /**
     * Data structure to be serialized for saving/loading the entire state.
     */
    @kotlinx.serialization.Serializable
    data class InventoryState(
        val items: List<InventoryItem>,
        val nextID: Int,
        // Potentially add other app-wide state here later, like current list name
        val currentListName: String
    )


    /**
     * Saves the current application state to the designated JSON file.
     * Uses the singleton Kotlinx Json serializer.
     * @param fileName The name of the file to save to (defaults to current_inventory.json)
     */
    fun saveInventory(fileName: String = INVENTORY_FILE_NAME) {
        // 1. Determine the save path
        val saveFile = File(subDirectory, fileName)

        // 2. Prepare the data state object
        val state = InventoryState(
            items = app.itemsList.toList(), // Convert observable list to regular list
            nextID = app.nextID.intValue,
            currentListName = fileName // Save the current file name as the active list
        )

        // 3. Serialize and save
        try {
            val jsonString = MyApplication.jsonSerializer.encodeToString(state)
            saveFile.writeText(jsonString)
            Log.d(TAG, "Successfully saved inventory to: ${saveFile.absolutePath}")

            //Schedule Alarms on LIST save, only if save is successful:
            //AlarmScheduler.scheduleAll(context)

        } catch (e: Exception) {
            Log.e(TAG, "Error saving inventory: ${saveFile.absolutePath}", e)
        }
    }

    /**
     * Loads the application state from a specific file.
     * Updates the application's global state (`itemsList`, `filteredList`, `nextID`).
     * @param jsonFile The file to load from.
     * @return True if loading was successful, false otherwise.
     */
    fun loadInventory(jsonFile: File = currentInventoryFile): Boolean {
        if (!jsonFile.exists()) {
            // This is expected on first launch, or if a list was deleted
            Log.w(TAG, "Load failed: File does not exist: ${jsonFile.absolutePath}")
            app.itemsList.clear()
            app.filteredList.clear()
            app.nextID.intValue = 1
            return false
        }

        try {
            val jsonString = jsonFile.readText()
            val state = MyApplication.jsonSerializer.decodeFromString<InventoryState>(jsonString)

            // Update application state from the loaded data
            app.itemsList.clear()
            app.itemsList.addAll(state.items)
            app.filteredList.clear() // Filtered list will be updated by the UI logic later

            // Update the next ID counter
            val maxId = app.itemsList.maxOfOrNull { it.id } ?: 0
            app.setNextID(maxId)

            app.nextID.intValue = state.nextID

            Log.d(TAG, "Successfully loaded inventory from: ${jsonFile.absolutePath} with ${app.itemsList.size} items.")
            return true

        } catch (e: FileNotFoundException) {
            Log.e(TAG, "File not found: ${jsonFile.absolutePath}", e)
            // Clear current list on failure to prevent stale data display
            app.itemsList.clear()
            app.filteredList.clear()
            app.nextID.intValue = 1
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error deserializing inventory: ${jsonFile.absolutePath}", e)
            // Clear current list on deserialization error
            app.itemsList.clear()
            app.filteredList.clear()
            app.nextID.intValue = 1
            return false
        }
    }

    // --- Working List Methods ---

    /**
     * Adds a new item to the live list, updates the next ID, and triggers a save.
     * @param item The new InventoryItem to add. Its ID should already be set correctly.
     */
    suspend fun addItem(item: InventoryItem) {
        // 1. Check for duplicates (optional, but safe)
        if (app.itemsList.find { it.id == item.id } == null) {
            // 2. Add to the live state (which the UI observes)
            app.itemsList.add(item)
        }
        // 4. DO NOT Save changes to file yet until the user hits save manually:
        saveInventory()

        // DO NOT Schedule alarms after just adding an item
        //AlarmScheduler.scheduleAll(context)
    }

    /**
     * Finds an existing item by ID, updates its properties in the live list, and triggers a save.
     * @param updatedItem The InventoryItem containing the new data.
     */
    suspend fun updateItem(updatedItem: InventoryItem) {
        // 1. Find the index of the item to be replaced
        val index = app.itemsList.indexOfFirst { it.id == updatedItem.id }

        if (index != -1) {
            // 2. Replace the old item object with the updated one
            app.itemsList[index] = updatedItem

            // 3. DO NOT Save changes to file yet until the user hits save manually:
            saveInventory()

            // DO NOT Schedule alarms after updating an existing item
            //AlarmScheduler.scheduleAll(context)
        }
    }

    /**
     * Removes an item by ID and triggers a save.
     * (If you need deletion logic in EditActivity later)
     */
    suspend fun deleteItem(item: InventoryItem) {
        app.itemsList.remove(item)

        saveInventory()

        // DO NOT Schedule alarms after saving
        //AlarmScheduler.scheduleAll(context)
    }


    // --- Utility Methods ---

    /**
     * Generates and returns a unique ID for a new item, then increments the global counter.
     */
    fun getNextID(): Int {
        return app.nextID.intValue++
    }

    /**
     * Returns a list of all saved inventory list names (JSON files) in the sub-directory.
     */
    fun getSavedListNames(): List<String> {
        return subDirectory.listFiles { _, name -> name.endsWith(".json") }
            ?.map { it.name }
            ?: Collections.emptyList()
    }

    /**
     * Deletes a specific inventory list file.
     * @param listName The name of the list/file to delete.
     * @return True if the file was deleted successfully, false otherwise.
     */
    fun deleteList(listName: String): Boolean {
        val fileToDelete = File(subDirectory, listName)
        return if (fileToDelete.exists()) {
            fileToDelete.delete().also { success ->
                if (success) {
                    Log.d(TAG, "Successfully deleted list: $listName")
                } else {
                    Log.e(TAG, "Failed to delete list file: $listName")
                }
            }
        } else {
            Log.w(TAG, "Attempted to delete non-existent list: $listName")
            true // Consider it a success if the file is already gone
        }
    }

    /**
     * Deletes all local files/lists in the inventory folder structure.
     * @return True if the directory structure was successfully cleaned up.
     */
    fun clearAllFiles(): Boolean {
        return try {
            mainDirectory.deleteRecursively()
            // Recreate the necessary directory structure after cleaning
            subDirectory.mkdirs()
            Log.d(TAG, "Successfully cleared all local inventory files.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing all local files.", e)
            false
        }
    }

    /**
     * Helper to get the File object for a specific list name.
     */
    fun getListFile(listName: String): File {
        return File(subDirectory, listName)
    }
}