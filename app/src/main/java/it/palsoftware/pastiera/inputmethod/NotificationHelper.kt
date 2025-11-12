package it.palsoftware.pastiera.inputmethod

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Helper per gestire le notifiche dell'app.
 */
object NotificationHelper {
    private const val CHANNEL_ID = "pastiera_nav_mode_channel"
    private const val CHANNEL_NAME = "Pastiera Nav Mode"
    private const val NOTIFICATION_ID = 1
    
    /**
     * Verifica se il permesso per le notifiche è concesso.
     * Su Android 13+ (API 33+) è necessario il permesso POST_NOTIFICATIONS.
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ richiede il permesso POST_NOTIFICATIONS
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Android 12 e precedenti non richiedono permessi espliciti per le notifiche
            true
        }
    }
    
    /**
     * Crea il canale di notifica (richiesto per Android 8.0+).
     */
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW // Notifica discreta
            ).apply {
                description = "Notifiche per il nav mode di Pastiera"
                setShowBadge(false)
            }
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * Mostra una notifica per l'attivazione del nav mode.
     * Verifica prima se il permesso è concesso.
     */
    fun showNavModeActivatedNotification(context: Context) {
        // Verifica se il permesso è concesso
        if (!hasNotificationPermission(context)) {
            android.util.Log.w("NotificationHelper", "Permesso per le notifiche non concesso")
            return
        }
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Crea il canale se non esiste (per Android 8.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel(context)
        }
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Nav Mode Activated")
            .setContentText("Nav mode activated")
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Icona di sistema
            .setPriority(NotificationCompat.PRIORITY_LOW) // Priorità bassa
            .setAutoCancel(true) // Si chiude automaticamente quando viene toccata
            .setOngoing(false) // Non persistente
            .build()
        
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    /**
     * Cancella la notifica del nav mode.
     */
    fun cancelNavModeNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }
}

