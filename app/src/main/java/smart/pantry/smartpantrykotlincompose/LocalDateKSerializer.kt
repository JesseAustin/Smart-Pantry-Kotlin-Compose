package smart.pantry.smartpantrykotlincompose

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Custom KSerializer for converting a java.time.LocalDate object to and from a JSON string ("yyyy-MM-dd").
 *
 * NOTE: This serializer does NOT handle the old complex JSON object format for dates.
 * That backward compatibility is an *API input* concern, not a local storage concern,
 * and should be handled by an explicit mapping layer if needed. For local storage, we save a simple string.
 */
object LocalDateKSerializer : KSerializer<LocalDate> {
    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalDate) {
        encoder.encodeString(value.format(formatter))
    }

    override fun deserialize(decoder: Decoder): LocalDate {
        val dateString = decoder.decodeString()
        try {
            return LocalDate.parse(dateString, formatter)
        } catch (e: DateTimeParseException) {
            // Handle cases where the string might not be perfectly formatted, or include time (though not expected)
            // If we strictly only ever save ISO_LOCAL_DATE, this catch block is for robustness.
            throw e
        }
    }
}