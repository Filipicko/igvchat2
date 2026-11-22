package sk.filip.igchat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Po reštarte mobilu alebo aktualizácii apky znova naplánuje kontrolu správ. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                MessageChecker.createChannels(context.applicationContext)
                MessageChecker.schedule(context.applicationContext)
            }
        }
    }
}
