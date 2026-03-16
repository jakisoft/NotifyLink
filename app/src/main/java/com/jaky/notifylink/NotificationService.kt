package com.jaky.notifylink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class NotificationService : NotificationListenerService() {

    companion object {
        private const val PREF_NAME = "NotifyLinkPref"
        private const val KEY_PENDING_NOTIFICATIONS = "pending_notifications"
        private const val MAX_PENDING_NOTIFICATIONS = 200
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        val sharedPref = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        thread { flushPendingNotifications(sharedPref) }
        return START_STICKY
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val sharedPref = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (!sharedPref.getBoolean("master_on", false)) return

        val currentPkg = sbn.packageName
        val packageFilterRaw = sharedPref.getString("package_names", sharedPref.getString("package_name", "") ?: "") ?: ""
        val packageFilters = parseMultiValue(packageFilterRaw)
        if (packageFilters.isNotEmpty() && packageFilters.none { it.equals(currentPkg, true) }) return

        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: "No Title"
        val text = extras.getCharSequence("android.text")?.toString() ?: "No Content"
        val keywordFilterRaw = sharedPref.getString("filter_keywords", sharedPref.getString("filter_keyword", "") ?: "") ?: ""
        val keywordFilters = parseMultiValue(keywordFilterRaw)

        if (keywordFilters.isNotEmpty() && keywordFilters.none { text.contains(it, true) || title.contains(it, true) }) return

        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val iconDataUri = getAppIconDataUri(currentPkg)
        val payload = buildWebhookPayload(currentPkg, title, text, time, iconDataUri, sbn.postTime)

        thread {
            flushPendingNotifications(sharedPref)

            val isDelivered = dispatchNotification(sharedPref, payload, currentPkg, time)
            if (!isDelivered) {
                enqueuePendingNotification(sharedPref, payload)
                addLog("[$time] QUEUED\nPkg: $currentPkg\nReason: Offline / delivery failed, akan dicoba ulang")
            }
        }
    }

    private fun dispatchNotification(
        sharedPref: android.content.SharedPreferences,
        payload: JSONObject,
        pkg: String,
        time: String
    ): Boolean {
        val isTelegramEnabled = sharedPref.getBoolean("telegram_on", false)
        val isWebhookEnabled = sharedPref.getBoolean("webhook_on", false)

        if (!isTelegramEnabled && !isWebhookEnabled) {
            val title = payload.optJSONObject("notification")?.optString("title", "Notification") ?: "Notification"
            val message = payload.optJSONObject("notification")?.optString("message", "No Content") ?: "No Content"
            addLog("[$time] $pkg\n$title: $message")
            return true
        }

        if (!hasInternetConnection()) {
            return false
        }

        var allSent = true

        if (isTelegramEnabled) {
            val token = sharedPref.getString("bot_token", "") ?: ""
            val chat = sharedPref.getString("chat_id", "") ?: ""
            val title = payload.optJSONObject("notification")?.optString("title", "No Title") ?: "No Title"
            val message = payload.optJSONObject("notification")?.optString("message", "No Content") ?: "No Content"

            if (token.isNotEmpty() && chat.isNotEmpty()) {
                val msg = "🚀 *NotifyLink*\n📦 Pkg: $pkg\n👤 $title\n📝 $message"
                val telegramSent = sendTelegram(token, chat, msg)
                if (!telegramSent) {
                    allSent = false
                }
            } else {
                allSent = false
            }
        }

        if (isWebhookEnabled) {
            val webUrl = sharedPref.getString("webhook_url", "") ?: ""
            if (webUrl.isNotEmpty()) {
                val webhookResult = sendWebhook(webUrl, payload.toString(), pkg, time)
                if (!webhookResult) {
                    allSent = false
                }
            } else {
                allSent = false
            }
        }

        return allSent
    }

    private fun flushPendingNotifications(sharedPref: android.content.SharedPreferences) {
        if (!hasInternetConnection()) return

        val raw = sharedPref.getString(KEY_PENDING_NOTIFICATIONS, "") ?: ""
        if (raw.isEmpty()) return

        val queue = try {
            JSONArray(raw)
        } catch (e: Exception) {
            sharedPref.edit().remove(KEY_PENDING_NOTIFICATIONS).apply()
            return
        }

        val remain = JSONArray()
        for (i in 0 until queue.length()) {
            val payload = queue.optJSONObject(i) ?: continue
            val pkg = payload.optJSONObject("app")?.optString("package", "unknown.package") ?: "unknown.package"
            val time = payload.optJSONObject("notification")?.optString("display_time", "--:--:--") ?: "--:--:--"
            val sent = dispatchNotification(sharedPref, payload, pkg, time)
            if (!sent) {
                remain.put(payload)
            }
        }

        if (remain.length() == 0) {
            sharedPref.edit().remove(KEY_PENDING_NOTIFICATIONS).apply()
        } else {
            sharedPref.edit().putString(KEY_PENDING_NOTIFICATIONS, remain.toString()).apply()
        }
    }

    private fun enqueuePendingNotification(sharedPref: android.content.SharedPreferences, payload: JSONObject) {
        val current = try {
            JSONArray(sharedPref.getString(KEY_PENDING_NOTIFICATIONS, "") ?: "")
        } catch (e: Exception) {
            JSONArray()
        }

        current.put(payload)
        while (current.length() > MAX_PENDING_NOTIFICATIONS) {
            removeFirstItem(current)
        }

        sharedPref.edit().putString(KEY_PENDING_NOTIFICATIONS, current.toString()).apply()
    }

    private fun removeFirstItem(array: JSONArray): JSONArray {
        val trimmed = JSONArray()
        for (i in 1 until array.length()) {
            trimmed.put(array.opt(i))
        }
        return trimmed.also { replacement ->
            for (i in array.length() - 1 downTo 0) {
                array.remove(i)
            }
            for (i in 0 until replacement.length()) {
                array.put(replacement.opt(i))
            }
        }
    }

    private fun hasInternetConnection(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    private fun buildWebhookPayload(
        packageName: String,
        title: String,
        message: String,
        eventTime: String,
        iconDataUri: String,
        postedAtMillis: Long
    ): JSONObject {
        val appLabel = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        } catch (e: Exception) {
            packageName
        }

        val device = JSONObject()
            .put("manufacturer", Build.MANUFACTURER ?: "Unknown")
            .put("brand", Build.BRAND ?: "Unknown")
            .put("model", Build.MODEL ?: "Unknown")
            .put("device", Build.DEVICE ?: "Unknown")
            .put("product", Build.PRODUCT ?: "Unknown")
            .put("hardware", Build.HARDWARE ?: "Unknown")
            .put("android_version", Build.VERSION.RELEASE ?: "Unknown")
            .put("sdk_int", Build.VERSION.SDK_INT)
            .put("fingerprint", Build.FINGERPRINT ?: "Unknown")

        val app = JSONObject()
            .put("name", appLabel)
            .put("package", packageName)

        val notification = JSONObject()
            .put("title", title)
            .put("message", message)
            .put("display_time", eventTime)
            .put("posted_at_millis", postedAtMillis)
            .put("icon_data_url", iconDataUri)

        return JSONObject()
            .put("source", "NotifyLink")
            .put("event", "notification_posted")
            .put("sent_at_millis", System.currentTimeMillis())
            .put("app", app)
            .put("notification", notification)
            .put("device", device)
    }

    private fun parseMultiValue(raw: String): List<String> {
        return raw.split("||", ",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) {
            return drawable.bitmap
        }
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun getAppIconDataUri(packageName: String): String {
        return try {
            val drawable = packageManager.getApplicationIcon(packageName)
            val bitmap = drawableToBitmap(drawable)
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            val encoded = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            "data:image/png;base64,$encoded"
        } catch (e: Exception) {
            ""
        }
    }

    private fun addLog(logEntry: String) {
        val sharedPref = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val oldLogs = sharedPref.getString("last_logs", "") ?: ""
        sharedPref.edit().putString("last_logs", "$logEntry|||$oldLogs").apply()
    }

    private fun sendTelegram(token: String, chat: String, msg: String): Boolean {
        return try {
            val url = URL("https://api.telegram.org/bot$token/sendMessage?chat_id=$chat&text=${URLEncoder.encode(msg, "UTF-8")}&parse_mode=Markdown")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            val responseCode = conn.responseCode
            conn.inputStream.read()
            conn.disconnect()
            responseCode in 200..299
        } catch (e: Exception) {
            false
        }
    }

    private fun sendWebhook(urlStr: String, json: String, pkg: String, time: String): Boolean {
        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.write(json.toByteArray())

            val responseCode = conn.responseCode
            val responseMsg = if (responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Error $responseCode"
            }

            if (responseCode in 200..299) {
                addLog("[$time] WEBHOOK SUCCESS\nPkg: $pkg\nResult: $responseMsg")
            } else {
                addLog("[$time] WEBHOOK FAILED\nPkg: $pkg\nError: $responseMsg")
            }

            conn.disconnect()
            responseCode in 200..299
        } catch (e: Exception) {
            addLog("[$time] WEBHOOK FAILED\nPkg: $pkg\nError: ${e.message}")
            false
        }
    }
}
