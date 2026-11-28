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
 * يستقبل محتوى الرسائل الذي يتيحه نظام Android في إشعارات التطبيقات،
 * بعد تفعيل صاحب الجهاز صلاحية الوصول إلى الإشعارات.
 */
class WhatsappNotificationService : NotificationListenerService() {

    companion object {
        private val NOISE_TITLES = listOf(
            "whatsapp", "واتساب", "إعادة الاتصال", "reconnecting",
            "جارٍ التحميل", "downloading", "تحديث", "backup", "نسخ احتياطي",
            "checking for messages", "connectivity", "battery"
        )

        private val fingerprints = mutableMapOf<String, Long>()
        private const val DEDUPE_MS = 30 * 60_000L

        @Volatile
        var listenerConnected: Boolean = false

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
        if (sbn.isOngoing) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()

        val now = System.currentTimeMillis()
        fingerprints.entries.removeAll { it.value < now - DEDUPE_MS }

        // MessagingStyle هو المصدر الأفضل لأنه يحمل وقت الرسالة والمرسل.
        val messages = extractMessagingMessages(extras, title, conversationTitle)
            .filter { !it.text.isNullOrBlank() }

        if (messages.isNotEmpty()) {
            for (m in messages) {
                val sender = m.sender?.trim().orEmpty()
                val body = m.text?.trim().orEmpty()
                val group = m.groupName?.trim().orEmpty()
                val contact = group.ifBlank { sender.ifBlank { title } }
                if (contact.isBlank() || body.isBlank()) continue

                val fp = "$pkg|${m.ts}|$contact|$sender|$body"
                val previous = fingerprints[fp]
                val duplicateWindow = if (m.ts > 0L) DEDUPE_MS else 5_000L
                if (previous != null && now - previous < duplicateWindow) continue
                fingerprints[fp] = now

                record(
                    pkg = pkg,
                    msgnr = msgnr,
                    contact = contact,
                    sender = sender,
                    text = body,
                    group = group.isNotBlank()
                )
            }
            return
        }

        // لا نخرج لمجرد وجود عناصر MessagingStyle فارغة؛ نجرب الحقول الاحتياطية.
        val fallbackText = when {
            text.isNotBlank() -> text
            bigText.isNotBlank() -> bigText
            else -> extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.mapNotNull { it?.toString()?.takeIf { line -> line.isNotBlank() } }
                ?.joinToString("\n")
                .orEmpty()
        }.trim()

        if (fallbackText.isBlank()) return
        if (fallbackText.equals("رسالة جديدة", ignoreCase = true) ||
            fallbackText.equals("new message", ignoreCase = true)
        ) return

        val titleIsNoise = title.isBlank() || NOISE_TITLES.any { title.contains(it, ignoreCase = true) }
        var contact = conversationTitle.ifBlank { title }
        var sender = title
        var body = fallbackText

        if (titleIsNoise && conversationTitle.isBlank()) {
            // بعض الإشعارات المجمعة تأتي بالشكل: اسم المرسل: نص الرسالة.
            val sep = fallbackText.indexOf(": ")
            if (sep in 1..80) {
                contact = fallbackText.substring(0, sep).trim()
                sender = contact
                body = fallbackText.substring(sep + 2).trim()
            } else {
                // لا ننشئ محادثة عامة باسم واتساب إذا لم يتوفر اسم مرسل أو نص محدد.
                return
            }
        }

        if (contact.isBlank() || body.isBlank()) return

        // نافذة قصيرة تمنع إعادة نشر الإشعار نفسه، لكنها لا تمنع رسالة مكررة لاحقًا.
        val fp = "legacy|$pkg|${sbn.key}|$contact|$sender|$body"
        val previous = fingerprints[fp]
        if (previous != null && now - previous < 5_000L) return
        fingerprints[fp] = now

        record(pkg, msgnr, contact, sender, body, group = conversationTitle.isNotBlank())
    }

    private fun record(
        pkg: String,
        msgnr: Boolean,
        contact: String,
        sender: String,
        text: String,
        group: Boolean
    ) {
        if (contact.isBlank() || text.isBlank()) return

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

        val meta = JSONObject().put("text", text)
        if (group && s.isNotBlank() && s != c) {
            meta.put("sender", s).put("group", true)
        }

        val json = JSONObject()
            .put("type", if (msgnr) "MESSENGER" else "WHATSAPP")
            .put("direction", "INCOMING")
            .put("title", c)
            .put("app", chatLabel(pkg))
            .put("packageName", pkg)
            .put("meta", meta)

        EventStore.addEvent(applicationContext, json)
    }

    private data class Msg(
        val sender: String?,
        val text: String?,
        val groupName: String?,
        val ts: Long
    )

    private fun extractMessagingMessages(
        extras: Bundle,
        title: String,
        conversationTitle: String
    ): List<Msg> {
        val out = mutableListOf<Msg>()
        try {
            val displayName = conversationTitle.ifBlank { title }
            val arr = if (Build.VERSION.SDK_INT >= 24) {
                extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            } else null

            if (arr != null) {
                for (item in arr) {
                    if (item !is Notification.MessagingStyle.Message) continue
                    val body = item.text?.toString()?.trim().orEmpty()
                    if (body.isBlank()) continue
                    out.add(
                        Msg(
                            sender = item.senderPerson?.name?.toString(),
                            text = body,
                            groupName = conversationTitle,
                            ts = item.timestamp
                        )
                    )
                }
            }

            // دعم بعض الإصدارات المعدلة التي تضع سجل الرسائل كسطور نصية.
            if (out.isEmpty()) {
                val history = extras.getCharSequenceArray(Notification.EXTRA_MESSAGES)
                if (history != null) {
                    for (line in history) {
                        val value = line?.toString()?.trim().orEmpty()
                        if (value.isBlank()) continue
                        val sep = value.indexOf(": ")
                        if (sep in 1..80) {
                            out.add(
                                Msg(
                                    sender = value.substring(0, sep).trim(),
                                    text = value.substring(sep + 2).trim(),
                                    groupName = conversationTitle,
                                    ts = 0L
                                )
                            )
                        } else {
                            out.add(
                                Msg(
                                    sender = title,
                                    text = value,
                                    groupName = conversationTitle,
                                    ts = 0L
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("WhatsappNotif", "تعذر تحليل محتوى الإشعار", e)
        }
        return out.filter { !it.text.isNullOrBlank() }
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
        listenerConnected = true
    }

    override fun onListenerDisconnected() {
        listenerConnected = false
        requestRebindNow(applicationContext)
    }

    override fun onDestroy() {
        listenerConnected = false
        super.onDestroy()
    }
}
