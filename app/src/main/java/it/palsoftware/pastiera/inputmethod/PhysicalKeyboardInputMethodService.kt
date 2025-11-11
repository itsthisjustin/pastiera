package it.palsoftware.pastiera.inputmethod

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import android.widget.TextView
import it.palsoftware.pastiera.inputmethod.KeyboardEventTracker
import java.util.concurrent.ConcurrentHashMap

/**
 * Servizio di immissione specializzato per tastiere fisiche.
 * Gestisce funzionalità avanzate come il long press che simula Alt+tasto.
 */
class PhysicalKeyboardInputMethodService : InputMethodService() {

    companion object {
        private const val TAG = "PastieraInputMethod"
    }

    // Mappa per tracciare i tasti premuti e il tempo di pressione
    private val pressedKeys = ConcurrentHashMap<Int, Long>()
    
    // Mappa per tracciare i Runnable dei long press in attesa
    private val longPressRunnables = ConcurrentHashMap<Int, Runnable>()
    
    // Mappa per tracciare se un long press è stato attivato
    private val longPressActivated = ConcurrentHashMap<Int, Boolean>()
    
    // Mappa per tracciare i caratteri normali inseriti (per poterli cancellare in caso di long press)
    private val insertedNormalChars = ConcurrentHashMap<Int, String>()
    
    // Handler per gestire i long press
    private val handler = Handler(Looper.getMainLooper())
    
    // Soglia per considerare un long press (in millisecondi) - caricata dalle preferenze
    private var longPressThreshold: Long = 500L
    
    // SharedPreferences per le impostazioni
    private lateinit var prefs: SharedPreferences
    
    // Keycode per il tasto SYM
    private val KEYCODE_SYM = 63
    
    // Stato per tracciare se SYM è attualmente attivo (latch/toggle)
    private var symKeyActive = false
    
    // Mappatura Alt+tasto -> carattere speciale (caricata da JSON)
    private val altKeyMap = mutableMapOf<Int, String>()
    
    // Mappatura SYM+tasto -> emoji (caricata da JSON)
    private val symKeyMap = mutableMapOf<Int, String>()
    
    // Mappatura Ctrl+tasto -> azione o keycode (caricata da JSON)
    private val ctrlKeyMap = mutableMapOf<Int, KeyMappingLoader.CtrlMapping>()
    
    // Stato Caps Lock
    private var capsLockEnabled = false
    
    // Tracciamento doppio tap su Shift per attivare Caps Lock
    private var lastShiftReleaseTime: Long = 0
    private var shiftPressed = false
    
    // Stati latch per Ctrl e Alt
    private var ctrlLatchActive = false
    private var altLatchActive = false
    
    // Tracciamento doppio tap su Ctrl e Alt
    private var lastCtrlReleaseTime: Long = 0
    private var ctrlPressed = false
    private var lastAltReleaseTime: Long = 0
    private var altPressed = false
    
    // Tracciamento tasti modificatori premuti fisicamente (per la status bar)
    private var shiftPhysicallyPressed = false
    private var ctrlPhysicallyPressed = false
    private var altPhysicallyPressed = false
    
    // Stati one-shot per i tasti modificatori (attivi fino al prossimo tasto)
    private var shiftOneShot = false
    private var ctrlOneShot = false
    private var altOneShot = false
    
    private val DOUBLE_TAP_THRESHOLD = 500L // millisecondi
    
    // Riferimento alla status bar per aggiornare l'icona Caps Lock
    private var statusBarTextView: TextView? = null
    private var statusBarLayout: LinearLayout? = null
    private var emojiMapTextView: TextView? = null
    
