package it.palsoftware.pastiera

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.palsoftware.pastiera.inputmethod.KeyboardEventTracker
import it.palsoftware.pastiera.ui.theme.PastieraTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings

class MainActivity : ComponentActivity() {
    
    data class KeyEventInfo(
        val keyCode: Int,
        val keyCodeName: String,
        val action: String,
        val unicodeChar: Int,
        val isAltPressed: Boolean,
        val isShiftPressed: Boolean,
        val isCtrlPressed: Boolean
    )
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Notifica solo gli eventi che non sono keycode di output generati dal servizio
        // I keycode di output (DPAD, TAB, PAGE_UP, PAGE_DOWN, ESCAPE) senza modificatori
        // sono generati dal servizio e l'evento originale con output è già stato notificato
        if (event != null) {
            val isOutputKeyCode = keyCode in listOf(
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_TAB,
                KeyEvent.KEYCODE_PAGE_UP,
                KeyEvent.KEYCODE_PAGE_DOWN,
                KeyEvent.KEYCODE_ESCAPE
            )
            val hasModifiers = event.isAltPressed || event.isShiftPressed || event.isCtrlPressed
            
            // Ignora i keycode di output senza modificatori (sono generati dal servizio)
            if (!isOutputKeyCode || hasModifiers) {
                KeyboardEventTracker.notifyKeyEvent(keyCode, event, "KEY_DOWN")
            }
        }
        return super.onKeyDown(keyCode, event)
    }
    
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // Notifica solo gli eventi che non sono keycode di output generati dal servizio
        if (event != null) {
            val isOutputKeyCode = keyCode in listOf(
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_TAB,
                KeyEvent.KEYCODE_PAGE_UP,
                KeyEvent.KEYCODE_PAGE_DOWN,
                KeyEvent.KEYCODE_ESCAPE
            )
            val hasModifiers = event.isAltPressed || event.isShiftPressed || event.isCtrlPressed
            
            // Ignora i keycode di output senza modificatori (sono generati dal servizio)
            if (!isOutputKeyCode || hasModifiers) {
                KeyboardEventTracker.notifyKeyEvent(keyCode, event, "KEY_UP")
            }
        }
        return super.onKeyUp(keyCode, event)
    }
    
    private fun getKeyCodeName(keyCode: Int): String {
        return when (keyCode) {
            KeyEvent.KEYCODE_Q -> "KEYCODE_Q"
            KeyEvent.KEYCODE_W -> "KEYCODE_W"
            KeyEvent.KEYCODE_E -> "KEYCODE_E"
            KeyEvent.KEYCODE_R -> "KEYCODE_R"
            KeyEvent.KEYCODE_T -> "KEYCODE_T"
            KeyEvent.KEYCODE_Y -> "KEYCODE_Y"
            KeyEvent.KEYCODE_U -> "KEYCODE_U"
            KeyEvent.KEYCODE_I -> "KEYCODE_I"
            KeyEvent.KEYCODE_O -> "KEYCODE_O"
            KeyEvent.KEYCODE_P -> "KEYCODE_P"
            KeyEvent.KEYCODE_A -> "KEYCODE_A"
            KeyEvent.KEYCODE_S -> "KEYCODE_S"
            KeyEvent.KEYCODE_D -> "KEYCODE_D"
            KeyEvent.KEYCODE_F -> "KEYCODE_F"
            KeyEvent.KEYCODE_G -> "KEYCODE_G"
            KeyEvent.KEYCODE_H -> "KEYCODE_H"
            KeyEvent.KEYCODE_J -> "KEYCODE_J"
            KeyEvent.KEYCODE_K -> "KEYCODE_K"
            KeyEvent.KEYCODE_L -> "KEYCODE_L"
            KeyEvent.KEYCODE_Z -> "KEYCODE_Z"
            KeyEvent.KEYCODE_X -> "KEYCODE_X"
            KeyEvent.KEYCODE_C -> "KEYCODE_C"
            KeyEvent.KEYCODE_V -> "KEYCODE_V"
            KeyEvent.KEYCODE_B -> "KEYCODE_B"
            KeyEvent.KEYCODE_N -> "KEYCODE_N"
            KeyEvent.KEYCODE_M -> "KEYCODE_M"
            KeyEvent.KEYCODE_SPACE -> "KEYCODE_SPACE"
            KeyEvent.KEYCODE_ENTER -> "KEYCODE_ENTER"
            KeyEvent.KEYCODE_DEL -> "KEYCODE_DEL"
            KeyEvent.KEYCODE_BACK -> "KEYCODE_BACK"
            else -> "KEYCODE_$keyCode"
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PastieraTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    KeyboardSetupScreen(
                        modifier = Modifier
                            .padding(innerPadding)
                            .fillMaxSize(),
                        activity = this@MainActivity
                    )
                }
            }
        }
    }
}

