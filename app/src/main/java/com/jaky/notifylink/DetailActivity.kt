package com.jaky.notifylink

import android.app.Activity
import android.os.Bundle
import android.util.Base64
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import java.io.ByteArrayOutputStream

class DetailActivity : Activity() {

    data class ParsedDetail(
        val time: String,
        val packageName: String,
        val title: String,
        val message: String,
        val raw: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        val content = intent.getStringExtra("content") ?: ""
        val parsed = parseContent(content)

        val tvTitle = findViewById<TextView>(R.id.tv_detail_title)
        val tvPackage = findViewById<TextView>(R.id.tv_detail_package)
        val tvMessage = findViewById<TextView>(R.id.tv_detail_message)
        val tvTime = findViewById<TextView>(R.id.tv_detail_time)
        val tvRaw = findViewById<TextView>(R.id.tv_detail_content)
        val tvJson = findViewById<TextView>(R.id.tv_detail_json)
        val ivIcon = findViewById<ImageView>(R.id.iv_detail_icon)

        tvTitle.text = parsed.title
        tvPackage.text = parsed.packageName
        tvMessage.text = parsed.message
        tvTime.text = parsed.time
        tvRaw.text = parsed.raw

        val iconDataUrl = getAppIconDataUrl(parsed.packageName)
        tvJson.text = buildWebhookJson(parsed, iconDataUrl)

        try {
            ivIcon.setImageDrawable(packageManager.getApplicationIcon(parsed.packageName))
        } catch (e: Exception) {
            ivIcon.setImageResource(R.drawable.icon)
        }

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener {
            finish()
        }
    }

    private fun buildWebhookJson(parsed: ParsedDetail, iconDataUrl: String): String {
        return """
{
  "package": "${escapeJson(parsed.packageName)}",
  "title": "${escapeJson(parsed.title)}",
  "message": "${escapeJson(parsed.message)}",
  "time": "${escapeJson(parsed.time)}",
  "icon_data_url": "${escapeJson(iconDataUrl)}"
}
""".trim()
    }

    private fun getAppIconDataUrl(packageName: String): String {
        return try {
            val drawable = packageManager.getApplicationIcon(packageName)
            val bitmap = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            val output = ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
            val encoded = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            "data:image/png;base64,$encoded"
        } catch (e: Exception) {
            ""
        }
    }

    private fun escapeJson(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
    }

    private fun parseContent(content: String): ParsedDetail {
        val lines = content.split("\n")
        val header = lines.firstOrNull()?.trim().orEmpty()
        val time = if (header.startsWith("[") && header.contains("]")) {
            header.substringAfter("[").substringBefore("]")
        } else {
            "--:--"
        }

        var packageName = if (header.contains("]")) header.substringAfter("]").trim() else "Unknown package"
        lines.firstOrNull { it.startsWith("Pkg:") }?.let {
            packageName = it.removePrefix("Pkg:").trim()
        }

        var title = "Notification"
        var message = lines.drop(1).joinToString(" ").trim()
        val titleLine = lines.firstOrNull { it.startsWith("Title:") }
        val messageLine = lines.firstOrNull { it.startsWith("Message:") }
        if (titleLine != null || messageLine != null) {
            title = titleLine?.removePrefix("Title:")?.trim().orEmpty().ifEmpty { "Notification" }
            message = messageLine?.removePrefix("Message:")?.trim().orEmpty()
        } else if (lines.size > 1 && lines[1].contains(":")) {
            title = lines[1].substringBefore(":").trim().ifEmpty { "Notification" }
            message = lines[1].substringAfter(":").trim()
        }
        if (lines.any { it.contains("WEBHOOK SUCCESS") }) {
            title = "Webhook Success"
            message = lines.find { it.startsWith("Result:") }?.removePrefix("Result:")?.trim().orEmpty()
        }
        if (lines.any { it.contains("WEBHOOK FAILED") }) {
            title = "Webhook Failed"
            message = lines.find { it.startsWith("Error:") }?.removePrefix("Error:")?.trim().orEmpty()
        }

        return ParsedDetail(time, packageName.ifEmpty { "Unknown package" }, title, message.ifEmpty { "No message content" }, content)
    }
}
