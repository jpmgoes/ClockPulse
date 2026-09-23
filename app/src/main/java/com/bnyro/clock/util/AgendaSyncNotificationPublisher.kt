package com.bnyro.clock.util

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.bnyro.clock.R
import com.bnyro.clock.ui.MainActivity

/** Posts one summary for each provider/account that supplied new agenda events. */
class AgendaSyncNotificationPublisher(private val context: Context) {
    fun publish(provider: String, account: String, eventCount: Int) {
        if (eventCount <= 0 || !canPostNotifications()) return

        val details = context.getString(R.string.agenda_events_added_from, provider, account)
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.SHOW_AGENDA_ACTION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pendingIntent = PendingIntent.getActivity(
            context,
            ("agenda-sync:$provider:$account").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, NotificationHelper.AGENDA_SYNC_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.resources.getQuantityString(
                R.plurals.agenda_events_added_notification_title, eventCount, eventCount
            ))
            .setContentText(details)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .build()
        NotificationManagerCompat.from(context).notify(
            ("agenda-sync:$provider:$account").hashCode() and Int.MAX_VALUE,
            notification
        )
    }

    private fun canPostNotifications(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED)
}
