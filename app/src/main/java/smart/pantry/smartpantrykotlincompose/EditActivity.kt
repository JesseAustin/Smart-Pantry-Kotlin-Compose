package smart.pantry.smartpantrykotlincompose

import EditViewModelFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import android.app.DatePickerDialog
import android.content.ContentValues.TAG
import android.content.Context
import android.util.Log
import androidx.activity.viewModels
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Surface
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import androidx.compose.ui.graphics.Color
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import smart.pantry.smartpantrykotlincompose.ui.theme.SmartPantryKotlinComposeTheme

import java.util.*
import kotlin.math.truncate

data class PackDetectionResult(
    val originalName: String,
    val detectedQuantity: Int,
    val cleanedName: String  // Name with pack reference removed
)

// EditActivity.kt - Helper extension to easily access MyApplication
val Context.myApp: MyApplication
    get() = applicationContext as MyApplication

@Composable
fun FreshnessIcon(status: FreshnessStatus) {
    // Map the ViewModel's status to your drawable resources
    val drawableId = when (status) {
        FreshnessStatus.SAFE -> R.drawable.safe_status
        FreshnessStatus.USE_NOW -> R.drawable.use_now_status
        FreshnessStatus.EXPIRED -> R.drawable.expired_status
    }

    Icon(
        painter = painterResource(id = drawableId),
        contentDescription = "Freshness status: ${status.name}",
        modifier = Modifier.size(40.dp), // Matches the 40dp defined in your XMLs
        tint = Color.Unspecified // Important: allows the tint defined in your XML files to be used
    )
}

class EditActivity : ComponentActivity() {

    var onBarcodeResult: ((String?, String?, PackDetectionResult?) -> Unit)? = null

