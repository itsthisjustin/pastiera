package it.palsoftware.pastiera.core.suggestions

import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.InputConnection

class AutoReplaceController(
    private val repository: DictionaryRepository,
    private val suggestionEngine: SuggestionEngine,
    private val settingsProvider: () -> SuggestionSettings
) {

    companion object {
        private const val TAG = "AutoReplaceController"
    }

    data class ReplaceResult(val replaced: Boolean, val committed: Boolean)
    
    // Track last replacement for undo
    private data class LastReplacement(
        val originalWord: String,
        val replacedWord: String
    )
    private var lastReplacement: LastReplacement? = null
    
    // Track rejected words to avoid auto-correcting them again
    private val rejectedWords = mutableSetOf<String>()

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

        if (shouldReplace) {
            val replacement = applyCasing(top!!.candidate, word)
            inputConnection.beginBatchEdit()
            inputConnection.deleteSurroundingText(word.length, 0)
            inputConnection.commitText(replacement, 1)
            repository.markUsed(replacement)
            
            // Store last replacement for undo
            lastReplacement = LastReplacement(
                originalWord = word,
                replacedWord = replacement
            )
            
            tracker.reset()
            inputConnection.endBatchEdit()
            if (boundaryChar != null) {
                inputConnection.commitText(boundaryChar.toString(), 1)
            }
            return ReplaceResult(true, true)
        }

        // Clear last replacement if no replacement happened
        lastReplacement = null
        tracker.onBoundaryReached(boundaryChar, inputConnection)
        return ReplaceResult(false, unicodeChar != 0)
    }

    fun handleBackspaceUndo(
        keyCode: Int,
        inputConnection: InputConnection?
    ): Boolean {
        val settings = settingsProvider()
        if (!settings.autoReplaceOnSpaceEnter || keyCode != KeyEvent.KEYCODE_DEL || inputConnection == null) {
            return false
        }

        val replacement = lastReplacement ?: return false
        
        // Get text before cursor (need extra chars to check for boundary char)
        val textBeforeCursor = inputConnection.getTextBeforeCursor(
            replacement.replacedWord.length + 2, // +2 for boundary char and safety
            0
        ) ?: return false

        if (textBeforeCursor.length < replacement.replacedWord.length) {
            return false
        }

        // Check if text ends with replaced word (with or without boundary char)
        val lastChars = textBeforeCursor.substring(
            maxOf(0, textBeforeCursor.length - replacement.replacedWord.length - 1)
        )

        val matchesReplacement = lastChars.endsWith(replacement.replacedWord) ||
            lastChars.trimEnd().endsWith(replacement.replacedWord)

        if (!matchesReplacement) {
            return false
        }

        // Calculate chars to delete: replaced word + potential boundary char
        val charsToDelete = if (lastChars.endsWith(replacement.replacedWord)) {
            // No boundary char after, just delete the word
            replacement.replacedWord.length
        } else {
            // There's whitespace/punctuation after, include it in deletion
            var deleteCount = replacement.replacedWord.length
            var i = textBeforeCursor.length - 1
            while (i >= 0 &&
                i >= textBeforeCursor.length - deleteCount - 1 &&
                (textBeforeCursor[i].isWhitespace() ||
                        textBeforeCursor[i] in ".,;:!?()[]{}\"'")
            ) {
                deleteCount++
                i--
            }
            deleteCount
        }

        inputConnection.beginBatchEdit()
        inputConnection.deleteSurroundingText(charsToDelete, 0)
        inputConnection.commitText(replacement.originalWord, 1)
        inputConnection.endBatchEdit()
        
        // Mark word as rejected so it won't be auto-corrected again
        rejectedWords.add(replacement.originalWord.lowercase())
        
        // Clear last replacement after undo
        lastReplacement = null
        return true
    }

    fun clearLastReplacement() {
        lastReplacement = null
    }
    
    fun clearRejectedWords() {
        rejectedWords.clear()
    }

    private fun applyCasing(candidate: String, original: String): String {
        return CasingHelper.applyCasing(candidate, original, forceLeadingCapital = false)
    }
}
