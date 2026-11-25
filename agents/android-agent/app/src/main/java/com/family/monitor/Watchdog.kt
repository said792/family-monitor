package com.family.monitor

import android.content.Context
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
        return Result.success()
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