    // Companion object for item pack regex:
    companion object {
        private val PACK_PATTERNS = listOf(
            Regex("""(\d+)[-\s]pack""", RegexOption.IGNORE_CASE),
            Regex("""(\d+)[-\s]piece""", RegexOption.IGNORE_CASE),
            Regex("""(\d+)[-\s]set""", RegexOption.IGNORE_CASE),
            Regex("""(\d+)[-\s]count""", RegexOption.IGNORE_CASE),
            Regex("""(\d+)[-\s]bundle""", RegexOption.IGNORE_CASE),
            Regex("""pack\s+of\s+(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""set\s+of\s+(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""bundle\s+of\s+(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""(\d+)\s*ct\b""", RegexOption.IGNORE_CASE),
            Regex("""(\d+)\s*pk\b""", RegexOption.IGNORE_CASE),
            Regex("""(\d+)\s*pc\b""", RegexOption.IGNORE_CASE),
        )
    }

    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            lifecycleScope.launch {
                val scannedCode = result.contents

                // Try upcitemdb first
                var productName = withContext(Dispatchers.IO) { lookupProductName(scannedCode) }

                // If not found, try shared database
                if (productName == null) {
                    productName = lookupProductInSharedDatabase(scannedCode)
                }

                // Check for pack/set detection
                val packInfo = productName?.let { extractPackInfo(it) }

                // Pass to compose with pack info
                onBarcodeResult?.invoke(scannedCode, productName, packInfo)
            }
        } else {
            Toast.makeText(this, "Scan cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    // Helper extension property to easily get the MyApplication instance
    private val Context.myApp: MyApplication
        get() = applicationContext as MyApplication

    // Get the item ID from the Intent. Note: Must be 'lazy' because 'intent' isn't available until onCreate.
    private val itemIdToLoad: Int by lazy { intent.getIntExtra("itemId", -1) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        //Log.d(TAG, "Caught Item ID extra: ${itemIdToLoad} to Edit Activity.")

        // Get the MyApplication instance (The Context.myApp helper is great, but a direct cast is always safe here)
        val myApp = applicationContext as MyApplication

        // Initialize the ViewModel using the correct factory signature.
        // This single block replaces the factory, itemIdToEdit, and loadItem calls.
        val viewModel: EditViewModel by viewModels {
            EditViewModelFactory(
                repository = myApp.repository,
                app = myApp,
                itemIdToLoad = itemIdToLoad // Pass the ID here
            )
        }

        setContent {
            SmartPantryKotlinComposeTheme {
                // Render the UI
                EditScreen(
                    viewModel = viewModel,
                    startScan = intent.getBooleanExtra("startScan", false),
                    onScanClick = { startBarcodeScan() },
                    onCancelClick = { finish() }
                )
            }
        }
    }

    private fun startBarcodeScan() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.ALL_CODE_TYPES)
            setPrompt("Volume \uD83D\uDD0A/\uD83D\uDD09 to turn \uD83D\uDD26 On/Off!")
            setBeepEnabled(myApp.scanBeepEnabled.value)
        }

        //Log.d("BarcodeScan", "Launching with prompt: ${options.promptText}")
        barcodeLauncher.launch(options)
    }

    private fun lookupProductName(upc: String): String? {
        val url = "https://api.upcitemdb.com/prod/trial/lookup?upc=$upc"
        val request = Request.Builder().url(url).build()
        return try {
            HttpClientManager.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = org.json.JSONObject(body)
                val items = json.getJSONArray("items")
                if (items.length() == 0) return null
                val first = items.getJSONObject(0)
                first.optString("title", "Code not found in database.")
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractPackInfo(productName: String): PackDetectionResult? {
        val quantity = extractQuantityFromProductName(productName) ?: return null

        var cleanedName = productName

        // Call item pack regex companion object:
        for (pattern in PACK_PATTERNS) {
            cleanedName = pattern.replace(cleanedName, "").trim()
        }

        // Clean up any double spaces or trailing dashes/commas
        cleanedName = cleanedName.replace(Regex("""\s+"""), " ")
            .replace(Regex("""[,\-]\s*$"""), "")
            .trim()

        return PackDetectionResult(
            originalName = productName,
            detectedQuantity = quantity,
            cleanedName = cleanedName
        )
    }

    private fun extractQuantityFromProductName(productName: String?): Int? {
        if (productName == null) return null

        for (pattern in PACK_PATTERNS) {
            val match = pattern.find(productName)
            if (match != null) {
                val quantityStr = match.groupValues[1]
                val quantity = quantityStr.toIntOrNull()
                // Validate reasonable range (1-999)
                if (quantity != null && quantity in 1..999) {
                    return quantity
                }
            }
        }

        return null  // No quantity found
    }

    private suspend fun lookupProductInSharedDatabase(upc: String): String? {
        return try {
            val firestore = Firebase.firestore
            val docRef = firestore.collection("shared_products").document(upc)
            val snapshot = docRef.get().await()

            if (snapshot.exists()) {
                snapshot.getString("name")
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("SharedDB", "Error fetching from shared database", e)
            null
        }
    }
}



@Composable
fun EditScreen(
    viewModel: EditViewModel,
    startScan: Boolean,
    onScanClick: () -> Unit,
    onCancelClick: () -> Unit
) {
    // Add state for pack detection dialog
    var showPackDialog by remember { mutableStateOf(false) }
    var packDialogData by remember { mutableStateOf<PackDetectionResult?>(null) }
    var adjustQuantity by remember { mutableStateOf(true) }
    var cleanName by remember { mutableStateOf(true) }

    // --- Local State for Date Fields (to handle D/M/Y independently) ---
    var dayStr by remember { mutableStateOf(viewModel.expDate?.dayOfMonth?.toString() ?: "") }
    var monthStr by remember { mutableStateOf(viewModel.expDate?.monthValue?.toString() ?: "") }
    var yearStr by remember { mutableStateOf(viewModel.expDate?.year?.toString()?.takeLast(2) ?: "") }


    // --- References and Helpers ---
    val context = LocalContext.current
    val myApp = context.myApp

    var showValidationDialog by remember { mutableStateOf(false) }
    var validationMessage by remember { mutableStateOf("") }

    // 1. Read the global state (it is a Compose State, so changes will trigger a recomposition)
    val isPerishableEnabled = myApp.perishableFilterEnabled.value

    //val activity = context as ComponentActivity // Used for activity.finish()
    val activity = context as EditActivity // Now we can access onBarcodeResult

    // Barcode Result Handler (Essential: This connects Activity scan result to ViewModel)
    DisposableEffect(Unit) {
        activity.onBarcodeResult = { code, name, packInfo ->
            if (code != null) viewModel.code = code
            if (name != null) {
                if (packInfo != null && viewModel.isNewItem) {
                    // Show dialog for pack detection
                    packDialogData = packInfo
                    showPackDialog = true
                    // Set name but don't adjust quantity yet - wait for user choice
                    viewModel.name = name
                    viewModel.wasNameManuallyEntered = false
                } else {
                    // No pack detected or editing existing item - just set the name
                    viewModel.name = name
                    viewModel.wasNameManuallyEntered = false
                }
            } else {
                Toast.makeText(
                    context,
                    "Code Scanned. Name not found in database.",
                    Toast.LENGTH_LONG
                ).show()
                viewModel.wasNameManuallyEntered = true
            }
        }
        onDispose { activity.onBarcodeResult = null }
    }

    // Auto-launch scanner if requested
    LaunchedEffect(startScan) { if (startScan) onScanClick() }

    // --- CRITICAL: Synchronization between 3 fields and 1 ViewModel property ---
    LaunchedEffect(dayStr, monthStr, yearStr) {
        val d = dayStr.toIntOrNull()
        val m = monthStr.toIntOrNull()
        val y = ("20" + yearStr).toIntOrNull()

        if (d != null && m != null && y != null) {
            try {
                viewModel.expDate = LocalDate.of(y, m, d)
            } catch (e: Exception) {
                viewModel.expDate = null  // ✅ Let validation catch invalid dates
            }
        } else {
            viewModel.expDate = null  // ✅ Also set to null if fields are incomplete
        }
    }

    var errorMessage by remember { mutableStateOf<String?>(null) }

    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("\uD83D\uDC40 Oops") },
            text = { Text(errorMessage!!) },
            confirmButton = {
                Button(onClick = { errorMessage = null }) { Text("OK") }
            }
        )
    }


    // --- HELPER COMPOSABLES (Defined at the top to resolve "Unresolved Reference") ---

    // Inside EditScreen Composable, where SmallNumberField is defined:

    @Composable
    fun SmallNumberField(
        value: String,
        onValueChange: (String) -> Unit,
        placeholderText: String,
        // ADD THIS NEW PARAMETER:
        label: @Composable (() -> Unit)? = null
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(viewModel.digitsOnlyLimit(it, 5)) },
            singleLine = true,
            modifier = Modifier.widthIn(min = 80.dp, max = 140.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            textStyle = TextStyle(fontSize = 18.sp, textAlign = TextAlign.Center),

            // PASS THE NEW PARAMETER:
            label = label,

            // We'll keep the placeholder logic simple for now
            placeholder = { Text(placeholderText) }
        )
    }

    @Composable
    fun DateField(value: String, onValueChange: (String) -> Unit, placeholderText: String, maxLen: Int) {
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(it.filter { char -> char.isDigit() }.take(maxLen)) },
            placeholder = { Text(placeholderText, fontSize = 14.sp) },
            singleLine = true,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 56.dp, max = 80.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            // FIX: Combining the two textStyle lines into one to fix the error
            textStyle = TextStyle(fontSize = 18.sp, textAlign = TextAlign.Center)
        )
    }

    // --- END HELPER COMPOSABLES ---

    // --- UI Layout ---

    Scaffold(
        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Delete Button
                if (!viewModel.isNewItem) {
                    var showDeleteDialog by remember { mutableStateOf(false) }

                    Button(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4040)),
                    ) {
                        Text("\uD83D\uDDD1\uFE0F")
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    if (showDeleteDialog) {
                        AlertDialog(
                            onDismissRequest = { showDeleteDialog = false },
                            title = { Text("Delete Item") },
                            text = { Text("Are you sure you want to delete '${viewModel.name}'? This action cannot be undone.") },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        viewModel.deleteItem { activity.finish() }
                                        showDeleteDialog = false
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xE2FF4040))
                                ) {
                                    Text("Delete")
                                }
                            },
                            dismissButton = {
                                OutlinedButton(onClick = { showDeleteDialog = false }) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }
                }

                // Cancel Button
                Button(
                    onClick = onCancelClick,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x2E6E59CE))
                ) {
                    Text("❌", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Save Button
                Button(
                    onClick = {
                        viewModel.saveItem(
                            onSaved = { activity.finish() },
                            onValidationError = { msg -> errorMessage = msg }
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("💾")
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // --- ITEM NAME INPUT WITH FRESHNESS INDICATOR ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = viewModel.name,
                    onValueChange = { viewModel.name = it },
                    placeholder = { Text("Item Name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = TextStyle(fontSize = 20.sp)
                )
                if (isPerishableEnabled) {
                    FreshnessIcon(status = viewModel.freshnessStatus)
                }
            }

            // BAR/QR CODE display
            Box(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = viewModel.code.ifEmpty { "BAR/QR CODE" },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 18.sp
                )
            }

            // QR scanner button
            Surface(
                modifier = Modifier.size(76.dp).align(Alignment.CenterHorizontally),
                shape = CircleShape,
                tonalElevation = 4.dp,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().clickable { onScanClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.qr_code_scanner),
                        contentDescription = "Scan Barcode",
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            // --- CONDITIONAL PERISHABLE FIELDS ---
            if (isPerishableEnabled) {
                // Days notice
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SmallNumberField(
                        value = viewModel.daysNoticeStr,
                        onValueChange = { viewModel.daysNoticeStr = it },
                        placeholderText = "",
                        label = { Text("Days Notice") }
                    )

                    // Calendar Picker Icon
                    IconButton(onClick = {
                        val localDateToUse = viewModel.expDate ?: LocalDate.now()
                        val calendar = Calendar.getInstance().apply {
                            set(localDateToUse.year, localDateToUse.monthValue - 1, localDateToUse.dayOfMonth)
                        }

                        DatePickerDialog(
                            context,
                            { _, y, m, d ->
                                viewModel.expDate = LocalDate.of(y, m + 1, d)
                                dayStr = d.toString()
                                monthStr = (m + 1).toString()
                                yearStr = y.toString().takeLast(2)
                            },
                            calendar.get(Calendar.YEAR),
                            calendar.get(Calendar.MONTH),
                            calendar.get(Calendar.DAY_OF_MONTH)
                        ).show()
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.calendar_icon),
                            contentDescription = "Pick Date",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                // --- DATE INPUT GROUP ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DateField(monthStr, { monthStr = it }, "MM", 2)
                    Text("/", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.titleMedium)
                    DateField(dayStr, { dayStr = it }, "DD", 2)
                    Text("/", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.titleMedium)
                    DateField(yearStr, { yearStr = it }, "YY", 2)
                }
            }

            // Quantity group
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    val v = viewModel.quantityStr.toIntOrNull() ?: 0
                    if (v > 0) viewModel.quantityStr = (v - 1).toString()
                }) {
                    Icon(painterResource(R.drawable.remove_button), contentDescription = "Remove 1")
                }

                SmallNumberField(
                    viewModel.quantityStr,
                    { viewModel.quantityStr = it },
                    placeholderText = "Qty"
                )

                IconButton(onClick = {
                    val v = viewModel.quantityStr.toIntOrNull() ?: 0
                    viewModel.quantityStr = (v + 1).toString()
                }) {
                    Icon(painterResource(R.drawable.add_button), contentDescription = "Add 1")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }
    }

    // DIALOGS
    if (showValidationDialog) {
        AlertDialog(
            onDismissRequest = { showValidationDialog = false },
            title = {
                Text(
                    if (validationMessage.contains("date", ignoreCase = true)) {
                        "Check That Date Again?"
                    } else {
                        "Oops!"
                    }
                )
            },
            text = { Text(validationMessage) },
            confirmButton = {
                Button(onClick = { showValidationDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    // Pack Detection Dialog
    if (showPackDialog && packDialogData != null) {
        AlertDialog(
            onDismissRequest = { showPackDialog = false },
            title = { Text("📦 Multi-Pack Detected") },
            text = {
                Column {
                    Text("Found: ${packDialogData!!.detectedQuantity} items")
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { adjustQuantity = !adjustQuantity }
                    ) {
                        Checkbox(
                            checked = adjustQuantity,
                            onCheckedChange = { adjustQuantity = it }
                        )
                        Text("Set quantity to ${packDialogData!!.detectedQuantity}")
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { cleanName = !cleanName }
                    ) {
                        Checkbox(
                            checked = cleanName,
                            onCheckedChange = { cleanName = it }
                        )
                        Text("Remove pack reference from name")
                    }

                    if (cleanName) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "New name: ${packDialogData!!.cleanedName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (adjustQuantity) {
                        viewModel.quantityStr = packDialogData!!.detectedQuantity.toString()
                    }
                    if (cleanName) {
                        viewModel.name = packDialogData!!.cleanedName
                    }
                    showPackDialog = false
                    packDialogData = null
                }) {
                    Text("Apply")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    // Keep original name and quantity
                    showPackDialog = false
                    packDialogData = null
                }) {
                    Text("Keep Original")
                }
            }
        )
    }
}


