package com.family.monitor

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * فحص احتياطي دوري (كل 15 دقيقة): لو خدمة المراقبة متوقفة لأي سبب
 * (قتل النظام للخدمة، سحب التطبيق من قائمة الأخيرة، قيود الطاقة)
 * يعيد تشغيلها تلقائيًا.
 *
 * يهتم أيضًا بخدمة إشعارات واتساب: بعض الواجهات لا تعيد ربطها
 * بعد إعادة تشغيل الجهاز — نطلب إعادة الربط دوريًا، وإن استمر الانقطاع
 * نوقف المكون ونشغّله (طريقة معروفة تجبر النظام على إعادة الربط).
 *
 * ميزة WorkManager أنه يستمر بعد إعادة تشغيل الجهاز تلقائيًا
 * (يستعيد جدولته بنفسه)، لذا يعمل كشبكة أمان ثانية بعد مستقبِل
 * إعادة التشغيل (BootReceiver).
 */
class Watchdog(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val context = applicationContext
        // يعمل فقط على الجهاز الفرعي المسجّل
        if (!Prefs.activated(context) || Prefs.apiKey(context) == null) return Result.success()
        // startMonitor آمنة داخليًا (try-catch): لو حجب النظام بدء الخدمة
        // من الخلفية لن يتعطل شيء — والمحاولة التالية بعد 15 دقيقة
        BootReceiver.startMonitor(context)
        ensureWhatsAppListener(context)
        return Result.success()
    }

    // ---------- ضمان عمل خدمة إشعارات واتساب ----------

    private fun ensureWhatsAppListener(context: Context) {
        try {
            val granted = androidx.core.app.NotificationManagerCompat
                .getEnabledListenerPackages(context).contains(context.packageName)
            val prefs = context.getSharedPreferences("monitor_rebind", Context.MODE_PRIVATE)
            if (!granted) return  // الصلاحية غير ممنوحة أصلاً — لا شيء نفعله

            if (WhatsappNotificationService.listenerConnected) {
                // كل شيء سليم — صفّر عدّاد المحاولات
                prefs.edit().putInt("attempts", 0).apply()
                return
            }

            val attempts = prefs.getInt("attempts", 0) + 1
            // المحاولة الخفيفة أولًا: طلب إعادة ربط من النظام
            WhatsappNotificationService.requestRebindNow(context)
            if (attempts % 3 == 0) {
                // منذ ~45 دقيقة لم تُربط — الحل الجذري: إيقاف المكون ثم تشغيله
                toggleListenerComponent(context)
            }
            prefs.edit().putInt("attempts", attempts).apply()
        } catch (e: Exception) {
            android.util.Log.e("Watchdog", "تعذر ضمان خدمة الإشعارات", e)
        }
    }

    /** إيقاف خدمة الإشعارات وتشغيلها يجبر النظام على إعادة ربطها */
    private fun toggleListenerComponent(context: Context) {
        try {
            val pm = context.packageManager
            val comp = ComponentName(context, WhatsappNotificationService::class.java)
            pm.setComponentEnabledSetting(
                comp, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            Thread.sleep(800)
            pm.setComponentEnabledSetting(
                comp, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            Thread.sleep(500)
            WhatsappNotificationService.requestRebindNow(context)
            android.util.Log.w("Watchdog", "أعيد تفعيل خدمة الإشعارات لإعادة ربطها")
        } catch (e: Exception) {
            android.util.Log.e("Watchdog", "تعذر تبديل خدمة الإشعارات", e)
        }
    }

    companion object {
        private const val WORK_NAME = "monitor_watchdog"

        /** جدولة الفحص الدوري — KEEP تعني: لا تُعاد الجدولة لو كانت موجودة بالفعل */
        fun schedule(context: Context) {
            try {
                val request = PeriodicWorkRequestBuilder<Watchdog>(15, TimeUnit.MINUTES).build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
                )
            } catch (e: Exception) {
                android.util.Log.e("Watchdog", "تعذرت جدولة الفحص الاحتياطي", e)
            }
        }
    }
}

