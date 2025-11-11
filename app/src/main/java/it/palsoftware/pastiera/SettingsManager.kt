package it.palsoftware.pastiera

import android.content.Context
import android.content.SharedPreferences

/**
 * Gestisce le impostazioni dell'app.
 * Centralizza l'accesso alle SharedPreferences per le impostazioni di Pastiera.
 */
object SettingsManager {
    private const val PREFS_NAME = "pastiera_prefs"
    
    // Chiavi delle impostazioni
    private const val KEY_LONG_PRESS_THRESHOLD = "long_press_threshold"
    private const val KEY_AUTO_CAPITALIZE_FIRST_LETTER = "auto_capitalize_first_letter"
    
    // Valori di default
    private const val DEFAULT_LONG_PRESS_THRESHOLD = 500L
    private const val MIN_LONG_PRESS_THRESHOLD = 50L
    private const val MAX_LONG_PRESS_THRESHOLD = 1000L
    private const val DEFAULT_AUTO_CAPITALIZE_FIRST_LETTER = false
    
    /**
     * Ottiene le SharedPreferences per Pastiera.
     */
    fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * Ottiene la soglia di long press in millisecondi.
     */
    fun getLongPressThreshold(context: Context): Long {
        return getPreferences(context).getLong(KEY_LONG_PRESS_THRESHOLD, DEFAULT_LONG_PRESS_THRESHOLD)
    }
    
    /**
     * Imposta la soglia di long press in millisecondi.
     * Il valore viene automaticamente limitato tra MIN e MAX.
     */
    fun setLongPressThreshold(context: Context, threshold: Long) {
        val clampedValue = threshold.coerceIn(MIN_LONG_PRESS_THRESHOLD, MAX_LONG_PRESS_THRESHOLD)
        getPreferences(context).edit()
            .putLong(KEY_LONG_PRESS_THRESHOLD, clampedValue)
            .apply()
    }
    
    /**
     * Ottiene il valore minimo consentito per la soglia di long press.
     */
    fun getMinLongPressThreshold(): Long = MIN_LONG_PRESS_THRESHOLD
    
    /**
     * Ottiene il valore massimo consentito per la soglia di long press.
     */
    fun getMaxLongPressThreshold(): Long = MAX_LONG_PRESS_THRESHOLD
    
    /**
     * Ottiene il valore di default per la soglia di long press.
     */
    fun getDefaultLongPressThreshold(): Long = DEFAULT_LONG_PRESS_THRESHOLD
    
    /**
     * Ottiene lo stato dell'auto-maiuscola per la prima lettera.
     */
    fun getAutoCapitalizeFirstLetter(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_AUTO_CAPITALIZE_FIRST_LETTER, DEFAULT_AUTO_CAPITALIZE_FIRST_LETTER)
    }
    
    /**
     * Imposta lo stato dell'auto-maiuscola per la prima lettera.
     */
    fun setAutoCapitalizeFirstLetter(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_AUTO_CAPITALIZE_FIRST_LETTER, enabled)
            .apply()
    }
}

