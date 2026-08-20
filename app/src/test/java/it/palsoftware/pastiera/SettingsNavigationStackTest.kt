package it.palsoftware.pastiera

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsNavigationStackTest {

    @Test
    fun restoresLegacyDestinationOnlyStack() {
        val stack = restoreSettingsStack(listOf("Main", "KeyboardsDevices"))

        assertEquals(
            listOf(SettingsDestination.Main, SettingsDestination.KeyboardsDevices),
            stack.map { it.destination }
        )
        assertEquals(
            KeyboardsDevicesDestination.Main,
            stack.last().keyboardsDevicesDestination
        )
    }

    @Test
    fun restoresPreviousFourFieldStackFormat() {
        val stack = restoreSettingsStack(
            listOf(
                "Customization",
                SettingsActivity.CUSTOMIZATION_DESTINATION_KEYBOARD_THEME,
                SettingsActivity.KEYBOARD_THEME_TARGET_SOFTWARE,
                ""
            )
        )

        assertEquals(1, stack.size)
        assertEquals(SettingsDestination.Customization, stack.single().destination)
        assertEquals(
            SettingsActivity.CUSTOMIZATION_DESTINATION_KEYBOARD_THEME,
            stack.single().customizationDestination
        )
        assertEquals(
            SettingsActivity.KEYBOARD_THEME_TARGET_SOFTWARE,
            stack.single().keyboardThemeTarget
        )
    }

    @Test
    fun restoresVersionTwoStackWithoutThemeTab() {
        val stack = restoreSettingsStack(
            listOf(
                "settings-stack-v2",
                "KeyboardsDevices",
                "",
                "",
                "",
                KeyboardsDevicesDestination.OnScreen.name
            )
        )

        assertEquals(KeyboardsDevicesDestination.OnScreen, stack.single().keyboardsDevicesDestination)
        assertEquals(null, stack.single().keyboardThemeTab)
    }

    @Test
    fun restoresVersionThreeThemeTab() {
        val stack = restoreSettingsStack(
            listOf(
                "settings-stack-v3",
                "Customization",
                SettingsActivity.CUSTOMIZATION_DESTINATION_KEYBOARD_THEME,
                SettingsManager.KeyboardThemeTarget.SOFTWARE.name,
                KeyboardThemeEditorTab.Keys.name,
                "",
                KeyboardsDevicesDestination.Main.name
            )
        )

        assertEquals(KeyboardThemeEditorTab.Keys.name, stack.single().keyboardThemeTab)
    }

    @Test
    fun malformedStackFallsBackToMain() {
        val stack = restoreSettingsStack(listOf("broken", "state"))

        assertEquals(listOf(SettingsDestination.Main), stack.map { it.destination })
    }
}
