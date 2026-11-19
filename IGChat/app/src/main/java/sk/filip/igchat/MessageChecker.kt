package sk.filip.igchat

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Kontrola nových správ na pozadí.
 * Používa rovnaké prihlásenie (cookies) ako WebView a rovnaké webové API,
 * ktoré volá instagram.com/direct, takže nie je potrebná oficiálna apka.
 */
object MessageChecker {

    private const val TAG = "IGChatCheck"
    const val INTERVAL_MS = 5 * 60 * 1000L          // každých 5 minút
    const val CHANNEL_ID = "messages"
    private const val ERROR_CHANNEL_ID = "errors"
    const val ACTION_CHECK = "sk.filip.igchat.CHECK"
    const val EXTRA_URL = "open_url"

    private const val INBOX_API =
        "https://www.instagram.com/api/v1/direct_v2/inbox/?persistentBadging=true&folder=&limit=20&thread_message_limit=1"
    private const val IG_WEB_APP_ID = "936619743392459"

    private const val PREFS = "igchat_seen"
    private const val KEY_BASELINE = "__baseline_done"
    private const val KEY_LAST_ERROR_DAY = "__last_error_day"

    // ---------- plánovanie ----------

    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmIntent(context)
        val at = SystemClock.elapsedRealtime() + INTERVAL_MS
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }
    }

    private fun alarmIntent(context: Context): PendingIntent {
        val i = Intent(context, CheckReceiver::class.java).setAction(ACTION_CHECK)
        return PendingIntent.getBroadcast(
            context, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ---------- kanály notifikácií ----------

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Nové správy", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Upozornenia na nové správy z Instagramu"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(ERROR_CHANNEL_ID, "Chyby kontroly", NotificationManager.IMPORTANCE_LOW)
        )
    }

    // ---------- samotná kontrola ----------

    /** Cookies treba prečítať na hlavnom vlákne. */
    fun readCookies(): String? = try {
        CookieManager.getInstance().getCookie("https://www.instagram.com")
    } catch (e: Exception) {
        Log.w(TAG, "CookieManager nedostupný", e)
        null
    }

    /**
     * @param notify false = iba si zapamätá aktuálny stav (napr. keď používateľ práve zatvoril apku)
     * Spúšťať mimo hlavného vlákna.
     */
    fun check(context: Context, cookies: String?, notify: Boolean) {
        if (cookies.isNullOrBlank() || !cookies.contains("sessionid")) {
            Log.i(TAG, "Nie si prihlásený, kontrolu preskakujem")
            return
        }
        val cookieMap = cookies.split(";").mapNotNull {
            val p = it.trim().split("=", limit = 2)
            if (p.size == 2) p[0] to p[1] else null
        }.toMap()
        val myId = cookieMap["ds_user_id"]
        val csrf = cookieMap["csrftoken"] ?: ""

        val conn = (URL(INBOX_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Cookie", cookies)
            setRequestProperty("X-IG-App-ID", IG_WEB_APP_ID)
            setRequestProperty("X-CSRFToken", csrf)
            setRequestProperty("X-Requested-With", "XMLHttpRequest")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Referer", "https://www.instagram.com/direct/inbox/")
            setRequestProperty("User-Agent", userAgent(context))
        }

        val body: String
        try {
            val code = conn.responseCode
            if (code != 200) {
                reportError(context, "HTTP $code")
                return
            }
            body = conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "Sieťová chyba", e) // bez internetu – ticho
            return
        } finally {
            conn.disconnect()
        }

        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            reportError(context, "neočakávaná odpoveď (nie JSON)")
            return
        }

        val threads = json.optJSONObject("inbox")?.optJSONArray("threads") ?: run {
            reportError(context, "v odpovedi chýba inbox")
            return
        }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val firstRun = !prefs.getBoolean(KEY_BASELINE, false)
        val edit = prefs.edit()

        for (i in 0 until threads.length()) {
            val t = threads.optJSONObject(i) ?: continue
            val threadId = t.optString("thread_id")
            if (threadId.isEmpty()) continue
            val items = t.optJSONArray("items")
            val last = items?.optJSONObject(0) ?: t.optJSONObject("last_permanent_item") ?: continue
            val itemId = last.optString("item_id")
            if (itemId.isEmpty()) continue
            val senderId = last.optString("user_id")

            val previous = prefs.getString(threadId, null)
            edit.putString(threadId, itemId)

            if (!notify || firstRun) continue
            if (previous == itemId) continue                 // nič nové
            if (myId != null && senderId == myId) continue   // moja vlastná správa

            // už prečítané (napr. na počítači)?
            val seenItem = myId?.let { t.optJSONObject("last_seen_at")?.optJSONObject(it)?.optString("item_id") }
            if (seenItem == itemId) continue

            val title = threadTitle(t, senderId)
            val text = messageText(last)
            Notifier.showMessage(context, threadId, title, text)
        }

        edit.putBoolean(KEY_BASELINE, true).apply()
    }

    private fun threadTitle(t: JSONObject, senderId: String): String {
        val users = t.optJSONArray("users")
        if (users != null) {
            for (i in 0 until users.length()) {
                val u = users.optJSONObject(i) ?: continue
                if (u.optString("pk") == senderId || u.optString("pk_id") == senderId) {
                    val name = u.optString("full_name").ifBlank { u.optString("username") }
                    val group = t.optBoolean("is_group", false)
                    return if (group) "$name · ${t.optString("thread_title")}" else name
                }
            }
        }
        return t.optString("thread_title").ifBlank { "Instagram" }
    }

    private fun messageText(item: JSONObject): String = when (item.optString("item_type")) {
        "text" -> item.optString("text").ifBlank { "Nová správa" }
        "media", "raven_media", "visual_media" -> "📷 Poslal(a) fotku alebo video"
        "voice_media" -> "🎤 Hlasová správa"
        "like" -> "❤️"
        "animated_media" -> "GIF"
        "clip", "media_share", "xma_media_share", "xma_clip" -> "Zdieľal(a) príspevok"
        "link" -> item.optJSONObject("link")?.optString("text")?.ifBlank { null } ?: "🔗 Odkaz"
        else -> "Nová správa"
    }

    private fun userAgent(context: Context): String = try {
        WebSettings.getDefaultUserAgent(context)
            .replace("; wv", "")
            .replace(Regex("Version/\\d+\\.\\d+ "), "")
    } catch (e: Exception) {
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    }

    /** Chybu ukáže najviac raz za deň, aby to neotravovalo. */
    private fun reportError(context: Context, msg: String) {
        Log.w(TAG, msg)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = System.currentTimeMillis() / 86_400_000L
        if (prefs.getLong(KEY_LAST_ERROR_DAY, -1) == today) return
        prefs.edit().putLong(KEY_LAST_ERROR_DAY, today).apply()
        Notifier.showError(context, ERROR_CHANNEL_ID, "Kontrola správ zlyhala: $msg")
    }

    fun threadUrl(threadId: String): String = "https://www.instagram.com/direct/t/$threadId/"

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun openIntent(context: Context, url: String?, requestCode: Int): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (url != null) {
                putExtra(EXTRA_URL, url)
                data = Uri.parse(url)
            }
        }
        return PendingIntent.getActivity(
            context, requestCode, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
