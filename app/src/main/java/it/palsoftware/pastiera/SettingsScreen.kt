package it.palsoftware.pastiera

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.activity.compose.BackHandler

/**
 * Schermata delle impostazioni dell'app.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    
    // Carica il valore salvato del long press threshold
    var longPressThreshold by remember { 
        mutableStateOf(SettingsManager.getLongPressThreshold(context))
    }
    
    // Carica il valore salvato dell'auto-maiuscola
    var autoCapitalizeFirstLetter by remember {
        mutableStateOf(SettingsManager.getAutoCapitalizeFirstLetter(context))
    }
    
    // Gestisci il back button di sistema
    BackHandler {
        onBack()
    }
    
    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header con pulsante back
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Indietro"
                )
            }
            Text(
                text = "Impostazioni",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        
        Divider()
        
        // Sezione Long Press Threshold
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Long Press",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                
                Text(
                    text = "Durata Long Press: ${longPressThreshold}ms",
                    style = MaterialTheme.typography.bodyLarge
                )
                
                Text(
                    text = "Tempo di attesa prima che un long press venga riconosciuto. Un valore più basso rende il long press più reattivo, mentre un valore più alto richiede una pressione più prolungata.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Slider(
                    value = longPressThreshold.toFloat(),
                    onValueChange = { newValue ->
                        val clampedValue = newValue.toLong().coerceIn(
                            SettingsManager.getMinLongPressThreshold(),
                            SettingsManager.getMaxLongPressThreshold()
                        )
                        longPressThreshold = clampedValue
                        // Salva il valore nelle preferenze
                        SettingsManager.setLongPressThreshold(context, clampedValue)
                    },
                    valueRange = SettingsManager.getMinLongPressThreshold().toFloat()..SettingsManager.getMaxLongPressThreshold().toFloat(),
                    steps = 18, // 19 valori (50, 100, 150, ..., 1000)
                    modifier = Modifier.fillMaxWidth()
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${SettingsManager.getMinLongPressThreshold()}ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${SettingsManager.getMaxLongPressThreshold()}ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Text(
                    text = "Impostazione applicata immediatamente",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        
        // Sezione Auto-Maiuscola
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Auto-Maiuscola Prima Lettera",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Mette automaticamente in maiuscolo la prima lettera quando si inizia a scrivere in un campo di testo vuoto.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Switch(
                        checked = autoCapitalizeFirstLetter,
                        onCheckedChange = { enabled ->
                            autoCapitalizeFirstLetter = enabled
                            SettingsManager.setAutoCapitalizeFirstLetter(context, enabled)
                        }
                    )
                }
            }
        }
        
        // Sezione Info
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
                    text = "Informazioni",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Le impostazioni vengono salvate automaticamente e applicate immediatamente all'input method.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

