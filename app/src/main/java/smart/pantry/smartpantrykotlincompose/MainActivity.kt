package smart.pantry.smartpantrykotlincompose

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons // Required for accessing Icons object
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Checkbox
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.animation.animateColorAsState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.getValue  // Required for 'by' delegate
import androidx.compose.runtime.setValue  // Required for 'by' delegate
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.rememberCoroutineScope // New required import
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch // New required import
import kotlinx.coroutines.withContext
import smart.pantry.smartpantrykotlincompose.ui.theme.SmartPantryKotlinComposeTheme
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import androidx.compose.material3.SearchBar
import androidx.compose.material.icons.filled.Clear
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

sealed class PermissionPrompt {
    object None : PermissionPrompt()
    object NotificationsPreface : PermissionPrompt()
    object ExactAlarmPreface : PermissionPrompt()
}

class MainActivity : ComponentActivity() {

    // Helper extension property to easily get the MyApplication instance
    private val Context.myApp: MyApplication
        get() = applicationContext as MyApplication

    // 1. State to track which Compose dialog to show
    val permissionPromptState = mutableStateOf<PermissionPrompt>(PermissionPrompt.None)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val myApp = application as MyApplication

        // Load the auto-saved working list FIRST (before UI)
        myApp.repository.loadInventory()

