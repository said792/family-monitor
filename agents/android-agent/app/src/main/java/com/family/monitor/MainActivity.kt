package com.family.monitor

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * شاشة التوجيه: تظهر أول مرة فقط لتسأل «جهاز أساسي ولا فرعي؟»
 * - أساسي: يفتح لوحة المتابعة داخل التطبيق (WebPanelActivity).
 * - فرعي: قبل إكمال الإعداد يفتح شاشة الإعداد (SetupActivity)؛
 *   بعد إكمال الإعداد والأذونات تظهر شاشة التمويه (DisguiseActivity) —
 *   شاشة بريئة بزر واحد يطلب كلمة مرور الأدمن لفتح الإعدادات.
 * الاختيار يُحفظ ولا تظهر الشاشة مرة أخرى إلا من «تغيير نوع الجهاز».
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // لو الاختيار محفوظ من قبل — وجّه مباشرة بدون سؤال
        when (Prefs.role(this)) {
            "main" -> { startActivity(Intent(this, WebPanelActivity::class.java)); finish(); return }
            "secondary" -> {
                val target = if (Prefs.isActivated(this)) DisguiseActivity::class.java else SetupActivity::class.java
                startActivity(Intent(this, target))
                finish()
                return
            }
        }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
        }

        layout.addView(TextView(this).apply {
            text = "مراقبة الأسرة"
            textSize = 28f
            gravity = android.view.Gravity.CENTER
            setPadding(0, pad, 0, pad / 2)
        })

        layout.addView(TextView(this).apply {
            text = "ما نوع هذا الجهاز؟"
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, pad * 2)
        })

        layout.addView(Button(this).apply {
            text = "جهاز أساسي — لوحة المتابعة"
            textSize = 16f
            setOnClickListener {
                Prefs.setRole(this@MainActivity, "main")
                startActivity(Intent(this@MainActivity, WebPanelActivity::class.java))
                finish()
            }
        })

        layout.addView(Button(this).apply {
            text = "جهاز فرعي — سيتم مراقبته"
            textSize = 16f
            setOnClickListener {
                Prefs.setRole(this@MainActivity, "secondary")
                startActivity(Intent(this@MainActivity, SetupActivity::class.java))
                finish()
            }
        })

        layout.addView(TextView(this).apply {
            text = "الجهاز الأساسي هو جهاز الأب/الأم الذي يفتح لوحة المتابعة بكلمة المرور.\n" +
                "الجهاز الفرعي هو هاتف الطفل الذي ستُسجَّل عليه الأحداث.\n\n" +
                "يمكن تغيير النوع في أي وقت من قائمة التطبيق (⋮) داخل أي شاشة."
            textSize = 13f
            setPadding(0, pad * 2, 0, 0)
        })

        setContentView(ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(layout)
        })
    }
}
