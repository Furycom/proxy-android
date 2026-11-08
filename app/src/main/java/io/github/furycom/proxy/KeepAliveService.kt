package io.github.furycom.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/** Garde l'app (et la connexion Bluetooth au Proxy) en vie quand on quitte l'écran. */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("proxy", "Proxy connecté", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "proxy")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Proxy")
            .setContentText("Connexion Bluetooth active")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        try {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (_: Exception) {
            // Android 12+ refuse parfois un service de premier plan lancé depuis l'arrière-plan : pas de plantage
            stopSelf()
            return START_NOT_STICKY
        }
        // pas de relance automatique par le système : c'est l'app qui démarre la connexion à son ouverture
        return START_NOT_STICKY
    }
}
