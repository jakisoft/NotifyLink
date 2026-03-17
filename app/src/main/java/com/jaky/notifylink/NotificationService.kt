package com.jaky.notifylink

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.os.Handler
import android.os.Looper
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
        private const val KEY_STATUS_API_URL = "status_api_url"
        private const val MAX_PENDING_NOTIFICATIONS = 200
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val DUPLICATE_WINDOW_MS = 5_000L
    }

    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            val sharedPref = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            thread { reportDeviceStatus(sharedPref, "heartbeat") }
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    private val recentNotificationSignatures = LinkedHashMap<String, Long>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        startHeartbeat()
        val sharedPref = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        thread {
            flushPendingNotifications(sharedPref)
            reportDeviceStatus(sharedPref, "listener_connected")
        }
    }

    override fun onListenerDisconnected() {
        stopHeartbeat()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        stopHeartbeat()
        super.onDestroy()
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        startHeartbeat()
        val sharedPref = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        thread {
            flushPendingNotifications(sharedPref)
            reportDeviceStatus(sharedPref, "listener_start")
        }
        return START_STICKY
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val sharedPref = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (!sharedPref.getBoolean("master_on", false)) return

        val currentPkg = sbn.packageName
        val packageFilterRaw = sharedPref.getString("package_names", sharedPref.getString("package_name", "") ?: "") ?: ""
        val packageFilters = parseMultiValue(packageFilterRaw)
        if (packageFilters.isNotEmpty() && packageFilters.none { it.equals(currentPkg, true) }) return

        val notificationContent = extractNotificationContent(sbn)
        val title = notificationContent.first
        val text = notificationContent.second
        val keywordFilterRaw = sharedPref.getString("filter_keywords", sharedPref.getString("filter_keyword", "") ?: "") ?: ""
        val keywordFilters = parseMultiValue(keywordFilterRaw)

        if (keywordFilters.isNotEmpty() && keywordFilters.none { text.contains(it, true) || title.contains(it, true) }) return

        if (isLikelyInvalidMirrorPayload(currentPkg, title, text)) return
        if (isDuplicateNotification(sbn, title, text)) return

        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val iconDataUri = getAppIconDataUri(currentPkg)
        val payload = buildWebhookPayload(currentPkg, title, text, time, iconDataUri, sbn.postTime)
        addLog("[$time] $currentPkg\nTitle: $title\nMessage: $text")

        thread {
            flushPendingNotifications(sharedPref)

            val isDelivered = dispatchNotification(sharedPref, payload, currentPkg, time)
            if (!isDelivered) {
                enqueuePendingNotification(sharedPref, payload)
                addLog("[$time] QUEUED\nPkg: $currentPkg\nReason: Offline / delivery failed, akan dicoba ulang")
            }

            reportDeviceStatus(sharedPref, "notification_event")
        }
    }

    private fun startHeartbeat() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        heartbeatHandler.post(heartbeatRunnable)
    }

    private fun stopHeartbeat() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
    }

    private fun isLikelyInvalidMirrorPayload(packageName: String, title: String, message: String): Boolean {
        val normalizedTitle = title.trim()
        val normalizedMessage = message.trim()
        if (normalizedTitle.equals("Pkg", true) && normalizedMessage.equals(packageName, true)) {
            return true
        }
        if (normalizedTitle.equals(packageName, true) && normalizedMessage.equals(packageName, true)) {
            return true
        }
        return false
    }

    private fun isDuplicateNotification(sbn: StatusBarNotification, title: String, message: String): Boolean {
        val now = System.currentTimeMillis()
        val signature = "${sbn.packageName}|${sbn.id}|${sbn.postTime}"
        val lastTime = recentNotificationSignatures[signature]
        recentNotificationSignatures[signature] = now

        val iterator = recentNotificationSignatures.entries.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (now - item.value > DUPLICATE_WINDOW_MS) {
                iterator.remove()
            }
        }

        return lastTime != null && now - lastTime <= DUPLICATE_WINDOW_MS
    }

    private fun extractNotificationContent(sbn: StatusBarNotification): Pair<String, String> {
        val extras = sbn.notification.extras ?: Bundle.EMPTY
        val titleCandidates = listOf(
            extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString(),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        )

        val messageFromMessagingStyle = extractMessagingStyleText(extras)
        val messageCandidates = listOf(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            messageFromMessagingStyle,
            sbn.notification.tickerText?.toString()
        )

        val fallbackTitle = getAppLabelSafe(sbn.packageName)
        val title = titleCandidates.firstNotNullOfOrNull { sanitizeNotificationText(it) } ?: fallbackTitle
        val message = messageCandidates.firstNotNullOfOrNull { sanitizeNotificationText(it) } ?: "No message content"

        return title to message
    }

    private fun extractMessagingStyleText(extras: Bundle): String? {
        val rawMessages = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        val parts = rawMessages.mapNotNull { item ->
            val bundle = item as? Bundle ?: return@mapNotNull null
            val text = bundle.getCharSequence("text")?.toString()
            sanitizeNotificationText(text)
        }
        return if (parts.isEmpty()) null else parts.joinToString(" | ")
    }

    private fun sanitizeNotificationText(value: String?): String? {
        val cleaned = value?.replace("\n", " ")?.trim().orEmpty()
        if (cleaned.isEmpty()) return null
        if (cleaned.equals("null", true)) return null
        return cleaned
    }

    private fun getAppLabelSafe(packageName: String): String {
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            packageName
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
        val queue = try {
            JSONArray(sharedPref.getString(KEY_PENDING_NOTIFICATIONS, "") ?: "")
        } catch (e: Exception) {
            JSONArray()
        }

        queue.put(payload)
        while (queue.length() > MAX_PENDING_NOTIFICATIONS) {
            trimOldestItem(queue)
        }

        sharedPref.edit().putString(KEY_PENDING_NOTIFICATIONS, queue.toString()).apply()
    }

    private fun trimOldestItem(array: JSONArray) {
        if (array.length() <= 0) return
        val copy = JSONArray()
        for (i in 1 until array.length()) {
            copy.put(array.opt(i))
        }
        while (array.length() > 0) {
            array.remove(0)
        }
        for (i in 0 until copy.length()) {
            array.put(copy.opt(i))
        }
    }

    private fun reportDeviceStatus(sharedPref: android.content.SharedPreferences, reason: String) {
        val statusApiUrl = sharedPref.getString(KEY_STATUS_API_URL, "")?.trim().orEmpty()
        if (statusApiUrl.isEmpty()) return

        val internetActive = hasInternetConnection()
        if (!internetActive) {
            return
        }

        val networkType = getNetworkType()
        val payload = JSONObject()
            .put("source", "NotifyLink")
            .put("event", "device_status")
            .put("sent_at_millis", System.currentTimeMillis())
            .put("reason", reason)
            .put("device", JSONObject()
                .put("device_id", getDeviceId())
                .put("manufacturer", Build.MANUFACTURER ?: "Unknown")
                .put("brand", Build.BRAND ?: "Unknown")
                .put("model", Build.MODEL ?: "Unknown")
                .put("device", Build.DEVICE ?: "Unknown")
                .put("android_version", Build.VERSION.RELEASE ?: "Unknown")
                .put("sdk_int", Build.VERSION.SDK_INT)
            )
            .put("status", JSONObject()
                .put("internet_active", internetActive)
                .put("heartbeat_interval_ms", HEARTBEAT_INTERVAL_MS)
                .put("network_type", networkType)
                .put("pending_notification_queue", getPendingQueueCount(sharedPref))
                .put("master_on", sharedPref.getBoolean("master_on", false))
                .put("telegram_on", sharedPref.getBoolean("telegram_on", false))
                .put("webhook_on", sharedPref.getBoolean("webhook_on", false))
            )

        val sent = sendDeviceStatus(statusApiUrl, payload.toString())
        if (!sent) {
            addLog("[${currentTime()}] STATUS API FAILED\nEndpoint: $statusApiUrl")
        }
    }

    private fun getPendingQueueCount(sharedPref: android.content.SharedPreferences): Int {
        val raw = sharedPref.getString(KEY_PENDING_NOTIFICATIONS, "") ?: ""
        return try {
            JSONArray(raw).length()
        } catch (e: Exception) {
            0
        }
    }

    private fun getDeviceId(): String {
        val fromSystem = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        return if (fromSystem.isNullOrBlank()) "unknown-device" else fromSystem
    }

    private fun getNetworkType(): String {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return "none"
            val capabilities = cm.getNetworkCapabilities(network) ?: return "none"
            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
        } catch (e: Exception) {
            "unknown"
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

    private fun currentTime(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
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

    private fun sendDeviceStatus(urlStr: String, json: String): Boolean {
        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.write(json.toByteArray())
            val responseCode = conn.responseCode
            conn.inputStream?.close()
            conn.disconnect()
            responseCode in 200..299
        } catch (e: Exception) {
            false
        }
    }
}
