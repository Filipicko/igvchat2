package sk.filip.igchat

import android.app.Notification
import android.app.NotificationManager
import android.content.Context

object Notifier {

    fun showMessage(context: Context, threadId: String, title: String, text: String) {
        if (!MessageChecker.hasNotificationPermission(context)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val id = threadId.hashCode()
        val n = Notification.Builder(context, MessageChecker.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setContentIntent(MessageChecker.openIntent(context, MessageChecker.threadUrl(threadId), id))
            .build()
        nm.notify(id, n)
    }

    fun showError(context: Context, channelId: String, text: String) {
        if (!MessageChecker.hasNotificationPermission(context)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val n = Notification.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("IG Chat")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(MessageChecker.openIntent(context, null, 0))
            .build()
        nm.notify(1, n)
    }

    fun clearAll(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancelAll()
    }
}
