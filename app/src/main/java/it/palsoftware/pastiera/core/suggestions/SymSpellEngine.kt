package it.palsoftware.pastiera.core.suggestions

import android.content.res.AssetManager
import android.util.Log
import com.darkrockstudios.symspellkt.api.SpellChecker
import com.darkrockstudios.symspellkt.common.SpellCheckSettings
import com.darkrockstudios.symspellkt.common.Verbosity
import com.darkrockstudios.symspell.fdic.loadFdicFile
import com.darkrockstudios.symspellkt.impl.SymSpell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Spelling suggestion engine powered by SymSpell algorithm.
 * SymSpell provides fast, accurate spelling correction using symmetric delete algorithm.
 */
class SymSpellEngine(
    private val assets: AssetManager,
    private val debugLogging: Boolean = false
) {
    companion object {
        private const val TAG = "SymSpellEngine"
        private const val DICTIONARY_PATH = "dictionaries/en-80k.fdic"
    }

    private var spellChecker: SpellChecker? = null
    private val loadMutex = Mutex()

    @Volatile
    var isReady: Boolean = false
        private set

    /**
     * Load the dictionary. Must be called from a background thread.
     */
    suspend fun loadDictionary() = withContext(Dispatchers.IO) {
        if (isReady) return@withContext

        loadMutex.withLock {
            // Double-check after acquiring lock
            if (isReady) return@withContext

            try {
                val settings = SpellCheckSettings(
                    maxEditDistance = 2.0,
                    prefixLength = 7,
                    verbosity = Verbosity.Closest
                )

                val checker = SymSpell(settings)

                // Load dictionary from fdic binary format (70% faster than txt)
                val startTime = System.currentTimeMillis()
                val fdicBytes = assets.open(DICTIONARY_PATH).use { it.readBytes() }
                checker.dictionary.loadFdicFile(fdicBytes)

                val loadTime = System.currentTimeMillis() - startTime
                if (debugLogging) {
                    Log.d(TAG, "Dictionary loaded in ${loadTime}ms")
                }

                spellChecker = checker
                isReady = true

            } catch (e: Exception) {
                Log.e(TAG, "Failed to load dictionary", e)
            }
        }
    }

    /**
     * Get spelling suggestions for a word.
     *
     * @param word The word to check
     * @param maxSuggestions Maximum number of suggestions to return
     * @return List of suggestions sorted by relevance
     */
    fun suggest(word: String, maxSuggestions: Int = 3): List<SuggestionResult> {
        if (!isReady || word.isBlank() || word.length < 2) {
            return emptyList()
        }

        val checker = spellChecker ?: return emptyList()

        return try {
            val suggestions = checker.lookup(word.lowercase(Locale.getDefault()), Verbosity.Closest, maxSuggestions.toDouble())

            if (debugLogging) {
                Log.d(TAG, "suggest('$word') -> ${suggestions.map { "${it.term}:${it.distance}" }}")
            }

            suggestions.map { item ->
                SuggestionResult(
                    candidate = item.term,
                    distance = item.distance.toInt(),
                    score = item.frequency,
                    source = SuggestionSource.MAIN
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting suggestions for '$word'", e)
            emptyList()
        }
    }

    /**
     * Check if a word is in the dictionary.
     */
    fun isKnownWord(word: String): Boolean {
        if (!isReady || word.isBlank()) return false

        val checker = spellChecker ?: return false

        return try {
            val suggestions = checker.lookup(word.lowercase(Locale.getDefault()), Verbosity.Closest, 1.0)
            suggestions.isNotEmpty() && suggestions[0].distance == 0.0
        } catch (e: Exception) {
            false
        }
    }
}
