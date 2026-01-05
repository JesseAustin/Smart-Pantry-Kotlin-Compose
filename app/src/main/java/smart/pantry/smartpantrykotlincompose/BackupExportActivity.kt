// File: smart/pantry/smartpantrykotlincompose/BackupExportActivity.kt

package smart.pantry.smartpantrykotlincompose

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import smart.pantry.smartpantrykotlincompose.ui.theme.SmartPantryKotlinComposeTheme

class BackupExportActivity : ComponentActivity() {

    private val viewModel: BackupExportViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SmartPantryKotlinComposeTheme {

                // Your app's main theme/wrapper (e.g., SmartPantryKotlinComposeTheme)
                // Replace with your actual theme composable name
                // SmartPantryKotlinComposeTheme {
                BackupExportScreen(viewModel = viewModel, activity = this)
                // }
            }
        }
        // Anonymous sign-in attempt
        if (Firebase.auth.currentUser == null) {
            viewModel.signInAnonymously(this)
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh the list of local files whenever the screen comes into focus
        viewModel.refreshLocalLists(applicationContext)

        // Only refresh cloud lists if cloud backup is enabled
        val cloudBackupEnabled = myApp.getPreference(UserSettings.KEY_CLOUD_BACKUP_ENABLED, false)
        if (cloudBackupEnabled) {
            viewModel.refreshCloudLists(applicationContext)
        }
    }
}