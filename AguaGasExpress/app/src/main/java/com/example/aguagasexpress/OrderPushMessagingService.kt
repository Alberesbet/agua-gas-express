package com.example.aguagasexpress

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.Locale

class OrderPushMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val prefs = getSharedPreferences("agua_gas_preferences", Context.MODE_PRIVATE)
        prefs.edit().putString("customer_fcm_token", token).apply()
        if (prefs.getString("admin_password_hash", null).isNullOrBlank()) return
        prefs.edit().putString("admin_fcm_token", token).apply()
        val auth = FirebaseAuth.getInstance()
        fun saveToken() {
            FirebaseFirestore.getInstance().collection("appConfig").document("main")
                .set(mapOf("adminFcmToken" to token), SetOptions.merge())
        }
        if (auth.currentUser != null) saveToken()
        else auth.signInAnonymously().addOnSuccessListener { saveToken() }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val type = message.data["type"] ?: return
        if (type !in setOf("new_order", "pix_reported", "customer_arrived")) return
        // No painel aberto, o listener do Firestore faz o aviso administrativo para evitar duplicidade.
        // O aviso de chegada ao cliente também pode ser mostrado enquanto o app estiver visível.
        if (type != "customer_arrived" && MainActivity.isAppVisible && !MainActivity.isOnDeliveryScreen) return
        val orderId = message.data["orderId"].orEmpty()
        if (orderId.isNotBlank()) {
            val prefs = getSharedPreferences("agua_gas_preferences", Context.MODE_PRIVATE)
            val seen = prefs.getString("admin_seen_pending_order_ids", "").orEmpty()
                .split(",").filter { it.isNotBlank() }.toMutableSet()
            seen.add(orderId)
            prefs.edit().putString("admin_seen_pending_order_ids", seen.joinToString(",")).apply()
        }
        createChannel()
        val title = message.data["title"] ?: if (type == "customer_arrived") "Pedido chegou" else "Novo pedido"
        val body = message.data["body"] ?: if (type == "customer_arrived") "Seu pedido chegou" else "Chegou mais um pedido!"
        showNotification(title, body)
        speakOrderAlert(if (type == "customer_arrived") "Seu pedido chegou" else "Chegou mais um pedido!")
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Novos pedidos",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Avisos de novos pedidos do Água & Gás Express"
                enableVibration(true)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(title: String, body: String) {
        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 2602, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(2602, notification)
        } catch (_: SecurityException) {
            // O Android pode bloquear notificações se o usuário não concedeu a permissão.
        }
    }

    private fun speakOrderAlert(phrase: String) {
        try {
            lateinit var speech: TextToSpeech
            speech = TextToSpeech(applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    try {
                        val languageStatus = speech.setLanguage(Locale("pt", "BR"))
                        if (languageStatus == TextToSpeech.LANG_MISSING_DATA ||
                            languageStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
                            speech.shutdown()
                            return@TextToSpeech
                        }
                        speech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {}
                            override fun onDone(utteranceId: String?) { speech.shutdown() }
                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) { speech.shutdown() }
                        })
                        speech.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "agua_gas_push_alert")
                    } catch (_: Exception) { speech.shutdown() }
                } else {
                    speech.shutdown()
                }
            }
        } catch (_: Exception) { }
    }

    companion object {
        private const val CHANNEL_ID = "pedidos_novos"
    }
}
