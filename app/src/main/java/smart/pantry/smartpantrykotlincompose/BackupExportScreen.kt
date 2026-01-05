// File: smart/pantry/smartpantrykotlincompose/BackupExportScreen.kt

package smart.pantry.smartpantrykotlincompose

import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachEmail
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.ViewModel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import smart.pantry.smartpantrykotlincompose.ui.theme.Purple40
import smart.pantry.smartpantrykotlincompose.ui.theme.Purple80


// --- Custom Colors based on user icons ---
val ColorCloudBackup = Color(0xFFE2BCFF) // Light Purple/Pink
val ColorExportFile = Color(0xFF92AEFF) // Light Blue
val ColorDelete = Color(0xFF727272) // Gray


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupExportScreen(
    viewModel: BackupExportViewModel = viewModel(),
    activity: Activity
) {
    val context = LocalContext.current
    val myApp = context.applicationContext as MyApplication

    val cloudBackupEnabled = myApp.getPreference(
        UserSettings.KEY_CLOUD_BACKUP_ENABLED,
        false
    )

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val localLists by viewModel.localLists.collectAsState()
    val cloudLists by viewModel.cloudLists.collectAsState()
    val isUserSignedIn by viewModel.isUserSignedIn.collectAsState()

    var listToDelete by remember { mutableStateOf<String?>(null) }
    var listToExport by remember { mutableStateOf<String?>(null) }
    var listToUpload by remember { mutableStateOf<String?>(null) }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showUploadDialog by remember { mutableStateOf(false) }
    var selectedLocalList by remember { mutableStateOf<String?>(null) }

    var showUploadOverwriteDialog by remember { mutableStateOf(false) }
    var showDownloadOverwriteDialog by remember { mutableStateOf(false) }
    var listToDownload by remember { mutableStateOf("") }
    var selectedCloudList by remember { mutableStateOf<String?>(null) }

    // Automatic cloud sign-in
    LaunchedEffect(cloudBackupEnabled) {
        if (cloudBackupEnabled && !isUserSignedIn) {
            viewModel.signInAnonymously(context)
        }
    }

    Scaffold(
        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
        topBar = {
            TopAppBar(
                title = { Text("Backup & Export") },
                navigationIcon = {
                    IconButton(onClick = { activity.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        if (isLandscape && cloudBackupEnabled) {
            // ========================================
            // LANDSCAPE + CLOUD ENABLED: Side by side
            // ========================================
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // LOCAL STORAGE COLUMN
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Device Internal Storage",
                        fontSize = 18.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    LocalListDisplay(
                        localLists = localLists,
                        onSelect = { listName -> selectedLocalList = listName },
                        onUploadRequest = { listName ->
                            listToUpload = listName
                            showUploadDialog = true
                        },
                        onExportRequest = { listName ->
                            listToExport = listName
                            viewModel.exportSelectedList(context, listToExport!!)
                        },
                        isCloudEnabled = cloudBackupEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                    )
                }

                // CLOUD STORAGE COLUMN
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Firebase Cloud Storage",
                        fontSize = 18.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    val isUserSignedIn by viewModel.isUserSignedIn.collectAsState()

                    CloudListDisplay(
                        cloudLists = cloudLists,
                        isUserSignedIn = isUserSignedIn,
                        viewModel = viewModel,
                        onDeleteRequest = { listName ->
                            listToDelete = listName
                            showDeleteDialog = true
                        },
                        onDownloadRequest = { listName ->  // ADD THIS
                            // Check if this list already exists locally
                            if (localLists.contains(listName)) {
                                listToDownload = listName
                                showDownloadOverwriteDialog = true
                            } else {
                                // No conflict, download directly
                                viewModel.downloadListFromCloud(context, listName)
                            }
                        },
                        context = context,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                    )

                    val userId = Firebase.auth.currentUser?.uid ?: "Not Signed In"
                    SelectionContainer {
                        Text(
                            "User ID: $userId",
                            fontSize = 10.sp,
                            color = Color.Gray,
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        )
                    }
                }
            }

            // DIALOGS FOR LANDSCAPE
            if (showUploadDialog && listToUpload != null) {
                AlertDialog(
                    onDismissRequest = { showUploadDialog = false },
                    title = { Text("Upload List? 📤") },
                    text = { Text("Are you sure you want to upload '${listToUpload}' to the cloud?") },
                    confirmButton = {
                        TextButton(onClick = {
                            // Check if this list already exists in the cloud
                            if (cloudLists.contains(listToUpload)) {
                                // Show overwrite dialog and close this one
                                showUploadOverwriteDialog = true
                                showUploadDialog = false
                            } else {
                                // No conflict, upload directly
                                listToUpload?.let { viewModel.uploadListToCloud(context, it) }
                                showUploadDialog = false
                                listToUpload = null
                                selectedLocalList = null
                            }
                        }, enabled = listToUpload != null && isUserSignedIn) { Text("Yes") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showUploadDialog = false
                            listToUpload = null
                        }) { Text("No") }
                    }
                )
            }

            if (showDeleteDialog && listToDelete != null) {
                AlertDialog(
                    onDismissRequest = { showDeleteDialog = false },
                    title = { Text("Delete List") },
                    text = { Text("Are you sure you want to delete '${listToDelete}'?") },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.deleteCloudList(context, listToDelete!!)
                            showDeleteDialog = false
                            listToDelete = null
                            selectedLocalList = null
                        }) { Text("Yes") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showDeleteDialog = false
                            listToDelete = null
                        }) { Text("No") }
                    }
                )
            }

            // Upload Overwrite Confirmation Dialog (LANDSCAPE)
            if (showUploadOverwriteDialog && listToUpload != null) {
                AlertDialog(
                    onDismissRequest = {
                        showUploadOverwriteDialog = false
                        listToUpload = null
                    },
                    title = { Text("Confirm Overwrite") },
                    text = {
                        Text("A cloud list named '$listToUpload' already exists. Do you want to replace it?")
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                listToUpload?.let { viewModel.uploadListToCloud(context, it) }
                                showUploadOverwriteDialog = false
                                showUploadDialog = false
                                listToUpload = null
                                selectedLocalList = null
                            }
                        ) {
                            Text("Overwrite")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showUploadOverwriteDialog = false
                            listToUpload = null
                        }) {
                            Text("Cancel")
                        }
                    }
                )
            }

