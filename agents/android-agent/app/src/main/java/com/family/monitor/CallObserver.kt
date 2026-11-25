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
                    // اسم صاحب الرقم من جهات الاتصال — الموقع يستخدمه لتجميع
                    // مكالمات الشخص في مجلده مع رسائل واتسابه
                    val contactName = lookupContactName(number)
                    if (contactName.isNotBlank()) {
                        json.put("meta", org.json.JSONObject().put("name", contactName).toString())
                    }
                    EventStore.addEvent(context, json)
                }
            }
        } catch (e: SecurityException) {
            android.util.Log.e("CallObserver", "صلاحية READ_CALL_LOG غير ممنوحة", e)
        } catch (e: Exception) {
            android.util.Log.e("CallObserver", "خطأ في قراءة سجل المكالمات", e)
        }
    }

    /** البحث عن اسم صاحب الرقم في جهات الاتصال (بدون الاسم يظل التجميع بالرقم) */
    private fun lookupContactName(number: String): String {
        return try {
            if (number.isBlank() || number == "رقم غير معروف") return ""
            val uri = android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI
                .buildUpon().appendPath(number).build()
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0)?.trim().orEmpty() else "" } ?: ""
        } catch (_: SecurityException) {
            "" // صلاحية جهات الاتصال غير ممنوحة — نكتفي بالرقم
        } catch (_: Exception) {
            ""
        }
    }

    private fun iso(ms: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date(ms))
    }
}
