package it.palsoftware.pastiera.data.layout

import it.palsoftware.pastiera.data.layout.LayoutFileStore.LayoutImportError
import it.palsoftware.pastiera.data.layout.LayoutFileStore.LayoutImportResult
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LayoutFileStoreImportTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        LayoutFileStore.getLayoutsDirectory(context).deleteRecursively()
    }

    @Test
    fun validSupportedMapping_isSaved() {
        val result = import(
            """{"name":"Русский","mappings":{"KEYCODE_Q":{"lowercase":"й","uppercase":"Й"}}}"""
        )

        assertEquals(LayoutImportResult.Success("Русский"), result)
        assertTrue(LayoutFileStore.getLayoutFile(context, "Русский").exists())
    }

    @Test
    fun malformedJson_isRejectedWithoutMutation() {
        assertRejectedWithoutMutation("{", LayoutImportError.MALFORMED_JSON)
    }

    @Test
    fun missingMappings_isRejectedWithoutMutation() {
        assertRejectedWithoutMutation("""{"name":"Missing"}""", LayoutImportError.MISSING_MAPPINGS)
    }

    @Test
    fun nonObjectMappings_isRejectedWithoutMutation() {
        assertRejectedWithoutMutation("""{"mappings":[]}""", LayoutImportError.MAPPINGS_NOT_OBJECT)
    }

    @Test
    fun emptyMappings_isRejectedWithoutMutation() {
        assertRejectedWithoutMutation("""{"mappings":{}}""", LayoutImportError.EMPTY_MAPPINGS)
    }

    @Test
    fun onlyUnknownKeyCodes_areRejectedWithoutMutation() {
        assertRejectedWithoutMutation(
            """{"mappings":{"KEYCODE_FUTURE":{"lowercase":"x","uppercase":"X"}}}""",
            LayoutImportError.NO_SUPPORTED_MAPPINGS
        )
    }

    @Test
    fun incompleteSupportedMapping_isRejectedWithoutMutation() {
        assertRejectedWithoutMutation(
            """{"mappings":{"KEYCODE_Q":{"lowercase":"й"}}}""",
            LayoutImportError.INVALID_MAPPING
        )
    }

    @Test
    fun invalidSupportedMapping_rejectsWholeFileEvenAlongsideValidMapping() {
        assertRejectedWithoutMutation(
            """{"mappings":{"KEYCODE_Q":{"lowercase":"й","uppercase":"Й"},"KEYCODE_W":{"lowercase":"ц","uppercase":3}}}""",
            LayoutImportError.INVALID_MAPPING
        )
    }

    @Test
    fun enabledMultiTap_requiresTwoUsableTaps() {
        assertRejectedWithoutMutation(
            """{"mappings":{"KEYCODE_Q":{"lowercase":"й","uppercase":"Й","multiTapEnabled":true,"taps":[{"lowercase":"й","uppercase":"Й"}]}}}""",
            LayoutImportError.INVALID_MAPPING
        )
    }

    @Test
    fun bundledEmptyTapPlaceholders_areIgnored() {
        val result = import(
            """{"mappings":{"KEYCODE_Q":{"lowercase":"я","uppercase":"Я","multiTapEnabled":true,"taps":[{"lowercase":"я","uppercase":"Я"},{"lowercase":"э","uppercase":"Э"},{"lowercase":"","uppercase":""}]}}}"""
        )

        assertTrue(result is LayoutImportResult.Success)
        val loaded = LayoutFileStore.loadLayoutFromFile(LayoutFileStore.getLayoutFile(context, "Русский"))
        assertEquals(2, loaded?.get(android.view.KeyEvent.KEYCODE_Q)?.taps?.size)
    }

    private fun assertRejectedWithoutMutation(json: String, expected: LayoutImportError) {
        val existing = LayoutFileStore.getLayoutFile(context, "Русский")
        existing.writeText("existing-layout")
        val before = existing.readBytes()

        val result = import(json)

        assertTrue(result is LayoutImportResult.Failure)
        assertEquals(expected, (result as LayoutImportResult.Failure).error)
        assertArrayEquals(before, existing.readBytes())
        assertFalse(LayoutFileStore.getLayoutFile(context, "Unexpected").exists())
    }

    private fun import(json: String): LayoutImportResult =
        LayoutFileStore.saveLayoutFromJson(context, "Русский", json)
}
