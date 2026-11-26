package com.family.monitor

/**
 * إعدادات ثابتة محفورة داخل التطبيق.
 */
object Config {

    /** عنوان الخادم الرسمي — يظهر مسبقًا في شاشة الإعداد حتى لا يخطئ المستخدم */
    const val DEFAULT_SERVER_URL = "https://family-monitor.vercel.app"

    /**
     * الرمز السري من لوحة الاتصال لإظهار التطبيق بعد إخفاء الأيقونة.
     * يُكتب في تطبيق الهاتف هكذا:  *#*#7594#*#*  ثم زر الاتصال.
     */
    const val SECRET_CODE_HOST = "7594"

    /** المتصفحات المعروفة — تُستثنى من تبويب «التطبيقات» لأن نشاطها يُرصد في تبويب «بحث وتصفح» */
    private val BROWSER_PACKAGES = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
        "com.chrome.beta", "com.android.chrome.debug",
        "com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser.beta",
        "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.focus",
        "org.mozilla.fenix", "org.mozilla.fenix.debug",
        "com.opera.browser", "com.opera.browser.beta", "com.opera.mini.native",
        "com.opera.mini.native.beta", "com.opera.touch", "com.opera.gx",
        "com.microsoft.emmx", "com.microsoft.emmx.beta",
        "com.UCMobile.intl", "com.UCMobile", "com.uc.browser.en",
        "com.brave.browser", "com.brave.browser_beta",
        "com.vivaldi.browser", "com.duckduckgo.mobile.android", "org.torproject.torbrowser",
        "com.google.android.googlequicksearchbox", "com.google.android.apps.searchlite",
        "com.android.browser", "com.kiwibrowser.browser", "com.kiwibrowser.browser.mobile",
        "org.adblockplus.browser", "com.linkbubble.playstore", "com.ghostery.android.ghostery",
        "com.yandex.browser", "com.sec.android.app.webview", "com.samsung.android.internet"
    )

    /** هل هذه الحزمة متصفح؟ (تُستثنى من «فتح التطبيقات») */
    fun isBrowser(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p in BROWSER_PACKAGES || p.contains("chrome") ||
            p.endsWith("browser") || p.contains(".browser.")
    }

    /** هل هذه الحزمة من عائلة واتساب (الرسمي + بزنس + كل النسخ المعدلة)؟ */
    fun isWhatsApp(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("whats") || p.contains("gbwhats") || p.endsWith("waplus")
    }

    /** عائلة فيسبوك: التطبيق الرئيسي + لايت + ماسنجر (لرصد الريلز والفيديوهات والروابط الداخلية) */
    fun isFacebook(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p == "com.facebook.katana" || p == "com.facebook.lite" ||
            p == "com.facebook.orca" || p == "com.facebook.mlite"
    }

    /** تطبيقات ماسنجر — إشعارات محادثاتها تُرصد مثل واتساب */
    fun isMessenger(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p == "com.facebook.orca" || p == "com.facebook.mlite" || p == "com.facebook.lite"
    }
}
