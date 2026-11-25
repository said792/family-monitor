package com.family.monitor

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * رصد فتح التطبيقات عبر UsageStatsManager.
 * كل استدعاء poll() يرصد التطبيقات التي انتقلت للمقدمة منذ آخر فحص.
 */
object UsageTracker {
    private var lastQueryEnd = System.currentTimeMillis() - 15_000
    private val knownPackages = mutableSetOf<String>()

    fun poll(ctx: Context) {
        try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(lastQueryEnd, now)
            lastQueryEnd = now

            val ev = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                val isForeground = when (ev.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> true        // Android 10+
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> true      // إصدارات أقدم
                    else -> false
                }
                if (isForeground && ev.packageName.isNotBlank()) {
                    // استثناء المتصفحات: نشاطها يُسجل في تبويب «بحث وتصفح» بدقة أعلى
                    if (Config.isBrowser(ev.packageName)) continue
                    // حدث واحد لكل فتح تطبيق (وليس كل انتقال بين نوافذ التطبيق نفسه)
                    if (knownPackages.add(ev.packageName + ":" + ev.timeStamp / 3000)) {
                        val json = org.json.JSONObject()
                            .put("type", "APP")
                            .put("packageName", ev.packageName)
                            .put("app", friendlyName(ctx, ev.packageName))
                            .put("title", "تم فتح التطبيق")
                            .put("occurredAt", iso(ev.timeStamp))
                        EventStore.addEvent(ctx, json)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("UsageTracker", "تعذر قراءة الاستخدام (تحقق من صلاحية الوصول)", e)
        }
    }

    fun friendlyName(ctx: Context, packageName: String): String {
        return try {
            ctx.packageManager.getApplicationLabel(
                ctx.packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    private fun iso(ms: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date(ms))
    }
}
