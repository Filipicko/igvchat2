package sk.filip.igchat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Budí sa každých 5 minút (a po reštarte mobilu), naplánuje ďalšiu kontrolu
 * a skontroluje nové správy.
 */
class CheckReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        MessageChecker.schedule(app)

        // Keď je apka otvorená, notifikácie netreba
        if (MainActivity.isVisible) return

        val cookies = MessageChecker.readCookies()
        val pending = goAsync()
        Thread {
            try {
                MessageChecker.check(app, cookies, notify = true)
            } catch (e: Exception) {
                Log.e("IGChatCheck", "Kontrola zlyhala", e)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
