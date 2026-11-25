package com.family.monitor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * شاشة الجهاز الأساسي: تفتح لوحة المتابعة (موقع Vercel) داخل التطبيق نفسه.
 * أول مرة تطلب رابط اللوحة، وبعدها تفتحه مباشرة وتحتفظ بجلسة تسجيل الدخول.
 */
class WebPanelActivity : AppCompatActivity() {

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webView
                if (wv != null && wv.canGoBack()) wv.goBack() else finish()
            }
        })

        val saved = Prefs.panelUrl(this)
        if (saved.isNullOrBlank()) showUrlInput() else showWeb(saved)
    }

    /** شاشة إدخال رابط اللوحة (أول مرة فقط) */
    private fun showUrlInput() {
        webView = null
        supportActionBar?.title = "جهاز أساسي"
        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        layout.addView(TextView(this).apply {
            text = "لوحة المتابعة — جهاز أساسي"
            textSize = 22f
            setPadding(0, 0, 0, pad)
        })

        val etUrl = EditText(this).apply {
            hint = "رابط اللوحة (مثل: https://xxxx.vercel.app)"
            inputType = android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            // الرابط محروق داخل التطبيق — يظهر معبأً تلقائيًا
            setText(Prefs.panelUrl(this@WebPanelActivity) ?: Config.DEFAULT_PANEL_URL)
        }
        layout.addView(etUrl)

        layout.addView(Button(this).apply {
            text = "فتح اللوحة"
            setOnClickListener {
                val url = normalizeUrl(etUrl.text.toString())
                if (url == null) {
                    Toast.makeText(context, "أدخل رابط صحيح يبدأ بـ https://", Toast.LENGTH_LONG).show()
                } else {
                    Prefs.setPanelUrl(this@WebPanelActivity, url)
                    showWeb(url)
                }
            }
        })

        layout.addView(TextView(this).apply {
            text = "اكتب نفس عنوان موقع اللوحة الذي تفتحه من المتصفح على الحاسوب " +
                "(الرابط الذي ينتهي بـ .vercel.app).\n" +
                "ستدخل كلمة المرور مرة واحدة، والتطبيق سيتذكرها."
            textSize = 13f
            setPadding(0, pad, 0, 0)
        })

        setContentView(ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(layout)
        })
    }

    /** فتح اللوحة داخل WebView */
    private fun showWeb(url: String) {
        supportActionBar?.title = "لوحة المتابعة"
        val wv = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val link = request.url.toString()
                    // الروابط العادية تفتح داخل التطبيق، وأي رابط خارجي (واتساب/تليفون...) في تطبيقه
                    return if (link.startsWith("http://") || link.startsWith("https://")) {
                        false
                    } else {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                        } catch (_: Exception) { }
                        true
                    }
                }
            }
        }
        webView = wv
        setContentView(wv)
        wv.loadUrl(url)
    }

    /** توحيد شكل الرابط: إضافة https:// وحذف / الزائدة والتحقق منه */
    private fun normalizeUrl(raw: String): String? {
        var u = raw.trim()
        if (u.isEmpty()) return null
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        while (u.endsWith("/")) u = u.dropLast(1)
        return try {
            val url = java.net.URL(u)
            if (url.host.contains(".") || url.host == "localhost") u else null
        } catch (_: Exception) {
            null
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "تغيير رابط اللوحة")
        menu.add(0, 2, 0, "تغيير نوع الجهاز")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            1 -> {
                Prefs.clearPanelUrl(this)
                showUrlInput()
                true
            }
            2 -> {
                Prefs.clearRole(this)
                startActivity(Intent(this, MainActivity::class.java))
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
