package it.palsoftware.pastiera.core.suggestions

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Spelling suggestion engine powered by SymSpell algorithm.
 * SymSpell provides fast, accurate spelling correction using symmetric delete algorithm.
 *
 * Uses Android's native spell checker to validate words, SymSpell for suggestions.
 * The dictionary is cached statically so it persists across IME recreations.
 */
class SymSpellEngine(
    private val context: Context,
    private val assets: AssetManager,
    private val debugLogging: Boolean = false
) {
    companion object {
        private const val TAG = "SymSpellEngine"
        // SymSpell 30k dictionary (fast loading, ~130KB)
        private const val DICTIONARY_PATH = "dictionaries/en_30k.fdic"

        // Static cache - survives IME recreations
        @Volatile
        private var cachedSpellChecker: SpellChecker? = null
        private val loadMutex = Mutex()

        @Volatile
        private var isLoaded: Boolean = false
    }

    // Android native spell checker for word validation
    private val textServicesManager = context.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as? TextServicesManager
    private var nativeSpellCheckerSession: SpellCheckerSession? = null

    val isReady: Boolean
        get() = isLoaded

    /**
     * Load the dictionary. Must be called from a background thread.
     * Dictionary is cached statically so subsequent calls are instant.
     */
    suspend fun loadDictionary() = withContext(Dispatchers.IO) {
        if (isLoaded) {
            // Initialize native spell checker if not already done
            initNativeSpellChecker()
            return@withContext
        }

        loadMutex.withLock {
            // Double-check after acquiring lock
            if (isLoaded) {
                initNativeSpellChecker()
                return@withContext
            }

            try {
                val settings = SpellCheckSettings(
                    maxEditDistance = 2.0,
                    prefixLength = 7,
                    verbosity = Verbosity.Closest
                )

                val checker = SymSpell(settings)

                // Load dictionary from fdic binary format (70% faster than txt)
                val startTime = System.currentTimeMillis()

                val dictBytes = assets.open(DICTIONARY_PATH).use { it.readBytes() }
                checker.dictionary.loadFdicFile(dictBytes)

                val loadTime = System.currentTimeMillis() - startTime
                Log.d(TAG, "Dictionary loaded in ${loadTime}ms (20k words)")

                cachedSpellChecker = checker
                isLoaded = true

                // Initialize native spell checker
                initNativeSpellChecker()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to load dictionary", e)
            }
        }
    }

    /**
     * Initialize Android's native spell checker session.
     * Must be called on main thread or use Handler.
     */
    private fun initNativeSpellChecker() {
        if (nativeSpellCheckerSession != null || textServicesManager == null) return

        try {
            // Create a dummy listener since we'll use synchronous calls
            val listener = object : SpellCheckerSession.SpellCheckerSessionListener {
                override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {}
                override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {}
            }

            nativeSpellCheckerSession = textServicesManager.newSpellCheckerSession(
                null, // Use default locale
                Locale.getDefault(),
                listener,
                true // Referencing TextInfos for synchronous calls
            )

            if (debugLogging) {
                Log.d(TAG, "Native spell checker session initialized")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize native spell checker", e)
        }
    }

    /**
     * Get spelling suggestions for a word.
     *
     * @param word The word to check
     * @param maxSuggestions Maximum number of suggestions to return
     * @return List of suggestions sorted by relevance (keyboard-aware)
     */
    fun suggest(word: String, maxSuggestions: Int = 3): List<SuggestionResult> {
        if (!isReady || word.isBlank() || word.length < 2) {
            return emptyList()
        }

        val checker = cachedSpellChecker ?: return emptyList()

        return try {
            // Get more candidates than needed for re-ranking
            val candidateCount = maxSuggestions * 3
            val suggestions = checker.lookup(word.lowercase(Locale.getDefault()), Verbosity.Closest, 2.0)
                .take(candidateCount)
                .map { item ->
                    SuggestionResult(
                        candidate = item.term,
                        distance = item.distance.toInt(),
                        score = item.frequency,
                        source = SuggestionSource.MAIN
                    )
                }

            // Re-rank using keyboard proximity
            val reRanked = KeyboardProximity.reRankSuggestions(word, suggestions)
                .take(maxSuggestions)

            if (debugLogging) {
                Log.d(TAG, "suggest('$word') -> ${reRanked.map { "${it.candidate}" }} (keyboard-aware)")
            }

            reRanked
        } catch (e: Exception) {
            Log.e(TAG, "Error getting suggestions for '$word'", e)
            emptyList()
        }
    }

    /**
     * Check if a word is in the dictionary using Android's native spell checker.
     * Falls back to SymSpell if native checker is unavailable.
     *
     * Note: Native spell checker is unreliable on some devices, so we primarily
     * use SymSpell with smart heuristics as fallback.
     */
    fun isKnownWord(word: String): Boolean {
        if (!isReady || word.isBlank()) return false

        // Directly use SymSpell with improved heuristics
        // The native spell checker is too slow and unreliable for real-time autocorrect
        return isKnownWordSymSpell(word)
    }

    /**
     * Fallback method using SymSpell to check if word exists.
     */
    private fun isKnownWordSymSpell(word: String): Boolean {
        val checker = cachedSpellChecker ?: return false

        return try {
            // Use lookup with edit distance 0 and check if we get an exact match
            val normalized = word.lowercase(Locale.getDefault())
            val suggestions = checker.lookup(normalized, Verbosity.Top, 0.0)

            // Check if any suggestion is an exact match (distance 0)
            val isKnown = suggestions.any { it.term == normalized && it.distance == 0.0 }

            if (debugLogging) {
                val sugList = suggestions.take(3).joinToString { "${it.term}:${it.distance}" }
                Log.d(TAG, "isKnownWord('$word') -> $isKnown (SymSpell fallback, suggestions: $sugList)")
            }

            // If word is not found but is reasonably long and alphabetic, apply heuristics
            // This handles cases where common words aren't in the 30k dictionary
            if (!isKnown && word.length >= 4 && word.all { it.isLetter() }) {
                val topSuggestion = suggestions.firstOrNull()

                // Case 1: No suggestions at all - likely a valid word
                if (topSuggestion == null) {
                    if (debugLogging) {
                        Log.d(TAG, "Word '$word' not in dictionary but no suggestions - treating as valid")
                    }
                    return true
                }

                // Case 2: Top suggestion is very far (distance >= 2) - likely valid word
                if (topSuggestion.distance >= 2.0) {
                    if (debugLogging) {
                        Log.d(TAG, "Word '$word' not in dictionary, closest is '${topSuggestion.term}':${topSuggestion.distance} - treating as valid")
                    }
                    return true
                }

                // Case 3: Check if suggestion has much higher frequency
                // If user typed word is not in dictionary but suggestion has very high frequency (1000+),
                // AND they're similar (distance 1), it might be a real typo
                // But if frequencies are similar, user's word is probably valid
                if (topSuggestion.distance == 1.0) {
                    // Get frequency of suggestions to make a judgment
                    if (topSuggestion.frequency < 1000) {
                        // Low frequency suggestion - user's word might be equally valid
                        if (debugLogging) {
                            Log.d(TAG, "Word '$word' vs '${topSuggestion.term}' (freq:${topSuggestion.frequency}) - treating as valid")
                        }
                        return true
                    }
                }
            }

            isKnown
        } catch (e: Exception) {
            if (debugLogging) {
                Log.e(TAG, "Error checking if word is known: '$word'", e)
            }
            false
        }
    }
}
