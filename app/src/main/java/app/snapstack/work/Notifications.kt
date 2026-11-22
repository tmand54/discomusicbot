package app.snapstack.work

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
import androidx.core.content.ContextCompat
import app.snapstack.MainActivity
import app.snapstack.R
import app.snapstack.data.FiredAlert
import app.snapstack.model.Variants
import app.snapstack.ui.components.money

object Notifications {
    const val ALERTS = "price_alerts"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(ALERTS, "Price alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a card you're watching crosses your target price"
            }
        )
    }

    fun showAlerts(context: Context, fired: List<FiredAlert>) {
        if (fired.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = NotificationManagerCompat.from(context)
        for (f in fired) {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_CARD_ID, f.card.id)
            }
            val pi = PendingIntent.getActivity(
                context, f.alert.id.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val direction = if (f.alert.above) "rose above" else "fell below"
            val n = NotificationCompat.Builder(context, ALERTS)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("${f.card.title} $direction ${money(f.alert.target)}")
                .setContentText("${Variants.label(f.alert.variant ?: f.card.defaultVariant)} · now ${money(f.price)} · ${f.card.setName}")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            try {
                nm.notify(f.alert.id.toInt(), n)
            } catch (_: SecurityException) {
            }
        }
    }
}
