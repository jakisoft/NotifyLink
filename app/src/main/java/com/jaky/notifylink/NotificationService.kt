package com.jaky.notifylink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.util.Base64
import kotlin.concurrent.thread

class NotificationService : NotificationListenerService() {

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val sharedPref = getSharedPreferences("NotifyLinkPref", Context.MODE_PRIVATE)
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

        if (sharedPref.getBoolean("telegram_on", false)) {
            val token = sharedPref.getString("bot_token", "") ?: ""
            val chat = sharedPref.getString("chat_id", "") ?: ""
            if (token.isNotEmpty() && chat.isNotEmpty()) {
                val msg = "🚀 *NotifyLink*\n📦 Pkg: $currentPkg\n👤 $title\n📝 $text"
                sendTelegram(token, chat, msg)
            }
        }

        if (sharedPref.getBoolean("webhook_on", false)) {
            val webUrl = sharedPref.getString("webhook_url", "") ?: ""
            if (webUrl.isNotEmpty()) {
                val json = "{\"package\":\"$currentPkg\",\"title\":\"${escapeJson(title)}\",\"message\":\"${escapeJson(text)}\",\"time\":\"$time\",\"icon_data_url\":\"${escapeJson(iconDataUri)}\"}"
                sendWebhook(webUrl, json, currentPkg, time)
            }
        } else {
            addLog("[$time] $currentPkg\n$title: $text")
        }
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

    private fun escapeJson(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
    }

    private fun addLog(logEntry: String) {
        val sharedPref = getSharedPreferences("NotifyLinkPref", Context.MODE_PRIVATE)
        val oldLogs = sharedPref.getString("last_logs", "") ?: ""
        sharedPref.edit().putString("last_logs", "$logEntry|||$oldLogs").apply()
    }

    private fun sendTelegram(token: String, chat: String, msg: String) {
        thread {
            try {
                val url = URL("https://api.telegram.org/bot$token/sendMessage?chat_id=$chat&text=${URLEncoder.encode(msg, "UTF-8")}&parse_mode=Markdown")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.inputStream.read()
                conn.disconnect()
            } catch (e: Exception) {
            }
        }
    }

    private fun sendWebhook(urlStr: String, json: String, pkg: String, time: String) {
        thread {
            try {
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.write(json.toByteArray())

                val responseCode = conn.responseCode
                val responseMsg = if (responseCode == 200) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Error $responseCode"
                }

                addLog("[$time] WEBHOOK SUCCESS\nPkg: $pkg\nResult: $responseMsg")
                conn.disconnect()
            } catch (e: Exception) {
                addLog("[$time] WEBHOOK FAILED\nPkg: $pkg\nError: ${e.message}")
            }
        }
    }
}