        NotificationPermissionHelper.startPermissionFlow(
            activity = this,
            forceShow = false,
            // 💡 NEW: Lambda to set the state and trigger the Compose AlertDialog
            onShowPrompt = { promptType ->
                permissionPromptState.value = promptType
            }
        )
        //WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            SmartPantryKotlinComposeTheme {
                WorkingListScreen(
                    onScanClicked = {
                        val intent = Intent(this, EditActivity::class.java).apply {
                            putExtra("startScan", true)
                            putExtra("itemId", -1) // 👈 explicitly mark as new
                        }
                        startActivity(intent)
                    },
                    // Parameter needed for live 'don't ask again' & 'permission reminders' checkbox sync
                    permissionPromptState = permissionPromptState
                )
            }
            // 3. Add the central dialog handler here
            PermissionDialogHandler(
                currentPrompt = permissionPromptState.value,
                onDismiss = { permissionPromptState.value = PermissionPrompt.None }
            )
        }

        // --- CHECK FOR FILE INTENT ---
        if (intent.action == Intent.ACTION_VIEW) {
            intent.data?.let { uri ->
                if (contentResolver.getType(uri)?.contains("json") == true || uri.lastPathSegment?.endsWith(".json", true) == true)
                {
                    handleJsonImport(uri)

                // Already done elsewhere:
                //val maxId = myApp.itemsList.maxOfOrNull { it.id } ?: 0
                //myApp.setNextID(maxId)

                }
            }
        }
    }

    /**
     * Extracts a clean filename from a content URI.
     * Removes the .json extension and handles various URI formats.
     */
    private fun extractFilenameFromUri(uri: Uri): String {
        // Try to get the display name from the content resolver
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val displayNameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (displayNameIndex != -1) {
                    val displayName = it.getString(displayNameIndex)
                    // Remove .json extension if present
                    return if (displayName.endsWith(".json", ignoreCase = true)) {
                        displayName.dropLast(5)
                    } else {
                        displayName
                    }
                }
            }
        }

        // Fallback: try to extract from the URI path
        val lastSegment = uri.lastPathSegment ?: "imported_list"
        return if (lastSegment.endsWith(".json", ignoreCase = true)) {
            lastSegment.dropLast(5)
        } else {
            lastSegment
        }
    }

    private fun handleJsonImport(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val importedList = ListFileManager.loadListFromUri(applicationContext, uri)
            // Extract filename while still on IO dispatcher
            val suggestedName = extractFilenameFromUri(uri)

            withContext(Dispatchers.Main) {
                if (importedList != null) {
                    // Pass the list to a function that triggers the new Save Confirmation dialog
                    myApp.handleImportedList(importedList, suggestedName)
                } else {
                    Toast.makeText(this@MainActivity, "Error importing JSON file.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Update onRequestPermissionsResult to pass the lambda
    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            // This is the callback that replaces onRequestPermissionsResult
            NotificationPermissionHelper.handleNotificationPermissionResult(
                activity = this,
                isGranted = isGranted,
                onShowPrompt = { promptType ->
                    permissionPromptState.value = promptType
                }
            )
        }

    override fun onResume() {
        super.onResume()
        NotificationPermissionHelper.checkAndScheduleOnResume(this)
    }




    // 5. The new Compose Dialog Handler
    // Inside MainActivity class:
    @Composable
    fun PermissionDialogHandler(
        currentPrompt: PermissionPrompt,
        onDismiss: () -> Unit
    ) {
        val prefs = this.getSharedPreferences(NotificationPermissionHelper.PREF_FILE_NAME, Context.MODE_PRIVATE)

        when (currentPrompt) {
            is PermissionPrompt.NotificationsPreface -> {
                var doNotAsk by remember { mutableStateOf(prefs.getBoolean(NotificationPermissionHelper.KEY_DO_NOT_ASK_PERMISSIONS, false)) }

                AlertDialog(
                    onDismissRequest = {
                        prefs.edit().putBoolean(NotificationPermissionHelper.KEY_DO_NOT_ASK_PERMISSIONS, doNotAsk).apply()
                        onDismiss()
                    },
                    title = { Text("Enable Notification Permissions") },
                    text = {
                        Column (
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        ) {
                        Text (
                            "Smart Pantry can remind you when items are expiring, if you allow these 2\uFE0F⃣ system permissions! \uD83D\uDE09"
                        )

                            Spacer(Modifier.height(16.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = doNotAsk,
                                    onCheckedChange = { doNotAsk = it }
                                )
                                Text("Don't ask again")
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            prefs.edit().putBoolean(NotificationPermissionHelper.KEY_DO_NOT_ASK_PERMISSIONS, doNotAsk).apply()
                            // ✅ Launches the system dialog
                            requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            onDismiss() // Dismiss the Compose dialog
                        }) {
                            Text("Continue")

                        }
                    },
                    // ✅ GAP FILLED: The complete dismissButton logic
                    dismissButton = {
                        OutlinedButton(onClick = {
                            prefs.edit().putBoolean(NotificationPermissionHelper.KEY_DO_NOT_ASK_PERMISSIONS, doNotAsk).apply()
                            onDismiss() // Dismiss the Compose dialog
                        }) {
                            Text("Not now")
                        }
                    }
                )
            }
            is PermissionPrompt.ExactAlarmPreface -> {
                var doNotAskExact by remember { mutableStateOf(prefs.getBoolean(NotificationPermissionHelper.KEY_DO_NOT_ASK_EXACT, false)) }

                AlertDialog(
                    onDismissRequest = {
                        prefs.edit().putBoolean(NotificationPermissionHelper.KEY_DO_NOT_ASK_EXACT, doNotAskExact).apply()
                        onDismiss()
                    },
                    title = { Text("Allow exact alarms") },
                    text = {
                        Column {
                            Text("One down, 1\uFE0F⃣ to go! This one is for exact alarms ⏰ so Smart Pantry can deliver reminders at precise times.")
                            Spacer(Modifier.height(16.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = doNotAskExact,
                                    onCheckedChange = { doNotAskExact = it }
                                )
                                Text("Don't ask again")
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            prefs.edit().putBoolean(NotificationPermissionHelper.KEY_DO_NOT_ASK_EXACT, doNotAskExact).apply()
                            // ✅ Jumps to system settings
                            NotificationPermissionHelper.openExactAlarmSettings(this)
                            onDismiss() // Dismiss the Compose dialog
                        }) {
                            Text("Open settings")
                        }
                    },
                    // ✅ GAP FILLED: The complete dismissButton logic
                    dismissButton = {
                        OutlinedButton(onClick = {
                            prefs.edit().putBoolean(NotificationPermissionHelper.KEY_DO_NOT_ASK_EXACT, doNotAskExact).apply()
                            onDismiss() // Dismiss the Compose dialog
                        }) {
                            Text("Not now")
                        }
                    }
                )
            }
            is PermissionPrompt.None -> Unit
        }
    }
}




    @SuppressLint("ContextCastToActivity")
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun WorkingListScreen(onScanClicked: () -> Unit, permissionPromptState: MutableState<PermissionPrompt>) {

        val context = LocalContext.current
        val myApp = context.myApp
        val scope = rememberCoroutineScope()

        // 1. Correct List Access: SnapshotStateList
        val inventoryListState = myApp.itemsList

        // 2. Local State for Search Bar and Menu
        var searchText by remember { mutableStateOf("") } // State for the search text field

        var showMenu by remember { mutableStateOf(false) }

        var permissionRemindersEnabled by remember {
            mutableStateOf(
                // Enabled if EITHER "do not ask" flag is false (meaning we're still asking)
                !myApp.getPreference(UserSettings.KEY_DO_NOT_ASK_PERMISSIONS, false) ||
                        !myApp.getPreference(UserSettings.KEY_DO_NOT_ASK_EXACT, false)
            )
        }

        var perishableFilterEnabled by remember {
            mutableStateOf(myApp.getPreference(UserSettings
                .KEY_PERISHABLE_ENABLED, true))
        }

        var cloudBackupEnabled by remember {
            mutableStateOf(myApp.getPreference(UserSettings.KEY_CLOUD_BACKUP_ENABLED, false))
        }


        var showClearDialog by remember { mutableStateOf(false) }
        var showDeleteDialog by remember { mutableStateOf(false) }
        var showOverwriteDialog by remember { mutableStateOf(false) }
        var showSaveDialog by remember { mutableStateOf(false) }

        var showLoadDialog by remember { mutableStateOf(false) }
        var listToLoad by remember { mutableStateOf("") }

        var showLoadConfirmDialog by remember { mutableStateOf(false) }
        var showEnableCloudDialog by remember { mutableStateOf(false) }
        var showCloudDisableWarning by remember { mutableStateOf(false) }
        var showCloudDisableConfirm by remember { mutableStateOf(false) }

        // Change the default to an empty string for use with placeholders
        var listNameInput by remember { mutableStateOf("") }

        // State to hold list of available files
        var availableLists by remember { mutableStateOf(emptyList<String>()) }

        // 3. Filtered List Logic (Derived State)
        // The filtered list updates automatically whenever searchText or inventoryListState changes.
        val filteredItems = remember {
            derivedStateOf {
                if (searchText.isBlank()) {
                    // If search is empty, show the entire list
                    inventoryListState
                } else {
                    val lowerSearchText = searchText.lowercase()

                    // Filter items where the lowercased name or code contains the lowercased search text
                    inventoryListState.filter { item ->
                        item.name.lowercase().contains(lowerSearchText) ||
                                item.code.lowercase().contains(lowerSearchText)
                    }
                }
            }
        }.value // Access the current value of the DerivedState

        // Monitor permission changes to update menu dynamically
        val notificationGranted = remember {
            mutableStateOf(NotificationPermissionHelper.isNotificationPermissionGranted(context))
        }
        val exactAlarmGranted = remember {
            mutableStateOf(NotificationPermissionHelper.isExactAlarmPermissionGranted(context))
        }

        // Re-check permissions every time the screen is shown
        LaunchedEffect(Unit) {
            notificationGranted.value = NotificationPermissionHelper.isNotificationPermissionGranted(context)
            exactAlarmGranted.value = NotificationPermissionHelper.isExactAlarmPermissionGranted(context)

            // Also re-sync the permission reminders checkbox
            val notifDontAsk = myApp.getPreference(NotificationPermissionHelper.KEY_DO_NOT_ASK_PERMISSIONS, false)
            val exactDontAsk = myApp.getPreference(NotificationPermissionHelper.KEY_DO_NOT_ASK_EXACT, false)
            permissionRemindersEnabled = !(notifDontAsk || exactDontAsk)
        }

        LaunchedEffect(permissionPromptState.value) {
            // When dialog is dismissed (becomes None), refresh the checkbox state
            if (permissionPromptState.value == PermissionPrompt.None) {
                val notifDontAsk = myApp.getPreference(NotificationPermissionHelper.KEY_DO_NOT_ASK_PERMISSIONS, false)
                val exactDontAsk = myApp.getPreference(NotificationPermissionHelper.KEY_DO_NOT_ASK_EXACT, false)
                permissionRemindersEnabled = !(notifDontAsk || exactDontAsk)
            }
        }

        /**
        // State for LazyColumn position
        val listState = rememberLazyListState()

        // Scroll to the top whenever the filteredItems list changes (e.g., after sort/filter)
        LaunchedEffect(filteredItems) {
            // Only scroll if the list is not empty, and animate to the very first item (index 0)
            if (filteredItems.isNotEmpty()) {
                listState.animateScrollToItem(0)
            }
            if (myApp.itemsList.isNotEmpty()) {
                listState.animateScrollToItem(0)
            }
        }

        **/

        fun sortItems(criteria: String) {


            val listToSort = myApp.itemsList.toList()
            //val listToSort = myApp.filteredList.toList()


            val sortedList = when (criteria) {
                "A-Z" -> listToSort.sortedBy { it.name }
                "Z-A" -> listToSort.sortedByDescending { it.name }
                "Old-New" -> listToSort.sortedBy { it.expDate }
                "New-Old" -> listToSort.sortedByDescending { it.expDate }
                "Least-Most" -> listToSort.sortedBy { it.quantity }
                "Most-Least" -> listToSort.sortedByDescending { it.quantity }
                "Order Scanned" -> listToSort.sortedBy { it.id }
                else -> listToSort
            }

            myApp.itemsList.clear()
            myApp.itemsList.addAll(sortedList)
        }

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),

            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0x1AC47CFF)
                    ),

                    title = {
                        // CRASH FIX: Using a safe Material Icon (Icons.Filled.Store)
                        // You can replace this with a custom Icon/Image once you find a simple, supported asset.
                        Image(
                            // IMPORTANT: Replace R.drawable.app_logo_512 with the actual resource ID of your PNG file
                            painter = painterResource(R.drawable.ic_launcher_round),
                            contentDescription = "Smart Pantry Logo",
                            modifier = Modifier.size(42.dp) // Maintain a small, appropriate size for the TopAppBar
                        )
                    },
                    actions = {
                        // BUTTONS (Visible on App Bar: Clear, Save, Load, Delete Saved, Cloud Export)


                        // 1. Clear Working List (The big X icon) - RED
                        IconButton(onClick = {
                            // CHANGE: Show the dialog instead of clearing directly
                            showClearDialog = true
                        }) {
                            Text("❌")
                            /**Icon(
                                Icons.Filled.Close,
                                contentDescription = "Clear Working List",
                                tint = Color(0xFFFF4040) // Red
                            )**/
                        }

                        // 2. Save List - LIGHT BLUE
                        IconButton(onClick = {
                            // Trigger Save Dialog (will handle overwrite check internally)
                            listNameInput = "" // Reset placeholder
                            showSaveDialog = true
                        }) {
                            Text("\uD83D\uDCBE")
                            /**Icon(
                                Icons.Filled.Save,
                                contentDescription = "Save Working List",
                                tint = Color(0xFF92AEFF) // Light Blue
                            )**/
                        }

                        // 3. Load List - LIGHT ORANGE/YELLOW
                        IconButton(onClick = {
                            scope.launch {
                                availableLists = myApp.getSavedListNames()
                                showLoadDialog = true
                            }
                        }) {
                            Text("\uD83D\uDDC2\uFE0F")
                            /**Icon(
                                Icons.Filled.Folder,
                                contentDescription = "Load Working List",
                                tint = Color(0xFFFFDA8D) // Light Orange/Yellow
                            )**/
                        }

                        // 4. Delete Saved List - RED
                        IconButton(onClick = {

                            scope.launch {
                                availableLists = myApp.getSavedListNames()
                                showDeleteDialog = true
                            }
                        }) {
                            Text("\uD83D\uDDD1\uFE0F")
                            /**Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Delete Saved List",
                                tint = Color(0xFF727272) // Gray
                            )**/
                        }

                        // Read the preference to drive the icon
                        //val cloudBackupEnabled = myApp.getPreference(
                        //    UserSettings.KEY_CLOUD_BACKUP_ENABLED,
                        //    false
                        //)

                        // 5. Dynamic Cloud / Export Button
                        IconButton(
                            onClick = {
                                // ✅ Both icons lead to Backup/Export screen
                                context.startActivity(Intent(context, BackupExportActivity::class.java))
                            }
                        ) {
                            if (cloudBackupEnabled) {
                                Text("☁\uFE0F")
                                /**Icon(
                                    Icons.Filled.CloudUpload,
                                    contentDescription = "Cloud Backup",
                                    tint = Color(0xFFC48CFF) // Light Purple/Pink (matches Cloud backup color)
                                )**/
                            } else {
                                Text("\uD83D\uDCE8")
                                /**Icon(
                                    Icons.Filled.AttachEmail,
                                    contentDescription = "Export / Share",
                                    tint = Color(0xFF92AEFF) // Light Blue (same as Save List tint family)
                                )**/
                            }
                        }

                        // DROPDOWN MENU START:
                        // 6. OVERFLOW MENU ICON (...)
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More Options")
                            }

                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                ListItem(
                                    headlineContent = { Text("Cloud Storage") },
                                    trailingContent = {
                                        Switch(
                                            checked = cloudBackupEnabled,  // ← Uses the top-level state
                                            onCheckedChange = { checked ->
                                                if (!checked) {
                                                    showCloudDisableWarning = true
                                                } else {
                                                    showEnableCloudDialog = true
                                                }
                                            }
                                        )
                                    }
                                )
                                HorizontalDivider(thickness = 0.3.dp, color = Color(0x80645275))

                                if (showEnableCloudDialog)
                                {
                                    AlertDialog(
                                        onDismissRequest = { showEnableCloudDialog = false },
                                        title = { Text("Enable Cloud Storage?") },
                                        text = {
                                            Column(verticalArrangement = Arrangement.Bottom)
                                            {
                                                Column(
                                                    Modifier
                                                        .verticalScroll(rememberScrollState()),
                                                    verticalArrangement = Arrangement.Top)
                                                {
                                                    Text("Only you have access to your cloud lists through the app \uD83D\uDD10\n\n" +
                                                            "Disable cloud backup and erase all of your data at any time ✅\n\n" +
                                                            "See our privacy policy for more details \uD83D\uDCDD")
                                                }
                                            } },
                                        confirmButton = {
                                            TextButton(onClick = {
                                                cloudBackupEnabled = true
                                                Toast.makeText(context, "Cloud storage enabled!", Toast.LENGTH_LONG).show()
                                                myApp.savePreference(UserSettings.KEY_CLOUD_BACKUP_ENABLED, true)
                                                showEnableCloudDialog = false
                                            }) { Text("Yes") }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = {
                                                showEnableCloudDialog = false

                                                // OPTIONAL - Close Dropdown menu itself:
                                                showMenu = false

                                            }) { Text("No") }
                                        }
                                    )
                                }

                                // State to track if user has cloud lists
                                val backupViewModel = remember { BackupExportViewModel() }
                                val cloudListsState by backupViewModel.cloudLists.collectAsState()
                                val hasCloudLists = cloudListsState.isNotEmpty()

                                // Check for cloud lists when the warning dialog is about to show
                                LaunchedEffect(showCloudDisableWarning) {
                                    if (showCloudDisableWarning) {
                                        // Refresh cloud lists - the StateFlow will automatically update hasCloudLists
                                        backupViewModel.refreshCloudLists(context)
                                    }
                                }

                                // STEP 1: Initial Warning Dialog
                                if (showCloudDisableWarning) {
                                    AlertDialog(
                                        onDismissRequest = { showCloudDisableWarning = false },
                                        title = { Text("Disable Cloud Storage?") },
                                        text = {
                                            Column(
                                                Modifier.verticalScroll(rememberScrollState())
                                            ) {
                                                // Only show data deletion warnings if they have cloud lists
                                                if (hasCloudLists) {
                                                    Text("⛔ All cloud lists will be permanently deleted", fontSize = 14.sp)
                                                }
                                                Text("👥 Your device will be signed out from Firebase", fontSize = 14.sp)
                                                Text("🛡️ You'll get a brand new device ID if you enable it again", fontSize = 14.sp)

                                                // Only show the "cannot be undone" warning if they have data to lose
                                                if (hasCloudLists) {
                                                    Spacer(Modifier.height(12.dp))
                                                    Text(
                                                        "This action cannot be undone, download and export the lists you want to keep first!",
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        },
                                        confirmButton = {
                                            Button(
                                                onClick = {
                                                    showCloudDisableWarning = false
                                                    // Only show second confirmation if they have cloud data
                                                    if (hasCloudLists) {
                                                        showCloudDisableConfirm = true
                                                    } else {
                                                        // No cloud data - just disable immediately
                                                        cloudBackupEnabled = false
                                                        myApp.savePreference(UserSettings.KEY_CLOUD_BACKUP_ENABLED, false)

                                                        scope.launch {
                                                            val backupViewModel = BackupExportViewModel()
                                                            backupViewModel.deleteAllCloudListsAndSignOut(context)

                                                            Toast.makeText(
                                                                context,
                                                                "Cloud storage disabled!",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = if (hasCloudLists) Color(0xFFFF6B6B) else MaterialTheme.colorScheme.primary
                                                )
                                            ) {
                                                Text("Yes")
                                            }
                                        },
                                        dismissButton = {
                                            OutlinedButton(onClick = {
                                                showCloudDisableWarning = false
                                                showMenu = false
                                            }) {
                                                Text("No")
                                            }
                                        }
                                    )
                                }

                                // STEP 2: Final Confirmation Dialog (only shown if they have cloud lists)
                                if (showCloudDisableConfirm) {
                                    AlertDialog(
                                        onDismissRequest = { showCloudDisableConfirm = false },
                                        title = {
                                            Text(
                                                "⚠️ Final Warning",
                                                fontSize = 20.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        },
                                        text = {
                                            Column {
                                                Text(
                                                    "Are you sure?",
                                                    fontSize = 16.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.padding(bottom = 8.dp)
                                                )
                                                Spacer(Modifier.height(4.dp))
                                                Column(
                                                    Modifier.verticalScroll(rememberScrollState())
                                                ) {
                                                    Text(
                                                        "Disabling cloud storage will delete all your cloud lists and sign you out!",
                                                        fontSize = 14.sp,
                                                        color = Color.Gray
                                                    )
                                                    Spacer(Modifier.height(12.dp))
                                                    Surface(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .background(Color(0x1FFF6B6B), RoundedCornerShape(8.dp))
                                                            .padding(12.dp),
                                                        color = Color.Transparent
                                                    ) {
                                                        Text(
                                                            "You still have lists in cloud storage.\nThis is your last chance to cancel!",
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color(0xFFD32F2F)
                                                        )
                                                    }
                                                }
                                            }
                                        },
                                        confirmButton = {
                                            Button(
                                                onClick = {
                                                    try {
                                                        cloudBackupEnabled = false
                                                        myApp.savePreference(UserSettings.KEY_CLOUD_BACKUP_ENABLED, false)

                                                        scope.launch {
                                                            val backupViewModel = BackupExportViewModel()
                                                            backupViewModel.deleteAllCloudListsAndSignOut(context)

                                                            showCloudDisableConfirm = false

                                                            Toast.makeText(
                                                                context,
                                                                "Cloud storage disabled & lists deleted!",
                                                                Toast.LENGTH_LONG
                                                            ).show()
                                                        }
                                                    } catch (e: Exception) {
                                                        Log.e("CloudStorage", "Error disabling cloud storage", e)
                                                        Toast.makeText(
                                                            context,
                                                            "Error: ${e.message}",
                                                            Toast.LENGTH_LONG
                                                        ).show()
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                                            ) {
                                                Text("Delete")
                                            }
                                        },
                                        dismissButton = {
                                            OutlinedButton(onClick = {
                                                showCloudDisableConfirm = false
                                            }) {
                                                Text("Cancel")
                                            }
                                        }
                                    )
                                }


                                // 2. Permission Reminders (only show if at least one permission missing)
                                val notifGranted = NotificationPermissionHelper.isNotificationPermissionGranted(context)
                                val alarmGranted = NotificationPermissionHelper.isExactAlarmPermissionGranted(context)
                                val bothPermissionsGranted = notifGranted && alarmGranted

                                if (!bothPermissionsGranted) {

                                    // Get the current Activity and cast it to MainActivity
                                    val activity = LocalContext.current as MainActivity

                                    // Use DropdownMenuItem to fit the overflow style
                                    DropdownMenuItem(
                                        // The text content
                                        text = { Text("Permission Reminders") },
                                        // The core action logic when the user clicks anywhere on the item
                                        onClick = {
                                            // Toggle the state
                                            val newState = !permissionRemindersEnabled
                                            permissionRemindersEnabled = newState

                                            // Save the preference states based on the toggle
                                            val doNotAskValue = !newState
                                            myApp.savePreference(NotificationPermissionHelper.KEY_DO_NOT_ASK_PERMISSIONS, doNotAskValue)
                                            myApp.savePreference(NotificationPermissionHelper.KEY_DO_NOT_ASK_EXACT, doNotAskValue)

                                            // Show toast feedback
                                            if (newState) {
                                                //Toast.makeText(context, "Permission reminders re-enabled", Toast.LENGTH_SHORT).show()

                                                // Engage permission flow immediately!
                                                NotificationPermissionHelper.startPermissionFlow(
                                                    activity = activity,
                                                    forceShow = true, // Force the check even if it was just denied
                                                    onShowPrompt = { promptType ->
                                                        // Access the state setter property in MainActivity
                                                        activity.permissionPromptState.value = promptType
                                                    }
                                                )
                                            } else {
                                                Toast.makeText(context, "Permission reminders disabled", Toast.LENGTH_SHORT).show()
                                            }

                                            // Do NOT close the menu here, let the Checkbox handle the visual state change.
                                            // Closing the menu would prevent the user from seeing the Checkbox update.
                                            // showMenu = false
                                        },
                                        // Embed the Checkbox into the trailing content for a clean look
                                        trailingIcon = {
                                            Checkbox(
                                                checked = permissionRemindersEnabled,
                                                // --- ON CHECKED CHANGE (Checkbox only) ---
                                                onCheckedChange = { checked ->
                                                    permissionRemindersEnabled = checked
                                                    val doNotAskValue = !checked

                                                    myApp.savePreference(UserSettings.KEY_DO_NOT_ASK_PERMISSIONS, doNotAskValue)
                                                    myApp.savePreference(UserSettings.KEY_DO_NOT_ASK_EXACT, doNotAskValue)


                                                    if (checked) {
                                                        //Toast.makeText(context, "Permission reminders re-enabled", Toast.LENGTH_SHORT).show()

                                                        // Engage permission flow immediately!
                                                        NotificationPermissionHelper.startPermissionFlow(
                                                            activity = activity,
                                                            forceShow = true,
                                                            onShowPrompt = { promptType ->
                                                                // Access the state setter property in MainActivity
                                                                activity.permissionPromptState.value = promptType
                                                            }
                                                        )
                                                    } else {
                                                        Toast.makeText(context, "Permission reminders disabled", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            )
                                        },
                                        // Add a modifier to ensure the checkbox doesn't steal focus
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                // 3. Perishable List Checkbox
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("Perishable List")
                                            Spacer(Modifier.width(8.dp))
                                            Checkbox(
                                                checked = myApp.perishableFilterEnabled.value,
                                                onCheckedChange = { isChecked ->
                                                    myApp.perishableFilterEnabled.value = isChecked
                                                    myApp.savePreference(UserSettings.KEY_PERISHABLE_ENABLED, isChecked)
                                                }
                                            )
                                        }
                                    },
                                    onClick = {
                                        val newState = !perishableFilterEnabled
                                        perishableFilterEnabled = newState
                                        myApp.perishableFilterEnabled.value = newState
                                        myApp.savePreference(UserSettings.KEY_PERISHABLE_ENABLED, newState)
                                    }
                                )

                                // 4. Scan Beep Checkbox
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("Scan Beep")
                                            Spacer(Modifier.width(8.dp))
                                            Checkbox(
                                                checked = myApp.scanBeepEnabled.value,
                                                onCheckedChange = {
                                                    myApp.scanBeepEnabled.value = it
                                                    myApp.savePreference(UserSettings.KEY_SCAN_BEEP_ENABLED, it)
                                                }
                                            )
                                        }
                                    },
                                    onClick = {
                                        val newState = !myApp.scanBeepEnabled.value
                                        myApp.scanBeepEnabled.value = newState
                                        myApp.savePreference(UserSettings.KEY_SCAN_BEEP_ENABLED, newState)
                                    }
                                )
                                HorizontalDivider(thickness = 0.3.dp, color = Color(0x80645275))

                                // 5. Sorting Options
                                listOf("A-Z", "Z-A", "Old-New", "New-Old", "Least-Most", "Most-Least", "Order Scanned").forEach { sortOption ->
                                    DropdownMenuItem(
                                        text = { Text("Sort $sortOption") },
                                        onClick = {
                                            sortItems(sortOption)
                                            showMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                )
            },
            // FLOATING ACTION BUTTON
            floatingActionButton = {

                FloatingActionButton(onClick = onScanClicked) {

                    Column(Modifier.padding(5.dp))
                    {
                        Icon(
                            painter = painterResource(R.drawable.qr_code_scanner),
                            contentDescription = "Scan Item"
                        )
                    }
                }
            }

        ) { paddingValues ->
            // ----------------------------------------------------
            // CONTENT AREA (Padding applied from Scaffold)
            // ----------------------------------------------------
            Column(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize()
                    .padding(horizontal = 16.dp) // Apply horizontal padding to align with other content
            ) {
                // Search Bar
                SearchBar(
                    // The search text you want to display
                    query = searchText,
                    // The text that appears when the query is empty
                    placeholder = { Text("Search List") },
                    // A fixed value that prevents the search bar from expanding
                    active = false,
                    // The function to call when the query text changes
                    onQueryChange = { searchText = it },
                    // The function to call when the search button is pressed (not typically used here)
                    onSearch = { },
                    // A lambda that runs when the active state changes (but we keep it false)
                    onActiveChange = { /* Do nothing to prevent expansion */ },

                    // Add the Search Icon as the leading icon
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = "Search Icon")
                    },

                    // Add the Clear Button as the trailing icon
                    trailingIcon = {
                        // Only show the clear button if there is text to clear
                        if (searchText.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    // Clear the search text on click
                                    searchText = ""
                                }
                            ) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear Search")
                            }
                        }
                    },

                    // Apply modifiers for full width and padding
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)

                ) {
                    // The content lambda is empty because 'active' is false, so it will never show.
                }

                // LazyColumn List - Now uses the filtered list
                if (filteredItems.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(filteredItems, key = { it.id }) { item ->
                            InventoryItemRow(item)
                        }
                    }
                } else if (inventoryListState.isNotEmpty() && filteredItems.isEmpty()) {
                    // Show this message if the search yielded no match
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No results found for \"$searchText\"")
                    }
                } else {
                    // Show this message if items exist but the search yielded no results
                    // Show this message if the master list is empty
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No items yet")
                    }
                }
            }
        }

        // --- Confirmation Dialog for Clearing Working List ---
        if (showClearDialog) {

            if (!myApp.itemsList.isEmpty())
            {
                AlertDialog(
                    onDismissRequest = { showClearDialog = false }, // Close dialog on outside tap
                    title = { Text("Clear List") },
                    text =
                        {
                            Column(Modifier.verticalScroll(rememberScrollState()))
                            {
                                Text("Are you sure you want to clear ALL items from the working list? This action cannot be undone.")
                            }
                        },
                    confirmButton = {
                        Button(
                            onClick = {
                                myApp.itemsList.clear() // Execute the actual clear operation
                                myApp.nextID.value = 0

                                // Debug line for item IDs
                                //Log.d("ID Assigned", "myApp.nexID is now ${myApp.getCurrentID()}")

                                showClearDialog = false  // Close the dialog
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0x9AFF1B1B))
                        ) {
                            Text("Clear")
                        }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = { showClearDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
            else
            {
                AlertDialog(
                    onDismissRequest = { showClearDialog = false },
                    title = { Text("Nothing To Clear!") },
                    text = { Column (Modifier.verticalScroll(rememberScrollState()))
                    { Text("There's nothing in the list!") } },
                    confirmButton = {
                        Button(onClick =
                            {
                                val maxId = myApp.itemsList.maxOfOrNull { it.id } ?: 0
                                myApp.nextID.value = 0

                                // Debug line for item IDs
                                //Log.d("ID Assigned", "myApp.nexID is now ${myApp.getCurrentID()}")

                                showClearDialog = false
                            }
                        ) { Text("OK") }
                    }
                )

            }
        }

        // In MainActivity.kt, at the end of WorkingListScreen (before the final closing '}')

        // --- UPDATED Load List Dialog (List-based Selection with Empty Check) ---
        if (showLoadDialog) {
            val dialogHeight = (availableLists.size * 56).coerceIn(150, 450)

            AlertDialog(
                onDismissRequest = { showLoadDialog = false },
                title = { Text("Load List", fontWeight = FontWeight.Bold) },
                text = {
                    // 💡 FIX: Wrap the list content in a Surface to apply rounded corners
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp, max = dialogHeight.dp.coerceAtLeast(300.dp)),
                        // Apply standard medium rounded shape to the container
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        if (availableLists.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    "No saved lists found.",
                                    color = Color.Gray
                                )
                            }
                        } else {
                            LazyColumn(
                                // Add some internal padding
                                contentPadding = PaddingValues(vertical = 4.dp)
                            ) {
                                items(availableLists) { listName ->
                                    ListItem(
                                        headlineContent = { Text(listName) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            // 💡 LIST ITEM FIX: Ensure the item itself has no color override
                                            // and relies on the Surface color and ripple for feedback
                                            .clickable {
                                                listToLoad = listName
                                                showLoadDialog = false

                                                if (inventoryListState.isNotEmpty()) {
                                                    showLoadConfirmDialog = true
                                                } else {
                                                    scope.launch {
                                                        val loadedList = myApp.loadWorkingList(listName)
                                                        if (loadedList != null) {
                                                            myApp.itemsList.clear()
                                                            myApp.itemsList.addAll(loadedList.items)

                                                            myApp.perishableFilterEnabled.value = loadedList.perishable

                                                            val maxId = loadedList.items.maxOfOrNull { it.id } ?: 0
                                                            myApp.setNextID(maxId)

                                                            // Debug line for item IDs
                                                            //Log.d("ID Assigned", "myApp.nexID is now ${myApp.getCurrentID()}")

                                                            Toast.makeText(context, "$listName loaded successfully.", Toast.LENGTH_SHORT).show()
                                                        } else {
                                                            Toast.makeText(context, "File '$listName' not found or is corrupt.", Toast.LENGTH_LONG).show()
                                                        }
                                                    }
                                                }
                                            }
                                            .padding(horizontal = 8.dp) // Maintain inner spacing
                                    )
                                    HorizontalDivider(thickness = 0.3.dp, color = Color(0x80645275))
                                    //Spacer(modifier = Modifier.height(1.dp))
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    OutlinedButton(onClick = { showLoadDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // --- Load Confirmation Dialog ---
        if (showLoadConfirmDialog) {
            AlertDialog(
                onDismissRequest = {
                    showLoadConfirmDialog = false
                    listToLoad = "" // Clear temporary state on outside click/dismiss
                },
                title = { Text("Confirm Load List") },
                text = { Text("Are you sure you want to load '$listToLoad'? This action will overwrite your current list.") },
                confirmButton = {
                    Button(
                        onClick = {
                            showLoadConfirmDialog = false
                            // Execute the actual load logic using the stored listToLoad
                            scope.launch {
                                val loadedList = myApp.loadWorkingList(listToLoad)
                                if (loadedList != null) {
                                    // EXECUTION: Clear current list and add new items
                                    myApp.itemsList.clear()
                                    myApp.itemsList.addAll(loadedList.items)

                                    // Update perishables filter state
                                    myApp.perishableFilterEnabled.value = loadedList.perishable
                                    perishableFilterEnabled = loadedList.perishable

                                    val maxId = loadedList.items.maxOfOrNull { it.id } ?: 0
                                    myApp.setNextID(maxId)

                                    // Debug line for item IDs
                                    //Log.d("ID Assigned", "myApp.nexID is now ${myApp.getCurrentID()}")

                                    Toast.makeText(context, "$listToLoad loaded successfully.", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "File '$listToLoad' not found or is corrupt.", Toast.LENGTH_LONG).show()
                                }
                                listToLoad = "" // Clear temporary state
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFDA8D)) // Light Orange/Yellow
                    ) {
                        Text("Load List")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = {
                        showLoadConfirmDialog = false
                        listToLoad = "" // Clear temporary state on cancel
                    }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // --- Save List Dialog (Handles file name input and checks for overwrite) ---
        if (showSaveDialog) {
            var expanded by remember { mutableStateOf(false) }

            // Refresh available lists when dialog opens
            LaunchedEffect(showSaveDialog) {
                if (showSaveDialog) {
                    availableLists = myApp.getSavedListNames()
                }
            }

            AlertDialog(
                onDismissRequest = { showSaveDialog = false },
                title = { Text("Save List") },
                text = {
                    Column(
                        Modifier.verticalScroll(rememberScrollState())
                    ) {
                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = it }
                        ) {
                            OutlinedTextField(
                                value = listNameInput,
                                onValueChange = { listNameInput = it },
                                label = { Text("List Name") },
                                placeholder = { Text("'Pantry 1', 'Books', 'Movies', etc.") },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor()
                            )

                            // Only show dropdown if there are saved lists
                            if (availableLists.isNotEmpty()) {
                                ExposedDropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false }
                                ) {
                                    availableLists.forEach { listName ->
                                        DropdownMenuItem(
                                            text = { Text(listName) },
                                            onClick = {
                                                listNameInput = listName
                                                expanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Optional: Hint if the list is empty
                        if (inventoryListState.isEmpty()) {
                            Text(
                                "List is empty. Cannot save.",
                                color = Color.Red,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showSaveDialog = false
                            scope.launch {
                                val file = ListFileManager.getListFile(context, listNameInput)
                                if (file.exists()) {
                                    showOverwriteDialog = true
                                } else {
                                    myApp.saveWorkingList(listNameInput)
                                    Toast.makeText(context, "List saved as '$listNameInput'.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = listNameInput.isNotBlank() && inventoryListState.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF92AEFF))
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showSaveDialog = false }) { Text("Cancel") }
                }
            )
        }

        // Overwrite Dialog ---
        if (showOverwriteDialog) {
            AlertDialog(
                onDismissRequest = { showOverwriteDialog = false },
                title = { Text("Confirm Overwrite") },
                text = { Text("A saved list named '$listNameInput' already exists. Do you want to replace it?") },
                confirmButton = {
                    Button(
                        onClick = {
                            scope.launch {
                                // Execute the file OVERWRITE logic
                                myApp.saveWorkingList(listNameInput)

                                Toast.makeText(context, "List '$listNameInput' overwritten.", Toast.LENGTH_SHORT).show()
                            }
                            showOverwriteDialog = false // Close the dialog
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF92AEFF))
                    ) {
                        Text("Overwrite")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showOverwriteDialog = false }) { Text("Cancel") }
                }
            )
        }

        // --- UPDATED Delete Dialog with Dropdown and Confirmation ---
        if (showDeleteDialog) {
            var showDeleteConfirmation by remember { mutableStateOf(false) }
            var selectedListToDelete by remember { mutableStateOf("") }
            var dropdownExpanded by remember { mutableStateOf(false) }

            // Refresh the list when the dialog opens OR when returning from confirmation
            LaunchedEffect(showDeleteDialog, showDeleteConfirmation) {
                if (showDeleteDialog && !showDeleteConfirmation) {
                    availableLists = myApp.getSavedListNames()
                }
            }

            if (!showDeleteConfirmation) {
                // First dialog: Select list with dropdown
                AlertDialog(
                    onDismissRequest = {
                        showDeleteDialog = false
                        listNameInput = ""
                    },
                    title = { Text("Delete Saved List") },
                    text = {
                        Column (
                            Modifier.verticalScroll(rememberScrollState())
                        ){
                            //Text("Select the list to delete:")
                            //Spacer(modifier = Modifier.height(8.dp))

                            if (availableLists.isEmpty()) {
                                Text("No saved lists found.", color = Color.Gray)
                            } else {
                                // Simple dropdown button
                                // Text field style
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                                        .clickable { dropdownExpanded = true },
                                    color = Color.Transparent
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(if (listNameInput.isBlank()) "Select a list..." else listNameInput)
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                    }
                                }

                                // Dropdown menu
                                Box {
                                    DropdownMenu(
                                        expanded = dropdownExpanded,
                                        onDismissRequest = { dropdownExpanded = false },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        availableLists.forEach { listName ->
                                            DropdownMenuItem(
                                                text = { Text(listName) },
                                                onClick = {
                                                    listNameInput = listName
                                                    dropdownExpanded = false
                                                }
                                            )
                                        }
                                    }
                                    /**
                                    Text(
                                        "Available Lists: ${availableLists.joinToString(", ")}",
                                        fontSize = 12.sp,
                                        //modifier = Modifier.verticalScroll(rememberScrollState()),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    **/
                                }
                            }

                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                selectedListToDelete = listNameInput
                                showDeleteConfirmation = true
                            },
                            enabled = listNameInput.isNotBlank() && availableLists.contains(listNameInput),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xE6FF4040))
                        ) {
                            Text("Delete")
                        }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = {
                            showDeleteDialog = false
                            listNameInput = ""
                        }) {
                            Text("Cancel")
                        }
                    }
                )
            } else {
                // Second dialog: Confirm deletion
                AlertDialog(
                    onDismissRequest = {
                        showDeleteConfirmation = false
                        showDeleteDialog = false
                        listNameInput = ""
                    },
                    title = { Text("Confirm Deletion") },
                    text = {
                        Text("Are you sure you want to permanently delete '$selectedListToDelete'? This action cannot be undone.")
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                scope.launch {
                                    val success = myApp.deleteSavedList(selectedListToDelete)
                                    if (success) {
                                        Toast.makeText(
                                            context,
                                            "List '$selectedListToDelete' deleted.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "Error: Could not delete '$selectedListToDelete'.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                                showDeleteConfirmation = false

                                // DO NOT Close the Delete list dialog right away
                                // so users can continue deleting lists:
                                //showDeleteDialog = false

                                listNameInput = ""
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xE6FF4040))
                        ) {
                            Text("Delete")
                        }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = {
                            showDeleteConfirmation = false
                            showDeleteDialog = false
                            listNameInput = ""
                        }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }

        val importedList = myApp.importedListToSave.value
        if (importedList != null) {
            AlertDialog(
                onDismissRequest = { myApp.importedListToSave.value = null },
                title = { Text("Import Successful") },
                text = { Text("You just imported a list with ${importedList.items.size} items. Would you like to save it now?") },
                confirmButton = {
                    Button(
                        onClick = {
                            myApp.importedListToSave.value = null // Dismiss dialog
                            listNameInput = ""

                            //listNameInput = importedList.items.firstOrNull()?.name ?: "" // Suggest a name based on the first item
                            // Use the suggested name from MyApplication
                            listNameInput = myApp.suggestedListName.value

                            //THIS IS WHERE I WILL REPLACE THE LINE ABOVE AND ASSIGN listNameInput by calling "getImportedListName" instead!


                            showSaveDialog = true // Open the existing save dialog
                        }
                    ) {
                        Text("Save Now")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { myApp.importedListToSave.value = null }) {
                        Text("Not Now")
                    }
                }
            )
        }
    }

    // --- Custom Expiration Colors ---
    // The colors you provided, converted to Compose Color objects (HTML #RRGGBB)
    private val ColorFreshStart = Color(0xFFEEEA29) // Lime Yellow/Green (Just starting to go)
    private val ColorMidGone = Color(0xFFFEBF18)    // Sun Yellow/Orange (Middle of going)
    private val ColorExpired = Color(0x5CF31F06)    // Deep Red (Expired)
    private val ColorNormalBackground = Color.Transparent // Default background color

    /**
     * Calculates the background color for the row based on the item's expiration status.
     * Implements a gradient effect between FreshStart, MidGone, and Expired states.
     * @param item The inventory item to check.
     * @return The calculated background Color.
     */
    private fun getExpirationColor(item: InventoryItem): Color {
        // Check if the item is explicitly expired (today or past)
        if (item.isExpired) {
            return ColorExpired.copy(alpha = 0.4f) // Solid Red tint for expired
        }

        // Days between today and the "expiring soon" date (expDate - daysNotice)
        val noticeDate = item.expSoonDate
        val today = LocalDate.now()

        // Check if the item is within the "days notice" window
        if (item.isExpiringSoon) {
            val totalNoticeDays = item.daysNotice.toFloat()
            // Days remaining until the expiration date
            val daysUntilExpiration = ChronoUnit.DAYS.between(today, item.expDate).toFloat()

            // Calculate the position within the warning window: 0.0 (just entered window) to 1.0 (expiration date)
            val interpolationFactor = 1f - (daysUntilExpiration / totalNoticeDays)

            // Define the gradient points:
            // T=0.0 (Start of notice window) -> FreshStart (Lime)
            // T=0.5 (Halfway through window) -> MidGone (Sun Yellow)
            // T=1.0 (Expiration day) -> Expired (Red)

            val color = when {
                interpolationFactor <= 0.5f -> {
                    // Interpolate between FreshStart (0.0) and MidGone (0.5)
                    // The factor is scaled from [0.0, 0.5] to [0.0, 1.0] for the lerp function
                    lerp(ColorFreshStart, ColorMidGone, interpolationFactor * 2f)
                }
                else -> {
                    // Interpolate between MidGone (0.5) and Expired (1.0)
                    // The factor is scaled from [0.5, 1.0] to [0.0, 1.0] for the lerp function
                    lerp(ColorMidGone, ColorExpired, (interpolationFactor - 0.5f) * 2f)
                }
            }
            // Apply a low alpha to keep it a subtle background tint
            return color.copy(alpha = 0.4f)
        }

        return ColorNormalBackground
    }



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryItemRow(item: InventoryItem) {
    val context = LocalContext.current
    val myApp = context.applicationContext as MyApplication
    val scope = rememberCoroutineScope()

    // State for delete confirmation
    var showDeleteDialog by remember { mutableStateOf(false) }
    var itemToDelete by remember { mutableStateOf<InventoryItem?>(null) }
    var keepSwipedDuringDialog by remember { mutableStateOf(false) }

    // Check the global perishable state
    val isPerishableEnabled by remember { myApp.perishableFilterEnabled }

    // Determine the background color (for expiration)
    val backgroundColor = if (isPerishableEnabled) {
        getExpirationColor(item)
    } else {
        ColorNormalBackground
    }

    // Swipe-to-dismiss state with lower threshold so it triggers sooner
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { dismissValue ->
            if (dismissValue == SwipeToDismissBoxValue.EndToStart) {
                // Store item and show dialog
                itemToDelete = item
                showDeleteDialog = true
                keepSwipedDuringDialog = true
                false // Return false to prevent auto-dismiss
            } else {
                false
            }
        },
        positionalThreshold = { distance -> distance * 0.37f } // Lower threshold
    )

    // Calculate swipe progress - only when targetValue indicates a swipe is happening
    val swipeProgress = if (keepSwipedDuringDialog) {
        1f // Keep it fully swiped during dialog
    } else if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) {
        // Actively swiping toward delete
        dismissState.progress.coerceIn(0f, 1f)
    } else {
        0f // Not swiping
    }

    // Card opacity follows swipe progress
    val cardAlpha = 1f - (swipeProgress * 0.7f)

    // Background icon visibility
    val iconAlpha = swipeProgress.coerceIn(0f, 1f)

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            // Simple background with delete icon (no red background)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = Color(0xFFFF4040).copy(alpha = iconAlpha),
                    modifier = Modifier.size(40.dp)
                )
            }
        },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true
    ) {
        val clipboard = LocalClipboardManager.current

        @OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .graphicsLayer {
                    // Apply alpha to the card based on swipe
                    alpha = cardAlpha
                }
                //.clickable {}
                .combinedClickable(
                    onClick =
                        {
                            val intent = Intent(context, EditActivity::class.java).apply {
                                putExtra("itemId", item.id)
                            }
                            context.startActivity(intent)
                        },
                    onLongClick =
                        {
                            scope.launch{
                                // Copy the item's bar code to the clipboard on long press:
                                clipboard.setText(AnnotatedString(item.code))
                                Toast.makeText(context, "Copied: ${item.code}", Toast.LENGTH_SHORT).show()
                            }
                        }
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(backgroundColor) // Keep your expiration color
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.titleMedium
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (isPerishableEnabled) {
                            // DATE DISPLAY
                            Text("📅 ${item.expDate}")
                            // DAYS NOTICE DISPLAY
                            Text("⏳ ${item.daysNotice}")
                        }
                        // QUANTITY DISPLAY (always shown)
                        Text("🧮 ${item.quantity}")
                    }
                }
            }
        }
    }

    // Delete Confirmation Dialog
    if (showDeleteDialog && itemToDelete != null) {
        AlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                itemToDelete = null
                keepSwipedDuringDialog = false
                scope.launch { dismissState.reset() }
            },
            title = { Text("Delete Item") },
            text = { Text("Are you sure you want to delete '${itemToDelete?.name}'? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            itemToDelete?.let { myApp.repository.deleteItem(it) }
                            showDeleteDialog = false
                            itemToDelete = null
                            keepSwipedDuringDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4040))
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    showDeleteDialog = false
                    itemToDelete = null
                    keepSwipedDuringDialog = false
                    scope.launch { dismissState.reset() }
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}