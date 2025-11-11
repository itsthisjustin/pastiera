package it.palsoftware.pastiera.inputmethod

import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.InputConnection

/**
 * Gestisce il nav mode: doppio tap su Ctrl per attivare/disattivare Ctrl latch
 * anche quando non siamo in un campo di testo.
 */
object NavModeHandler {
    private const val TAG = "NavModeHandler"
    private const val DOUBLE_TAP_THRESHOLD = 500L // millisecondi
    
    /**
     * Gestisce la pressione di Ctrl nel nav mode.
     * @param keyCode Il keycode del tasto Ctrl premuto
     * @param ctrlPressed Flag che indica se Ctrl è già premuto
     * @param ctrlLatchActive Flag che indica se Ctrl latch è attivo
     * @param lastCtrlReleaseTime Timestamp dell'ultimo rilascio di Ctrl
     * @return Pair<Boolean, NavModeResult> dove il Boolean indica se consumare l'evento,
     *         e NavModeResult contiene le modifiche da applicare agli stati
     */
    fun handleCtrlKeyDown(
        keyCode: Int,
        ctrlPressed: Boolean,
        ctrlLatchActive: Boolean,
        lastCtrlReleaseTime: Long
    ): Pair<Boolean, NavModeResult> {
        if (ctrlPressed) {
            // Ctrl già premuto, non fare nulla
            return Pair(false, NavModeResult())
        }
        
        val currentTime = System.currentTimeMillis()
        
        if (ctrlLatchActive) {
            // Se Ctrl latch è attivo, un singolo tap lo disattiva
            Log.d(TAG, "Nav mode: Ctrl latch disattivato")
            return Pair(true, NavModeResult(
                ctrlLatchActive = false,
                ctrlPhysicallyPressed = true,
                shouldHideKeyboard = true,
                lastCtrlReleaseTime = 0
            ))
        } else {
            // Controlla il doppio tap
            if (currentTime - lastCtrlReleaseTime < DOUBLE_TAP_THRESHOLD && lastCtrlReleaseTime > 0) {
                // Doppio tap rilevato - attiva Ctrl latch e mostra la tastiera
                Log.d(TAG, "Nav mode: Ctrl latch attivato con doppio tap")
                return Pair(true, NavModeResult(
                    ctrlLatchActive = true,
                    ctrlPhysicallyPressed = true,
                    shouldShowKeyboard = true,
                    lastCtrlReleaseTime = 0
                ))
            } else {
                // Singolo tap - non fare nulla, aspetta il secondo tap
                Log.d(TAG, "Nav mode: primo tap su Ctrl, aspetto il secondo")
                return Pair(true, NavModeResult(
                    ctrlPhysicallyPressed = true,
                    lastCtrlReleaseTime = lastCtrlReleaseTime
                ))
            }
        }
    }
    
    /**
     * Gestisce il rilascio di Ctrl nel nav mode.
     */
    fun handleCtrlKeyUp(): NavModeResult {
        return NavModeResult(
            ctrlPressed = false,
            ctrlPhysicallyPressed = false,
            lastCtrlReleaseTime = System.currentTimeMillis()
        )
    }
    
    /**
     * Risultato delle operazioni del nav mode.
     * Contiene le modifiche da applicare agli stati del servizio.
     */
    data class NavModeResult(
        val ctrlLatchActive: Boolean? = null,
        val ctrlPhysicallyPressed: Boolean? = null,
        val ctrlPressed: Boolean? = null,
        val lastCtrlReleaseTime: Long? = null,
        val shouldShowKeyboard: Boolean = false,
        val shouldHideKeyboard: Boolean = false
    )
}

