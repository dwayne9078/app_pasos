package com.example.myapplication
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import android.util.Log
import androidx.core.app.ActivityCompat // Import for ActivityCompat
import com.example.myapplication.presentation.MainActivity

// Estas constantes ya están definidas en MainActivity, pero las repetimos aquí
// para asegurar que estén disponibles para este servicio sin problemas de importación
// en caso de que cambies la estructura de paquetes en el futuro.
const val NOTIFICATION_CHANNEL_ID = "step_tracker_channel"
const val NOTIFICATION_ID = 101

/**
 * Servicio para manejar los mensajes de Firebase Cloud Messaging (FCM).
 * Este servicio se activa cuando la aplicación recibe una notificación push de FCM.
 */
class MyFirebaseMessagingService : FirebaseMessagingService() {

    /**
     * Se llama cuando se recibe un mensaje de FCM.
     * Puede contener una carga útil de notificación, una carga útil de datos o ambas.
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        // Manejar mensajes de FCM.
        // Comprobar si el mensaje contiene una carga útil de notificación (título, cuerpo).
        remoteMessage.notification?.let { notification ->
            val title = notification.title ?: "Notificación"
            val body = notification.body ?: "Mensaje de la aplicación"

            // Mostrar la notificación utilizando la función auxiliar.
            showNotification(applicationContext, title, body)
        }

        // Comprobar si el mensaje contiene una carga útil de datos.
        // Los mensajes de datos son útiles para enviar información a la aplicación
        // sin necesidad de mostrar una notificación visual directamente.
        remoteMessage.data.isNotEmpty().let {
            // Manejar la carga útil de datos (por ejemplo, actualizar la interfaz de usuario,
            // activar tareas en segundo plano, etc.).
            // Por ejemplo, si tu carga útil de datos tiene "steps_milestone": "10000".
            val milestone = remoteMessage.data["steps_milestone"]
            if (milestone != null) {
                // Puedes optar por mostrar una notificación diferente o realizar otra acción.
                showNotification(applicationContext, "¡Felicidades!", "Has alcanzado $milestone pasos!")
            }
        }
    }

    /**
     * Se llama cuando se genera un nuevo token de registro de FCM.
     * Debes enviar este token a tu servidor para poder enviar notificaciones dirigidas
     * a este dispositivo específico.
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("FCM_Token", "Token de registro de FCM actualizado: $token")
        // Aquí es donde normalmente enviarías el 'token' a tu servidor de aplicaciones.
    }

    /**
     * Función auxiliar para mostrar una notificación en el dispositivo.
     * Verifica el permiso POST_NOTIFICATIONS para Android 13+ antes de mostrarla.
     */
    private fun showNotification(context: Context, title: String, message: String) {
        // Intent para abrir la MainActivity cuando se toca la notificación.
        val intent = Intent(context, MainActivity::class.java).apply {
            // Estas flags aseguran que se cree una nueva tarea para la actividad
            // y que cualquier actividad existente en la pila sea eliminada.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        // PendingIntent para envolver el Intent, permitiendo que otro proceso
        // (el sistema de notificaciones) lo ejecute. FLAG_IMMUTABLE es requerido en Android 12+.
        val pendingIntent: PendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        // Constructor de la notificación.
        val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_notification_overlay) // Icono pequeño de la notificación. ¡Considera usar uno propio!
            .setContentTitle(title) // Título de la notificación.
            .setContentText(message) // Texto principal de la notificación.
            .setPriority(NotificationCompat.PRIORITY_DEFAULT) // Prioridad de la notificación.
            .setContentIntent(pendingIntent) // Establece el Intent que se lanza al tocar la notificación.
            .setAutoCancel(true) // La notificación se cancela automáticamente al tocarla.

        with(NotificationManagerCompat.from(context)) {
            // Para Android 13+ (API 33), el permiso POST_NOTIFICATIONS es obligatorio.
            // Si el permiso no está concedido, no se puede mostrar la notificación.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    // El permiso no está concedido. Registramos un mensaje y salimos.
                    // La lógica para solicitar este permiso debe estar en la Activity.
                    Log.w("FCM_Notification", "Permiso POST_NOTIFICATIONS no concedido. No se puede mostrar la notificación.")
                    return
                }
            }
            // Muestra la notificación. NOTIFICATION_ID es un entero único para cada notificación.
            notify(NOTIFICATION_ID, builder.build())
        }
    }
}