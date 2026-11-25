package com.family.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * مستقبِل إعادة تشغيل الجهاز — هذا كان الجزء المفقود الذي يمنع التطبيق
 * من الرجوع للعمل بعد إعادة تشغيل الموبيل.
 *
 * يبدأ خدمة المراقبة تلقائيًا بعد:
 *  - إعادة تشغيل الهاتف (BOOT_COMPLETED — يصدر بعد فتح قفل المستخدم)
 *  - إعادة تشغيل بعض الواجهات بنوع آخر (QUICKBOOT_POWERON — HTC وبعض الشركات)
 *  - تحديث التطبيق بنسخة جديدة (MY_PACKAGE_REPLACED)
 *  - طلب إعادة تشغيل داخلي (RESTART_SERVICE — بعد سحب التطبيق من قائمة الأخيرة)
 *
 * مهم لأنواع الموبيلات المختلفة (شاومي / هواوي / أوبو / فيفو...):
 * بعض الواجهات تحجب بث إعادة التشغيل عن التطبيق ما لم يفعّل المستخدم
 * «التشغيل التلقائي» له مرة واحدة — شاشة الإعداد (SetupActivity)
 * تفتح الصفحة المناسبة لكل نوع بزر واحد.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != ACTION_QUICKBOOT &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != ACTION_RESTART
        ) return

        // المراقبة تعمل فقط على الجهاز الفرعي المسجّل مسبقًا (وليس جهاز اللوحة)
        if (!Prefs.activated(context) || Prefs.apiKey(context) == null) return

        startMonitor(context)

        // جدولة الفحص الاحتياطي الدوري — WorkManager يستمر بعد إعادة التشغيل تلقائيًا
        Watchdog.schedule(context)
    }

    companion object {
        /** بث داخلي لإعادة تشغيل الخدمة (يُرسل من المنبه بعد سحب التطبيق من الأخيرة) */
        const val ACTION_RESTART = "com.family.monitor.RESTART_SERVICE"

        /** بث بديل لإعادة التشغيل في بعض الواجهات */
        const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"

        /**
         * بدء خدمة المراقبة بأمان على كل إصدارات أندرويد (7 حتى 15+).
         * إجراء آمن ومتكرر: لو الخدمة شغالة بالفعل فلن يحدث تكرار.
         */
        fun startMonitor(context: Context) {
            try {
                val service = Intent(context, MonitorService::class.java)
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(service)
                } else {
                    context.startService(service)
                }
            } catch (e: Exception) {
                android.util.Log.e("BootReceiver", "تعذر بدء خدمة المراقبة", e)
            }
        }
    }
}