    // Flag per tracciare se siamo in un contesto di input valido
    private var isInputViewActive = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate() chiamato")
        prefs = getSharedPreferences("pastiera_prefs", Context.MODE_PRIVATE)
        loadLongPressThreshold()
        altKeyMap.putAll(KeyMappingLoader.loadAltKeyMappings(assets))
        symKeyMap.putAll(KeyMappingLoader.loadSymKeyMappings(assets))
        ctrlKeyMap.putAll(KeyMappingLoader.loadCtrlKeyMappings(assets))
        Log.d(TAG, "onCreate() completato")
    }
    
    /**
     * Carica la soglia del long press dalle preferenze.
     */
    private fun loadLongPressThreshold() {
        longPressThreshold = prefs.getLong("long_press_threshold", 500L).coerceIn(50L, 1000L)
    }

    override fun onCreateInputView(): View? {
        Log.d(TAG, "onCreateInputView() chiamato - statusBarLayout è null: ${statusBarLayout == null}")
        // Crea una barra di stato che mostra "Pastiera attiva"
        statusBarLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(Color.parseColor("#2196F3")) // Blu Material
        }
        
        statusBarTextView = TextView(this).apply {
            updateStatusBarText()
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(16, 12, 16, 12)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        
        statusBarLayout?.addView(statusBarTextView)
        
        // TextView per la mappa emoji (inizialmente nascosto)
        emojiMapTextView = TextView(this).apply {
            text = buildEmojiMapText()
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(16, 8, 16, 12)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
        }
        
        statusBarLayout?.addView(emojiMapTextView)
        
        updateStatusBarLayout()
        
        Log.d(TAG, "onCreateInputView() completato - view creata: ${statusBarLayout != null}")
        return statusBarLayout
    }
    
    /**
     * Costruisce il testo della mappa emoji da mostrare nella status bar.
     */
    private fun buildEmojiMapText(): String {
        val keyLabels = mapOf(
            KeyEvent.KEYCODE_Q to "Q", KeyEvent.KEYCODE_W to "W", KeyEvent.KEYCODE_E to "E",
            KeyEvent.KEYCODE_R to "R", KeyEvent.KEYCODE_T to "T", KeyEvent.KEYCODE_Y to "Y",
            KeyEvent.KEYCODE_U to "U", KeyEvent.KEYCODE_I to "I", KeyEvent.KEYCODE_O to "O",
            KeyEvent.KEYCODE_P to "P", KeyEvent.KEYCODE_A to "A", KeyEvent.KEYCODE_S to "S",
            KeyEvent.KEYCODE_D to "D", KeyEvent.KEYCODE_F to "F", KeyEvent.KEYCODE_G to "G",
            KeyEvent.KEYCODE_H to "H", KeyEvent.KEYCODE_J to "J", KeyEvent.KEYCODE_K to "K",
            KeyEvent.KEYCODE_L to "L", KeyEvent.KEYCODE_Z to "Z", KeyEvent.KEYCODE_X to "X",
            KeyEvent.KEYCODE_C to "C", KeyEvent.KEYCODE_V to "V", KeyEvent.KEYCODE_B to "B",
            KeyEvent.KEYCODE_N to "N", KeyEvent.KEYCODE_M to "M"
        )
        
        val rows = mutableListOf<String>()
        val keys = listOf(
            listOf(KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_Y, KeyEvent.KEYCODE_U, KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_O, KeyEvent.KEYCODE_P),
            listOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_L),
            listOf(KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_M)
        )
        
        for (row in keys) {
            val rowText = row.joinToString("  ") { keyCode ->
                val label = keyLabels[keyCode] ?: ""
                val emoji = symKeyMap[keyCode] ?: ""
                "$label:$emoji"
            }
            rows.add(rowText)
        }
        
        return rows.joinToString("\n")
    }
    
    /**
     * Aggiorna il layout della status bar per mostrare/nascondere la mappa emoji.
     */
    private fun updateStatusBarLayout() {
        if (symKeyActive) {
            emojiMapTextView?.visibility = View.VISIBLE
        } else {
            emojiMapTextView?.visibility = View.GONE
        }
    }
    
    // Flag per tracciare se Ctrl latch è stato attivato nel nav mode (anche quando si entra in un campo di testo)
    private var ctrlLatchFromNavMode = false
    
    /**
     * Resetta tutti gli stati dei tasti modificatori.
     * Viene chiamato quando si esce da un campo o si chiude/riapre la tastiera.
     * @param preserveNavMode Se true, preserva Ctrl latch se attivo nel nav mode
     */
    private fun resetModifierStates(preserveNavMode: Boolean = false) {
        Log.d(TAG, "resetModifierStates() chiamato - reset di tutti gli stati modificatori, preserveNavMode: $preserveNavMode, ctrlLatchActive: $ctrlLatchActive, ctrlLatchFromNavMode: $ctrlLatchFromNavMode")
        
        // Salva lo stato del nav mode se necessario
        // Se Ctrl latch è attivo e viene dal nav mode, preservalo
        val savedCtrlLatch = if (preserveNavMode && (ctrlLatchActive || ctrlLatchFromNavMode)) {
            if (ctrlLatchActive) {
                ctrlLatchFromNavMode = true // Marca che il Ctrl latch viene dal nav mode
                true
            } else if (ctrlLatchFromNavMode) {
                true // Mantieni attivo se era già marcato come nav mode
            } else {
                false
            }
        } else {
            false
        }
        
        // Reset Caps Lock
        capsLockEnabled = false
        
        // Reset stati one-shot
        shiftOneShot = false
        ctrlOneShot = false
        altOneShot = false
        
        // Reset stati latch (ma preserva Ctrl latch nel nav mode se richiesto)
        if (preserveNavMode && savedCtrlLatch) {
            // Mantieni Ctrl latch attivo nel nav mode
            ctrlLatchActive = true
            Log.d(TAG, "resetModifierStates() - preservato Ctrl latch nel nav mode")
        } else {
            ctrlLatchActive = false
            ctrlLatchFromNavMode = false // Reset anche il flag del nav mode
        }
        altLatchActive = false
        
        // Reset SYM
        symKeyActive = false
        
        // Reset stati fisici
        shiftPressed = false
        ctrlPressed = false
        altPressed = false
        
        // Reset stati fisicamente premuti (per status bar)
        shiftPhysicallyPressed = false
        ctrlPhysicallyPressed = false
        altPhysicallyPressed = false
        
        // Reset tempi di rilascio
        lastShiftReleaseTime = 0
        lastCtrlReleaseTime = 0
        lastAltReleaseTime = 0
        
        // Cancella tutti i long press in attesa
        longPressRunnables.values.forEach { handler.removeCallbacks(it) }
        longPressRunnables.clear()
        pressedKeys.clear()
        longPressActivated.clear()
        insertedNormalChars.clear()
        
        // Aggiorna la status bar
        updateStatusBarText()
    }
    
    /**
     * Forza la creazione e visualizzazione della view.
     * Viene chiamata quando viene premuto il primo tasto fisico.
     * Mostra la tastiera se c'è un campo di testo attivo o se Ctrl latch è attivo (nav mode).
     */
    private fun ensureInputViewCreated() {
        Log.d(TAG, "ensureInputViewCreated() chiamato - statusBarLayout è null: ${statusBarLayout == null}, isInputViewActive: $isInputViewActive, ctrlLatchActive: $ctrlLatchActive")
        
        // Eccezione per nav mode: mostra la tastiera se Ctrl latch è attivo
        val shouldShow = isInputViewActive || ctrlLatchActive
        
        if (!shouldShow) {
            Log.d(TAG, "ensureInputViewCreated() - non siamo in un contesto di input valido e Ctrl latch non è attivo, non mostro la tastiera")
            return
        }
        
        // Verifica se c'è un input connection valido (campo di testo attivo)
        // Nel nav mode, potrebbe non esserci un input connection, ma mostriamo comunque la tastiera
        val inputConnection = currentInputConnection
        if (inputConnection == null && !ctrlLatchActive) {
            Log.d(TAG, "ensureInputViewCreated() - nessun inputConnection e Ctrl latch non è attivo, non mostro la tastiera")
            return
        }
        
        // Se la view non esiste, creala
        if (statusBarLayout == null) {
            Log.d(TAG, "Creazione della view...")
            val newView = onCreateInputView()
            Log.d(TAG, "View creata: ${newView != null}")
            if (newView != null) {
                Log.d(TAG, "Chiamata setInputView()...")
                setInputView(newView)
                Log.d(TAG, "Chiamata requestShowSelf() per forzare la visualizzazione...")
                requestShowSelf(0)
                Log.d(TAG, "ensureInputViewCreated() completato")
            } else {
                Log.w(TAG, "View è null, non posso chiamare setInputView()")
            }
        } else {
            // View già esistente: controlla se ha già un parent
            val hasParent = statusBarLayout?.parent != null
            Log.d(TAG, "View già esistente, hasParent: $hasParent")
            
            // Aggiorna sempre il testo della status bar quando viene mostrata
            updateStatusBarText()
            
            if (!hasParent) {
                // La view esiste ma non è ancora impostata, impostala
                Log.d(TAG, "Chiamata setInputView() per view esistente senza parent...")
                setInputView(statusBarLayout)
            } else {
                Log.d(TAG, "View ha già un parent, non chiamo setInputView()")
            }
            
            // Forza la visualizzazione con requestShowSelf() solo se c'è un input connection
            Log.d(TAG, "Chiamata requestShowSelf() per forzare la visualizzazione...")
            requestShowSelf(0)
            Log.d(TAG, "ensureInputViewCreated() completato")
        }
    }
    
    
    /**
     * Gestisce i tasti quando Ctrl latch è attivo nel nav mode (senza campo di testo).
     * Permette di usare le combinazioni Ctrl+tasto anche quando non c'è un campo di testo attivo.
     */
    private fun handleCtrlKeyInNavMode(keyCode: Int, event: KeyEvent?): Boolean {
        // Notifica l'evento al tracker
        KeyboardEventTracker.notifyKeyEvent(keyCode, event, "KEY_DOWN")
        
        // Forza la creazione della view se necessario
        ensureInputViewCreated()
        
        // Gestisci gli shortcut Ctrl+tasto (solo keycode direzionali e azioni, non inserimento testo)
        val ctrlMapping = ctrlKeyMap[keyCode]
        if (ctrlMapping != null) {
            when (ctrlMapping.type) {
                "action" -> {
                    // Nel nav mode, le azioni come copy/paste non hanno senso senza campo di testo
                    // Ma possiamo gestire expand_selection se necessario
                    when (ctrlMapping.value) {
                        "expand_selection_left", "expand_selection_right" -> {
                            // Queste azioni richiedono un input connection, quindi non funzionano nel nav mode
                            Log.d(TAG, "Nav mode: azione $ctrlMapping.value richiede input connection")
                        }
                        else -> {
                            Log.d(TAG, "Nav mode: azione $ctrlMapping.value non supportata senza campo di testo")
                        }
                    }
                    // Consumiamo l'evento per evitare che Android lo gestisca
                    return true
                }
                "keycode" -> {
                    // Invia il keycode direzionale usando lo stesso metodo che usiamo quando siamo in un campo di testo
                    val dpadKeyCode = when (ctrlMapping.value) {
                        "DPAD_UP" -> KeyEvent.KEYCODE_DPAD_UP
                        "DPAD_DOWN" -> KeyEvent.KEYCODE_DPAD_DOWN
                        "DPAD_LEFT" -> KeyEvent.KEYCODE_DPAD_LEFT
                        "DPAD_RIGHT" -> KeyEvent.KEYCODE_DPAD_RIGHT
                        else -> null
                    }
                    if (dpadKeyCode != null) {
                        // Usa lo stesso metodo che funziona quando siamo in un campo di testo
                        val inputConnection = currentInputConnection
                        if (inputConnection != null) {
                            // Usa esattamente lo stesso metodo che funziona in campo di testo
                            inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, dpadKeyCode))
                            inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, dpadKeyCode))
                            Log.d(TAG, "Nav mode: inviato keycode $dpadKeyCode tramite inputConnection.sendKeyEvent (stesso metodo usato in campo di testo)")
                            return true
                        } else {
                            Log.w(TAG, "Nav mode: nessun inputConnection disponibile per inviare keycode $dpadKeyCode")
                            // Consumiamo comunque l'evento per evitare che venga processato
                            return true
                        }
                    }
                }
            }
        }
        
        // Se non c'è mappatura, passa l'evento ad Android
        return super.onKeyDown(keyCode, event)
    }
    
    /**
     * Aggiorna il testo della status bar per mostrare lo stato dei tasti modificatori.
     * Mostra minuscolo quando premuti fisicamente, maiuscolo quando in latch.
     * Se siamo in nav mode, mostra "NAV MODE" invece del testo normale.
     */
    private fun updateStatusBarText() {
        // Se siamo in nav mode, mostra "NAV MODE" come testo separato
        if (ctrlLatchFromNavMode && ctrlLatchActive) {
            statusBarTextView?.text = "NAV MODE"
            // Aggiorna lo stile per nav mode: nero semi-trasparente e più basso
            // Usa Color.argb() per creare un nero con alpha 200 (circa 78% opaco)
            statusBarLayout?.setBackgroundColor(Color.argb(100, 0, 0, 0))
            statusBarTextView?.setPadding(16, 6, 16, 6) // Padding ridotto per renderla più bassa
            statusBarTextView?.textSize = 10f // Testo leggermente più piccolo
            updateStatusBarLayout()
            return
        }
        
        // Reset dello stile normale quando non siamo in nav mode
        statusBarLayout?.setBackgroundColor(Color.parseColor("#2196F3")) // Blu Material
        statusBarTextView?.setPadding(16, 12, 16, 12) // Padding normale
        statusBarTextView?.textSize = 14f // Testo normale
        
        val statusParts = mutableListOf<String>()
        
        // Caps Lock (Shift latch) - mostra sempre 🔒 quando attivo
        if (capsLockEnabled) {
            statusParts.add("🔒")
        }
        
        // SYM key
        if (symKeyActive) {
            statusParts.add("🔣")
        }
        
        // Shift: minuscolo se premuto fisicamente, maiuscolo se in latch (Caps Lock), minuscolo se one-shot
        if (capsLockEnabled) {
            statusParts.add("SHIFT")
        } else if (shiftPhysicallyPressed) {
            statusParts.add("shift")
        } else if (shiftOneShot) {
            statusParts.add("shift")
        }
        
        // Ctrl: minuscolo se premuto fisicamente, maiuscolo se in latch, minuscolo se one-shot
        // NON mostrare Ctrl se siamo in nav mode (mostriamo "NAV MODE" invece)
        if (!ctrlLatchFromNavMode) {
            if (ctrlLatchActive) {
                statusParts.add("CTRL")
            } else if (ctrlPhysicallyPressed) {
                statusParts.add("ctrl")
            } else if (ctrlOneShot) {
                statusParts.add("ctrl")
            }
        }
        
        // Alt: minuscolo se premuto fisicamente, maiuscolo se in latch, minuscolo se one-shot
        if (altLatchActive) {
            statusParts.add("ALT")
        } else if (altPhysicallyPressed) {
            statusParts.add("alt")
        } else if (altOneShot) {
            statusParts.add("alt")
        }
        
        val status = if (statusParts.isNotEmpty()) {
            "${statusParts.joinToString(" ")} Pastiera attiva"
        } else {
            "Pastiera attiva"
        }
        statusBarTextView?.text = status
        updateStatusBarLayout()
    }
    

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        Log.d(TAG, "onStartInput() chiamato - restarting: $restarting, info: ${info?.packageName}, inputType: ${info?.inputType}, ctrlLatchActive: $ctrlLatchActive")
        
        // Verifica se il campo è effettivamente editabile
        val isEditable = info?.let { editorInfo ->
            val inputType = editorInfo.inputType
            // Controlla se è un campo di testo editabile (non TYPE_NULL)
            val isTextInput = inputType and android.text.InputType.TYPE_MASK_CLASS != android.text.InputType.TYPE_NULL
            // Escludi i campi non editabili come le liste
            val isNotNoInput = inputType and android.text.InputType.TYPE_MASK_CLASS != 0
            isTextInput && isNotNoInput
        } ?: false
        
        Log.d(TAG, "onStartInput() - isEditable: $isEditable")
        
        // Segna che siamo in un contesto di input valido solo se il campo è editabile
        isInputViewActive = isEditable
        
        // Disabilita i suggerimenti per evitare popup
        if (info != null && isEditable) {
            info.inputType = info.inputType or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        
        // Reset degli stati modificatori quando si entra in un nuovo campo (solo se non è un restart)
        // IMPORTANTE: Disattiva il nav mode SOLO quando si entra in un campo di testo editabile
        // Non disattivarlo quando si passa a un altro elemento UI (come icone, liste, ecc.)
        if (!restarting) {
            // Se siamo in nav mode, preservalo SEMPRE a meno che non entriamo in un campo realmente editabile
            if (ctrlLatchFromNavMode && ctrlLatchActive) {
                // Verifica se il campo è REALMENTE editabile (non solo sembra editabile)
                val isReallyEditable = isEditable && info?.let { editorInfo ->
                    val inputType = editorInfo.inputType
                    val inputClass = inputType and android.text.InputType.TYPE_MASK_CLASS
                    // Verifica che sia un tipo di input realmente editabile
                    inputClass == android.text.InputType.TYPE_CLASS_TEXT ||
                    inputClass == android.text.InputType.TYPE_CLASS_NUMBER ||
                    inputClass == android.text.InputType.TYPE_CLASS_PHONE ||
                    inputClass == android.text.InputType.TYPE_CLASS_DATETIME
                } ?: false
                
                // Verifica anche se abbiamo un input connection valido
                val inputConnection = currentInputConnection
                val hasValidInputConnection = inputConnection != null
                
                if (isReallyEditable && hasValidInputConnection) {
                    // Disattiva il nav mode SOLO quando si entra in un campo di testo realmente editabile
                    ctrlLatchFromNavMode = false
                    ctrlLatchActive = false
                    Log.d(TAG, "onStartInput() - disattivato nav mode perché entrato in campo di testo realmente editabile")
                    resetModifierStates(preserveNavMode = false)
                } else {
                    // Non è un campo realmente editabile o non c'è input connection - mantieni il nav mode
                    Log.d(TAG, "onStartInput() - nav mode attivo, campo non realmente editabile (isReallyEditable: $isReallyEditable, hasInputConnection: $hasValidInputConnection), mantengo nav mode")
                    // Non resettare gli stati modificatori, preserva il nav mode
                }
            } else if (isEditable) {
                // Non siamo in nav mode ma siamo in un campo editabile - reset normale
                resetModifierStates(preserveNavMode = false)
            } else if (!ctrlLatchFromNavMode) {
                // Non siamo in nav mode e non siamo in un campo editabile - reset normale
                resetModifierStates(preserveNavMode = false)
            }
            // Se siamo in nav mode e non siamo in un campo editabile, non fare nulla (mantieni nav mode)
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.d(TAG, "onStartInputView() chiamato - restarting: $restarting, statusBarLayout: ${statusBarLayout != null}, ctrlLatchFromNavMode: $ctrlLatchFromNavMode")
        
        // Verifica se il campo è effettivamente editabile
        val isEditable = info?.let { editorInfo ->
            val inputType = editorInfo.inputType
            val isTextInput = inputType and android.text.InputType.TYPE_MASK_CLASS != android.text.InputType.TYPE_NULL
            val isNotNoInput = inputType and android.text.InputType.TYPE_MASK_CLASS != 0
            isTextInput && isNotNoInput
        } ?: false
        
        // Segna che siamo in un contesto di input valido solo se il campo è editabile
        // MA: se siamo in nav mode, non impostare isInputViewActive a true se non c'è un campo editabile
        if (isEditable) {
            // Verifica se il campo è REALMENTE editabile (non solo sembra editabile)
            val isReallyEditable = info?.let { editorInfo ->
                val inputType = editorInfo.inputType
                val inputClass = inputType and android.text.InputType.TYPE_MASK_CLASS
                // Verifica che sia un tipo di input realmente editabile
                inputClass == android.text.InputType.TYPE_CLASS_TEXT ||
                inputClass == android.text.InputType.TYPE_CLASS_NUMBER ||
                inputClass == android.text.InputType.TYPE_CLASS_PHONE ||
                inputClass == android.text.InputType.TYPE_CLASS_DATETIME
            } ?: false
            
            if (isReallyEditable) {
                isInputViewActive = true
                // Se siamo in nav mode e entriamo in un campo realmente editabile, disattiva il nav mode
                if (ctrlLatchFromNavMode && ctrlLatchActive) {
                    val inputConnection = currentInputConnection
                    if (inputConnection != null) {
                        ctrlLatchFromNavMode = false
                        ctrlLatchActive = false
                        Log.d(TAG, "onStartInputView() - disattivato nav mode perché entrato in campo di testo realmente editabile")
                    }
                }
            } else {
                // Non è un campo realmente editabile - se siamo in nav mode, mantienilo
                if (ctrlLatchFromNavMode && ctrlLatchActive) {
                    Log.d(TAG, "onStartInputView() - nav mode attivo, campo non realmente editabile, mantengo nav mode")
                    isInputViewActive = false
                } else {
                    isInputViewActive = false
                }
            }
        } else if (!ctrlLatchFromNavMode) {
            // Non siamo in nav mode e non c'è un campo editabile
            isInputViewActive = false
        } else {
            // Siamo in nav mode e non c'è un campo editabile - mantieni isInputViewActive = false
            isInputViewActive = false
        }
        // Ricarica la soglia del long press (potrebbe essere cambiata nelle impostazioni)
        loadLongPressThreshold()
        // Reset dello stato quando si inizia a inserire testo
        pressedKeys.clear()
        longPressActivated.clear()
        insertedNormalChars.clear()
        // Cancella tutti i long press in attesa
        longPressRunnables.values.forEach { handler.removeCallbacks(it) }
        longPressRunnables.clear()
    }
    
    override fun onFinishInput() {
        super.onFinishInput()
        Log.d(TAG, "onFinishInput() chiamato - reset degli stati modificatori")
        // Segna che non siamo più in un contesto di input valido
        isInputViewActive = false
        // Reset degli stati modificatori quando si esce da un campo
        // Preserva Ctrl latch se attivo nel nav mode
        resetModifierStates(preserveNavMode = true)
    }
    
    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        Log.d(TAG, "onFinishInputView() chiamato - finishingInput: $finishingInput, ctrlLatchFromNavMode: $ctrlLatchFromNavMode, ctrlLatchActive: $ctrlLatchActive")
        // Segna che non siamo più in un contesto di input valido
        isInputViewActive = false
        // Reset degli stati modificatori quando la view viene nascosta
        // IMPORTANTE: Preserva il nav mode anche qui, altrimenti viene resettato quando si naviga
        if (finishingInput) {
            resetModifierStates(preserveNavMode = true)
        }
    }
    
    override fun onWindowShown() {
        super.onWindowShown()
        Log.d(TAG, "onWindowShown() chiamato - window è visibile")
        // Aggiorna il testo quando la window viene mostrata
        updateStatusBarText()
    }
    
    override fun onWindowHidden() {
        super.onWindowHidden()
        Log.d(TAG, "onWindowHidden() chiamato - window è nascosta, reset degli stati modificatori")
        // Reset degli stati modificatori quando la tastiera viene nascosta
        // Preserva Ctrl latch se attivo nel nav mode
        resetModifierStates(preserveNavMode = true)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        val inputConnection = currentInputConnection ?: return super.onKeyLongPress(keyCode, event)
        
        // Intercetta i long press PRIMA che Android li gestisca
        if (altKeyMap.containsKey(keyCode)) {
            // Consumiamo l'evento per evitare il popup di Android
            return true
        }
        
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Eccezione per il nav mode: gestisci Ctrl anche quando non siamo in un campo di testo
        val isCtrlKey = keyCode == KeyEvent.KEYCODE_CTRL_LEFT || keyCode == KeyEvent.KEYCODE_CTRL_RIGHT
        val isBackKey = keyCode == KeyEvent.KEYCODE_BACK
        
        // Gestisci il Back per uscire dal nav mode
        if (isBackKey && ctrlLatchFromNavMode && ctrlLatchActive && !isInputViewActive) {
            // Siamo in nav mode e premiamo Back - disattiva il nav mode
            ctrlLatchActive = false
            ctrlLatchFromNavMode = false
            Log.d(TAG, "Nav mode disattivato da Back")
            updateStatusBarText()
            requestHideSelf(0) // Nascondi la tastiera quando si esce dal nav mode
            // Non consumiamo l'evento Back, lasciamo che Android lo gestisca
            return super.onKeyDown(keyCode, event)
        }
        
        // Se non siamo in un contesto di input valido, gestisci Ctrl per il nav mode
        // e anche altri tasti se Ctrl latch è attivo (nav mode attivo)
        if (!isInputViewActive) {
            if (isCtrlKey) {
                // Gestisci il nav mode: doppio tap su Ctrl per attivare/disattivare Ctrl latch
                val (shouldConsume, result) = NavModeHandler.handleCtrlKeyDown(
                    keyCode,
                    ctrlPressed,
                    ctrlLatchActive,
                    lastCtrlReleaseTime
                )
                
                // IMPORTANTE: Applica PRIMA ctrlLatchActive e ctrlLatchFromNavMode
                // PRIMA di chiamare ensureInputViewCreated(), così quando viene chiamato
                // onStartInput() o onStartInputView(), il flag è già impostato
                result.ctrlLatchActive?.let { 
                    ctrlLatchActive = it
                    // Se viene attivato nel nav mode, marca il flag PRIMA
                    if (it) {
                        ctrlLatchFromNavMode = true
                        Log.d(TAG, "Nav mode: Ctrl latch attivato, ctrlLatchFromNavMode = true (impostato PRIMA di ensureInputViewCreated)")
                    } else {
                        ctrlLatchFromNavMode = false
                        Log.d(TAG, "Nav mode: Ctrl latch disattivato, ctrlLatchFromNavMode = false")
                    }
                }
                result.ctrlPhysicallyPressed?.let { ctrlPhysicallyPressed = it }
                result.lastCtrlReleaseTime?.let { lastCtrlReleaseTime = it }
                
                // Aggiorna la status bar PRIMA di mostrare la tastiera
                updateStatusBarText()
                
                if (result.shouldShowKeyboard) {
                    // Ora che i flag sono impostati, mostra la tastiera
                    ensureInputViewCreated()
                }
                if (result.shouldHideKeyboard) {
                    requestHideSelf(0)
                }
                
                if (shouldConsume) {
                    ctrlPressed = true
                    return true
                }
            } else if (ctrlLatchActive) {
                // Se Ctrl latch è attivo nel nav mode, gestisci i tasti anche senza campo di testo
                // Questo permette di usare le combinazioni Ctrl+tasto nel nav mode
                return handleCtrlKeyInNavMode(keyCode, event)
            }
            Log.d(TAG, "onKeyDown() - non siamo in un contesto di input valido, passo l'evento ad Android")
            return super.onKeyDown(keyCode, event)
        }
        
        val inputConnection = currentInputConnection ?: return super.onKeyDown(keyCode, event)
        
        Log.d(TAG, "onKeyDown() - keyCode: $keyCode, statusBarLayout: ${statusBarLayout != null}, inputConnection: ${inputConnection != null}")
        
        // Notifica sempre l'evento al tracker (anche se viene consumato)
        KeyboardEventTracker.notifyKeyEvent(keyCode, event, "KEY_DOWN")
        
        // Forza la creazione della view al primo tasto premuto (inclusi i tasti modificatori)
        // Questo assicura che la status bar sia visibile anche se la tastiera virtuale non è abilitata
        Log.d(TAG, "onKeyDown() - chiamata ensureInputViewCreated() per attivare la tastiera")
        ensureInputViewCreated()
        
        // Gestisci il doppio tap su Shift per attivare/disattivare Caps Lock
        if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            if (!shiftPressed) {
                // Shift appena premuto
                shiftPhysicallyPressed = true
                val currentTime = System.currentTimeMillis()
                
                if (capsLockEnabled) {
                    // Se Caps Lock è attivo, un singolo tap lo disattiva
                    capsLockEnabled = false
                    updateStatusBarText()
                    lastShiftReleaseTime = 0 // Reset per evitare attivazioni indesiderate
                } else if (shiftOneShot) {
                    // Se Shift one-shot è attivo, controlla se è un doppio tap veloce
                    if (currentTime - lastShiftReleaseTime < DOUBLE_TAP_THRESHOLD && lastShiftReleaseTime > 0) {
                        // Doppio tap rilevato mentre one-shot è attivo - attiva Caps Lock
                        shiftOneShot = false
                        capsLockEnabled = true
                        Log.d(TAG, "Shift doppio tap: one-shot -> Caps Lock")
                        updateStatusBarText()
                        lastShiftReleaseTime = 0 // Reset per evitare triple tap
                    } else {
                        // Singolo tap mentre one-shot è attivo - disattiva one-shot
                        shiftOneShot = false
                        Log.d(TAG, "Shift one-shot disattivato")
                        updateStatusBarText()
                        lastShiftReleaseTime = 0 // Reset per evitare attivazioni indesiderate
                    }
                } else {
                    // Se Caps Lock non è attivo e one-shot non è attivo, controlla il doppio tap
                    if (currentTime - lastShiftReleaseTime < DOUBLE_TAP_THRESHOLD && lastShiftReleaseTime > 0) {
                        // Doppio tap rilevato - attiva Caps Lock
                        capsLockEnabled = true
                        updateStatusBarText()
                        lastShiftReleaseTime = 0 // Reset per evitare triple tap
                    } else {
                        // Shift premuto da solo - attiva one-shot
                        shiftOneShot = true
                        Log.d(TAG, "Shift one-shot attivato")
                        updateStatusBarText() // Aggiorna per mostrare "shift"
                    }
                }
                shiftPressed = true
            }
            // Non consumiamo l'evento, lasciamo che Android gestisca Shift normalmente
            return super.onKeyDown(keyCode, event)
        }
        
        // Gestisci il doppio tap su Ctrl per attivare/disattivare Ctrl latch
        if (keyCode == KeyEvent.KEYCODE_CTRL_LEFT || keyCode == KeyEvent.KEYCODE_CTRL_RIGHT) {
            if (!ctrlPressed) {
                // Ctrl appena premuto
                ctrlPhysicallyPressed = true
                val currentTime = System.currentTimeMillis()
                
                if (ctrlLatchActive) {
                    // Se Ctrl latch è attivo, un singolo tap lo disattiva
                    // MA: se è attivo dal nav mode e NON siamo in un campo di testo, disattiva il nav mode
                    // Se siamo in un campo di testo, il nav mode è già stato disattivato in onStartInput
                    if (ctrlLatchFromNavMode && !isInputViewActive) {
                        // Siamo in nav mode e premiamo Ctrl - disattiva il nav mode
                        ctrlLatchActive = false
                        ctrlLatchFromNavMode = false
                        Log.d(TAG, "Ctrl latch disattivato dal nav mode (premuto Ctrl)")
                        updateStatusBarText()
                        requestHideSelf(0) // Nascondi la tastiera quando si esce dal nav mode
                    } else if (!ctrlLatchFromNavMode) {
                        // Ctrl latch normale (non nav mode), disattivalo normalmente
                        ctrlLatchActive = false
                        updateStatusBarText()
                    } else {
                        // Ctrl latch dal nav mode ma siamo in un campo di testo - non dovrebbe succedere
                        // ma se succede, disattiva comunque
                        ctrlLatchActive = false
                        ctrlLatchFromNavMode = false
                        updateStatusBarText()
                    }
                    lastCtrlReleaseTime = 0 // Reset per evitare attivazioni indesiderate
                } else if (ctrlOneShot) {
                    // Se Ctrl one-shot è attivo, controlla se è un doppio tap veloce
                    if (currentTime - lastCtrlReleaseTime < DOUBLE_TAP_THRESHOLD && lastCtrlReleaseTime > 0) {
                        // Doppio tap rilevato mentre one-shot è attivo - attiva Ctrl latch
                        ctrlOneShot = false
                        ctrlLatchActive = true
                        Log.d(TAG, "Ctrl doppio tap: one-shot -> latch")
                        updateStatusBarText()
                        lastCtrlReleaseTime = 0 // Reset per evitare triple tap
                    } else {
                        // Singolo tap mentre one-shot è attivo - disattiva one-shot
                        ctrlOneShot = false
                        Log.d(TAG, "Ctrl one-shot disattivato")
                        updateStatusBarText()
                        lastCtrlReleaseTime = 0 // Reset per evitare attivazioni indesiderate
                    }
                } else {
                    // Se Ctrl latch non è attivo e one-shot non è attivo, controlla il doppio tap
                    if (currentTime - lastCtrlReleaseTime < DOUBLE_TAP_THRESHOLD && lastCtrlReleaseTime > 0) {
                        // Doppio tap rilevato - attiva Ctrl latch
                        ctrlLatchActive = true
                        updateStatusBarText()
                        lastCtrlReleaseTime = 0 // Reset per evitare triple tap
                    } else {
                        // Ctrl premuto da solo - attiva one-shot
                        ctrlOneShot = true
                        updateStatusBarText() // Aggiorna per mostrare "ctrl"
                    }
                }
                ctrlPressed = true
            }
            // Non consumiamo l'evento, lasciamo che Android gestisca Ctrl normalmente
            return super.onKeyDown(keyCode, event)
        }
        
        // Gestisci il doppio tap su Alt per attivare/disattivare Alt latch
        if (keyCode == KeyEvent.KEYCODE_ALT_LEFT || keyCode == KeyEvent.KEYCODE_ALT_RIGHT) {
            if (!altPressed) {
                // Alt appena premuto
                altPhysicallyPressed = true
                val currentTime = System.currentTimeMillis()
                
                if (altLatchActive) {
                    // Se Alt latch è attivo, un singolo tap lo disattiva
                    altLatchActive = false
                    updateStatusBarText()
                    lastAltReleaseTime = 0 // Reset per evitare attivazioni indesiderate
                } else if (altOneShot) {
                    // Se Alt one-shot è attivo, controlla se è un doppio tap veloce
                    if (currentTime - lastAltReleaseTime < DOUBLE_TAP_THRESHOLD && lastAltReleaseTime > 0) {
                        // Doppio tap rilevato mentre one-shot è attivo - attiva Alt latch
                        altOneShot = false
                        altLatchActive = true
                        Log.d(TAG, "Alt doppio tap: one-shot -> latch")
                        updateStatusBarText()
                        lastAltReleaseTime = 0 // Reset per evitare triple tap
                    } else {
                        // Singolo tap mentre one-shot è attivo - disattiva one-shot
                        altOneShot = false
                        Log.d(TAG, "Alt one-shot disattivato")
                        updateStatusBarText()
                        lastAltReleaseTime = 0 // Reset per evitare attivazioni indesiderate
                    }
                } else {
                    // Se Alt latch non è attivo e one-shot non è attivo, controlla il doppio tap
                    if (currentTime - lastAltReleaseTime < DOUBLE_TAP_THRESHOLD && lastAltReleaseTime > 0) {
                        // Doppio tap rilevato - attiva Alt latch
                        altLatchActive = true
                        updateStatusBarText()
                        lastAltReleaseTime = 0 // Reset per evitare triple tap
                    } else {
                        // Alt premuto da solo - attiva one-shot
                        altOneShot = true
                        updateStatusBarText() // Aggiorna per mostrare "alt"
                    }
                }
                altPressed = true
            }
            // Non consumiamo l'evento, lasciamo che Android gestisca Alt normalmente
            return super.onKeyDown(keyCode, event)
        }
        
        // Gestisci il tasto SYM (toggle/latch)
        if (keyCode == KEYCODE_SYM) {
            // Toggle dello stato SYM
            symKeyActive = !symKeyActive
            updateStatusBarText()
            // Consumiamo l'evento per evitare che Android lo gestisca
            return true
        }
        
        // Gestisci il keycode 322 per cancellare l'ultima parola
        if (keyCode == 322) {
            if (TextSelectionHelper.deleteLastWord(inputConnection)) {
                // Consumiamo l'evento
                return true
            }
        }
        
        // Se il tasto è già premuto, consumiamo l'evento per evitare ripetizioni e popup
        if (pressedKeys.containsKey(keyCode)) {
            return true
        }
        
        // Gestisci gli shortcut Ctrl+tasto (controlla sia Ctrl premuto che Ctrl latch attivo o one-shot)
        // IMPORTANTE: Se siamo in nav mode (ctrlLatchFromNavMode), il Ctrl latch NON deve essere disattivato
        // MA: se siamo in un campo di testo, il nav mode è già stato disattivato, quindi usiamo il Ctrl latch normale
        if (event?.isCtrlPressed == true || ctrlLatchActive || ctrlOneShot) {
            // Se era one-shot, disattivalo dopo l'uso (ma NON se siamo in nav mode)
            val wasOneShot = ctrlOneShot
            if (wasOneShot && !ctrlLatchFromNavMode) {
                ctrlOneShot = false
                updateStatusBarText()
            }
            // IMPORTANTE: Se siamo in nav mode, NON disattivare mai il Ctrl latch dopo l'uso di un tasto
            // Il Ctrl latch rimane attivo finché non si esce dal nav mode
            
            // Controlla se esiste una mappatura Ctrl per questo tasto
            val ctrlMapping = ctrlKeyMap[keyCode]
            if (ctrlMapping != null) {
                when (ctrlMapping.type) {
                    "action" -> {
                        // Gestisci azioni speciali personalizzate
                        when (ctrlMapping.value) {
                            "expand_selection_left" -> {
                                if (TextSelectionHelper.expandSelectionLeft(inputConnection)) {
                                    return true
                                }
                            }
                            "expand_selection_right" -> {
                                if (TextSelectionHelper.expandSelectionRight(inputConnection)) {
                                    return true
                                }
                            }
                            else -> {
                                // Esegui l'azione del context menu standard
                                val actionId = when (ctrlMapping.value) {
                                    "copy" -> android.R.id.copy
                                    "paste" -> android.R.id.paste
                                    "cut" -> android.R.id.cut
                                    "undo" -> android.R.id.undo
                                    "select_all" -> android.R.id.selectAll
                                    else -> null
                                }
                                if (actionId != null) {
                                    inputConnection.performContextMenuAction(actionId)
                                    return true
                                }
                            }
                        }
                    }
                    "keycode" -> {
                        // Invia il keycode direzionale
                        val dpadKeyCode = when (ctrlMapping.value) {
                            "DPAD_UP" -> KeyEvent.KEYCODE_DPAD_UP
                            "DPAD_DOWN" -> KeyEvent.KEYCODE_DPAD_DOWN
                            "DPAD_LEFT" -> KeyEvent.KEYCODE_DPAD_LEFT
                            "DPAD_RIGHT" -> KeyEvent.KEYCODE_DPAD_RIGHT
                            else -> null
                        }
                        if (dpadKeyCode != null) {
                            inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, dpadKeyCode))
                            inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, dpadKeyCode))
                            return true
                        }
                    }
                }
            }
        }
        
        // Se Alt è premuto o Alt latch è attivo o Alt one-shot, gestisci la combinazione Alt+tasto
        if (event?.isAltPressed == true || altLatchActive || altOneShot) {
            // Cancella eventuali long press in attesa per questo tasto
            longPressRunnables[keyCode]?.let { handler.removeCallbacks(it) }
            longPressRunnables.remove(keyCode)
            // Se era one-shot, disattivalo dopo l'uso
            if (altOneShot) {
                altOneShot = false
                updateStatusBarText()
            }
            return handleAltKey(keyCode, inputConnection, event)
        }
        
        // Se SYM è attivo, controlla prima la mappa SYM
        if (symKeyActive) {
            val symChar = symKeyMap[keyCode]
            if (symChar != null) {
                // Inserisci l'emoji dalla mappa SYM
                inputConnection.commitText(symChar, 1)
                // Consumiamo l'evento
                return true
            }
        }
        
        // Gestisci Shift one-shot PRIMA di tutto (deve avere priorità)
        if (shiftOneShot && event != null && event.unicodeChar != 0) {
            var char = event.unicodeChar.toChar().toString()
            if (char.isNotEmpty() && char[0].isLetter()) {
                Log.d(TAG, "Shift one-shot attivo, carattere originale: $char")
                // Forza sempre il maiuscolo quando shiftOneShot è attivo
                char = char.uppercase()
                Log.d(TAG, "Shift one-shot, carattere modificato: $char")
                shiftOneShot = false
                updateStatusBarText()
                inputConnection.commitText(char, 1)
                return true
            }
        }
        
        // Controlla se questo tasto ha una mappatura Alt
        val hasAltMapping = altKeyMap.containsKey(keyCode)
        
        if (hasAltMapping) {
            
            // Consumiamo l'evento per evitare che Android mostri il popup
            // Registra il tempo di pressione del tasto
            pressedKeys[keyCode] = System.currentTimeMillis()
            longPressActivated[keyCode] = false
            
            // Inserisci SUBITO il carattere normale a schermo
            var normalChar = if (event != null && event.unicodeChar != 0) {
                event.unicodeChar.toChar().toString()
            } else {
                ""
            }
            
            // Applica Caps Lock se attivo (ma solo se Shift non è premuto)
            if (normalChar.isNotEmpty() && capsLockEnabled && event?.isShiftPressed != true) {
                // Se Caps Lock è attivo e Shift non è premuto, rendi maiuscolo
                normalChar = normalChar.uppercase()
            } else if (normalChar.isNotEmpty() && capsLockEnabled && event?.isShiftPressed == true) {
                // Se Caps Lock è attivo e Shift è premuto, rendi minuscolo (comportamento standard)
                normalChar = normalChar.lowercase()
            }
            
            if (normalChar.isNotEmpty()) {
                inputConnection.commitText(normalChar, 1)
                insertedNormalChars[keyCode] = normalChar
            }
            
            // Gestisci il long press per simulare Alt+tasto
            scheduleLongPress(keyCode, inputConnection)
            
            // Consumiamo l'evento per evitare il popup standard di Android
            return true
        }
        
        // Se non ha mappatura, gestisci Caps Lock per i caratteri normali
        // Applica Caps Lock ai caratteri alfabetici
        if (event != null && event.unicodeChar != 0) {
            var char = event.unicodeChar.toChar().toString()
            var shouldConsume = false
            
            // Applica Caps Lock se attivo (ma solo se Shift non è premuto)
            if (capsLockEnabled && event.isShiftPressed != true && char.isNotEmpty() && char[0].isLetter()) {
                char = char.uppercase()
                shouldConsume = true
            } else if (capsLockEnabled && event.isShiftPressed == true && char.isNotEmpty() && char[0].isLetter()) {
                // Se Caps Lock è attivo e Shift è premuto, rendi minuscolo
                char = char.lowercase()
                shouldConsume = true
            }
            
            if (shouldConsume) {
                inputConnection.commitText(char, 1)
                return true
            }
        }
        
        // Se non ha mappatura, lascia che Android gestisca normalmente
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // Eccezione per il nav mode: gestisci Ctrl anche quando non siamo in un campo di testo
        val isCtrlKey = keyCode == KeyEvent.KEYCODE_CTRL_LEFT || keyCode == KeyEvent.KEYCODE_CTRL_RIGHT
        
        if (!isInputViewActive && isCtrlKey) {
            // Gestisci il rilascio di Ctrl nel nav mode
            if (ctrlPressed) {
                val result = NavModeHandler.handleCtrlKeyUp()
                result.ctrlPressed?.let { ctrlPressed = it }
                result.ctrlPhysicallyPressed?.let { ctrlPhysicallyPressed = it }
                result.lastCtrlReleaseTime?.let { lastCtrlReleaseTime = it }
                updateStatusBarText()
                Log.d(TAG, "Nav mode: Ctrl rilasciato")
            }
            // Consumiamo l'evento per evitare che Android lo gestisca
            return true
        }
        
        val inputConnection = currentInputConnection ?: return super.onKeyUp(keyCode, event)
        
        // Notifica sempre l'evento al tracker (anche se viene consumato)
        KeyboardEventTracker.notifyKeyEvent(keyCode, event, "KEY_UP")
        
        // Gestisci il rilascio di Shift per il doppio tap
        if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            if (shiftPressed) {
                lastShiftReleaseTime = System.currentTimeMillis()
                shiftPressed = false
                shiftPhysicallyPressed = false
                // Non disattivare shiftOneShot qui - viene disattivato quando viene usato
                // Se viene rilasciato senza essere usato, rimane attivo per il prossimo tasto
                updateStatusBarText() // Aggiorna per rimuovere "shift" fisico
            }
            // Non consumiamo l'evento, lasciamo che Android gestisca Shift normalmente
            return super.onKeyUp(keyCode, event)
        }
        
        // Gestisci il rilascio di Ctrl per il doppio tap
        if (keyCode == KeyEvent.KEYCODE_CTRL_LEFT || keyCode == KeyEvent.KEYCODE_CTRL_RIGHT) {
            if (ctrlPressed) {
                lastCtrlReleaseTime = System.currentTimeMillis()
                ctrlPressed = false
                ctrlPhysicallyPressed = false
                // Non disattivare ctrlOneShot qui - viene disattivato quando viene usato
                // Se viene rilasciato senza essere usato, rimane attivo per il prossimo tasto
                updateStatusBarText() // Aggiorna per rimuovere "ctrl" fisico
            }
            // Non consumiamo l'evento, lasciamo che Android gestisca Ctrl normalmente
            return super.onKeyUp(keyCode, event)
        }
        
        // Gestisci il rilascio di Alt per il doppio tap
        if (keyCode == KeyEvent.KEYCODE_ALT_LEFT || keyCode == KeyEvent.KEYCODE_ALT_RIGHT) {
            if (altPressed) {
                lastAltReleaseTime = System.currentTimeMillis()
                altPressed = false
                altPhysicallyPressed = false
                // Non disattivare altOneShot qui - viene disattivato quando viene usato
                // Se viene rilasciato senza essere usato, rimane attivo per il prossimo tasto
                updateStatusBarText() // Aggiorna per rimuovere "alt" fisico
            }
            // Non consumiamo l'evento, lasciamo che Android gestisca Alt normalmente
            return super.onKeyUp(keyCode, event)
        }
        
        // Gestisci il rilascio del tasto SYM (non serve fare nulla, è un toggle)
        if (keyCode == KEYCODE_SYM) {
            // Consumiamo l'evento
            return true
        }
        
        val pressStartTime = pressedKeys.remove(keyCode)
        
        // Controlla PRIMA se è stato un long press (prima di rimuovere dalla mappa)
        val wasLongPress = longPressActivated.containsKey(keyCode)
        if (wasLongPress) {
            // Rimuovi dalla mappa dopo aver controllato
            longPressActivated.remove(keyCode)
        }
        
        val insertedChar = insertedNormalChars.remove(keyCode)
        
        // Cancella il long press in attesa per questo tasto
        val longPressRunnable = longPressRunnables.remove(keyCode)
        longPressRunnable?.let { handler.removeCallbacks(it) }
        
        // Se il tasto ha una mappatura Alt e abbiamo gestito l'evento in onKeyDown
        // Ma solo se SYM non era attivo (perché se SYM era attivo, abbiamo già inserito l'emoji)
        if (pressStartTime != null && altKeyMap.containsKey(keyCode) && !symKeyActive) {
            // Consumiamo sempre l'evento per evitare il popup
            if (wasLongPress) {
                // È stato un long press, il carattere normale è già stato cancellato
                // e il carattere Alt è già stato inserito nel Runnable
                // Non fare nulla
                return true
            } else {
                // Pressione normale, il carattere normale è già stato inserito in onKeyDown
                // Non fare nulla
                return true
            }
        }
        
        return super.onKeyUp(keyCode, event)
    }

    /**
     * Gestisce la combinazione Alt+tasto premuta fisicamente.
     */
    private fun handleAltKey(
        keyCode: Int,
        inputConnection: InputConnection,
        event: KeyEvent?
    ): Boolean {
        val altChar = altKeyMap[keyCode]
        
        if (altChar != null) {
            // Invia il carattere speciale corrispondente ad Alt+tasto
            inputConnection.commitText(altChar, 1)
            return true
        }
        
        // Se non c'è una mappatura, lascia che il sistema gestisca il tasto
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Programma il controllo del long press per simulare Alt+tasto.
     */
    private fun scheduleLongPress(
        keyCode: Int,
        inputConnection: InputConnection
    ) {
        // Ricarica sempre il valore dalle preferenze per applicare le modifiche immediatamente
        loadLongPressThreshold()
        
        // Crea un Runnable per gestire il long press
        val longPressRunnable = Runnable {
            // Se il tasto è ancora premuto dopo il threshold, è un long press
            if (pressedKeys.containsKey(keyCode)) {
                val altChar = altKeyMap[keyCode]
                val insertedChar = insertedNormalChars[keyCode]
                
                if (altChar != null) {
                    // Segna che il long press è stato attivato
                    longPressActivated[keyCode] = true
                    
                    // Cancella il carattere normale che è stato inserito in onKeyDown
                    if (insertedChar != null && insertedChar.isNotEmpty()) {
                        // Cancella il carattere normale (backspace)
                        inputConnection.deleteSurroundingText(1, 0)
                    }
                    
                    // Inserisci il carattere Alt+tasto
                    inputConnection.commitText(altChar, 1)
                    
                    // Rimuovi il carattere normale dalla mappa
                    insertedNormalChars.remove(keyCode)
                    
                    // Rimuovi anche il Runnable dalla mappa
                    longPressRunnables.remove(keyCode)
                }
            }
        }
        
        // Salva il Runnable per poterlo cancellare se necessario
        longPressRunnables[keyCode] = longPressRunnable
        
        // Programma l'esecuzione dopo il threshold (usa il valore dalle preferenze)
        handler.postDelayed(longPressRunnable, longPressThreshold)
    }

    
    /**
     * Aggiunge una nuova mappatura Alt+tasto -> carattere.
     */
    fun addAltKeyMapping(keyCode: Int, character: String) {
        altKeyMap[keyCode] = character
    }

    /**
     * Rimuove una mappatura Alt+tasto esistente.
     */
    fun removeAltKeyMapping(keyCode: Int) {
        altKeyMap.remove(keyCode)
    }
}

