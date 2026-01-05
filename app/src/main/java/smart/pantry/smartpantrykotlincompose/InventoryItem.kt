// smart.pantry.smartpantrykotlincompose/InventoryItem.kt
package smart.pantry.smartpantrykotlincompose

import androidx.annotation.NonNull
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Represents a single item in the inventory.
 *
 * All properties in the primary constructor (id, name, etc.) automatically generate
 * public fields and the equivalent of Java getters/setters.
 */
@Serializable
data class InventoryItem(
    // These properties replace all your direct field access and Java getters (like getID(), getName())
    val id: Int,
    var name: String,
    var code: String,

    // FIX: Custom serializer applied to correctly handle LocalDate
    @Serializable(with = LocalDateKSerializer::class)
    var expDate: LocalDate,
    var quantity: Int,
    var daysNotice: Int
) {
    // --- Computed Properties (Replaces Java Getters) ---

    // Replaces the original Java method: public LocalDate getExpSoonDate()
    /**
     * Calculates the "expiration soon" date based on expDate minus daysNotice.
     */
    val expSoonDate: LocalDate
        get() = expDate.minusDays(daysNotice.toLong())

    // Replaces the original Java method: public boolean isExpired()
    /**
     * Checks if the item's expiration date is today or in the past.
     */
    val isExpired: Boolean
        get() = expDate.isBefore(LocalDate.now()) || expDate.isEqual(LocalDate.now())

    // New utility property (Useful for UI display/sorting)
    /**
     * Calculates the number of days between now and the expiration date.
     */
    val daysUntilExpiration: Long
        get() = ChronoUnit.DAYS.between(LocalDate.now(), expDate)

    // New utility property (More readable alternative to checking expSoonDate)
    /**
     * Checks if the item is not yet expired but is in the "expiring soon" window.
     */
    val isExpiringSoon: Boolean
        get() = !isExpired && (expSoonDate.isBefore(LocalDate.now()) || expSoonDate.isEqual(LocalDate.now()))


    @NonNull
    override fun toString(): String {
        return "InventoryItem(id=$id, name='$name', expDate=$expDate, quantity=$quantity, daysNotice=$daysNotice)"
    }
}