// Download Overwrite Confirmation Dialog (LANDSCAPE)
            if (showDownloadOverwriteDialog && listToDownload.isNotEmpty()) {
                AlertDialog(
                    onDismissRequest = {
                        showDownloadOverwriteDialog = false
                        listToDownload = ""
                    },
                    title = { Text("Confirm Overwrite") },
                    text = {
                        Text("A local list named '$listToDownload' already exists. Do you want to replace it?")
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                if (listToDownload.isNotEmpty()) {
                                    viewModel.downloadListFromCloud(context, listToDownload)
                                }
                                showDownloadOverwriteDialog = false
                                listToDownload = ""
                                selectedCloudList = null
                            }
                        ) {
                            Text("Overwrite")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showDownloadOverwriteDialog = false
                            listToDownload = ""
                        }) {
                            Text("Cancel")
                        }
                    }
                )
            }

        } else {
            // ========================================
            // PORTRAIT OR CLOUD DISABLED: Stack vertically
            // ========================================
            Column(
                modifier = Modifier
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // LOCAL STORAGE SECTION
                Text(
                    "Device Internal Storage",
                    fontSize = 18.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                LocalListDisplay(
                    localLists = localLists,
                    onSelect = { listName -> selectedLocalList = listName },
                    onUploadRequest = { listName ->
                        listToUpload = listName
                        showUploadDialog = true
                    },
                    onExportRequest = { listName ->
                        listToExport = listName
                        viewModel.exportSelectedList(context, listToExport!!)
                    },
                    isCloudEnabled = cloudBackupEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                )

                // CLOUD STORAGE SECTION (conditional)
                if (cloudBackupEnabled) {
                    Text(
                        "Firebase Cloud Storage",
                        fontSize = 18.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    val isUserSignedIn by viewModel.isUserSignedIn.collectAsState()

                    CloudListDisplay(
                        cloudLists = cloudLists,
                        isUserSignedIn = isUserSignedIn,
                        viewModel = viewModel,
                        onDeleteRequest = { listName ->
                            listToDelete = listName
                            showDeleteDialog = true
                        },
                        onDownloadRequest = { listName ->  // ADD THIS
                            // Check if this list already exists locally
                            if (localLists.contains(listName)) {
                                listToDownload = listName
                                showDownloadOverwriteDialog = true
                            } else {
                                // No conflict, download directly
                                viewModel.downloadListFromCloud(context, listName)
                            }
                        },
                        context = context,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                    )

                    val userId = Firebase.auth.currentUser?.uid ?: "Not Signed In"
                    SelectionContainer {
                        Text(
                            "User ID: $userId",
                            fontSize = 10.sp,
                            color = Color.Gray,
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        )
                    }
                } else {
                    val userId = Firebase.auth.currentUser?.uid ?: "Not Signed In"
                    SelectionContainer {
                        Text(
                            "User ID: $userId",
                            fontSize = 10.sp,
                            color = Color.Gray,
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // DIALOGS FOR PORTRAIT
            if (showUploadDialog && listToUpload != null) {
                AlertDialog(
                    onDismissRequest = { showUploadDialog = false },
                    title = { Text("Upload List? 📤") },
                    text = { Text("Are you sure you want to upload '${listToUpload}' to the cloud?") },
                    confirmButton = {
                        TextButton(onClick = {
                            // Check if this list already exists in the cloud
                            if (cloudLists.contains(listToUpload)) {
                                // Show overwrite dialog and close this one
                                showUploadOverwriteDialog = true
                                showUploadDialog = false
                            } else {
                                // No conflict, upload directly
                                listToUpload?.let { viewModel.uploadListToCloud(context, it) }
                                showUploadDialog = false
                                listToUpload = null
                                selectedLocalList = null
                            }
                        }, enabled = listToUpload != null && isUserSignedIn) { Text("Yes") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showUploadDialog = false
                            listToUpload = null
                        }) { Text("No") }
                    }
                )
            }

            if (showDeleteDialog && listToDelete != null) {
                AlertDialog(
                    onDismissRequest = { showDeleteDialog = false },
                    title = { Text("Delete List") },
                    text = { Text("Are you sure you want to delete '${listToDelete}'?") },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.deleteCloudList(context, listToDelete!!)
                            showDeleteDialog = false
                            listToDelete = null
                            selectedLocalList = null
                        }) { Text("Yes") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showDeleteDialog = false
                            listToDelete = null
                        }) { Text("No") }
                    }
                )
            }

            // Upload Overwrite Confirmation Dialog
            if (showUploadOverwriteDialog && listToUpload != null) {
                AlertDialog(
                    onDismissRequest = {
                        showUploadOverwriteDialog = false
                        listToUpload = null
                    },
                    title = { Text("Confirm Overwrite") },
                    text = {
                        Text("A cloud list named '$listToUpload' already exists. Do you want to replace it?")
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                // Execute the upload/overwrite
                                listToUpload?.let { viewModel.uploadListToCloud(context, it) }
                                showUploadOverwriteDialog = false
                                showUploadDialog = false
                                listToUpload = null
                                selectedLocalList = null
                            }
                        ) {
                            Text("Overwrite")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showUploadOverwriteDialog = false
                            listToUpload = null
                        }) {
                            Text("Cancel")
                        }
                    }
                )
            }

