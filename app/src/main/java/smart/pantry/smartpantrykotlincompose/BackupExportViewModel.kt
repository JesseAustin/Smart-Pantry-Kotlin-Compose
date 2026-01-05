package smart.pantry.smartpantrykotlincompose

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File

class BackupExportViewModel : ViewModel() {

    // --- StateFlows (from BackupExportActivity.kt setup) ---
    private val _localLists = MutableStateFlow<List<String>>(emptyList())
    val localLists: StateFlow<List<String>> = _localLists.asStateFlow()

    private val _cloudLists = MutableStateFlow<List<String>>(emptyList())
    val cloudLists: StateFlow<List<String>> = _cloudLists.asStateFlow()

    private val _isUserSignedIn = MutableStateFlow(false)
    val isUserSignedIn: StateFlow<Boolean> = _isUserSignedIn.asStateFlow()

    // --- Firebase Initialization ---
    private val db = Firebase.firestore
    private val auth = Firebase.auth
    private val USER_COLLECTION = "users"
    private val LISTS_SUBCOLLECTION = "lists"
    private val FIELD_LIST_DATA = "listData" // Field name to hold the JSON string

    private val _selectedLocalList = MutableStateFlow<String?>(null)
    val selectedLocalList = mutableStateOf<String?>(null)


    fun selectLocalList(name: String) {
        _selectedLocalList.value = name
    }

    fun deleteLocalList(context: Context, filename: String) {
        viewModelScope.launch {
            val success = ListFileManager.deleteList(context, filename)
            if (success) {
                refreshLocalLists(context)
                selectedLocalList.value = null // Clear any selected row if needed
            } else {
                Toast.makeText(context, "Failed to delete $filename", Toast.LENGTH_SHORT).show()
            }
        }
    }