@Composable
fun KeyboardSetupScreen(
    modifier: Modifier = Modifier,
    activity: MainActivity
) {
    val context = LocalContext.current
    
    var testText by remember { mutableStateOf("") }
    val lastKeyEventState = remember { mutableStateOf<KeyboardEventTracker.KeyEventInfo?>(null) }
    val lastKeyEvent by lastKeyEventState
    
    // Stato per la navigazione alle impostazioni
    var showSettings by remember { mutableStateOf(false) }
    
    // Collega lo stato al tracker globale
    LaunchedEffect(Unit) {
        KeyboardEventTracker.registerState(lastKeyEventState)
    }
    
    // Pulisci lo stato quando il composable viene rimosso
    DisposableEffect(Unit) {
        onDispose {
            KeyboardEventTracker.unregisterState()
        }
    }
    
    // Navigazione condizionale
    if (showSettings) {
        SettingsScreen(
            modifier = modifier,
            onBack = { showSettings = false }
        )
        return
    }
    
    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header con pulsante impostazioni
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Pastiera Keyboard",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Tastiera fisica con supporto long press",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { showSettings = true }) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Impostazioni"
                )
            }
        }
        
        Divider()
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Installazione",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "1. Clicca il pulsante qui sotto per aprire le impostazioni",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "2. Attiva 'Tastiera Fisica Pastiera'",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "3. Torna qui e testa la tastiera",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        
        Button(
            onClick = {
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Apri Impostazioni Tastiera")
        }
        
        Divider()
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Test Tastiera",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Collega una tastiera fisica e testa qui sotto:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "• Pressione normale: inserisce il carattere",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "• Long press (500ms): inserisce il carattere Alt",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        
        OutlinedTextField(
            value = testText,
            onValueChange = { testText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Campo di test") },
            placeholder = { Text("Digita qui con la tastiera fisica...") },
            minLines = 5,
            maxLines = 10
        )
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Ultimo Evento Tastiera",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                val event = lastKeyEvent
                if (event != null) {
                    Text(
                        text = "KeyCode: ${event.keyCode} (${event.keyCodeName})",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Azione: ${event.action}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Unicode: ${event.unicodeChar} (${if (event.unicodeChar != 0) event.unicodeChar.toChar() else "N/A"})",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                    if (event.outputKeyCodeName != null) {
                        Text(
                            text = "Output: ${event.outputKeyCodeName}${if (event.outputKeyCode != null) " (${event.outputKeyCode})" else ""}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (event.isAltPressed) {
                            Text(
                                text = "ALT",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (event.isShiftPressed) {
                            Text(
                                text = "SHIFT",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (event.isCtrlPressed) {
                            Text(
                                text = "CTRL",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    Text(
                        text = "Nessun evento ancora",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Mappature Long Press",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Q→0, W→1, E→2, R→3, T→(, Y→), U→-, I→_, O→', P→:",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "A→@, S→4, D→5, F→6, G→*, H→#, J→+, K→\", L→'",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Z→!, X→7, C→8, V→9, B→., N→', M→?",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        
        Button(
            onClick = { showSettings = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.tertiary
            )
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Impostazioni")
        }
        
        Button(
            onClick = {
                val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary
            )
        ) {
            Text("Cambia Metodo di Immissione")
        }
    }
}