// Download Overwrite Confirmation Dialog
            if (showDownloadOverwriteDialog && listToDownload.isNotEmpty()) {
                AlertDialog(
                    onDismissRequest = {
                        showDownloadOverwriteDialog = false
                        listToDownload = ""
                    },
                    title = { Text("Confirm Overwrite") },
                    text = {
                        Text("A local list named '$listToDownload' already exists. Do you want to replace it?")
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                // Execute the download/overwrite
                                if (listToDownload.isNotEmpty()) {
                                    viewModel.downloadListFromCloud(context, listToDownload)
                                }
                                showDownloadOverwriteDialog = false
                                listToDownload = ""
                                selectedCloudList = null
                            }
                        ) {
                            Text("Overwrite")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showDownloadOverwriteDialog = false
                            listToDownload = ""
                        }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}

// ----------------------------------------------------
// Reusable List Composable for Internal Storage
// ----------------------------------------------------

@Composable
fun LocalListDisplay(
    localLists: List<String>,
    onSelect: (String) -> Unit,
    onUploadRequest: (String) -> Unit,
    onExportRequest: (String) -> Unit,
    isCloudEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    if (localLists.isEmpty()) {
        Text(
            "No lists saved locally.",
            color = Color.Gray,
            modifier = Modifier.padding(top = 8.dp)
        )
        return
    }

    Column(
        modifier = modifier
            .border(width = 0.3.dp, color = Color(0x80645275))
            .verticalScroll(rememberScrollState())
    ) {
        localLists.forEach { listName ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(listName) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = listName,
                            maxLines = 1,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        )
                    }
                }

                Row {
                    if (isCloudEnabled) {
                        IconButton(onClick = { onUploadRequest(listName) }) {
                            Icon(
                                Icons.Filled.Upload,
                                contentDescription = "Upload List",
                                tint = ColorCloudBackup
                            )
                        }
                    }

                    IconButton(onClick = { onExportRequest(listName) }) {
                        Icon(
                            Icons.Filled.Send,
                            contentDescription = "Export List",
                            tint = ColorExportFile
                        )
                    }
                }
            }
            HorizontalDivider(thickness = 0.3.dp, color = Color(0x80645275))
        }
    }
}

// MODIFY CloudListDisplay to accept callback and track selection:
@Composable
fun CloudListDisplay(
    cloudLists: List<String>,
    isUserSignedIn: Boolean,
    onDeleteRequest: (String) -> Unit,
    onDownloadRequest: (String) -> Unit,  // ADD THIS PARAMETER
    viewModel: BackupExportViewModel,
    context: Context,
    modifier: Modifier = Modifier
) {
    if (cloudLists.isEmpty()) {
        Text(
            "No lists saved in cloud storage.",
            color = Color.Gray,
            modifier = Modifier.padding(top = 8.dp)
        )
        return
    }

    Column(
        modifier = modifier
            .border(width = 0.3.dp, color = Color(0x80645275))
            .verticalScroll(rememberScrollState())
    ) {
        cloudLists.forEach { listName ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = listName,
                            maxLines = 1,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        )
                    }
                }

                Row {
                    IconButton(onClick = {
                        onDownloadRequest(listName)  // USE THE CALLBACK
                    }) {
                        Icon(
                            Icons.Filled.Download,
                            contentDescription = "Download to Device",
                            tint = ColorExportFile
                        )
                    }

                    IconButton(onClick = {
                        onDeleteRequest(listName)
                    }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete Cloud List",
                            tint = ColorDelete
                        )
                    }
                }
            }
            HorizontalDivider(thickness = 0.3.dp, color = Color(0x80645275))
        }
    }
}