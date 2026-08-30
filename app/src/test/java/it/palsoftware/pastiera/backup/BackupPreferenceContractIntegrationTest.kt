package it.palsoftware.pastiera.backup

import android.content.Context
import android.net.Uri
import it.palsoftware.pastiera.SettingsManager
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupPreferenceContractIntegrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        listOf(
            "pastiera_prefs",
            "app_list_cache_prefs",
            "perf_img_scale",
            "recent_emojis_prefs"
        ).forEach { prefName ->
            context.getSharedPreferences(prefName, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun richExport_restoresFreshUserStateWithZeroSkippedKeys() = runBlocking {
        val hardwareTheme = SettingsManager.defaultKeyboardTheme().copy(
            background = 0xFF102030.toInt(),
            accent = 0xFF405060.toInt()
        )
        val softwareTheme = SettingsManager.defaultKeyboardTheme().copy(
            background = 0xFF607080.toInt(),
            ortholinear = true,
            showLeds = false
        )
        val savedTheme = SettingsManager.defaultKeyboardTheme().copy(
            background = 0xFF112233.toInt()
        )
        val draftTheme = SettingsManager.defaultKeyboardTheme().copy(
            background = 0xFF445566.toInt()
        )
        val enterOverride = SettingsManager.AppEnterBehaviorOverride(
            packageName = "example.transfer.test",
            behavior = SettingsManager.ENTER_BEHAVIOR_ENTER_NEWLINE
        )

        SettingsManager.setKeyboardTheme(context, SettingsManager.KeyboardThemeTarget.HARDWARE, hardwareTheme)
        SettingsManager.setKeyboardTheme(context, SettingsManager.KeyboardThemeTarget.SOFTWARE, softwareTheme)
        SettingsManager.saveKeyboardTheme(context, "Transfer library", savedTheme)
        SettingsManager.saveKeyboardThemeDraft(
            context,
            SettingsManager.KeyboardThemeDraft(
                name = "Transfer draft",
                theme = draftTheme,
                populatedFields = setOf("background")
            )
        )
        SettingsManager.setKeyboardLayoutAutoByLocale(context, false)
        SettingsManager.setAppEnterBehaviorEnabled(context, true)
        SettingsManager.setAppEnterBehaviorPreset(
            context,
            SettingsManager.ENTER_BEHAVIOR_PRESET_CUSTOM
        )
        SettingsManager.setAppEnterBehaviorOverrides(context, listOf(enterOverride))
        SettingsManager.setNavModeCtrlHoldEnabled(context, true)
        SettingsManager.setDynamicVariationBarResizeToContent(context, true)
        SettingsManager.getPreferences(context).edit()
            .putString("user_dictionary_entries", "[\"ContractWord\"]")
            .putString("auto_correct_custom_de", "{\"ctr\":\"ContractReplacement\"}")
            .putString("app_language_tag", "de-DE")
            .putBoolean("alt_shift_default_initialized", true)
            .putInt("current_sym_page", 2)
            .putBoolean("legacy_german_qwertz_default_migrated", true)
            .putInt("last_seen_whats_new_version", 86)
            .putInt("nav_mode_default_mappings_version", 3)
            .putBoolean("quick_launcher_default_assigned", true)
            .putFloat("keyboard_theme_preview_viewport_scale", 1.4f)
            .putBoolean("pastierina_mode_active", true)
            .commit()
        context.getSharedPreferences("app_list_cache_prefs", Context.MODE_PRIVATE).edit()
            .putInt("package_change_sequence", 42)
            .commit()
        context.getSharedPreferences("perf_img_scale", Context.MODE_PRIVATE).edit()
            .putInt("2131230908", 3)
            .commit()

        val backupZip = File.createTempFile("preference_contract_", ".zip", context.cacheDir)
        val backupResult = BackupManager.createBackup(context, Uri.fromFile(backupZip))
        assertTrue(backupResult is BackupResult.Success)

        val zipEntries = readZipEntries(backupZip)
        assertTrue(zipEntries.containsKey("prefs/pastiera_prefs.json"))
        assertFalse(zipEntries.containsKey("prefs/app_list_cache_prefs.json"))
        assertFalse(zipEntries.containsKey("prefs/perf_img_scale.json"))
        assertFalse(zipEntries.containsKey("prefs/recent_emojis_prefs.json"))
        val exportedEntries = JSONObject(zipEntries.getValue("prefs/pastiera_prefs.json"))
            .getJSONObject("entries")
        BackupPreferenceContract.deliberatelyExcludedPastieraKeys.keys.forEach { excludedKey ->
            assertFalse("Excluded key was exported: $excludedKey", exportedEntries.has(excludedKey))
        }

        SettingsManager.getPreferences(context).edit().clear().commit()
        val restoreResult = RestoreManager.restore(context, Uri.fromFile(backupZip))
        val restored = restoreResult as RestoreResult.Success

        assertTrue(restored.preferencesSummary.skippedKeys.isEmpty())
        assertEquals(
            exportedEntries.keys().asSequence().map { "pastiera_prefs:$it" }.toSet(),
            restored.preferencesSummary.appliedKeys.toSet()
        )
        assertEquals(
            hardwareTheme,
            SettingsManager.getKeyboardTheme(context, SettingsManager.KeyboardThemeTarget.HARDWARE)
        )
        assertEquals(
            softwareTheme,
            SettingsManager.getKeyboardTheme(context, SettingsManager.KeyboardThemeTarget.SOFTWARE)
        )
        assertEquals(
            listOf(SettingsManager.NamedKeyboardTheme("Transfer library", savedTheme)),
            SettingsManager.getSavedKeyboardThemes(context)
        )
        assertEquals(
            listOf(
                SettingsManager.KeyboardThemeDraft(
                    "Transfer draft",
                    draftTheme,
                    setOf("background")
                )
            ),
            SettingsManager.getKeyboardThemeDrafts(context)
        )
        assertFalse(SettingsManager.isKeyboardLayoutAutoByLocale(context))
        assertTrue(SettingsManager.getAppEnterBehaviorEnabled(context))
        assertEquals(
            SettingsManager.ENTER_BEHAVIOR_PRESET_CUSTOM,
            SettingsManager.getAppEnterBehaviorPreset(context)
        )
        assertEquals(listOf(enterOverride), SettingsManager.getAppEnterBehaviorOverrides(context))
        assertTrue(SettingsManager.getNavModeCtrlHoldEnabled(context))
        assertTrue(SettingsManager.getDynamicVariationBarResizeToContent(context))
        assertEquals(
            "[\"ContractWord\"]",
            SettingsManager.getPreferences(context).getString("user_dictionary_entries", null)
        )
        assertEquals(
            "{\"ctr\":\"ContractReplacement\"}",
            SettingsManager.getPreferences(context).getString("auto_correct_custom_de", null)
        )
        assertEquals("de-DE", SettingsManager.getAppLanguageTag(context))
    }

    @Test
    fun observedTransferKeys_haveExplicitTypesAndInternalKeysAreClassified() {
        val expectedUserTypes = mapOf(
            "keyboard_theme_hardware" to PreferenceValueType.STRING,
            "keyboard_theme_software" to PreferenceValueType.STRING,
            "keyboard_theme_saved_themes" to PreferenceValueType.STRING,
            "keyboard_theme_drafts" to PreferenceValueType.STRING,
            "keyboard_layout_auto_by_locale" to PreferenceValueType.BOOLEAN,
            "app_enter_behavior_enabled" to PreferenceValueType.BOOLEAN,
            "app_enter_behavior_preset" to PreferenceValueType.STRING,
            "app_enter_behavior_overrides" to PreferenceValueType.STRING,
            "nav_mode_ctrl_hold_enabled" to PreferenceValueType.BOOLEAN,
            "dynamic_variation_bar_resize_to_content" to PreferenceValueType.BOOLEAN
        )

        expectedUserTypes.forEach { (key, type) ->
            assertEquals(type, BackupPreferenceContract.expectedExportType("pastiera_prefs", key))
            assertTrue(BackupPreferenceContract.isExportable("pastiera_prefs", key))
            assertEquals(type, PreferenceSchemas.expectedType("pastiera_prefs", key))
        }
        listOf(
            "alt_shift_default_initialized",
            "current_sym_page",
            "legacy_german_qwertz_default_migrated",
            "last_seen_whats_new_version",
            "nav_mode_default_mappings_version",
            "quick_launcher_default_assigned",
            "keyboard_theme_preview_viewport_scale"
        ).forEach { key ->
            assertTrue(BackupPreferenceContract.deliberatelyExcludedPastieraKeys.containsKey(key))
            assertFalse(BackupPreferenceContract.isExportable("pastiera_prefs", key))
        }
        assertEquals(
            PreferenceValueType.STRING,
            BackupPreferenceContract.expectedExportType("pastiera_prefs", "auto_correct_custom_de")
        )
    }

    @Test
    fun everyPastieraKeyObservedInIssue179Backup_isExplicitlyExportedOrExcluded() {
        val observedKeys = """
            alt_shift_default_initialized
            alt_shift_layout_switch
            app_enter_behavior_enabled
            app_enter_behavior_overrides
            app_enter_behavior_preset
            auto_capitalize_first_letter
            auto_correct_custom_de
            auto_correct_custom_fr
            auto_correct_enabled_languages
            auto_replace_on_space_enter
            auto_space_punctuation
            comma_space
            current_sym_page
            custom_input_styles
            dismissed_releases
            dynamic_variation_bar_resize_to_content
            french_punctuation_only_french
            french_punctuation_spacing
            input_style_suggestion_locales
            keyboard_layout
            keyboard_layout_auto_by_locale
            keyboard_layout_list
            keyboard_theme_hardware
            keyboard_theme_saved_themes
            last_seen_whats_new_version
            launcher_shortcuts
            launcher_shortcuts_enabled
            legacy_german_qwertz_default_migrated
            long_press_modifier
            long_press_threshold
            mid_word_quote_to_apostrophe
            modifier_indicator_mode
            nav_mode_ctrl_hold_enabled
            nav_mode_default_mappings_version
            nav_mode_mappings_updated
            pastierina_mode_override
            physical_keyboard_currency_symbol
            physical_keyboard_profile_override
            quick_launcher_animation_duration_ms
            quick_launcher_auto_start_single
            quick_launcher_behavior
            quick_launcher_default_assigned
            quick_launcher_limit_results
            shift_backspace_delete
            smart_quotes
            software_keyboard_mode
            space_after_punctuation
            spaced_hyphen_to_en_dash
            static_variation_bar_base_layer_enabled
            static_variation_bar_mode
            static_variation_bar_preset
            sym_mappings_custom
            titan2_elite_rounded_corner_insets
            trackpad_gestures_enabled
            tutorial_completed
            user_dictionary_entries
            variations_updated
        """.trimIndent().lineSequence().filter(String::isNotBlank).toSet()

        val unclassified = observedKeys.filterNot { key ->
            BackupPreferenceContract.isExportable("pastiera_prefs", key) ||
                BackupPreferenceContract.deliberatelyExcludedPastieraKeys.containsKey(key)
        }

        assertTrue("Unclassified observed keys: $unclassified", unclassified.isEmpty())
    }

    private fun readZipEntries(zipFile: File): Map<String, String> {
        val entries = linkedMapOf<String, String>()
        ZipInputStream(FileInputStream(zipFile)).use { zipInput ->
            var entry = zipInput.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    entries[entry.name] = zipInput.readBytes().toString(Charsets.UTF_8)
                }
                zipInput.closeEntry()
                entry = zipInput.nextEntry
            }
        }
        return entries
    }
}
