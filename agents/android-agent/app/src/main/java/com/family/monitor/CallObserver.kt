package com.family.monitor

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.provider.CallLog

/**
 * رصد المكالمات (واردة/صادرة/فائتة) عبر مراقبة سجل المكالمات.
 */
class CallObserver(private val context: Context, handler: Handler) : ContentObserver(handler) {

    private var lastCallId = ""

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        try {
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls._ID,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION
                ),
                null, null,
                CallLog.Calls.DATE + " DESC"
            ) ?: return

            cursor.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getString(0)
                    if (id == lastCallId) return  // نفس المكالمة رُصدت بالفعل
                    lastCallId = id

                    val number = c.getString(1) ?: "رقم غير معروف"
                    val type = c.getInt(2)
                    val date = c.getLong(3)
                    val duration = c.getInt(4)

                    val direction = when (type) {
                        CallLog.Calls.INCOMING_TYPE -> "INCOMING"
                        CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                        CallLog.Calls.MISSED_TYPE -> "MISSED"
                        else -> "INCOMING"
                    }

                    val json = org.json.JSONObject()
                        .put("type", "CALL")
                        .put("direction", direction)
                        .put("phone", number)
                        .put("durationSec", duration)
                        .put("app", "الهاتف")
                        .put("occurredAt", iso(date))
                    EventStore.addEvent(context, json)
                }
            }
        } catch (e: SecurityException) {
            android.util.Log.e("CallObserver", "صلاحية READ_CALL_LOG غير ممنوحة", e)
        } catch (e: Exception) {
            android.util.Log.e("CallObserver", "خطأ في قراءة سجل المكالمات", e)
        }
    }

    private fun iso(ms: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date(ms))
    }
}
