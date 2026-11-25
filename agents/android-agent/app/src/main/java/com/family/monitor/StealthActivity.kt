package com.family.monitor

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * شاشة التخفي: تظهر بدلًا من التطبيق بعد إخفاء الأيقونة.
 * بريئة تمامًا (ساعة + ملاحظات العائلة) — الدخول الحقيقي عبر:
 *  1) الضغط على إشعار «أذكاري» الثابت ← شاشة الأذكار ← زر الترس ← الرقم السري.
 *  2) أو صورة الترس داخل هذه الشاشة ← نفس كلمة المرور.
 * الرقم السري = كود الاقتران نفسه الذي سُجّل به الجهاز،
 * وعند الإدخال الصحيح تظهر الأيقونة فورًا ويفتح شاشة الإعداد.
 */
class StealthActivity : AppCompatActivity() {

    companion object {
        /** إشارة من إشعار الخدمة: افتح مربع الرقم السري مباشرة */
        const val EXTRA_ASK_PASSWORD = "ask_password"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF101828.toInt())
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }

        content.addView(TextClock(this).apply {
            format12Hour = "hh:mm a"
            textSize = 52f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
        })
        content.addView(TextClock(this).apply {
            format24Hour = "EEEE، d MMMM yyyy"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, pad * 4)
            setTextColor(0xFF94A3B8.toInt())
        })

        content.addView(TextView(this).apply {
            text = "ملاحظات العائلة"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(0xFFE2E8F0.toInt())
        })
        content.addView(TextView(this).apply {
            text = "«اجعل وقتك مع أهلك أجمل — ولا شيء أهم من يوم جميل تبدأه بنية طيبة»"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, 0)
            setTextColor(0xFF64748B.toInt())
            setLineSpacing(0f, 1.3f)
        })

        root.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // صورة الترس — مدخل الإعدادات الحقيقي
        val gear = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_preferences)
            setBackgroundColor(0x00000000)
            contentDescription = "الإعدادات"
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            setOnClickListener { askPassword() }
        }
        root.addView(gear, FrameLayout.LayoutParams(
            (40 * resources.displayMetrics.density).toInt(),
            (40 * resources.displayMetrics.density).toInt(),
            Gravity.BOTTOM or Gravity.START).apply {
            setMargins(pad * 2, 0, 0, pad * 2)
        })

        setContentView(root)

        // لو الجهاز غير مفعّل، لا داعي للتخفي — افتح الإعداد مباشرة
        if (!Prefs.activated(this)) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        // الضغط على الإشعار الثابت يطلب الرقم السري فورًا ثم يُظهر الأيقونة
        if (intent?.getBooleanExtra(EXTRA_ASK_PASSWORD, false) == true) {
            askPassword()
        }
    }

    private fun askPassword() {
        val stored = Prefs.pairingCode(this)
        if (stored.isNullOrBlank()) {
            Toast.makeText(this, "أعد تسجيل الجهاز لتعيين الرقم السري", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        val input = EditText(this).apply {
            hint = "أدخل الرقم السري (كود الاقتران)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }

        AlertDialog.Builder(this)
            .setTitle("الرقم السري")
            .setView(input)
            .setPositiveButton("متابعة") { _, _ ->
                if (input.text.toString().trim() == stored) {
                    // الرقم صحيح — أظهر الأيقونة فورًا وافتح شاشة الإعداد
                    Prefs.setLauncherIconHidden(this, false)
                    Toast.makeText(this, "ظهرت أيقونة التطبيق في الشاشة الرئيسية ✓", Toast.LENGTH_LONG).show()
                    startActivity(Intent(this, SetupActivity::class.java))
                    finish()
                } else {
                    Toast.makeText(this, "الرقم السري غير صحيح", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        super.onBackPressed()
        finishAffinity()
    }
}