    // --- Authentication ---
    fun signInAnonymously(context: Context) {
        viewModelScope.launch {
            try {
                Firebase.auth.signInAnonymously().await()
                _isUserSignedIn.value = (Firebase.auth.currentUser != null)
                //Toast.makeText(context, "Signed in anonymously.", Toast.LENGTH_SHORT).show()
                refreshCloudLists(context) // Load cloud lists after sign-in
            } catch (e: Exception) {
                _isUserSignedIn.value = false

                Log.e("BkupExpViewModel", "Cloud sign-in failed: ${e.message}", e)
                Toast.makeText(context, "Cloud sign-in failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // --- Local Storage Management ---

    fun refreshLocalLists(context: Context) {
        viewModelScope.launch {
            _localLists.value = ListFileManager.listSavedLists(context)
        }
    }

    // --- Cloud Storage Implementation ---

    /**
     * Uploads a local list to the user's secure Cloud Firestore path.
     */
    fun uploadListToCloud(context: Context, filename: String) {
        val uid = auth.currentUser?.uid
        if (uid == null || filename.isBlank()) return

        viewModelScope.launch {
            try {
                // 1. Load the InventoryList object from internal storage
                val inventoryList = ListFileManager.loadList(context, filename)
                if (inventoryList == null) {
                    Toast.makeText(context, "Local list '$filename' not found.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // 2. Convert the InventoryList to a JSON string using the helper
                val jsonString = ListFileManager.listToJsonString(inventoryList)

                // 3. Upload the string to Firestore
                db.collection(USER_COLLECTION).document(uid)
                    .collection(LISTS_SUBCOLLECTION).document(filename)
                    .set(mapOf(FIELD_LIST_DATA to jsonString))
                    .await()

                Toast.makeText(context, "'$filename' uploaded successfully.", Toast.LENGTH_SHORT).show()
                refreshCloudLists(context)
            } catch (e: Exception) {
                Log.e("BackupExportVM", "Error uploading list: ${e.message}", e)
                Toast.makeText(context, "Upload failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun exportSelectedList(context: Context, listName: String) {

        if (listName.isBlank()) return

        viewModelScope.launch {
            try {
                // 1. Load list object
                val inventoryList = ListFileManager.loadList(context, listName)
                if (inventoryList == null) {
                    Toast.makeText(context, "Could not load selected list.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // 2. Convert to JSON
                val json = ListFileManager.listToJsonString(inventoryList)

                // 3. Write to temporary cache file
                val file = File(context.cacheDir, "$listName.json")
                file.writeText(json)

                // 4. FileProvider Uri
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )

                // 5. Launch share sheet
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                context.startActivity(
                    Intent.createChooser(shareIntent, "Export List As JSON")
                )

            } catch (e: Exception) {
                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                e.printStackTrace()
            }
        }
    }


    /**
     * Downloads a list from the cloud and saves it to local storage.
     */
    fun downloadListFromCloud(context: Context, filename: String) {
        val uid = auth.currentUser?.uid
        if (uid == null) return

        viewModelScope.launch {
            try {
                // 1. Fetch the document from Firestore
                val document = db.collection(USER_COLLECTION).document(uid)
                    .collection(LISTS_SUBCOLLECTION).document(filename)
                    .get()
                    .await()

                val jsonString = document.getString(FIELD_LIST_DATA)
                if (jsonString.isNullOrBlank()) {
                    Toast.makeText(context, "Cloud list data for '$filename' is empty.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // 2. Convert the JSON string back to an InventoryList object using the helper
                val inventoryList = ListFileManager.jsonStringToList(jsonString)
                if (inventoryList == null) {
                    Toast.makeText(context, "Failed to parse cloud data for '$filename'.", Toast.LENGTH_LONG).show()
                    return@launch
                }

                // 3. Save the InventoryList to internal storage
                ListFileManager.saveList(context, filename, inventoryList)

                Toast.makeText(context, "'$filename' downloaded and saved to device.", Toast.LENGTH_SHORT).show()
                refreshLocalLists(context)
            } catch (e: Exception) {
                Log.e("BackupExportVM", "Error downloading list: ${e.message}", e)
                Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Deletes a list from the user's secure Cloud Firestore path.
     */
    fun deleteCloudList(context: Context, filename: String) {
        val uid = auth.currentUser?.uid
        if (uid == null) return

        viewModelScope.launch {
            try {
                db.collection(USER_COLLECTION).document(uid)
                    .collection(LISTS_SUBCOLLECTION).document(filename)
                    .delete()
                    .await()

                Toast.makeText(context, "'$filename' deleted from cloud storage.", Toast.LENGTH_SHORT).show()
                refreshCloudLists(context)
            } catch (e: Exception) {
                Log.e("BackupExportVM", "Error deleting cloud list: ${e.message}", e)
                Toast.makeText(context, "Deletion failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Deletes ALL cloud lists for the current user, then signs them out.
     * Call this before Firebase.auth.signOut() to clean up data.
     */
    fun deleteAllCloudListsAndSignOut(context: Context) {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            Log.w("BackupExportVM", "No user signed in, skipping cloud cleanup")
            Firebase.auth.signOut()
            return
        }

        viewModelScope.launch {
            try {
                Log.d("BackupExportVM", "Starting deletion of all cloud lists for user: $uid")

                // Get all documents in the user's lists subcollection
                val querySnapshot = db.collection(USER_COLLECTION).document(uid)
                    .collection(LISTS_SUBCOLLECTION)
                    .get()
                    .await()

                val deleteCount = querySnapshot.documents.size
                Log.d("BackupExportVM", "Found $deleteCount lists to delete")

                // Delete each document
                querySnapshot.documents.forEach { document ->
                    db.collection(USER_COLLECTION).document(uid)
                        .collection(LISTS_SUBCOLLECTION).document(document.id)
                        .delete()
                        .await()
                    Log.d("BackupExportVM", "Deleted cloud list: ${document.id}")
                }

                Log.d("BackupExportVM", "All $deleteCount cloud lists deleted successfully")

                // Now sign out
                Firebase.auth.signOut()
                Log.d("BackupExportVM", "User signed out of Firebase")

            } catch (e: Exception) {
                Log.e("BackupExportVM", "Error deleting cloud lists: ${e.message}", e)
                // Still try to sign out even if deletion fails
                try {
                    Firebase.auth.signOut()
                } catch (signOutError: Exception) {
                    Log.e("BackupExportVM", "Error during sign out: ${e.message}", signOutError)
                }
            }
        }
    }


    /**
     * Fetches the names (Document IDs) of all lists stored in the cloud for the current user.
     */
    fun refreshCloudLists(context: Context) {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            _cloudLists.value = emptyList()
            return
        }

        viewModelScope.launch {
            try {
                val querySnapshot = db.collection(USER_COLLECTION).document(uid)
                    .collection(LISTS_SUBCOLLECTION)
                    .get()
                    .await()

                val names = querySnapshot.documents.map { it.id }.sorted()
                _cloudLists.value = names
            } catch (e: Exception) {
                Log.e("BackupExportVM", "Error fetching cloud list names: ${e.message}", e)
                _cloudLists.value = emptyList()
                //Toast.makeText(context, "Failed to load cloud lists.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}