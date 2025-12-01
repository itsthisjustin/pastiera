package it.palsoftware.pastiera.core.suggestions

import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import java.util.Locale

class AutoReplaceController(
    private val repository: DictionaryRepository,
    private val suggestionEngine: SuggestionEngine,
    private val settingsProvider: () -> SuggestionSettings
) {

    companion object {
        private const val TAG = "AutoReplaceController"
    }

    data class ReplaceResult(val replaced: Boolean, val committed: Boolean)

    fun handleBoundary(
        keyCode: Int,
        event: KeyEvent?,
        tracker: CurrentWordTracker,
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
        Log.d(TAG, "handleBoundary: autoReplaceOnSpaceEnter=${settings.autoReplaceOnSpaceEnter} maxDist=${settings.maxAutoReplaceDistance}")

        if (!settings.autoReplaceOnSpaceEnter || inputConnection == null) {
            Log.d(TAG, "handleBoundary: skipping - autoReplace=${settings.autoReplaceOnSpaceEnter} ic=${inputConnection != null}")
            tracker.onBoundaryReached(boundaryChar, inputConnection)
            return ReplaceResult(false, unicodeChar != 0)
        }

        val word = tracker.currentWord
        Log.d(TAG, "handleBoundary: word='$word' dictReady=${repository.isReady}")

        if (word.isBlank()) {
            tracker.onBoundaryReached(boundaryChar, inputConnection)
            return ReplaceResult(false, unicodeChar != 0)
        }

        val suggestions = suggestionEngine.suggest(word, limit = 3, includeAccentMatching = settings.accentMatching)
        val top = suggestions.firstOrNull()
        val isKnown = repository.isKnownWord(word)
        Log.d(TAG, "handleBoundary: suggestions=${suggestions.size} top=${top?.candidate}:${top?.distance} isKnown=$isKnown")

        val shouldReplace = top != null && !isKnown && top.distance <= settings.maxAutoReplaceDistance
        Log.d(TAG, "handleBoundary: shouldReplace=$shouldReplace (top!=null:${top!=null}, !isKnown:${!isKnown}, dist<=${settings.maxAutoReplaceDistance}:${top?.distance?.let { it <= settings.maxAutoReplaceDistance }})")

        if (shouldReplace && top != null) {
            val replacement = applyCasing(top.candidate, word)
            inputConnection.beginBatchEdit()
            inputConnection.deleteSurroundingText(word.length, 0)
            inputConnection.commitText(replacement, 1)
            repository.markUsed(replacement)
            tracker.reset()
            inputConnection.endBatchEdit()
            if (boundaryChar != null) {
                inputConnection.commitText(boundaryChar.toString(), 1)
            }
            return ReplaceResult(true, true)
        }

        tracker.onBoundaryReached(boundaryChar, inputConnection)
        return ReplaceResult(false, unicodeChar != 0)
    }

    private fun applyCasing(candidate: String, original: String): String {
        if (original.isEmpty()) return candidate
        return when {
            original.first().isUpperCase() && original.drop(1).all { it.isLowerCase() } ->
                candidate.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            else -> candidate
        }
    }
}
