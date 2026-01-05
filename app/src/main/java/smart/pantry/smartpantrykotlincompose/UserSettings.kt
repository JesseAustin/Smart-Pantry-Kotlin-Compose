package smart.pantry.smartpantrykotlincompose

/**
 * Singleton object to hold shared preference keys and other global constants.
 * Replaces UserSettings.java.
 */
object UserSettings {
    const val PREFERENCES = "preferences"
    const val KEY_DO_NOT_ASK_PERMISSIONS = "dont_ask_permissions"
    const val KEY_DO_NOT_ASK_EXACT = "dont_ask_exact"

    // Keys for settings from the menu:
    const val KEY_CLOUD_BACKUP_ENABLED = "cloud_backup_enabled"
    const val KEY_SCAN_BEEP_ENABLED = "scan_beep_enabled"
    const val KEY_PERISHABLE_ENABLED = "key_perishable_enabled"
}