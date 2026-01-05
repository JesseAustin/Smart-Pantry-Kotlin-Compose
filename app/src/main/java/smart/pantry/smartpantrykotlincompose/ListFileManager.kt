package smart.pantry.smartpantrykotlincompose

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

object ListFileManager {

    private const val FOLDER_ROOT = "Smart Inventory"
    private const val FOLDER_SUB = "All Lists"
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }


    /**
     * Loads an InventoryList from a content URI provided by the system's file picker/manager.
     */
    suspend fun loadListFromUri(context: Context, uri: Uri): InventoryList? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val jsonText = inputStream.bufferedReader().use { it.readText() }

                // Use your existing JSON deserialization logic
                return@withContext json.decodeFromString(InventoryList.serializer(), jsonText)
            }
        } catch (e: Exception) {
            // Log the error for debugging
            Log.e("ListFileManager", "Error loading JSON from URI: $uri", e)
            return@withContext null
        }
        return@withContext null
    }

    // --- New Serialization Helpers for Cloud Storage ---
    suspend fun listToJsonString(list: InventoryList): String = withContext(Dispatchers.Default) {
        // Uses the existing private 'json' instance in ListFileManager
        json.encodeToString(InventoryList.serializer(), list)
    }

    suspend fun jsonStringToList(jsonString: String): InventoryList? = withContext(Dispatchers.Default) {
        try {
            // Uses the existing private 'json' instance
            json.decodeFromString(InventoryList.serializer(), jsonString)
        } catch (e: Exception) {
            Log.e("ListFileManager", "Error deserializing JSON string from cloud.", e)
            null
        }
    }

    // --- File Path Helpers (From InventoryFileHelper.kt) ---

    private suspend fun getSubDirectory(context: Context): File = withContext(Dispatchers.IO) {
        val subDir = File(File(context.filesDir, FOLDER_ROOT), FOLDER_SUB)
        subDir.mkdirs() // Ensure directories exist
        subDir
    }

    suspend fun getListFile(context: Context, filename: String): File = withContext(Dispatchers.IO) {
        // Filenames should end with .json
        val finalFilename = if (filename.endsWith(".json", true)) filename else "$filename.json"
        File(getSubDirectory(context), finalFilename)
    }

    // --- I/O Operations (From InventoryJsonHelper.kt) ---

    suspend fun saveList(context: Context, filename: String, list: InventoryList) = withContext(Dispatchers.IO) {
        val file = getListFile(context, filename)
        file.writeText(json.encodeToString(InventoryList.serializer(), list))
    }

    suspend fun loadList(context: Context, filename: String): InventoryList? = withContext(Dispatchers.IO) {
        val file = getListFile(context, filename)
        if (file.exists()) {
            try {
                json.decodeFromString(InventoryList.serializer(), file.readText())
            } catch (e: Exception) {
                // Log error
                null
            }
        } else null
    }

    suspend fun deleteList(context: Context, filename: String): Boolean = withContext(Dispatchers.IO) {
        val file = getListFile(context, filename)
        file.delete()
    }

    suspend fun listSavedLists(context: Context): List<String> = withContext(Dispatchers.IO) {
        val subDir = getSubDirectory(context)
        subDir.listFiles { file -> file.isFile && file.extension.equals("json", true) }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()
    }
}