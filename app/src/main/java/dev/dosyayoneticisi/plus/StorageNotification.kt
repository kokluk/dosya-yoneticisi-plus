package dev.dosyayoneticisi.plus

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

@Composable
internal fun StorageNotification(s: Screen, p: Preferences) {
    val context = LocalContext.current
    val primary = s.volumes.firstOrNull { it.primary }
    val threshold = p.setting("storageAlert", "off").toIntOrNull()
    LaunchedEffect(primary, threshold, p.english) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val used = primary?.let { if (it.total > 0) (1 - it.free.toDouble() / it.total) * 100 else 0.0 }
        if (threshold == null || used == null || used < threshold) {
            manager.cancel(403)
            return@LaunchedEffect
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return@LaunchedEffect
        }
        manager.createNotificationChannel(NotificationChannel("storage", p.tr("Depolama", "Storage"),
            NotificationManager.IMPORTANCE_DEFAULT))
        if (manager.activeNotifications.none { it.id == 403 }) {
            manager.notify(403, NotificationCompat.Builder(context, "storage")
                .setSmallIcon(R.drawable.ic_launcher_mono)
                .setContentTitle(p.tr("Bellek dolmak üzere", "Storage almost full"))
                .setContentText(p.tr("Dahili depolama kullanımı", "Internal storage usage") + " · " + used.toInt() + "%")
                .setOnlyAlertOnce(true).build())
        }
    }
}
