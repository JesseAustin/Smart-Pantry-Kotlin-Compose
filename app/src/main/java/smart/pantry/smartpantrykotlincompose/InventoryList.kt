@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package smart.pantry.smartpantrykotlincompose

import kotlinx.serialization.Serializable

@Serializable
data class InventoryList(
    val perishable: Boolean = false,
    val items: List<InventoryItem>
)
