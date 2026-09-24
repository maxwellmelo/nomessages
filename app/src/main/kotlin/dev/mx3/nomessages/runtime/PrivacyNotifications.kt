package dev.mx3.nomessages.runtime

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.mx3.nomessages.MainActivity
import dev.mx3.nomessages.R

internal class PrivacyNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "NoMessages", NotificationManager.IMPORTANCE_DEFAULT).apply {
            lockscreenVisibility = Notification.VISIBILITY_SECRET
            setShowBadge(false)
        })
    }
    fun show() {
        if (!canPost()) return
        manager.notify(MESSAGES_ID, build(context.getString(R.string.app_name), null))
    }

    /**
     * "Somebody rang while the vault was locked."
     *
     * Same channel as [show] on purpose: this is the one doorbell notification that must actually
     * reach the user, so it inherits the system's default importance, sound and vibration, plus the
     * secret lockscreen visibility the channel already carries. The persistent notification of the
     * `:tor` process is the exact opposite - silent, ongoing, its own low-importance channel - and
     * the two must never share one.
     *
     * Its own id, so it coexists with [show] instead of replacing it, and the text is a fixed
     * sentence: no count, no sender, no preview. Whoever sees the phone's screen learns only that
     * the app should be opened, which is the entire point of the feature.
     */
    fun showDoorbellPending() {
        if (!canPost()) return
        manager.notify(DOORBELL_ID, build(context.getString(R.string.doorbell_pending_title), context.getString(R.string.doorbell_pending_body)))
    }

    /** Idempotent: unlocking always clears it, whether or not anybody ever rang. */
    fun cancelDoorbellPending() { manager.cancel(DOORBELL_ID) }

    fun clear() { manager.cancelAll() }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun build(title: String, body: String?): Notification {
        val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_nomessages_notification)
            .setContentTitle(title)
            .apply { if (body != null) setContentText(body) }
            .setContentIntent(intent)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setLocalOnly(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "private_messages"
        /** Fixed ids: one per notification this app may ever show at the same time. */
        private const val MESSAGES_ID = 1
        private const val DOORBELL_ID = 2
        const val BACKGROUND_NOTIFICATION_ID = 3

        /**
         * Channel of the persistent notification that keeps the `:tor` process alive in minimal
         * mode. Separate from [CHANNEL] and deliberately its opposite: `IMPORTANCE_LOW` (no sound,
         * no vibration, no peek), no badge, secret on the lockscreen.
         *
         * The user-visible channel name is neutral ("Segundo plano" / "Background"): it appears in
         * the system's app-settings list, where a name like "Doorbell" or "Tor" would describe this
         * app's behaviour to anyone holding the phone.
         */
        const val BACKGROUND_CHANNEL = "background_service"

        /**
         * The ongoing notification itself, built in whichever process calls it - the `:tor` child
         * builds its own, and creating an existing channel again is a no-op, so no cross-process
         * setup is needed.
         *
         * Title is the bare app name and there is no body, the same zero-content convention every
         * other notification here follows: the system forces this one to be visible, so it must say
         * nothing beyond what the launcher icon already says.
         */
        fun backgroundNotification(context: Context): Notification {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(BACKGROUND_CHANNEL, context.getString(R.string.background_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                },
            )
            val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return Notification.Builder(context, BACKGROUND_CHANNEL)
                .setSmallIcon(R.drawable.ic_nomessages_notification)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentIntent(intent)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setLocalOnly(true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }
    }
}
