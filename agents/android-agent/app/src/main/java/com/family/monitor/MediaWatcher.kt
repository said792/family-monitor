package com.family.monitor

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import org.json.JSONObject

/**
 * رصد المحتوى المعروض داخل التطبيقات (ريلز فيسبوك، فيديوهات، يوتيوب، تيك توك...)
 * عبر جلسات الوسائط (MediaSession): عندما يشغّل أي تطبيق فيديو أو صوت، تسجّل النظام
 * جلسة وسائط تحمل عنوان المحتوى. نستعديها كل 5 ثوانٍ من حلقة الخدمة الدائمة
 * ونحفظ «مرات الدخول» لكل عنوان — العنوان الجديد يُسجل فورًا، والعنوان نفسه
 * لا يتكرر إلا بعد انقطاع 10 دقائق (يُحسب دخولًا جديدًا).
 *
 * الصلاحية: الوصول إلى الإشعارات (ممنوحة أصلًا لرصد الرسائل) — لا صلاحيات جديدة.
 */
object MediaWatcher {

    /** آخر وقت تسجيل لكل عنوان: pkg|title → آخر وقت */
    private val lastLogged = HashMap<String, Long>()
    private var lastAnyLog = 0L

    /** فاصل إعادة تسجيل العنوان نفسه — يحدد «مرات الدخول» */
    private const val REENTRY_MS = 10 * 60_000L
    /** فاصل أدنى بين أي تسجيلين (حماية من الضوضاء) */
    private const val MIN_GAP_MS = 8_000L

    fun poll(ctx: Context) {
        try {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
            val sessions = try {
                msm.getActiveSessions(ComponentName(ctx, WhatsappNotificationService::class.java))
            } catch (_: SecurityException) {
                return // صلاحية الإشعارات غير ممنوحة بعد
            }
            val now = System.currentTimeMillis()
            for (sc in sessions) {
                val pkg = sc.packageName ?: continue
                if (pkg == ctx.packageName) continue
                // لا تسجيل إلا أثناء التشغيل الفعلي (مش متوقف أو مكوّم)
                val st = sc.playbackState
                if (st != null && st.state != PlaybackState.STATE_PLAYING &&
                    st.state != PlaybackState.STATE_BUFFERING
                ) continue

                val md = sc.metadata ?: continue
                val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim() ?: continue
                if (title.isEmpty() || title.length < 2) continue

                val key = "$pkg|$title"
                val last = lastLogged[key] ?: 0L
                if (last == 0L || (now - last >= REENTRY_MS && now - lastAnyLog >= MIN_GAP_MS)) {
                    lastLogged[key] = now
                    lastAnyLog = now
                    val json = JSONObject()
                        .put("type", "MEDIA")
                        .put("title", title.take(120))
                        .put("app", appLabel(pkg))
                        .put("packageName", pkg)
                    EventStore.addEvent(ctx, json)
                }
            }
            // تنظيف الخريطة إن كبرت (إدخالات أقدم من 24 ساعة)
            if (lastLogged.size > 400) {
                val cutoff = now - 24 * 60 * 60_000L
                lastLogged.entries.removeAll { it.value < cutoff }
            }
        } catch (_: Throwable) {
        }
    }

    private fun appLabel(pkg: String): String = when (pkg.lowercase()) {
        "com.facebook.katana" -> "فيسبوك"
        "com.facebook.lite" -> "فيسبوك لايت"
        "com.facebook.orca" -> "ماسنجر"
        "com.instagram.android" -> "إنستجرام"
        "com.google.android.youtube" -> "يوتيوب"
        "com.google.android.apps.youtube.music" -> "يوتيوب ميوزك"
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill" -> "تيك توك"
        "com.snapchat.android" -> "سناب شات"
        "com.twitter.android" -> "إكس (تويتر)"
        else -> pkg.substringAfterLast('.').ifBlank { "تطبيق" }
    }
}
