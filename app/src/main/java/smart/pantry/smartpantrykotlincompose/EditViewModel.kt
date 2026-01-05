package smart.pantry.smartpantrykotlincompose

import android.content.ContentValues.TAG
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import okhttp3.Request

enum class FreshnessStatus {
    SAFE, USE_NOW, EXPIRED
}

class EditViewModel(
    private val repository: InventoryRepository,
    private val app: MyApplication,
    itemIdToLoad: Int = -1
) : ViewModel() {

    var isNewItem: Boolean = true
        private set

    var itemId: Int = -1

    val freshnessStatus: FreshnessStatus
        get() {
            val date = expDate ?: return FreshnessStatus.SAFE
            val today = LocalDate.now()
            val daysRemaining = ChronoUnit.DAYS.between(today, date)
            val noticeDays = daysNoticeStr.toIntOrNull() ?: 0

            return when {
                daysRemaining <= 0 -> FreshnessStatus.EXPIRED
                daysRemaining <= noticeDays -> FreshnessStatus.USE_NOW
                else -> FreshnessStatus.SAFE
            }
        }

    var name by mutableStateOf("")

    var wasNameManuallyEntered by mutableStateOf(false)

    var code by mutableStateOf("")
    var quantityStr by mutableStateOf("1")
    var daysNoticeStr by mutableStateOf("60")
    var expDate: LocalDate? by mutableStateOf(null)

    // Properties for profanity filtering:
    private var profanityList: Set<String>? = null
    private var profanityListLoaded = false

    init {
        if (itemIdToLoad != -1) {
            isNewItem = false
            itemId = itemIdToLoad

            app.getInventoryItemById(itemIdToLoad)?.let { item ->
                name = item.name
                code = item.code
                quantityStr = item.quantity.toString()
                daysNoticeStr = item.daysNotice.toString()
                expDate = item.expDate // LocalDate assigned to LocalDate? (automatic conversion)
            }
        } else {
            isNewItem = true
            itemId = app.nextID.intValue
            app.nextID.intValue++
            expDate = LocalDate.now()
        }

        // Load profanity list in background
        viewModelScope.launch {
            loadProfanityList()
        }
    }

    private suspend fun loadProfanityList() {
        if (profanityListLoaded) return // Already attempted to load

        try {
            val url = "https://raw.githubusercontent.com/LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words/master/en"
            val request = Request.Builder().url(url).build()

            withContext(Dispatchers.IO) {
                HttpClientManager.client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val words = response.body?.string()
                            ?.split("\n")
                            ?.map { it.trim().lowercase() }
                            ?.filter { it.isNotEmpty() }
                            ?.toSet() ?: emptySet()

                        profanityList = words
                        profanityListLoaded = true
                        Log.i(TAG, "✅ Loaded ${words.size} words into profanity filter")
                    } else {
                        Log.w(TAG, "⚠️ Failed to load profanity list: ${response.code}")
                        profanityList = emptySet()
                        profanityListLoaded = true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load profanity list", e)
            // Fail safe - use empty set but mark as loaded
            profanityList = emptySet()
            profanityListLoaded = true
        }
    }

    private fun containsProfanity(text: String): Boolean {
        val list = profanityList

        // If list hasn't loaded yet, block the save to be safe
        if (!profanityListLoaded) {
            Log.w(TAG, "⚠️ Profanity list not loaded yet, blocking save")
            return true // Safer to block than allow
        }

        // If list is empty (failed to load), allow it
        if (list == null || list.isEmpty()) {
            Log.w(TAG, "⚠️ Profanity list empty, allowing content")
            return false
        }

        // Normalize text: lowercase, remove special chars
        val normalized = text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        // Check individual words
        val words = normalized.split(" ")
        if (words.any { it in list }) {
            Log.w(TAG, "⚠️ Profanity detected in: $text")
            return true
        }

        // Check for l33t speak substitutions
        val leetSpeak = normalized
            .replace("0", "o")
            .replace("1", "i")
            .replace("3", "e")
            .replace("4", "a")
            .replace("5", "s")
            .replace("7", "t")
            .replace("@", "a")
            .replace("$", "s")

        val leetWords = leetSpeak.split(" ")
        if (leetWords.any { it in list }) {
            Log.w(TAG, "⚠️ L33t speak profanity detected in: $text")
            return true
        }

        // Check for profanity hidden in the middle of words (scunthorpe problem aware)
        // Only flag if the profane word is >50% of the total word length
        for (word in words) {
            if (word.length > 3) { // Only check words longer than 3 chars
                for (profaneWord in list) {
                    if (profaneWord.length > 3 && word.contains(profaneWord)) {
                        // If profane word is more than 50% of the word, flag it
                        if (profaneWord.length.toFloat() / word.length > 0.5f) {
                            Log.w(TAG, "⚠️ Embedded profanity detected: $profaneWord in $word")
                            return true
                        }
                    }
                }
            }
        }

        return false
    }

    private fun validateProductNameForSharing(name: String): String? {
        // Basic length validation
        if (name.length < 2) return "Product name too short"
        if (name.length > 200) return "Product name too long"

        // Must contain at least some letters
        if (name.count { it.isLetter() } < 2) {
            return "Product name must contain at least two letters"
        }

        // Check for repeated characters (spam like "aaaaaaa")
        if (Regex("""(.)\1{4,}""").containsMatchIn(name)) {
            return "Product name contains too many repeated characters"
        }

        // PROFANITY CHECK - This is the key check
        if (containsProfanity(name)) {
            return "Product name contains inappropriate language"
        }

        return null // Valid
    }

    // Add this function to save to shared database
    private suspend fun saveToSharedDatabase(code: String, name: String) {
        // Check if user is authenticated
        val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            Log.w(TAG, "⚠️ User not authenticated, skipping shared database save")
            return // User not signed in, can't save to shared database
        }

        // Validate before sending
        val validationError = validateProductNameForSharing(name)
        if (validationError != null) {
            Log.w(TAG, "⚠️ Blocked sharing product name: $validationError - '$name'")
            return // Silently block - don't confuse user with error
        }

        try {
            val firestore = Firebase.firestore
            val data = hashMapOf(
                "name" to name,
                "addedAt" to com.google.firebase.Timestamp.now(),
                "addedBy" to (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "anonymous"),
                "timesAdded" to com.google.firebase.firestore.FieldValue.increment(1)
            )

            // Use set with merge to avoid overwriting if multiple users add same code
            firestore.collection("shared_products")
                .document(code)
                .set(data, com.google.firebase.firestore.SetOptions.merge())
                .await()

            Log.i(TAG, "✅ Successfully saved to shared database: $code -> $name")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to save to shared database: ${e.message}", e)
            when (e) {
                is com.google.firebase.firestore.FirebaseFirestoreException -> {
                    Log.e(TAG, "Firestore error code: ${e.code}")
                }
            }
        }
    }

    val dateDisplay: String
        get() = expDate?.toString() ?: "MM/DD/YY"

    fun digitsOnlyLimit(s: String, maxLen: Int) = s.filter { it.isDigit() }.take(maxLen)

    // --- SAVE WITH VALIDATION ---
    fun saveItem(
        onSaved: () -> Unit,
        onValidationError: (String) -> Unit
    ) {
        // 1. NAME VALIDATION
        if (name.isBlank()) {
            onValidationError("Please enter a name!")
            return
        }

        // 2. DATE VALIDATION
        val validatedExpDate: LocalDate

        if (app.perishableFilterEnabled.value) {
            // PERISHABLES ON: Strict validation with error dialogs
            val currentDate = expDate

            if (currentDate == null) {
                onValidationError("Check that date again?")
                return
            }

            val d = currentDate.dayOfMonth
            val m = currentDate.monthValue
            val y = currentDate.year

            // Validate month
            if (m !in 1..12) {
                onValidationError(" \uD83D\uDC40 Check that date again?\n\nYou entered an impossible month (must be 1-12).")
                return
            }

            // Validate day
            if (d !in 1..31) {
                onValidationError(" \uD83D\uDC40 Check that date again?\n\nYou entered an impossible day (must be 1-31).")
                return
            }

            validatedExpDate = currentDate
        } else {
            // PERISHABLES OFF: Silently fix bad dates to today
            val currentDate = expDate

            if (currentDate == null) {
                validatedExpDate = LocalDate.now()
            } else {
                val d = currentDate.dayOfMonth
                val m = currentDate.monthValue
                val y = currentDate.year

                // Check if date is invalid, if so use today
                validatedExpDate = if (m in 1..12 && d in 1..31 && y in 2000..2100) {
                    currentDate // Date is valid, use it
                } else {
                    LocalDate.now() // Date is invalid, silently fix to today
                }
            }
        }

        // 3. QUANTITY VALIDATION
        val quantity = quantityStr.toIntOrNull()
        if (quantity == null || quantity <= 0) {
            onValidationError("Please enter a valid quantity!")
            return
        }

        // 4. DAYS NOTICE VALIDATION (auto-fill if blank)
        if (app.perishableFilterEnabled.value && daysNoticeStr.isBlank()) {
            daysNoticeStr = "60" // Auto default
        }

        val daysNotice = daysNoticeStr.toIntOrNull() ?: run {
            onValidationError("Days notice must be a number!")
            return
        }

        // 5. BUILD THE ITEM (validatedExpDate is guaranteed non-null here)
        val item = InventoryItem(
            id = itemId,
            name = name.trim(),
            code = code.trim(),
            expDate = validatedExpDate, // Already non-null from validation
            quantity = quantity,
            daysNotice = daysNotice
        )

        Log.d(TAG, "Saving item: $item, isNewItem=$isNewItem")

        // 6. SAVE TO REPOSITORY
        viewModelScope.launch {
            if (isNewItem) {
                // Check for duplicate
                val duplicate = app.itemsList.find { existingItem ->
                    existingItem.name.equals(item.name, ignoreCase = true) &&
                            existingItem.code.equals(item.code, ignoreCase = true) &&
                            existingItem.expDate == item.expDate
                }

                if (duplicate != null) {
                    Log.i(TAG, "CONSOLIDATION: Found duplicate item (ID: ${duplicate.id}). " +
                            "Merging quantities: ${duplicate.quantity} + ${item.quantity}")

                    val updatedItem = duplicate.copy(
                        quantity = duplicate.quantity + item.quantity
                    )

                    repository.updateItem(updatedItem)
                    Log.i(TAG, "CONSOLIDATION COMPLETE: New quantity = ${updatedItem.quantity}")
                } else {
                    Log.i(TAG, "REPOSITORY CALL: ADD new item: $item")
                    repository.addItem(item)
                }
            } else {
                Log.i(TAG, "REPOSITORY CALL: UPDATE item: $item")
                repository.updateItem(item)
            }

            // Save to shared database if manually entered
            if (wasNameManuallyEntered && code.isNotBlank() && name.isNotBlank()) {
                saveToSharedDatabase(code.trim(), name.trim())
            }

            Log.d(TAG, "onSaved callback will now run")
            onSaved()
        }
    }

    fun deleteItem(onDeleted: () -> Unit) {
        if (isNewItem) {
            return
        }

        viewModelScope.launch {
            app.getInventoryItemById(itemId)?.let { item ->
                repository.deleteItem(item)
            }
            onDeleted()
        }
    }
}
