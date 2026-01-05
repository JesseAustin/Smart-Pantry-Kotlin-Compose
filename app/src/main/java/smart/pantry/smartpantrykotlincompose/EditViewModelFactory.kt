// Create or modify EditViewModelFactory.kt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import smart.pantry.smartpantrykotlincompose.EditViewModel
import smart.pantry.smartpantrykotlincompose.InventoryRepository
import smart.pantry.smartpantrykotlincompose.MyApplication

class EditViewModelFactory(
    // 1. Accept the repository and application context
    private val repository: InventoryRepository,
    private val app: MyApplication,
    // 2. Accept the ID to be loaded
    private val itemIdToLoad: Int
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(EditViewModel::class.java)) {
            // 3. Pass all arguments to the EditViewModel constructor
            return EditViewModel(repository, app, itemIdToLoad) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}