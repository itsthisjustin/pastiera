package it.palsoftware.pastiera.core.suggestions

import android.content.Context
import android.content.res.AssetManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import it.palsoftware.pastiera.inputmethod.NotificationHelper

class SuggestionController(
    context: Context,
    assets: AssetManager,
    private val settingsProvider: () -> SuggestionSettings,
    private val isEnabled: () -> Boolean = { true },
    debugLogging: Boolean = false,
    onSuggestionsUpdated: (List<SuggestionResult>) -> Unit
) {

    private val appContext = context.applicationContext
    private val debugLogging: Boolean = debugLogging
    private val symSpellEngine = SymSpellEngine(assets, debugLogging = debugLogging)
    private val tracker = CurrentWordTracker(
        onWordChanged = { word ->
            val settings = settingsProvider()
            if (settings.suggestionsEnabled) {
                onSuggestionsUpdated(symSpellEngine.suggest(word, settings.maxSuggestions))
            }
        },
        onWordReset = { onSuggestionsUpdated(emptyList()) }
    )
    private val latestSuggestions: AtomicReference<List<SuggestionResult>> = AtomicReference(emptyList())
    private val loadScope = CoroutineScope(Dispatchers.Default)
    private val cursorHandler = Handler(Looper.getMainLooper())
    private var cursorRunnable: Runnable? = null
    private val cursorDebounceMs = 120L

    var suggestionsListener: ((List<SuggestionResult>) -> Unit)? = onSuggestionsUpdated

    fun onCharacterCommitted(text: CharSequence, inputConnection: InputConnection?) {
        if (!isEnabled()) return
        if (debugLogging) Log.d("PastieraIME", "SuggestionController.onCharacterCommitted('$text')")
        ensureDictionaryLoaded()
        tracker.onCharacterCommitted(text)
        updateSuggestions()
    }

    fun refreshFromInputConnection(inputConnection: InputConnection?) {
        if (!isEnabled()) return
        tracker.onBackspace()
        updateSuggestions()
    }

    private fun updateSuggestions() {
        val settings = settingsProvider()
        if (settings.suggestionsEnabled) {
            val next = symSpellEngine.suggest(tracker.currentWord, settings.maxSuggestions)
            val summary = next.take(3).joinToString { "${it.candidate}:${it.distance}" }
            if (debugLogging) Log.d("PastieraIME", "suggestions (${next.size}): $summary")
            latestSuggestions.set(next)
            suggestionsListener?.invoke(next)
        } else {
            suggestionsListener?.invoke(emptyList())
        }
    }

    data class ReplaceResult(val replaced: Boolean, val committed: Boolean)

    fun onBoundaryKey(
        keyCode: Int,
        event: KeyEvent?,
        inputConnection: InputConnection?
    ): ReplaceResult {
        val unicodeChar = event?.unicodeChar ?: 0
        val boundaryChar = when {
            unicodeChar != 0 -> unicodeChar.toChar()
            keyCode == KeyEvent.KEYCODE_SPACE -> ' '
            keyCode == KeyEvent.KEYCODE_ENTER -> '\n'
            else -> null
        }

        val settings = settingsProvider()
        if (debugLogging) {
            Log.d("PastieraIME", "onBoundaryKey: autoReplace=${settings.autoReplaceOnSpaceEnter} word='${tracker.currentWord}'")
        }

        if (!settings.autoReplaceOnSpaceEnter || inputConnection == null) {
            tracker.onBoundaryReached(boundaryChar, inputConnection)
            suggestionsListener?.invoke(emptyList())
            return ReplaceResult(false, unicodeChar != 0)
        }

        ensureDictionaryLoaded()

        val word = tracker.currentWord
        if (word.isBlank()) {
            tracker.onBoundaryReached(boundaryChar, inputConnection)
            suggestionsListener?.invoke(emptyList())
            return ReplaceResult(false, unicodeChar != 0)
        }

        val suggestions = symSpellEngine.suggest(word, maxSuggestions = 1)
        val top = suggestions.firstOrNull()
        val isKnown = symSpellEngine.isKnownWord(word)

        if (debugLogging) {
            Log.d("PastieraIME", "onBoundaryKey: top=${top?.candidate}:${top?.distance} isKnown=$isKnown maxDist=${settings.maxAutoReplaceDistance}")
        }

        val shouldReplace = top != null && !isKnown && top.distance <= settings.maxAutoReplaceDistance

        if (shouldReplace && top != null) {
            val replacement = applyCasing(top.candidate, word)
            if (debugLogging) {
                Log.d("PastieraIME", "onBoundaryKey: replacing '$word' -> '$replacement'")
            }
            inputConnection.beginBatchEdit()
            inputConnection.deleteSurroundingText(word.length, 0)
            inputConnection.commitText(replacement, 1)
            tracker.reset()
            inputConnection.endBatchEdit()
            if (boundaryChar != null) {
                inputConnection.commitText(boundaryChar.toString(), 1)
            }
            NotificationHelper.triggerHapticFeedback(appContext)
            suggestionsListener?.invoke(emptyList())
            return ReplaceResult(true, true)
        }

        tracker.onBoundaryReached(boundaryChar, inputConnection)
        suggestionsListener?.invoke(emptyList())
        return ReplaceResult(false, unicodeChar != 0)
    }

    private fun applyCasing(candidate: String, original: String): String {
        if (original.isEmpty()) return candidate
        return when {
            original.all { it.isUpperCase() } -> candidate.uppercase()
            original.first().isUpperCase() -> candidate.replaceFirstChar { it.uppercaseChar() }
            else -> candidate
        }
    }

    fun onCursorMoved(inputConnection: InputConnection?) {
        if (!isEnabled()) return
        ensureDictionaryLoaded()
        cursorRunnable?.let { cursorHandler.removeCallbacks(it) }
        if (inputConnection == null) {
            tracker.reset()
            suggestionsListener?.invoke(emptyList())
            return
        }
        cursorRunnable = Runnable {
            if (!symSpellEngine.isReady) {
                tracker.reset()
                suggestionsListener?.invoke(emptyList())
                return@Runnable
            }
            val word = extractWordAtCursor(inputConnection)
            if (!word.isNullOrBlank()) {
                tracker.setWord(word)
                updateSuggestions()
            } else {
                tracker.reset()
                suggestionsListener?.invoke(emptyList())
            }
        }
        cursorHandler.postDelayed(cursorRunnable!!, cursorDebounceMs)
    }

    fun onContextReset() {
        if (!isEnabled()) return
        tracker.onContextChanged()
        suggestionsListener?.invoke(emptyList())
    }

    fun onNavModeToggle() {
        if (!isEnabled()) return
        tracker.onContextChanged()
    }

    fun currentSuggestions(): List<SuggestionResult> = latestSuggestions.get()

    private fun extractWordAtCursor(inputConnection: InputConnection?): String? {
        if (inputConnection == null) return null
        return try {
            val before = inputConnection.getTextBeforeCursor(12, 0)?.toString() ?: ""
            val after = inputConnection.getTextAfterCursor(12, 0)?.toString() ?: ""
            val boundary = " \t\n\r.,;:!?()[]{}\"'"
            var start = before.length
            while (start > 0 && !boundary.contains(before[start - 1])) {
                start--
            }
            var end = 0
            while (end < after.length && !boundary.contains(after[end])) {
                end++
            }
            val word = before.substring(start) + after.substring(0, end)
            if (word.isBlank()) null else word
        } catch (_: Exception) {
            null
        }
    }

    private fun ensureDictionaryLoaded() {
        if (!symSpellEngine.isReady) {
            loadScope.launch {
                symSpellEngine.loadDictionary()
            }
        }
    }
}
