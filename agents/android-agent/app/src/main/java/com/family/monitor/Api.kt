package com.family.monitor

import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * طبقة الاتصال بالخادم المركزي: تسجيل الجهاز + رفع الأحداث + نبضة الاتصال.
 */
object Api {
    private const val TIMEOUT = 15000

    fun post(serverUrl: String, path: String, body: JSONObject, deviceKey: String? = null): JSONObject? {
        return try {
            val conn = URL(serverUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = TIMEOUT
            conn.readTimeout = TIMEOUT
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            deviceKey?.let { conn.setRequestProperty("X-Device-Key", it) }
            BufferedWriter(OutputStreamWriter(conn.outputStream, Charsets.UTF_8)).use { w ->
                w.write(body.toString())
            }
            val code = conn.responseCode
            if (code in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                JSONObject("{\"ok\":true}")
            } else {
                val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                android.util.Log.e("Api", "خطأ $code: $err")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("Api", "فشل الاتصال", e)
            null
        }
    }

    /** تسجيل الجهاز بكود الاقتران — يعيد مفتاح الـ API أو null */
    fun register(serverUrl: String, pairingCode: String, deviceName: String): String? {
        val body = JSONObject()
            .put("pairingCode", pairingCode)
            .put("name", deviceName)
            .put("platform", "android")
        return try {
            val conn = URL(serverUrl.trimEnd('/') + "/api/device/register").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = TIMEOUT
            conn.readTimeout = TIMEOUT
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            BufferedWriter(OutputStreamWriter(conn.outputStream, Charsets.UTF_8)).use { w ->
                w.write(body.toString())
            }
            if (conn.responseCode in 200..299) {
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                JSONObject(text).optString("apiKey").takeIf { it.isNotBlank() }
            } else null
        } catch (e: Exception) {
            android.util.Log.e("Api", "فشل التسجيل", e)
            null
        }
    }

    fun sendEvents(serverUrl: String, deviceKey: String, events: JSONArray): Boolean {
        val body = JSONObject().put("events", events)
        return post(serverUrl, "/api/device/events", body, deviceKey) != null
    }

    fun heartbeat(serverUrl: String, deviceKey: String): Boolean {
        return post(serverUrl, "/api/device/heartbeat", JSONObject(), deviceKey) != null
    }

    /** هل خدمة إمكانية الوصول مفعّلة لهذا التطبيق؟ */
    fun isAccessibilityEnabled(ctx: Context): Boolean {
        val expected = ctx.packageName + "/" + UrlAccessibilityService::class.java.canonicalName
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
            .any { it.equals(expected, ignoreCase = true) }
    }
}
