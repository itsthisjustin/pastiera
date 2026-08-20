package it.palsoftware.pastiera.inputmethod.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import it.palsoftware.pastiera.data.emoji.EmojiRepository
import it.palsoftware.pastiera.data.emoji.EmojiSearchRepository
import it.palsoftware.pastiera.data.emoji.RecentEmojiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EmojiPickerOnScreenSearchTest {

    @Test
    fun emojiCommitRunsSynchronouslyBeforeAutoClose() {
        val events = mutableListOf<String>()
        var viewRef: EmojiPickerView? = null
        val view = EmojiPickerView(RuntimeEnvironment.getApplication()) {
            events.add("close")
            // Mimics the real SYM auto-close in software keyboard mode, which evicts the
            // picker from its container and cancels the view's coroutine scope.
            (viewRef?.privateField("coroutineScope")?.get(viewRef) as? CoroutineScope)?.cancel()
        }
        viewRef = view
        val ic = RecordingInputConnection { events.add("commit") }
        view.setInputConnection(ic)

        invokeOnEmojiSelected(view, "\uD83D\uDE00", "smileys")

        assertEquals(listOf("commit", "close"), events)
        assertEquals(listOf("\uD83D\uDE00"), ic.committedTexts)
    }

    @Test
    fun recentsPersistEvenWhenScopeIsCancelledAtTapTime() {
        val context = RuntimeEnvironment.getApplication()
        val view = EmojiPickerView(context, onCloseRequested = null)
        val cancelledScope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { it.cancel() }
        view.setPrivateField("coroutineScope", cancelledScope)
        view.setInputConnection(RecordingInputConnection())

        invokeOnEmojiSelected(view, "\uD83D\uDE80", "smileys")

        awaitUntil { RecentEmojiManager.getRecentEmojis(context).contains("\uD83D\uDE80") }
    }

    @Test
    fun onScreenTextInputRoutesIntoSearchFieldWhileCaptureActive() {
        val view = EmojiPickerView(RuntimeEnvironment.getApplication())
        view.setPrivateField("isSearchPanelVisible", true)
        view.setPrivateField("searchInputCaptureEnabled", true)

        val handled = view.handleSearchTextInput("ha")

        assertTrue(handled)
        assertEquals("ha", view.searchText())
    }

    @Test
    fun onScreenTextInputFallsThroughWhenCaptureInactive() {
        val view = EmojiPickerView(RuntimeEnvironment.getApplication())
        view.setPrivateField("isSearchPanelVisible", true)
        view.setPrivateField("searchInputCaptureEnabled", false)

        val handled = view.handleSearchTextInput("ha")

        assertFalse(handled)
        assertEquals("", view.searchText())
    }

    @Test
    fun onScreenBackspaceDeletesLastSearchCharacter() {
        val view = EmojiPickerView(RuntimeEnvironment.getApplication())
        view.setPrivateField("isSearchPanelVisible", true)
        view.setPrivateField("searchInputCaptureEnabled", true)
        view.searchField().setText("ha")
        view.searchField().setSelection(2)

        val handled = view.handleSearchBackspace()

        assertTrue(handled)
        assertEquals("h", view.searchText())
    }

    @Test
    fun onScreenTextInputInsertsAtSearchCursor() {
        val view = EmojiPickerView(RuntimeEnvironment.getApplication())
        view.setPrivateField("isSearchPanelVisible", true)
        view.setPrivateField("searchInputCaptureEnabled", true)
        view.searchField().setText("hat")
        view.searchField().setSelection(2)

        val handled = view.handleSearchTextInput("u")

        assertTrue(handled)
        assertEquals("haut", view.searchText())
        assertEquals(3, view.searchField().selectionStart)
    }

    @Test
    fun onScreenBackspaceDeletesBeforeSearchCursor() {
        val view = EmojiPickerView(RuntimeEnvironment.getApplication())
        view.setPrivateField("isSearchPanelVisible", true)
        view.setPrivateField("searchInputCaptureEnabled", true)
        view.searchField().setText("haut")
        view.searchField().setSelection(3)

        val handled = view.handleSearchBackspace()

        assertTrue(handled)
        assertEquals("hat", view.searchText())
        assertEquals(2, view.searchField().selectionStart)
    }

    @Test
    fun enterKeyCommitsTopSearchResultAndClosesPicker() {
        val events = mutableListOf<String>()
        val view = EmojiPickerView(RuntimeEnvironment.getApplication()) { events.add("close") }
        view.setPrivateField("isSearchPanelVisible", true)
        view.setPrivateField("searchInputCaptureEnabled", true)
        view.setPrivateField(
            "lastSearchResults",
            listOf(
                EmojiSearchRepository.EmojiSearchResult(
                    EmojiRepository.EmojiEntry("\uD83D\uDE80", emptyList()),
                    "travel",
                    1
                )
            )
        )
        val ic = RecordingInputConnection()
        view.setInputConnection(ic)

        val handled = view.handleSearchKeyDown(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))

        assertTrue(handled)
        assertEquals(listOf("\uD83D\uDE80"), ic.committedTexts)
        assertEquals(listOf("close"), events)
    }

    @Test
    fun enterKeyStaysNeutralWithoutSearchResults() {
        val events = mutableListOf<String>()
        val view = EmojiPickerView(RuntimeEnvironment.getApplication()) { events.add("close") }
        view.setPrivateField("isSearchPanelVisible", true)
        view.setPrivateField("searchInputCaptureEnabled", true)
        view.setPrivateField(
            "lastSearchResults",
            emptyList<EmojiSearchRepository.EmojiSearchResult>()
        )
        val ic = RecordingInputConnection()
        view.setInputConnection(ic)

        val handled = view.handleSearchKeyDown(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))

        assertTrue(handled)
        assertTrue(ic.committedTexts.isEmpty())
        assertTrue(events.isEmpty())
    }

    @Test
    fun searchPanelToggleNotifiesHost() {
        val toggles = mutableListOf<Boolean>()
        val view = EmojiPickerView(RuntimeEnvironment.getApplication())
        view.onSearchPanelVisibilityChanged = { toggles.add(it) }

        invokeSetSearchPanelVisible(view, true)
        invokeSetSearchPanelVisible(view, false)

        assertEquals(listOf(true, false), toggles)
    }

    @Test
    fun detachResetsSearchStateQuietly() {
        val toggles = mutableListOf<Boolean>()
        val view = EmojiPickerView(RuntimeEnvironment.getApplication())
        view.onSearchPanelVisibilityChanged = { toggles.add(it) }
        view.setPrivateField("isSearchPanelVisible", true)
        view.setPrivateField("searchInputCaptureEnabled", true)
        view.searchField().setText("rocket")

        invokeOnDetachedFromWindow(view)

        assertEquals(false, view.privateField("isSearchPanelVisible").get(view))
        assertEquals(false, view.isSearchInputActive())
        assertEquals("", view.searchText())
        assertTrue(toggles.isEmpty())
    }

    @Test
    fun detachDuringContainerReorderKeepsSearchState() {
        val view = EmojiPickerView(RuntimeEnvironment.getApplication())
        view.setPrivateField("isSearchPanelVisible", true)
        view.setPrivateField("searchInputCaptureEnabled", true)
        view.searchField().setText("rocket")

        view.reorderingWithinContainer {
            invokeOnDetachedFromWindow(view)
        }

        assertEquals(true, view.privateField("isSearchPanelVisible").get(view))
        assertEquals("rocket", view.searchText())
        assertTrue((view.privateField("coroutineScope").get(view) as CoroutineScope).isActive)
    }

    @Test
    fun recyclerViewHasNoItemAnimator() {
        val view = EmojiPickerView(RuntimeEnvironment.getApplication())

        val recyclerView = view.privateField("recyclerView").get(view) as androidx.recyclerview.widget.RecyclerView

        assertNull(recyclerView.itemAnimator)
    }

    private fun invokeOnDetachedFromWindow(view: EmojiPickerView) {
        val method = EmojiPickerView::class.java.getDeclaredMethod("onDetachedFromWindow")
        method.isAccessible = true
        method.invoke(view)
    }

    private fun invokeOnEmojiSelected(view: EmojiPickerView, emoji: String, categoryId: String) {
        val method = EmojiPickerView::class.java.getDeclaredMethod(
            "onEmojiSelected",
            String::class.java,
            String::class.java,
            Boolean::class.javaObjectType
        )
        method.isAccessible = true
        method.invoke(view, emoji, categoryId, null)
    }

    private fun invokeSetSearchPanelVisible(view: EmojiPickerView, visible: Boolean) {
        val method = EmojiPickerView::class.java.getDeclaredMethod(
            "setSearchPanelVisible",
            Boolean::class.javaPrimitiveType
        )
        method.isAccessible = true
        method.invoke(view, visible)
    }

    private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError("Condition not met within ${timeoutMs}ms")
    }

    private fun EmojiPickerView.searchText(): String {
        return searchField().text?.toString().orEmpty()
    }

    private fun EmojiPickerView.searchField(): EditText {
        return privateField("searchField").get(this) as EditText
    }

    private fun Any.setPrivateField(name: String, value: Any?) {
        privateField(name).set(this, value)
    }

    private fun Any.privateField(name: String) =
        javaClass.getDeclaredField(name).apply { isAccessible = true }

    private class RecordingInputConnection(
        private val onCommit: () -> Unit = {}
    ) : InputConnection {
        val committedTexts = mutableListOf<String>()

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            committedTexts.add(text?.toString().orEmpty())
            onCommit()
            return true
        }

        override fun getTextBeforeCursor(len: Int, flags: Int): CharSequence? = null
        override fun getTextAfterCursor(len: Int, flags: Int): CharSequence? = null
        override fun getSelectedText(flags: Int): CharSequence? = null
        override fun getCursorCapsMode(reqModes: Int): Int = 0
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = null
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean = false
        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = false
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = false
        override fun setComposingRegion(start: Int, end: Int): Boolean = false
        override fun finishComposingText(): Boolean = false
        override fun commitCompletion(text: CompletionInfo?): Boolean = false
        override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = false
        override fun setSelection(start: Int, end: Int): Boolean = false
        override fun performEditorAction(actionCode: Int): Boolean = false
        override fun performContextMenuAction(id: Int): Boolean = false
        override fun beginBatchEdit(): Boolean = false
        override fun endBatchEdit(): Boolean = false
        override fun sendKeyEvent(event: KeyEvent?): Boolean = false
        override fun clearMetaKeyStates(states: Int): Boolean = false
        override fun reportFullscreenMode(enabled: Boolean): Boolean = false
        override fun performPrivateCommand(action: String?, data: Bundle?): Boolean = false
        override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false
        override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean = false
        override fun setImeConsumesInput(imeConsumesInput: Boolean): Boolean = false
        override fun getHandler(): android.os.Handler? = null
        override fun closeConnection() = Unit
    }
}
